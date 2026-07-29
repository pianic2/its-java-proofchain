package it.itsprodigi.proofchain.custodyevent.application;

import static it.itsprodigi.proofchain.custodyevent.domain.CustodyEventFixtures.custodyCase;
import static it.itsprodigi.proofchain.custodyevent.domain.CustodyEventFixtures.evidence;
import static it.itsprodigi.proofchain.custodyevent.domain.CustodyEventFixtures.operator;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import it.itsprodigi.proofchain.custodycase.domain.CustodyCase;
import it.itsprodigi.proofchain.custodycase.persistence.CaseMembershipRepository;
import it.itsprodigi.proofchain.custodycase.persistence.CustodyCaseRepository;
import it.itsprodigi.proofchain.custodyevent.domain.CustodyEvent;
import it.itsprodigi.proofchain.custodyevent.persistence.CustodyEventRepository;
import it.itsprodigi.proofchain.custodyevent.protocol.CanonicalCustodyEvent;
import it.itsprodigi.proofchain.custodyevent.protocol.CustodyEventCanonicalizer;
import it.itsprodigi.proofchain.custodyevent.protocol.CustodyEventHashing;
import it.itsprodigi.proofchain.custodyevent.protocol.CustodyEventPayload;
import it.itsprodigi.proofchain.custodyevent.protocol.EvidenceRegisteredPayload;
import it.itsprodigi.proofchain.custodyevent.protocol.IntegrityVerifiedPayload;
import it.itsprodigi.proofchain.evidence.domain.DigitalEvidence;
import it.itsprodigi.proofchain.evidence.persistence.DigitalEvidenceRepository;
import it.itsprodigi.proofchain.operator.domain.Operator;
import it.itsprodigi.proofchain.operator.domain.OperatorRole;
import it.itsprodigi.proofchain.operator.persistence.OperatorRepository;
import it.itsprodigi.proofchain.support.PostgreSqlIntegrationTest;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

class CustodyEventAppenderIT extends PostgreSqlIntegrationTest {

    @Autowired
    private CustodyEventAppender appender;

    @Autowired
    private CustodyEventRepository events;

    @Autowired
    private DigitalEvidenceRepository evidences;

    @Autowired
    private CaseMembershipRepository memberships;

    @Autowired
    private CustodyCaseRepository custodyCases;

    @Autowired
    private OperatorRepository operators;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        cleanDatabaseInDependencyOrder();
        executor = Executors.newFixedThreadPool(2);
    }

    @AfterEach
    void tearDown() {
        if (executor != null) {
            executor.shutdownNow();
        }
        cleanDatabaseInDependencyOrder();
    }

    @Test
    void appendsGenesisAndLaterEventsWithCanonicalHashesAndOneAtomicHead() {
        EventContext context = saveContext("chain");
        EvidenceRegisteredPayload registered = registeredPayload(context.evidence(), false);
        CustodyEventAppendResult genesis = transactionTemplate.execute(
                status -> appender.append(context.evidence().getId(), context.actor(), registered));
        IntegrityVerifiedPayload verified = verifiedPayload();
        CustodyEventAppendResult second = transactionTemplate.execute(
                status -> appender.append(context.evidence().getId(), context.actor(), verified));

        List<CustodyEvent> timeline = events.findAllByEvidenceIdOrderBySequenceNumberAsc(
                context.evidence().getId());
        DigitalEvidence reloaded =
                evidences.findById(context.evidence().getId()).orElseThrow();

        assertThat(timeline).extracting(CustodyEvent::getSequenceNumber).containsExactly(1L, 2L);
        assertThat(timeline.getFirst().getPreviousHash()).isEqualTo(CustodyEventHashing.ZERO_HASH);
        assertThat(timeline.getLast().getPreviousHash()).isEqualTo(genesis.eventHash());
        assertThat(timeline.getFirst().getEventHash()).isEqualTo(genesis.eventHash());
        assertThat(timeline.getLast().getEventHash()).isEqualTo(second.eventHash());
        assertThat(reloaded.getCustodyEventCount()).isEqualTo(2);
        assertThat(reloaded.getCustodyChainHeadHash()).isEqualTo(second.eventHash());
        assertThat(genesis.eventHash())
                .isEqualTo(CustodyEventHashing.eventHash(
                        canonical(context, genesis, registered, CustodyEventHashing.ZERO_HASH)));
        assertThat(second.eventHash())
                .isEqualTo(CustodyEventHashing.eventHash(canonical(context, second, verified, genesis.eventHash())));
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT payload_json = CAST(? AS jsonb) FROM custody_events WHERE id = ?",
                        Boolean.class,
                        CustodyEventCanonicalizer.canonicalizePayload(registered),
                        genesis.eventId()))
                .isTrue();
    }

    @Test
    void rollsBackEventAndHeadTogetherAndRequiresAnExistingTransaction() {
        EventContext context = saveContext("rollback");

        assertThatThrownBy(() -> appender.append(
                        context.evidence().getId(), context.actor(), registeredPayload(context.evidence(), false)))
                .isInstanceOf(IllegalTransactionStateException.class);

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
                    appender.append(
                            context.evidence().getId(), context.actor(), registeredPayload(context.evidence(), false));
                    throw new RollbackMarker();
                }))
                .isInstanceOf(RollbackMarker.class);

        DigitalEvidence reloaded =
                evidences.findById(context.evidence().getId()).orElseThrow();
        assertThat(events.countByEvidenceId(context.evidence().getId())).isZero();
        assertThat(reloaded.getCustodyEventCount()).isZero();
        assertThat(reloaded.getCustodyChainHeadHash()).isEqualTo(CustodyEventHashing.ZERO_HASH);
    }

    @Test
    void initializesGenesisWithoutASecondEvidenceLockQueryAndRejectsReuse() {
        Operator actor = operators.saveAndFlush(operator("event-genesis", OperatorRole.ADMIN));
        CustodyCase custodyCase = custodyCases.saveAndFlush(custodyCase("Managed genesis case", actor));

        UUID evidenceId = transactionTemplate.execute(status -> {
            DigitalEvidence managed = evidences.saveAndFlush(evidence(custodyCase, actor, "GENESIS"));
            appender.initializeGenesis(managed, actor, registeredPayload(managed, false), managed.getCreatedAt());
            return managed.getId();
        });

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
                    DigitalEvidence managed = evidences.findById(evidenceId).orElseThrow();
                    appender.initializeGenesis(
                            managed, actor, registeredPayload(managed, false), managed.getCreatedAt());
                }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("genesis requires an empty custody chain");

        DigitalEvidence reloaded = evidences.findById(evidenceId).orElseThrow();
        assertThat(events.countByEvidenceId(evidenceId)).isEqualTo(1);
        assertThat(reloaded.getCustodyEventCount()).isEqualTo(1);
        assertThat(reloaded.getCustodyChainHeadHash()).isNotEqualTo(CustodyEventHashing.ZERO_HASH);
    }

    @Test
    void serializesConcurrentAppendsToTheSameEvidenceWithoutBreakingLinkage() throws Exception {
        EventContext context = saveContext("same-lock");
        transactionTemplate.executeWithoutResult(status -> appender.append(
                context.evidence().getId(), context.actor(), registeredPayload(context.evidence(), false)));
        CyclicBarrier start = new CyclicBarrier(2);

        Future<CustodyEventAppendResult> first = executor.submit(() -> transactionTemplate.execute(status -> {
            await(start);
            return appender.append(context.evidence().getId(), context.actor(), verifiedPayload());
        }));
        Future<CustodyEventAppendResult> second = executor.submit(() -> transactionTemplate.execute(status -> {
            await(start);
            return appender.append(context.evidence().getId(), context.actor(), verifiedPayload());
        }));

        List<CustodyEventAppendResult> results = List.of(get(first), get(second)).stream()
                .sorted(Comparator.comparingLong(CustodyEventAppendResult::sequenceNumber))
                .toList();
        List<CustodyEvent> timeline = events.findAllByEvidenceIdOrderBySequenceNumberAsc(
                context.evidence().getId());
        DigitalEvidence reloaded =
                evidences.findById(context.evidence().getId()).orElseThrow();

        assertThat(results).extracting(CustodyEventAppendResult::sequenceNumber).containsExactly(2L, 3L);
        assertThat(timeline).extracting(CustodyEvent::getSequenceNumber).containsExactly(1L, 2L, 3L);
        assertThat(timeline.get(1).getPreviousHash())
                .isEqualTo(timeline.getFirst().getEventHash());
        assertThat(timeline.get(2).getPreviousHash()).isEqualTo(timeline.get(1).getEventHash());
        assertThat(reloaded.getCustodyEventCount()).isEqualTo(3);
        assertThat(reloaded.getCustodyChainHeadHash())
                .isEqualTo(timeline.getLast().getEventHash());
    }

    @Test
    void differentEvidenceAppendsReachTheCommitBarrierWithoutGlobalSerialization() throws Exception {
        EventContext first = saveContext("independent-a");
        EventContext second = saveContext("independent-b");
        CyclicBarrier start = new CyclicBarrier(2);
        CyclicBarrier bothAppendedBeforeCommit = new CyclicBarrier(2);

        Future<CustodyEventAppendResult> firstResult = executor.submit(() -> transactionTemplate.execute(status -> {
            await(start);
            CustodyEventAppendResult result = appender.append(
                    first.evidence().getId(), first.actor(), registeredPayload(first.evidence(), false));
            await(bothAppendedBeforeCommit);
            return result;
        }));
        Future<CustodyEventAppendResult> secondResult = executor.submit(() -> transactionTemplate.execute(status -> {
            await(start);
            CustodyEventAppendResult result = appender.append(
                    second.evidence().getId(), second.actor(), registeredPayload(second.evidence(), false));
            await(bothAppendedBeforeCommit);
            return result;
        }));

        assertThat(List.of(get(firstResult).sequenceNumber(), get(secondResult).sequenceNumber()))
                .containsExactly(1L, 1L);
        assertThat(events.countByEvidenceId(first.evidence().getId())).isEqualTo(1);
        assertThat(events.countByEvidenceId(second.evidence().getId())).isEqualTo(1);
    }

    private EventContext saveContext(String suffix) {
        Operator actor = operators.saveAndFlush(operator("event-" + suffix, OperatorRole.ADMIN));
        CustodyCase custodyCase = custodyCases.saveAndFlush(custodyCase("Appender case " + suffix, actor));
        DigitalEvidence evidence = evidences.saveAndFlush(evidence(custodyCase, actor, "APP-" + suffix));
        return new EventContext(actor, custodyCase, evidence);
    }

    private static EvidenceRegisteredPayload registeredPayload(DigitalEvidence evidence, boolean backfilled) {
        return new EvidenceRegisteredPayload(
                backfilled,
                evidence.getReferenceTag(),
                evidence.getTitle(),
                evidence.getDescription(),
                evidence.getStatus(),
                evidence.getSourceType(),
                evidence.getSourceDescription(),
                evidence.getSourceManufacturer(),
                evidence.getSourceModel(),
                evidence.getSourceSerialNumber(),
                evidence.getSourceLogicalIdentifier(),
                evidence.getAcquisitionMethod(),
                evidence.getAcquiredAt(),
                evidence.getAcquisitionLocation(),
                evidence.getAcquisitionToolName(),
                evidence.getAcquisitionToolVersion(),
                evidence.getAcquisitionNotes(),
                evidence.getOriginalFilename(),
                evidence.getFileExtension(),
                evidence.getMediaType(),
                evidence.getFileSize(),
                evidence.getContentSha256(),
                evidence.getContextualSha256(),
                evidence.getUploadedBy().getId(),
                evidence.getCurrentHolder().getId());
    }

    private static IntegrityVerifiedPayload verifiedPayload() {
        return new IntegrityVerifiedPayload(
                IntegrityVerifiedPayload.SHA_256, "b".repeat(64), "b".repeat(64), true, 4096L);
    }

    private static CanonicalCustodyEvent canonical(
            EventContext context, CustodyEventAppendResult result, CustodyEventPayload payload, String previousHash) {
        return new CanonicalCustodyEvent(
                result.eventId(),
                context.custodyCase().getId(),
                context.evidence().getId(),
                context.actor().getId(),
                context.actor().getRole(),
                result.sequenceNumber(),
                result.eventType(),
                result.occurredAt(),
                CanonicalCustodyEvent.PAYLOAD_VERSION,
                payload,
                previousHash);
    }

    private static void await(CyclicBarrier barrier) {
        try {
            barrier.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while coordinating custody appends", exception);
        } catch (BrokenBarrierException | TimeoutException exception) {
            throw new IllegalStateException("Timed out while coordinating custody appends", exception);
        }
    }

    private static CustodyEventAppendResult get(Future<CustodyEventAppendResult> future) throws Exception {
        return future.get(10, TimeUnit.SECONDS);
    }

    private void cleanDatabaseInDependencyOrder() {
        jdbcTemplate.execute("TRUNCATE TABLE custody_events");
        evidences.deleteAllInBatch();
        memberships.deleteAllInBatch();
        custodyCases.deleteAllInBatch();
        operators.deleteAllInBatch();
    }

    private record EventContext(Operator actor, CustodyCase custodyCase, DigitalEvidence evidence) {}

    private static final class RollbackMarker extends RuntimeException {}
}
