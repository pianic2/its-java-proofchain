package it.itsprodigi.proofchain.evidence.application;

import static it.itsprodigi.proofchain.custodyevent.domain.CustodyEventFixtures.custodyCase;
import static it.itsprodigi.proofchain.custodyevent.domain.CustodyEventFixtures.evidence;
import static it.itsprodigi.proofchain.custodyevent.domain.CustodyEventFixtures.operator;
import static it.itsprodigi.proofchain.support.OperationalCommandTestSupport.authenticated;
import static it.itsprodigi.proofchain.support.OperationalCommandTestSupport.await;
import static it.itsprodigi.proofchain.support.OperationalCommandTestSupport.awaitLockWaiters;
import static it.itsprodigi.proofchain.support.OperationalCommandTestSupport.principal;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import it.itsprodigi.proofchain.custodycase.domain.CaseMembership;
import it.itsprodigi.proofchain.custodycase.domain.CustodyCase;
import it.itsprodigi.proofchain.custodycase.persistence.CaseMembershipRepository;
import it.itsprodigi.proofchain.custodycase.persistence.CustodyCaseRepository;
import it.itsprodigi.proofchain.custodyevent.application.CustodyEventAppendResult;
import it.itsprodigi.proofchain.custodyevent.domain.CustodyEvent;
import it.itsprodigi.proofchain.custodyevent.domain.EventType;
import it.itsprodigi.proofchain.custodyevent.persistence.CustodyEventRepository;
import it.itsprodigi.proofchain.custodyevent.protocol.CustodyEventHashing;
import it.itsprodigi.proofchain.custodyevent.protocol.CustodyTransferredPayload;
import it.itsprodigi.proofchain.custodyevent.protocol.EvidenceReleasedPayload;
import it.itsprodigi.proofchain.custodyevent.protocol.EvidenceSealedPayload;
import it.itsprodigi.proofchain.evidence.api.EvidenceOperationResponse;
import it.itsprodigi.proofchain.evidence.api.PatchEvidenceMetadataRequest;
import it.itsprodigi.proofchain.evidence.domain.DigitalEvidence;
import it.itsprodigi.proofchain.evidence.domain.EvidenceStatus;
import it.itsprodigi.proofchain.evidence.persistence.DigitalEvidenceRepository;
import it.itsprodigi.proofchain.operator.domain.Operator;
import it.itsprodigi.proofchain.operator.domain.OperatorRole;
import it.itsprodigi.proofchain.operator.persistence.OperatorRepository;
import it.itsprodigi.proofchain.support.PostgreSqlIntegrationTest;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.json.JsonMapper;

/**
 * Deterministic metadata update concurrency, coordinated with latches and PostgreSQL lock observation only.
 *
 * <p>Blocking a command right after it acquired the evidence write lock pins it inside the frozen lock order, so the
 * competing command provably waits on the database row lock instead of on a timer.
 */
class EvidenceMetadataUpdateConcurrencyIT extends PostgreSqlIntegrationTest {

    private static final String REASON = "Concurrent metadata correction.";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private EvidenceMetadataUpdateService metadata;

    @Autowired
    private EvidenceOperationalCommandService commands;

    @MockitoSpyBean
    private EvidenceCommandResponseMapper responses;

    @Autowired
    private CaseMembershipRepository memberships;

    @Autowired
    private CustodyEventRepository events;

    @Autowired
    private DigitalEvidenceRepository evidences;

    @Autowired
    private CustodyCaseRepository custodyCases;

    @Autowired
    private OperatorRepository operators;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private ExecutorService executor;
    private Operator manager;
    private Operator officer;
    private CustodyCase owningCase;
    private DigitalEvidence first;
    private DigitalEvidence second;

    @BeforeEach
    void setUp() {
        cleanDatabaseInDependencyOrder();
        executor = Executors.newFixedThreadPool(4);
        manager = operators.saveAndFlush(operator("meta-race-manager", OperatorRole.CASE_MANAGER));
        officer = operators.saveAndFlush(operator("meta-race-officer", OperatorRole.EVIDENCE_OFFICER));
        owningCase = custodyCases.saveAndFlush(custodyCase("Metadata race case", manager));
        memberships.saveAndFlush(CaseMembership.assign(owningCase, manager, manager));
        memberships.saveAndFlush(CaseMembership.assign(owningCase, officer, manager));
        first = evidences.saveAndFlush(evidence(owningCase, officer, "META.A"));
        second = evidences.saveAndFlush(evidence(owningCase, officer, "META.B"));
    }

    @AfterEach
    void tearDown() {
        if (executor != null) {
            executor.shutdownNow();
        }
        SecurityContextHolder.clearContext();
        cleanDatabaseInDependencyOrder();
    }

    @Test
    void twoUpdatesOnTheSameEvidenceSerializeAndTheSecondAppliesToTheCommittedFirstResult() throws Exception {
        CountDownLatch insideLocks = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        blockFirstEvidenceLock(insideLocks, release);

        Future<EvidenceOperationResponse> winner =
                executor.submit(() -> update(first.getId(), "{\"title\":\"Winner title\",\"reason\":\"%s\"}"));
        assertThat(insideLocks.await(20, TimeUnit.SECONDS)).isTrue();
        Future<EvidenceOperationResponse> queued = executor.submit(
                () -> update(first.getId(), "{\"acquisitionNotes\":\"Queued notes\",\"reason\":\"%s\"}"));
        awaitLockWaiters(jdbcTemplate, 1);
        release.countDown();

        long winnerSequence = winner.get(20, TimeUnit.SECONDS).eventSummary().sequenceNumber();
        long queuedSequence = queued.get(20, TimeUnit.SECONDS).eventSummary().sequenceNumber();
        List<CustodyEvent> timeline = events.findAllByEvidenceIdOrderBySequenceNumberAsc(first.getId());
        DigitalEvidence reloaded =
                evidences.findByIdForVisibility(first.getId()).orElseThrow();

        assertThat(List.of(winnerSequence, queuedSequence)).containsExactly(1L, 2L);
        assertThat(timeline)
                .extracting(CustodyEvent::getEventType)
                .containsExactly(EventType.METADATA_UPDATED, EventType.METADATA_UPDATED);
        assertThat(timeline.getFirst().getPreviousHash()).isEqualTo(CustodyEventHashing.ZERO_HASH);
        assertThat(timeline.getLast().getPreviousHash())
                .isEqualTo(timeline.getFirst().getEventHash());
        assertThat(reloaded.getCustodyEventCount()).isEqualTo(2L);
        assertThat(reloaded.getCustodyChainHeadHash())
                .isEqualTo(timeline.getLast().getEventHash());
        assertThat(reloaded.getTitle())
                .as("the queued update merged onto the committed first result")
                .isEqualTo("Winner title");
        assertThat(reloaded.getAcquisitionNotes()).isEqualTo("Queued notes");
        assertThat(reloaded.getUpdatedAt()).isEqualTo(timeline.getLast().getOccurredAt());
    }

    @Test
    void reEvaluatesAStaleNoOpAfterTheEvidenceLockIsAcquired() throws Exception {
        CountDownLatch insideLocks = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        blockFirstEvidenceLock(insideLocks, release);
        String body = "{\"title\":\"Same new title\",\"reason\":\"%s\"}";

        Future<EvidenceOperationResponse> winner = executor.submit(() -> update(first.getId(), body));
        assertThat(insideLocks.await(20, TimeUnit.SECONDS)).isTrue();
        Future<EvidenceOperationResponse> stale = executor.submit(() -> update(first.getId(), body));
        awaitLockWaiters(jdbcTemplate, 1);
        release.countDown();

        assertThat(winner.get(20, TimeUnit.SECONDS).eventSummary().sequenceNumber())
                .isEqualTo(1L);
        assertThatThrownBy(() -> stale.get(20, TimeUnit.SECONDS)).hasCauseInstanceOf(MetadataUpdateNoOpException.class);
        assertThat(events.countByEvidenceId(first.getId())).isEqualTo(1L);
        assertThat(evidences.findByIdForVisibility(first.getId()).orElseThrow().getTitle())
                .isEqualTo("Same new title");
    }

    @Test
    void updatesOnDifferentEvidenceOfTheSameCaseAreNotGloballySerialized() throws Exception {
        CountDownLatch insideLocks = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        blockFirstEvidenceLock(insideLocks, release);
        String body = "{\"title\":\"Independent title\",\"reason\":\"%s\"}";

        Future<EvidenceOperationResponse> blocking = executor.submit(() -> update(first.getId(), body));
        assertThat(insideLocks.await(20, TimeUnit.SECONDS)).isTrue();
        Future<EvidenceOperationResponse> independent = executor.submit(() -> update(second.getId(), body));

        assertThat(independent.get(20, TimeUnit.SECONDS).eventSummary().sequenceNumber())
                .as("an update on another evidence of the same open case must not wait")
                .isEqualTo(1L);
        release.countDown();
        assertThat(blocking.get(20, TimeUnit.SECONDS).eventSummary().sequenceNumber())
                .isEqualTo(1L);
        assertThat(events.countByEvidenceId(first.getId())).isEqualTo(1L);
        assertThat(events.countByEvidenceId(second.getId())).isEqualTo(1L);
    }

    @Test
    void sealWinsTheRaceAndTheLaterUpdateIsRejectedAfterTheSeal() throws Exception {
        CountDownLatch insideLocks = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        Future<EvidenceOperationResponse> sealing = executor.submit(() -> authenticated(
                manager,
                () -> commands.execute(
                        EvidenceOperationalCommand.EVIDENCE_SEAL, first.getId(), principal(manager), context -> {
                            insideLocks.countDown();
                            await(release);
                            UUID holderId =
                                    context.evidence().getCurrentHolder().getId();
                            EvidenceStatus previousStatus = context.evidence().getStatus();
                            context.evidence().seal();
                            return new EvidenceSealedPayload(previousStatus, EvidenceStatus.SEALED, holderId, REASON);
                        })));
        assertThat(insideLocks.await(20, TimeUnit.SECONDS)).isTrue();
        Future<EvidenceOperationResponse> queued =
                executor.submit(() -> update(first.getId(), "{\"title\":\"Too late\",\"reason\":\"%s\"}"));
        awaitLockWaiters(jdbcTemplate, 1);
        release.countDown();

        assertThat(sealing.get(20, TimeUnit.SECONDS).eventSummary().eventType()).isEqualTo(EventType.EVIDENCE_SEALED);
        assertThatThrownBy(() -> queued.get(20, TimeUnit.SECONDS))
                .hasCauseInstanceOf(InvalidEvidenceStateException.class);
        DigitalEvidence reloaded =
                evidences.findByIdForVisibility(first.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(EvidenceStatus.SEALED);
        assertThat(reloaded.getTitle()).isEqualTo("Forensic disk image");
        assertThat(events.countByEvidenceId(first.getId())).isEqualTo(1L);
    }

    @Test
    void releaseWinsTheRaceAndTheLaterUpdateIsRejectedAfterTheRelease() throws Exception {
        CountDownLatch insideLocks = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        Future<EvidenceOperationResponse> releasing = executor.submit(() -> authenticated(
                manager,
                () -> commands.execute(
                        EvidenceOperationalCommand.EVIDENCE_RELEASE, first.getId(), principal(manager), context -> {
                            insideLocks.countDown();
                            await(release);
                            UUID previousHolderId =
                                    context.evidence().getCurrentHolder().getId();
                            EvidenceStatus previousStatus = context.evidence().getStatus();
                            context.evidence().release();
                            return new EvidenceReleasedPayload(
                                    previousStatus, EvidenceStatus.RELEASED, previousHolderId, null, REASON);
                        })));
        assertThat(insideLocks.await(20, TimeUnit.SECONDS)).isTrue();
        Future<EvidenceOperationResponse> queued =
                executor.submit(() -> update(first.getId(), "{\"title\":\"Too late\",\"reason\":\"%s\"}"));
        awaitLockWaiters(jdbcTemplate, 1);
        release.countDown();

        assertThat(releasing.get(20, TimeUnit.SECONDS).eventSummary().eventType())
                .isEqualTo(EventType.EVIDENCE_RELEASED);
        assertThatThrownBy(() -> queued.get(20, TimeUnit.SECONDS))
                .hasCauseInstanceOf(InvalidEvidenceStateException.class);
        assertThat(evidences.findByIdForVisibility(first.getId()).orElseThrow().getStatus())
                .isEqualTo(EvidenceStatus.RELEASED);
        assertThat(events.countByEvidenceId(first.getId())).isEqualTo(1L);
    }

    @Test
    void whenTheUpdateLocksFirstBothTheUpdateAndTheQueuedSealArePreservedInLockOrder() throws Exception {
        CountDownLatch insideLocks = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        blockFirstEvidenceLock(insideLocks, release);

        Future<EvidenceOperationResponse> updating =
                executor.submit(() -> update(first.getId(), "{\"title\":\"Updated in time\",\"reason\":\"%s\"}"));
        assertThat(insideLocks.await(20, TimeUnit.SECONDS)).isTrue();
        Future<EvidenceOperationResponse> sealing = executor.submit(() -> authenticated(
                manager,
                () -> commands.execute(
                        EvidenceOperationalCommand.EVIDENCE_SEAL, first.getId(), principal(manager), context -> {
                            UUID holderId =
                                    context.evidence().getCurrentHolder().getId();
                            EvidenceStatus previousStatus = context.evidence().getStatus();
                            context.evidence().seal();
                            return new EvidenceSealedPayload(previousStatus, EvidenceStatus.SEALED, holderId, REASON);
                        })));
        awaitLockWaiters(jdbcTemplate, 1);
        release.countDown();

        assertThat(updating.get(20, TimeUnit.SECONDS).eventSummary().sequenceNumber())
                .isEqualTo(1L);
        assertThat(sealing.get(20, TimeUnit.SECONDS).eventSummary().sequenceNumber())
                .isEqualTo(2L);
        List<CustodyEvent> timeline = events.findAllByEvidenceIdOrderBySequenceNumberAsc(first.getId());
        DigitalEvidence reloaded =
                evidences.findByIdForVisibility(first.getId()).orElseThrow();

        assertThat(timeline)
                .extracting(CustodyEvent::getEventType)
                .containsExactly(EventType.METADATA_UPDATED, EventType.EVIDENCE_SEALED);
        assertThat(timeline.getLast().getPreviousHash())
                .isEqualTo(timeline.getFirst().getEventHash());
        assertThat(reloaded.getStatus()).isEqualTo(EvidenceStatus.SEALED);
        assertThat(reloaded.getTitle()).isEqualTo("Updated in time");
    }

    @Test
    void aQueuedTransferAndTheWinningUpdatePreserveBothEffectsInLockOrder() throws Exception {
        CountDownLatch insideLocks = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        blockFirstEvidenceLock(insideLocks, release);

        Future<EvidenceOperationResponse> updating = executor.submit(
                () -> update(first.getId(), "{\"title\":\"Updated before handover\",\"reason\":\"%s\"}"));
        assertThat(insideLocks.await(20, TimeUnit.SECONDS)).isTrue();
        Future<EvidenceOperationResponse> transferring = executor.submit(() -> authenticated(
                manager,
                () -> commands.execute(
                        EvidenceOperationalCommand.CUSTODY_TRANSFER, first.getId(), principal(manager), context -> {
                            UUID previousHolderId =
                                    context.evidence().getCurrentHolder().getId();
                            context.evidence().transferTo(context.actor());
                            return new CustodyTransferredPayload(
                                    previousHolderId, context.actor().getId(), REASON);
                        })));
        awaitLockWaiters(jdbcTemplate, 1);
        release.countDown();

        assertThat(updating.get(20, TimeUnit.SECONDS).eventSummary().sequenceNumber())
                .isEqualTo(1L);
        assertThat(transferring.get(20, TimeUnit.SECONDS).eventSummary().sequenceNumber())
                .isEqualTo(2L);
        List<CustodyEvent> timeline = events.findAllByEvidenceIdOrderBySequenceNumberAsc(first.getId());
        DigitalEvidence reloaded =
                evidences.findByIdForVisibility(first.getId()).orElseThrow();

        assertThat(timeline)
                .extracting(CustodyEvent::getEventType)
                .containsExactly(EventType.METADATA_UPDATED, EventType.CUSTODY_TRANSFERRED);
        assertThat(timeline.getLast().getPreviousHash())
                .isEqualTo(timeline.getFirst().getEventHash());
        assertThat(reloaded.getTitle()).isEqualTo("Updated before handover");
        assertThat(reloaded.getCurrentHolder().getId()).isEqualTo(manager.getId());
    }

    /**
     * Pins the first command just before its transaction commits, while it still holds the custody case read lock and
     * the evidence write lock, so a competing command provably waits on the database row lock and not on a timer.
     */
    private void blockFirstEvidenceLock(CountDownLatch insideLocks, CountDownLatch release) {
        AtomicBoolean firstArrival = new AtomicBoolean(true);
        doAnswer(invocation -> {
                    Object response = invocation.callRealMethod();
                    if (firstArrival.compareAndSet(true, false)) {
                        insideLocks.countDown();
                        await(release);
                    }
                    return response;
                })
                .when(responses)
                .toResponse(any(DigitalEvidence.class), any(Operator.class), any(CustodyEventAppendResult.class));
    }

    private EvidenceOperationResponse update(UUID evidenceId, String bodyTemplate) {
        PatchEvidenceMetadataRequest request =
                JSON.readValue(bodyTemplate.formatted(REASON), PatchEvidenceMetadataRequest.class);
        return authenticated(manager, () -> metadata.update(evidenceId, request, principal(manager)));
    }

    private void cleanDatabaseInDependencyOrder() {
        jdbcTemplate.execute("TRUNCATE TABLE custody_events");
        evidences.deleteAllInBatch();
        memberships.deleteAllInBatch();
        custodyCases.deleteAllInBatch();
        operators.deleteAllInBatch();
    }
}
