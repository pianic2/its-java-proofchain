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
import it.itsprodigi.proofchain.custodyevent.protocol.EvidenceReleasedPayload;
import it.itsprodigi.proofchain.custodyevent.protocol.EvidenceSealedPayload;
import it.itsprodigi.proofchain.evidence.api.EvidenceOperationResponse;
import it.itsprodigi.proofchain.evidence.api.TransferCustodyRequest;
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

/**
 * Deterministic custody transfer concurrency, coordinated with latches and PostgreSQL lock observation only.
 *
 * <p>Blocking right after the evidence write lock is acquired pins a command inside the frozen lock order, so the
 * competing command provably waits on the database row lock instead of on a timer.
 */
class CustodyTransferConcurrencyIT extends PostgreSqlIntegrationTest {

    private static final String REASON = "Concurrent handover.";

    @Autowired
    private CustodyTransferService transfers;

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
    private Operator otherOfficer;
    private CustodyCase owningCase;
    private DigitalEvidence first;
    private DigitalEvidence second;

    @BeforeEach
    void setUp() {
        cleanDatabaseInDependencyOrder();
        executor = Executors.newFixedThreadPool(4);
        manager = operators.saveAndFlush(operator("race-manager", OperatorRole.CASE_MANAGER));
        officer = operators.saveAndFlush(operator("race-officer", OperatorRole.EVIDENCE_OFFICER));
        otherOfficer = operators.saveAndFlush(operator("race-other-officer", OperatorRole.EVIDENCE_OFFICER));
        owningCase = custodyCases.saveAndFlush(custodyCase("Race case", manager));
        memberships.saveAndFlush(CaseMembership.assign(owningCase, manager, manager));
        memberships.saveAndFlush(CaseMembership.assign(owningCase, officer, manager));
        memberships.saveAndFlush(CaseMembership.assign(owningCase, otherOfficer, manager));
        first = evidences.saveAndFlush(evidence(owningCase, officer, "RACE.A"));
        second = evidences.saveAndFlush(evidence(owningCase, officer, "RACE.B"));
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
    void twoTransfersOnTheSameEvidenceSerializeIntoOneFinalHolderAndAGaplessChain() throws Exception {
        CountDownLatch insideLocks = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        blockFirstEvidenceLock(insideLocks, release);

        Future<EvidenceOperationResponse> winner =
                executor.submit(() -> transfer(manager, first.getId(), manager.getId()));
        assertThat(insideLocks.await(20, TimeUnit.SECONDS)).isTrue();
        Future<EvidenceOperationResponse> queued =
                executor.submit(() -> transfer(manager, first.getId(), otherOfficer.getId()));
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
                .containsExactly(EventType.CUSTODY_TRANSFERRED, EventType.CUSTODY_TRANSFERRED);
        assertThat(timeline.getFirst().getPreviousHash()).isEqualTo(CustodyEventHashing.ZERO_HASH);
        assertThat(timeline.getLast().getPreviousHash())
                .isEqualTo(timeline.getFirst().getEventHash());
        assertThat(reloaded.getCustodyEventCount()).isEqualTo(2L);
        assertThat(reloaded.getCustodyChainHeadHash())
                .isEqualTo(timeline.getLast().getEventHash());
        assertThat(reloaded.getCurrentHolder().getId()).isEqualTo(otherOfficer.getId());
        assertThat(reloaded.getUpdatedAt()).isEqualTo(timeline.getLast().getOccurredAt());
    }

    @Test
    void reEvaluatesAStaleNoOpAfterTheEvidenceLockIsAcquired() throws Exception {
        CountDownLatch insideLocks = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        blockFirstEvidenceLock(insideLocks, release);

        Future<EvidenceOperationResponse> winner =
                executor.submit(() -> transfer(manager, first.getId(), manager.getId()));
        assertThat(insideLocks.await(20, TimeUnit.SECONDS)).isTrue();
        Future<EvidenceOperationResponse> stale =
                executor.submit(() -> transfer(manager, first.getId(), manager.getId()));
        awaitLockWaiters(jdbcTemplate, 1);
        release.countDown();

        assertThat(winner.get(20, TimeUnit.SECONDS).eventSummary().sequenceNumber())
                .isEqualTo(1L);
        assertThatThrownBy(() -> stale.get(20, TimeUnit.SECONDS))
                .hasCauseInstanceOf(CustodyTransferNoOpException.class);
        assertThat(events.countByEvidenceId(first.getId())).isEqualTo(1L);
        assertThat(evidences
                        .findByIdForVisibility(first.getId())
                        .orElseThrow()
                        .getCurrentHolder()
                        .getId())
                .isEqualTo(manager.getId());
    }

    @Test
    void transfersOnDifferentEvidenceOfTheSameCaseAreNotGloballySerialized() throws Exception {
        CountDownLatch insideLocks = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        blockFirstEvidenceLock(insideLocks, release);

        Future<EvidenceOperationResponse> blocking =
                executor.submit(() -> transfer(manager, first.getId(), manager.getId()));
        assertThat(insideLocks.await(20, TimeUnit.SECONDS)).isTrue();
        Future<EvidenceOperationResponse> independent =
                executor.submit(() -> transfer(manager, second.getId(), manager.getId()));

        assertThat(independent.get(20, TimeUnit.SECONDS).eventSummary().sequenceNumber())
                .as("a transfer on another evidence of the same open case must not wait")
                .isEqualTo(1L);
        release.countDown();
        assertThat(blocking.get(20, TimeUnit.SECONDS).eventSummary().sequenceNumber())
                .isEqualTo(1L);
        assertThat(events.countByEvidenceId(first.getId())).isEqualTo(1L);
        assertThat(events.countByEvidenceId(second.getId())).isEqualTo(1L);
    }

    @Test
    void releaseWinsTheRaceAndTheLaterTransferFailsWithInvalidEvidenceState() throws Exception {
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
                executor.submit(() -> transfer(manager, first.getId(), manager.getId()));
        awaitLockWaiters(jdbcTemplate, 1);
        release.countDown();

        assertThat(releasing.get(20, TimeUnit.SECONDS).eventSummary().eventType())
                .isEqualTo(EventType.EVIDENCE_RELEASED);
        assertThatThrownBy(() -> queued.get(20, TimeUnit.SECONDS))
                .hasCauseInstanceOf(InvalidEvidenceStateException.class);
        DigitalEvidence reloaded =
                evidences.findByIdForVisibility(first.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(EvidenceStatus.RELEASED);
        assertThat(events.countByEvidenceId(first.getId())).isEqualTo(1L);
    }

    @Test
    void sealWinsTheRaceAndTheLaterTransferKeepsTheEvidenceSealed() throws Exception {
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
                executor.submit(() -> transfer(manager, first.getId(), manager.getId()));
        awaitLockWaiters(jdbcTemplate, 1);
        release.countDown();

        assertThat(sealing.get(20, TimeUnit.SECONDS).eventSummary().eventType()).isEqualTo(EventType.EVIDENCE_SEALED);
        assertThat(queued.get(20, TimeUnit.SECONDS).evidence().status()).isEqualTo(EvidenceStatus.SEALED);
        List<CustodyEvent> timeline = events.findAllByEvidenceIdOrderBySequenceNumberAsc(first.getId());
        DigitalEvidence reloaded =
                evidences.findByIdForVisibility(first.getId()).orElseThrow();

        assertThat(timeline)
                .extracting(CustodyEvent::getEventType)
                .containsExactly(EventType.EVIDENCE_SEALED, EventType.CUSTODY_TRANSFERRED);
        assertThat(timeline.getLast().getPreviousHash())
                .isEqualTo(timeline.getFirst().getEventHash());
        assertThat(reloaded.getStatus()).isEqualTo(EvidenceStatus.SEALED);
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

    private EvidenceOperationResponse transfer(Operator actor, UUID evidenceId, UUID newHolderId) {
        return authenticated(
                actor,
                () -> transfers.transfer(
                        evidenceId, new TransferCustodyRequest(newHolderId, REASON), principal(actor)));
    }

    private void cleanDatabaseInDependencyOrder() {
        jdbcTemplate.execute("TRUNCATE TABLE custody_events");
        evidences.deleteAllInBatch();
        memberships.deleteAllInBatch();
        custodyCases.deleteAllInBatch();
        operators.deleteAllInBatch();
    }
}
