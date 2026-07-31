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
import it.itsprodigi.proofchain.evidence.api.EvidenceOperationResponse;
import it.itsprodigi.proofchain.evidence.api.ReleaseEvidenceRequest;
import it.itsprodigi.proofchain.evidence.api.SealEvidenceRequest;
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
 * Deterministic seal and release concurrency, coordinated with latches and PostgreSQL lock observation only.
 *
 * <p>Blocking a command right before its transaction commits pins it inside the frozen lock order while it still holds
 * the custody case read lock and the evidence write lock, so the competing command provably waits on the database row
 * lock instead of on a timer. Every proof asserts the committed state, the appended event types and a gapless,
 * duplicate-free sequence.
 */
class EvidenceLifecycleConcurrencyIT extends PostgreSqlIntegrationTest {

    private static final String REASON = "Concurrent lifecycle command.";

    @Autowired
    private EvidenceSealService seals;

    @Autowired
    private EvidenceReleaseService releases;

    @Autowired
    private CustodyTransferService transfers;

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
        manager = operators.saveAndFlush(operator("lifecycle-race-manager", OperatorRole.CASE_MANAGER));
        officer = operators.saveAndFlush(operator("lifecycle-race-officer", OperatorRole.EVIDENCE_OFFICER));
        otherOfficer = operators.saveAndFlush(operator("lifecycle-race-other", OperatorRole.EVIDENCE_OFFICER));
        owningCase = custodyCases.saveAndFlush(custodyCase("Lifecycle race case", manager));
        memberships.saveAndFlush(CaseMembership.assign(owningCase, manager, manager));
        memberships.saveAndFlush(CaseMembership.assign(owningCase, officer, manager));
        memberships.saveAndFlush(CaseMembership.assign(owningCase, otherOfficer, manager));
        first = evidences.saveAndFlush(evidence(owningCase, officer, "LRACE.A"));
        second = evidences.saveAndFlush(evidence(owningCase, officer, "LRACE.B"));
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
    void twoSealsOnTheSameEvidenceProduceExactlyOneSuccessAndOneInvalidEvidenceState() throws Exception {
        Blocker blocker = blockFirstCommit();

        Future<EvidenceOperationResponse> winner = executor.submit(() -> seal(first.getId()));
        blocker.awaitArrival();
        Future<EvidenceOperationResponse> queued = executor.submit(() -> seal(first.getId()));
        awaitLockWaiters(jdbcTemplate, 1);
        blocker.release();

        assertThat(winner.get(20, TimeUnit.SECONDS).eventSummary().sequenceNumber())
                .isEqualTo(1L);
        assertThatThrownBy(() -> queued.get(20, TimeUnit.SECONDS))
                .hasCauseInstanceOf(InvalidEvidenceStateException.class);
        DigitalEvidence reloaded = reload(first);
        assertThat(reloaded.getStatus()).isEqualTo(EvidenceStatus.SEALED);
        assertThat(reloaded.getCurrentHolder().getId()).isEqualTo(officer.getId());
        assertThat(sequences(first)).containsExactly(1L);
    }

    @Test
    void twoReleasesOnTheSameEvidenceProduceExactlyOneSuccessAndOneInvalidEvidenceState() throws Exception {
        Blocker blocker = blockFirstCommit();

        Future<EvidenceOperationResponse> winner = executor.submit(() -> release(first.getId()));
        blocker.awaitArrival();
        Future<EvidenceOperationResponse> queued = executor.submit(() -> release(first.getId()));
        awaitLockWaiters(jdbcTemplate, 1);
        blocker.release();

        assertThat(winner.get(20, TimeUnit.SECONDS).evidence().status()).isEqualTo(EvidenceStatus.RELEASED);
        assertThatThrownBy(() -> queued.get(20, TimeUnit.SECONDS))
                .hasCauseInstanceOf(InvalidEvidenceStateException.class);
        DigitalEvidence reloaded = reload(first);
        assertThat(reloaded.getStatus()).isEqualTo(EvidenceStatus.RELEASED);
        assertThat(reloaded.getCurrentHolder()).isNull();
        assertThat(sequences(first)).containsExactly(1L);
    }

    /**
     * The lock winner decides the outcome, but neither ordering may produce an impossible state or a duplicate
     * sequence: a sealed-then-released evidence keeps both events, and a released-then-sealed attempt is refused.
     */
    @Test
    void sealVersusReleaseNeverProducesAnImpossibleStateOrADuplicateSequence() throws Exception {
        Blocker sealFirstBlocker = blockFirstCommit();

        Future<EvidenceOperationResponse> sealing = executor.submit(() -> seal(first.getId()));
        sealFirstBlocker.awaitArrival();
        Future<EvidenceOperationResponse> releasing = executor.submit(() -> release(first.getId()));
        awaitLockWaiters(jdbcTemplate, 1);
        sealFirstBlocker.release();

        assertThat(sealing.get(20, TimeUnit.SECONDS).evidence().status()).isEqualTo(EvidenceStatus.SEALED);
        assertThat(releasing.get(20, TimeUnit.SECONDS).evidence().status()).isEqualTo(EvidenceStatus.RELEASED);
        assertThat(reload(first).getStatus()).isEqualTo(EvidenceStatus.RELEASED);
        assertThat(reload(first).getCurrentHolder()).isNull();
        assertThat(eventTypes(first)).containsExactly(EventType.EVIDENCE_SEALED, EventType.EVIDENCE_RELEASED);
        assertThat(sequences(first)).containsExactly(1L, 2L);
        assertLinkedChain(first);

        Blocker releaseFirstBlocker = blockFirstCommit();
        Future<EvidenceOperationResponse> releasingSecond = executor.submit(() -> release(second.getId()));
        releaseFirstBlocker.awaitArrival();
        Future<EvidenceOperationResponse> sealingSecond = executor.submit(() -> seal(second.getId()));
        awaitLockWaiters(jdbcTemplate, 1);
        releaseFirstBlocker.release();

        assertThat(releasingSecond.get(20, TimeUnit.SECONDS).evidence().status())
                .isEqualTo(EvidenceStatus.RELEASED);
        assertThatThrownBy(() -> sealingSecond.get(20, TimeUnit.SECONDS))
                .hasCauseInstanceOf(InvalidEvidenceStateException.class);
        assertThat(reload(second).getStatus()).isEqualTo(EvidenceStatus.RELEASED);
        assertThat(eventTypes(second)).containsExactly(EventType.EVIDENCE_RELEASED);
        assertThat(sequences(second)).containsExactly(1L);
    }

    /** The transfer winner changes the holder, and the queued seal freezes the evidence around that new holder. */
    @Test
    void transferWinnerIsSealedAroundTheNewHolder() throws Exception {
        Blocker blocker = blockFirstCommit();

        Future<EvidenceOperationResponse> transferring =
                executor.submit(() -> transfer(first.getId(), otherOfficer.getId()));
        blocker.awaitArrival();
        Future<EvidenceOperationResponse> sealing = executor.submit(() -> seal(first.getId()));
        awaitLockWaiters(jdbcTemplate, 1);
        blocker.release();

        assertThat(transferring
                        .get(20, TimeUnit.SECONDS)
                        .evidence()
                        .currentHolder()
                        .id())
                .isEqualTo(otherOfficer.getId());
        EvidenceOperationResponse sealed = sealing.get(20, TimeUnit.SECONDS);
        assertThat(sealed.evidence().status()).isEqualTo(EvidenceStatus.SEALED);
        assertThat(sealed.evidence().currentHolder().id())
                .as("the seal freezes the holder committed by the transfer winner")
                .isEqualTo(otherOfficer.getId());
        assertThat(eventTypes(first)).containsExactly(EventType.CUSTODY_TRANSFERRED, EventType.EVIDENCE_SEALED);
        assertThat(sequences(first)).containsExactly(1L, 2L);
        assertLinkedChain(first);
    }

    /** The release winner ends custody, so the queued transfer can no longer find a holder to move. */
    @Test
    void releaseWinnerMakesTheQueuedTransferFailWithInvalidEvidenceState() throws Exception {
        Blocker blocker = blockFirstCommit();

        Future<EvidenceOperationResponse> releasing = executor.submit(() -> release(first.getId()));
        blocker.awaitArrival();
        Future<EvidenceOperationResponse> transferring =
                executor.submit(() -> transfer(first.getId(), otherOfficer.getId()));
        awaitLockWaiters(jdbcTemplate, 1);
        blocker.release();

        assertThat(releasing.get(20, TimeUnit.SECONDS).evidence().status()).isEqualTo(EvidenceStatus.RELEASED);
        assertThatThrownBy(() -> transferring.get(20, TimeUnit.SECONDS))
                .hasCauseInstanceOf(InvalidEvidenceStateException.class);
        assertThat(reload(first).getCurrentHolder()).isNull();
        assertThat(eventTypes(first)).containsExactly(EventType.EVIDENCE_RELEASED);
        assertThat(sequences(first)).containsExactly(1L);
    }

    @Test
    void lifecycleCommandsOnDifferentEvidenceOfTheSameCaseAreNotGloballySerialized() throws Exception {
        Blocker blocker = blockFirstCommit();

        Future<EvidenceOperationResponse> blocking = executor.submit(() -> seal(first.getId()));
        blocker.awaitArrival();
        Future<EvidenceOperationResponse> independent = executor.submit(() -> release(second.getId()));

        assertThat(independent.get(20, TimeUnit.SECONDS).evidence().status())
                .as("a lifecycle command on another evidence of the same open case must not wait")
                .isEqualTo(EvidenceStatus.RELEASED);
        blocker.release();
        assertThat(blocking.get(20, TimeUnit.SECONDS).evidence().status()).isEqualTo(EvidenceStatus.SEALED);
        assertThat(events.countByEvidenceId(first.getId())).isEqualTo(1L);
        assertThat(events.countByEvidenceId(second.getId())).isEqualTo(1L);
    }

    /**
     * Pins the first arriving command just before its transaction commits, while it still holds the custody case read
     * lock and the evidence write lock.
     */
    private Blocker blockFirstCommit() {
        CountDownLatch arrived = new CountDownLatch(1);
        CountDownLatch proceed = new CountDownLatch(1);
        AtomicBoolean firstArrival = new AtomicBoolean(true);
        doAnswer(invocation -> {
                    Object response = invocation.callRealMethod();
                    if (firstArrival.compareAndSet(true, false)) {
                        arrived.countDown();
                        await(proceed);
                    }
                    return response;
                })
                .when(responses)
                .toResponse(any(DigitalEvidence.class), any(Operator.class), any(CustodyEventAppendResult.class));
        return new Blocker(arrived, proceed);
    }

    private record Blocker(CountDownLatch arrived, CountDownLatch proceed) {

        void awaitArrival() throws InterruptedException {
            assertThat(arrived.await(20, TimeUnit.SECONDS)).isTrue();
        }

        void release() {
            proceed.countDown();
        }
    }

    private EvidenceOperationResponse seal(UUID evidenceId) {
        return authenticated(
                manager, () -> seals.seal(evidenceId, new SealEvidenceRequest(REASON), principal(manager)));
    }

    private EvidenceOperationResponse release(UUID evidenceId) {
        return authenticated(
                manager, () -> releases.release(evidenceId, new ReleaseEvidenceRequest(REASON), principal(manager)));
    }

    private EvidenceOperationResponse transfer(UUID evidenceId, UUID newHolderId) {
        return authenticated(
                manager,
                () -> transfers.transfer(
                        evidenceId, new TransferCustodyRequest(newHolderId, REASON), principal(manager)));
    }

    private DigitalEvidence reload(DigitalEvidence evidence) {
        return evidences.findByIdForVisibility(evidence.getId()).orElseThrow();
    }

    private List<Long> sequences(DigitalEvidence evidence) {
        return events.findAllByEvidenceIdOrderBySequenceNumberAsc(evidence.getId()).stream()
                .map(CustodyEvent::getSequenceNumber)
                .toList();
    }

    private List<EventType> eventTypes(DigitalEvidence evidence) {
        return events.findAllByEvidenceIdOrderBySequenceNumberAsc(evidence.getId()).stream()
                .map(CustodyEvent::getEventType)
                .toList();
    }

    private void assertLinkedChain(DigitalEvidence evidence) {
        List<CustodyEvent> timeline = events.findAllByEvidenceIdOrderBySequenceNumberAsc(evidence.getId());
        assertThat(timeline.getFirst().getPreviousHash()).isEqualTo(CustodyEventHashing.ZERO_HASH);
        assertThat(timeline.getLast().getPreviousHash())
                .isEqualTo(timeline.getFirst().getEventHash());
        assertThat(reload(evidence).getCustodyChainHeadHash())
                .isEqualTo(timeline.getLast().getEventHash());
    }

    private void cleanDatabaseInDependencyOrder() {
        jdbcTemplate.execute("TRUNCATE TABLE custody_events");
        evidences.deleteAllInBatch();
        memberships.deleteAllInBatch();
        custodyCases.deleteAllInBatch();
        operators.deleteAllInBatch();
    }
}
