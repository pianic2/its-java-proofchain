package it.itsprodigi.proofchain.evidence.application;

import static it.itsprodigi.proofchain.custodyevent.domain.CustodyEventFixtures.custodyCase;
import static it.itsprodigi.proofchain.custodyevent.domain.CustodyEventFixtures.operator;
import static it.itsprodigi.proofchain.support.OperationalCommandTestSupport.authenticated;
import static it.itsprodigi.proofchain.support.OperationalCommandTestSupport.await;
import static it.itsprodigi.proofchain.support.OperationalCommandTestSupport.awaitLockWaiters;
import static it.itsprodigi.proofchain.support.OperationalCommandTestSupport.principal;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

import it.itsprodigi.proofchain.custodycase.api.UpdateCaseStatusRequest;
import it.itsprodigi.proofchain.custodycase.application.CaseClosedException;
import it.itsprodigi.proofchain.custodycase.application.CustodyCaseService;
import it.itsprodigi.proofchain.custodycase.domain.CaseMembership;
import it.itsprodigi.proofchain.custodycase.domain.CaseStatus;
import it.itsprodigi.proofchain.custodycase.domain.CustodyCase;
import it.itsprodigi.proofchain.custodycase.persistence.CaseMembershipRepository;
import it.itsprodigi.proofchain.custodycase.persistence.CustodyCaseRepository;
import it.itsprodigi.proofchain.custodyevent.domain.CustodyEvent;
import it.itsprodigi.proofchain.custodyevent.domain.EventType;
import it.itsprodigi.proofchain.custodyevent.persistence.CustodyEventRepository;
import it.itsprodigi.proofchain.custodyevent.protocol.CustodyEventHashing;
import it.itsprodigi.proofchain.custodyevent.protocol.CustodyTransferredPayload;
import it.itsprodigi.proofchain.custodyevent.protocol.EvidenceReleasedPayload;
import it.itsprodigi.proofchain.evidence.api.EvidenceOperationResponse;
import it.itsprodigi.proofchain.evidence.api.IntegrityVerificationResponse;
import it.itsprodigi.proofchain.evidence.domain.AcquisitionMethod;
import it.itsprodigi.proofchain.evidence.domain.DigitalEvidence;
import it.itsprodigi.proofchain.evidence.domain.EvidenceStatus;
import it.itsprodigi.proofchain.evidence.domain.SourceType;
import it.itsprodigi.proofchain.evidence.persistence.DigitalEvidenceRepository;
import it.itsprodigi.proofchain.operator.domain.Operator;
import it.itsprodigi.proofchain.operator.domain.OperatorRole;
import it.itsprodigi.proofchain.operator.persistence.OperatorRepository;
import it.itsprodigi.proofchain.support.PostgreSqlIntegrationTest;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * Deterministic integrity-verification concurrency, coordinated with latches and PostgreSQL lock observation only.
 *
 * <p>Every proof pins a verification <em>inside</em> the storage read, which is only reachable once the custody case
 * read lock and the evidence write lock are both held. A competing command therefore provably waits on the database
 * row lock rather than on a timer, and the pin also demonstrates that the evidence write lock stays held for the whole
 * duration of the file read.
 */
class EvidenceIntegrityVerificationConcurrencyIT extends PostgreSqlIntegrationTest {

    private static final String REASON = "Concurrent operational command.";
    private static final byte[] CONTENT = "concurrent-integrity".getBytes(StandardCharsets.UTF_8);

    @TempDir
    static Path storageRoot;

    @DynamicPropertySource
    static void configureStorage(DynamicPropertyRegistry registry) {
        registry.add("proofchain.storage.root", () -> storageRoot.toString());
        registry.add("proofchain.storage.max-file-size", () -> "1MB");
    }

    private final AtomicInteger referenceTags = new AtomicInteger();

    @Autowired
    private EvidenceIntegrityVerificationService verification;

    @Autowired
    private EvidenceOperationalCommandService commands;

    @Autowired
    private CustodyCaseService cases;

    @MockitoSpyBean
    private EvidenceStoragePort storage;

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
    private Operator admin;
    private Operator manager;
    private Operator officer;
    private CustodyCase owningCase;
    private DigitalEvidence first;
    private DigitalEvidence second;

    @BeforeEach
    void setUp() throws IOException {
        cleanDatabaseInDependencyOrder();
        cleanStorage();
        executor = Executors.newFixedThreadPool(4);
        admin = operators.saveAndFlush(operator("race-integrity-admin", OperatorRole.ADMIN));
        manager = operators.saveAndFlush(operator("race-integrity-manager", OperatorRole.CASE_MANAGER));
        officer = operators.saveAndFlush(operator("race-integrity-officer", OperatorRole.EVIDENCE_OFFICER));
        owningCase = custodyCases.saveAndFlush(custodyCase("Integrity race case", manager));
        memberships.saveAndFlush(CaseMembership.assign(owningCase, manager, manager));
        memberships.saveAndFlush(CaseMembership.assign(owningCase, officer, manager));
        first = storedEvidence();
        second = storedEvidence();
    }

    @AfterEach
    void tearDown() throws IOException {
        if (executor != null) {
            executor.shutdownNow();
        }
        reset(storage);
        SecurityContextHolder.clearContext();
        cleanDatabaseInDependencyOrder();
        cleanStorage();
    }

    @Test
    void twoVerificationsOnTheSameEvidenceSerializeAndEachAppendsItsOwnEvent() throws Exception {
        CountDownLatch insideRead = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        pinFirstStorageRead(insideRead, release);

        Future<IntegrityVerificationResponse> winner = executor.submit(() -> verifyAs(manager, first.getId()));
        assertThat(insideRead.await(20, TimeUnit.SECONDS)).isTrue();
        Future<IntegrityVerificationResponse> queued = executor.submit(() -> verifyAs(officer, first.getId()));
        awaitLockWaiters(jdbcTemplate, 1);
        release.countDown();

        IntegrityVerificationResponse firstResult = winner.get(20, TimeUnit.SECONDS);
        IntegrityVerificationResponse secondResult = queued.get(20, TimeUnit.SECONDS);
        List<CustodyEvent> timeline = timeline(first.getId());
        DigitalEvidence reloaded = reload(first);

        assertThat(List.of(
                        firstResult.eventSummary().sequenceNumber(),
                        secondResult.eventSummary().sequenceNumber()))
                .containsExactly(1L, 2L);
        assertThat(firstResult.eventSummary().id())
                .isNotEqualTo(secondResult.eventSummary().id());
        assertThat(firstResult.valid()).isTrue();
        assertThat(secondResult.valid()).isTrue();
        assertThat(timeline)
                .extracting(CustodyEvent::getEventType)
                .containsExactly(EventType.INTEGRITY_VERIFIED, EventType.INTEGRITY_VERIFIED);
        assertThat(timeline.getFirst().getPreviousHash()).isEqualTo(CustodyEventHashing.ZERO_HASH);
        assertThat(timeline.getLast().getPreviousHash())
                .isEqualTo(timeline.getFirst().getEventHash());
        assertThat(reloaded.getCustodyEventCount()).isEqualTo(2L);
        assertThat(reloaded.getCustodyChainHeadHash())
                .isEqualTo(timeline.getLast().getEventHash());
    }

    /** Release is terminal for mutation but never for verification: the queued verification still completes. */
    @Test
    void releaseWinsTheRaceAndTheLaterVerificationStillSucceeds() throws Exception {
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
        Future<IntegrityVerificationResponse> queued = executor.submit(() -> verifyAs(manager, first.getId()));
        awaitLockWaiters(jdbcTemplate, 1);
        release.countDown();

        assertThat(releasing.get(20, TimeUnit.SECONDS).eventSummary().eventType())
                .isEqualTo(EventType.EVIDENCE_RELEASED);
        IntegrityVerificationResponse verified = queued.get(20, TimeUnit.SECONDS);
        DigitalEvidence reloaded = reload(first);

        assertThat(verified.valid()).isTrue();
        assertThat(verified.eventSummary().sequenceNumber()).isEqualTo(2L);
        assertThat(reloaded.getStatus()).isEqualTo(EvidenceStatus.RELEASED);
        assertThat(timeline(first.getId()))
                .extracting(CustodyEvent::getEventType)
                .containsExactly(EventType.EVIDENCE_RELEASED, EventType.INTEGRITY_VERIFIED);
    }

    /** The closure waits for the running verification and then blocks every later one before any file is opened. */
    @Test
    void caseClosureWaitsForTheRunningVerificationAndBlocksLaterOnesBeforeAnyFileRead() throws Exception {
        CountDownLatch insideRead = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        pinFirstStorageRead(insideRead, release);

        Future<IntegrityVerificationResponse> running = executor.submit(() -> verifyAs(manager, first.getId()));
        assertThat(insideRead.await(20, TimeUnit.SECONDS)).isTrue();
        Future<CaseStatus> closing = executor.submit(() -> authenticated(admin, () -> {
            cases.updateStatus(owningCase.getId(), new UpdateCaseStatusRequest(CaseStatus.CLOSED), principal(admin));
            return CaseStatus.CLOSED;
        }));
        awaitLockWaiters(jdbcTemplate, 1);
        release.countDown();

        assertThat(running.get(20, TimeUnit.SECONDS).eventSummary().sequenceNumber())
                .isEqualTo(1L);
        assertThat(closing.get(20, TimeUnit.SECONDS)).isEqualTo(CaseStatus.CLOSED);
        reset(storage);

        assertThatThrownBy(() -> verifyAs(manager, first.getId())).isInstanceOf(CaseClosedException.class);

        verify(storage, never()).open(anyString());
        assertThat(events.countByEvidenceId(first.getId())).isEqualTo(1L);
    }

    @Test
    void verificationsOnDifferentEvidenceOfTheSameCaseAreNotGloballySerialized() throws Exception {
        CountDownLatch insideRead = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        pinFirstStorageRead(insideRead, release);

        Future<IntegrityVerificationResponse> blocking = executor.submit(() -> verifyAs(manager, first.getId()));
        assertThat(insideRead.await(20, TimeUnit.SECONDS)).isTrue();
        Future<IntegrityVerificationResponse> independent = executor.submit(() -> verifyAs(manager, second.getId()));

        assertThat(independent.get(20, TimeUnit.SECONDS).eventSummary().sequenceNumber())
                .as("a verification of another evidence of the same open case must not wait")
                .isEqualTo(1L);
        release.countDown();
        assertThat(blocking.get(20, TimeUnit.SECONDS).eventSummary().sequenceNumber())
                .isEqualTo(1L);
        assertThat(events.countByEvidenceId(first.getId())).isEqualTo(1L);
        assertThat(events.countByEvidenceId(second.getId())).isEqualTo(1L);
    }

    /** A transfer cannot slip between the observed metadata and the appended verification event. */
    @Test
    void aQueuedTransferIsAppendedAfterTheVerificationThatHeldTheEvidenceLockDuringTheRead() throws Exception {
        CountDownLatch insideRead = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        pinFirstStorageRead(insideRead, release);

        Future<IntegrityVerificationResponse> verifying = executor.submit(() -> verifyAs(manager, first.getId()));
        assertThat(insideRead.await(20, TimeUnit.SECONDS)).isTrue();
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

        assertThat(verifying.get(20, TimeUnit.SECONDS).eventSummary().sequenceNumber())
                .isEqualTo(1L);
        assertThat(transferring.get(20, TimeUnit.SECONDS).eventSummary().sequenceNumber())
                .isEqualTo(2L);
        List<CustodyEvent> timeline = timeline(first.getId());
        assertThat(timeline)
                .extracting(CustodyEvent::getEventType)
                .containsExactly(EventType.INTEGRITY_VERIFIED, EventType.CUSTODY_TRANSFERRED);
        assertThat(timeline.getLast().getPreviousHash())
                .isEqualTo(timeline.getFirst().getEventHash());
        assertThat(reload(first).getCurrentHolder().getId()).isEqualTo(manager.getId());
    }

    /**
     * Pins the first verification inside the storage read, while it still holds the custody case read lock and the
     * evidence write lock, so a competing command provably waits on the database row lock and not on a timer.
     */
    private void pinFirstStorageRead(CountDownLatch insideRead, CountDownLatch release) {
        AtomicBoolean firstArrival = new AtomicBoolean(true);
        doAnswer(invocation -> {
                    if (firstArrival.compareAndSet(true, false)) {
                        insideRead.countDown();
                        await(release);
                    }
                    return invocation.callRealMethod();
                })
                .when(storage)
                .open(anyString());
    }

    private IntegrityVerificationResponse verifyAs(Operator actor, UUID evidenceId) {
        return authenticated(actor, () -> verification.verify(evidenceId, principal(actor)));
    }

    private DigitalEvidence storedEvidence() {
        UUID evidenceId = UUID.randomUUID();
        String storageKey = EvidenceStorageKeyFactory.forEvidence(owningCase.getId(), evidenceId);
        String contentSha256 =
                HexFormat.of().formatHex(EvidenceHashing.newContentDigest().digest(CONTENT));
        DigitalEvidence evidence = evidences.saveAndFlush(DigitalEvidence.create(
                evidenceId,
                owningCase,
                officer,
                manager,
                "RACEINT" + referenceTags.incrementAndGet(),
                "Forensic disk image",
                null,
                SourceType.DEVICE,
                null,
                null,
                null,
                null,
                null,
                AcquisitionMethod.PHYSICAL,
                null,
                null,
                null,
                null,
                Instant.EPOCH,
                "disk-image.E01",
                "application/octet-stream",
                CONTENT.length,
                contentSha256,
                EvidenceHashing.contextualSha256(owningCase.getId(), evidenceId, contentSha256),
                storageKey));
        StagedEvidence staged = storage.stage(storageKey, new ByteArrayInputStream(CONTENT));
        storage.finalizeStaged(staged);
        return evidence;
    }

    private List<CustodyEvent> timeline(UUID evidenceId) {
        return events.findAllByEvidenceIdOrderBySequenceNumberAsc(evidenceId);
    }

    private DigitalEvidence reload(DigitalEvidence evidence) {
        return evidences.findByIdForVisibility(evidence.getId()).orElseThrow();
    }

    private void cleanDatabaseInDependencyOrder() {
        jdbcTemplate.execute("TRUNCATE TABLE custody_events");
        evidences.deleteAllInBatch();
        memberships.deleteAllInBatch();
        custodyCases.deleteAllInBatch();
        operators.deleteAllInBatch();
    }

    private static void cleanStorage() throws IOException {
        if (!Files.exists(storageRoot)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(storageRoot)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                if (!path.equals(storageRoot)) {
                    Files.deleteIfExists(path);
                }
            }
        }
        Files.createDirectories(storageRoot.resolve(".staging"));
    }
}
