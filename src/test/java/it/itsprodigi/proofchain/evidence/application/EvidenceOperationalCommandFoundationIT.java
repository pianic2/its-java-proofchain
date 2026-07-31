package it.itsprodigi.proofchain.evidence.application;

import static it.itsprodigi.proofchain.custodyevent.domain.CustodyEventFixtures.custodyCase;
import static it.itsprodigi.proofchain.custodyevent.domain.CustodyEventFixtures.evidence;
import static it.itsprodigi.proofchain.custodyevent.domain.CustodyEventFixtures.operator;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import it.itsprodigi.proofchain.auth.security.AuthenticatedOperator;
import it.itsprodigi.proofchain.common.exception.ResourceNotFoundException;
import it.itsprodigi.proofchain.custodycase.application.CaseClosedException;
import it.itsprodigi.proofchain.custodycase.domain.CaseMembership;
import it.itsprodigi.proofchain.custodycase.domain.CustodyCase;
import it.itsprodigi.proofchain.custodycase.persistence.CaseMembershipRepository;
import it.itsprodigi.proofchain.custodycase.persistence.CustodyCaseRepository;
import it.itsprodigi.proofchain.custodyevent.application.CustodyEventConcurrencyConflictException;
import it.itsprodigi.proofchain.custodyevent.domain.CustodyEvent;
import it.itsprodigi.proofchain.custodyevent.persistence.CustodyEventRepository;
import it.itsprodigi.proofchain.custodyevent.protocol.CustodyEventHashing;
import it.itsprodigi.proofchain.custodyevent.protocol.CustodyTransferredPayload;
import it.itsprodigi.proofchain.evidence.api.EvidenceOperationResponse;
import it.itsprodigi.proofchain.evidence.domain.DigitalEvidence;
import it.itsprodigi.proofchain.evidence.persistence.DigitalEvidenceRepository;
import it.itsprodigi.proofchain.operator.domain.Operator;
import it.itsprodigi.proofchain.operator.domain.OperatorRole;
import it.itsprodigi.proofchain.operator.persistence.OperatorRepository;
import it.itsprodigi.proofchain.support.PostgreSqlIntegrationTest;
import java.sql.SQLException;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Proves the shared operational foundation against PostgreSQL: the frozen lock order, the re-check after lock
 * acquisition, the single shared command instant, conflict translation without retry and rollback behavior.
 */
class EvidenceOperationalCommandFoundationIT extends PostgreSqlIntegrationTest {

    @Autowired
    private EvidenceOperationalCommandService commands;

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
    private Operator manager;
    private Operator officer;
    private CustodyCase owningCase;
    private DigitalEvidence target;

    @BeforeEach
    void setUp() {
        cleanDatabaseInDependencyOrder();
        executor = Executors.newFixedThreadPool(4);
        manager = operators.saveAndFlush(operator("foundation-manager", OperatorRole.CASE_MANAGER));
        officer = operators.saveAndFlush(operator("foundation-officer", OperatorRole.EVIDENCE_OFFICER));
        owningCase = custodyCases.saveAndFlush(custodyCase("Foundation case", manager));
        memberships.saveAndFlush(CaseMembership.assign(owningCase, manager, manager));
        memberships.saveAndFlush(CaseMembership.assign(owningCase, officer, manager));
        target = evidences.saveAndFlush(evidence(owningCase, officer, "FOUND"));
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
    void acquiresTheCaseReadLockBeforeAnyEvidenceWriteLock() throws Exception {
        LockBlocker blocker = holdRowLock("custody_cases", owningCase.getId());
        Future<EvidenceOperationResponse> command;
        try {
            command = executor.submit(() -> authenticated(manager, () -> transferCommand(manager)));
            awaitLockWaiters(1);

            assertThat(tryLockForUpdateNoWait("digital_evidence", target.getId()))
                    .as("the evidence must still be lockable while the command waits for the custody case")
                    .isTrue();
        } finally {
            blocker.release();
        }

        assertThat(command.get(20, TimeUnit.SECONDS)).isNotNull();
        blocker.awaitCompletion();
    }

    @Test
    void holdsTheCaseReadLockWhileWaitingForTheEvidenceWriteLock() throws Exception {
        LockBlocker blocker = holdRowLock("digital_evidence", target.getId());
        Future<EvidenceOperationResponse> command;
        try {
            command = executor.submit(() -> authenticated(manager, () -> transferCommand(manager)));
            awaitLockWaiters(1);

            assertThat(tryLockForUpdateNoWait("custody_cases", owningCase.getId()))
                    .as("the custody case read lock must already be held while the evidence lock is awaited")
                    .isFalse();
        } finally {
            blocker.release();
        }

        assertThat(command.get(20, TimeUnit.SECONDS)).isNotNull();
        blocker.awaitCompletion();
    }

    @Test
    void reEvaluatesCaseStatusAfterTheCaseLockIsAcquired() throws Exception {
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<Void> closer = executor.submit(() -> {
            transactionTemplate.executeWithoutResult(status -> {
                jdbcTemplate.queryForObject(
                        "SELECT id FROM custody_cases WHERE id = ? FOR UPDATE", UUID.class, owningCase.getId());
                locked.countDown();
                await(release);
                jdbcTemplate.update(
                        "UPDATE custody_cases SET status = 'CLOSED', closed_at = now(), version = version + 1 WHERE id = ?",
                        owningCase.getId());
            });
            return null;
        });
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

        Future<EvidenceOperationResponse> command =
                executor.submit(() -> authenticated(manager, () -> transferCommand(manager)));
        awaitLockWaiters(1);
        release.countDown();
        closer.get(20, TimeUnit.SECONDS);

        assertThatThrownBy(() -> command.get(20, TimeUnit.SECONDS)).hasCauseInstanceOf(CaseClosedException.class);
        assertThat(events.countByEvidenceId(target.getId())).isZero();
    }

    @Test
    void sharesOneMicrosecondInstantBetweenTheAggregateAndTheAppendedEvent() {
        EvidenceOperationResponse response = authenticated(manager, () -> transferCommand(manager));

        DigitalEvidence reloaded =
                evidences.findByIdForVisibility(target.getId()).orElseThrow();
        CustodyEvent appended = events.findAllByEvidenceIdOrderBySequenceNumberAsc(target.getId())
                .getFirst();

        assertThat(response.eventSummary().occurredAt())
                .isEqualTo(response.evidence().updatedAt())
                .isEqualTo(appended.getOccurredAt())
                .isEqualTo(reloaded.getUpdatedAt())
                .isEqualTo(appended.getOccurredAt().truncatedTo(ChronoUnit.MICROS));
        assertThat(response.eventSummary().sequenceNumber()).isEqualTo(1L);
        assertThat(response.eventSummary().previousHash()).isEqualTo(CustodyEventHashing.ZERO_HASH);
        assertThat(reloaded.getCustodyChainHeadHash()).isEqualTo(appended.getEventHash());
        assertThat(reloaded.getCustodyEventCount()).isEqualTo(1L);
        assertThat(reloaded.getVersion()).isGreaterThan(target.getVersion());
        assertThat(reloaded.getCurrentHolder().getId()).isEqualTo(manager.getId());
    }

    @Test
    void rollbackLeavesTheChainHeadEventCountAndAggregateUnchanged() {
        assertThatThrownBy(() -> authenticated(
                        manager,
                        () -> commands.execute(
                                EvidenceOperationalCommand.CUSTODY_TRANSFER,
                                target.getId(),
                                principal(manager),
                                context -> {
                                    context.evidence().transferTo(managed(manager));
                                    throw new InvalidEvidenceStateException("workflow rejected the command");
                                })))
                .isInstanceOf(InvalidEvidenceStateException.class);

        DigitalEvidence reloaded =
                evidences.findByIdForVisibility(target.getId()).orElseThrow();
        assertThat(events.countByEvidenceId(target.getId())).isZero();
        assertThat(reloaded.getCustodyEventCount()).isZero();
        assertThat(reloaded.getCustodyChainHeadHash()).isEqualTo(CustodyEventHashing.ZERO_HASH);
        assertThat(reloaded.getCurrentHolder().getId()).isEqualTo(officer.getId());
        assertThat(reloaded.getUpdatedAt()).isEqualTo(target.getUpdatedAt());
        assertThat(reloaded.getVersion()).isEqualTo(target.getVersion());

        assertThat(authenticated(manager, () -> transferCommand(manager))).isNotNull();
    }

    @Test
    void translatesWriteConflictsWithoutRetryAndWithoutPersistingAnything() {
        AtomicInteger attempts = new AtomicInteger();

        assertThatThrownBy(() -> authenticated(
                        manager,
                        () -> commands.execute(
                                EvidenceOperationalCommand.CUSTODY_TRANSFER,
                                target.getId(),
                                principal(manager),
                                context -> {
                                    attempts.incrementAndGet();
                                    throw new CannotAcquireLockException("could not obtain lock on row");
                                })))
                .isInstanceOf(CustodyEventConcurrencyConflictException.class);

        assertThat(attempts).hasValue(1);
        assertThat(events.countByEvidenceId(target.getId())).isZero();
    }

    @Test
    void hiddenAndNonexistentEvidenceAreIndistinguishableWhileVisibleUnauthorizedIsForbidden() {
        Operator outsider = operators.saveAndFlush(operator("foundation-outsider", OperatorRole.CASE_MANAGER));
        Operator auditor = operators.saveAndFlush(operator("foundation-auditor", OperatorRole.AUDITOR));
        memberships.saveAndFlush(CaseMembership.assign(owningCase, auditor, manager));

        assertThatThrownBy(() -> authenticated(outsider, () -> transferCommand(outsider)))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> authenticated(
                        outsider,
                        () -> commands.execute(
                                EvidenceOperationalCommand.CUSTODY_TRANSFER,
                                UUID.randomUUID(),
                                principal(outsider),
                                context -> {
                                    throw new AssertionError("the body must not run");
                                })))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> authenticated(auditor, () -> transferCommand(auditor)))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(events.countByEvidenceId(target.getId())).isZero();
    }

    private EvidenceOperationResponse transferCommand(Operator actor) {
        return commands.execute(
                EvidenceOperationalCommand.CUSTODY_TRANSFER, target.getId(), principal(actor), context -> {
                    UUID previousHolderId =
                            context.evidence().getCurrentHolder().getId();
                    context.evidence().transferTo(managed(actor));
                    return new CustodyTransferredPayload(
                            previousHolderId, actor.getId(), "operational foundation handover");
                });
    }

    private Operator managed(Operator operator) {
        return operators.findById(operator.getId()).orElseThrow();
    }

    private <T> T authenticated(Operator actor, java.util.function.Supplier<T> action) {
        try {
            SecurityContextHolder.setContext(SecurityContextHolder.createEmptyContext());
            SecurityContextHolder.getContext()
                    .setAuthentication(new UsernamePasswordAuthenticationToken(
                            principal(actor),
                            null,
                            List.of(new SimpleGrantedAuthority(
                                    "ROLE_" + actor.getRole().name()))));
            return action.get();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private LockBlocker holdRowLock(String table, UUID id) throws Exception {
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<Void> future = executor.submit(() -> {
            transactionTemplate.executeWithoutResult(status -> {
                jdbcTemplate.queryForObject("SELECT id FROM " + table + " WHERE id = ? FOR UPDATE", UUID.class, id);
                locked.countDown();
                await(release);
            });
            return null;
        });
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
        return new LockBlocker(release, future);
    }

    private boolean tryLockForUpdateNoWait(String table, UUID id) {
        try {
            jdbcTemplate.queryForObject("SELECT id FROM " + table + " WHERE id = ? FOR UPDATE NOWAIT", UUID.class, id);
            return true;
        } catch (DataAccessException exception) {
            if (isLockNotAvailable(exception)) {
                return false;
            }
            throw exception;
        }
    }

    private static boolean isLockNotAvailable(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof SQLException sqlException && "55P03".equals(sqlException.getSQLState())) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private void awaitLockWaiters(int expected) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            Integer waiters = jdbcTemplate.queryForObject("""
                    SELECT COUNT(*)
                    FROM pg_stat_activity
                    WHERE datname = current_database()
                      AND pid <> pg_backend_pid()
                      AND wait_event_type = 'Lock'
                    """, Integer.class);
            if (waiters != null && waiters >= expected) {
                return;
            }
        }
        throw new AssertionError("Expected at least " + expected + " PostgreSQL lock waiters");
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(20, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out while coordinating an operational command lock");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while coordinating an operational command lock", exception);
        }
    }

    private static AuthenticatedOperator principal(Operator operator) {
        return new AuthenticatedOperator(
                operator.getId(),
                operator.getUsername(),
                operator.getEmail(),
                operator.getFirstName(),
                operator.getLastName(),
                operator.getRole(),
                operator.getStatus(),
                operator.getCreatedAt(),
                operator.getUpdatedAt());
    }

    private void cleanDatabaseInDependencyOrder() {
        jdbcTemplate.execute("TRUNCATE TABLE custody_events");
        evidences.deleteAllInBatch();
        memberships.deleteAllInBatch();
        custodyCases.deleteAllInBatch();
        operators.deleteAllInBatch();
    }

    private record LockBlocker(CountDownLatch releaseLatch, Future<Void> future) {
        void release() {
            releaseLatch.countDown();
        }

        void awaitCompletion() throws Exception {
            future.get(20, TimeUnit.SECONDS);
        }
    }
}
