package it.itsprodigi.proofchain.custodycase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import it.itsprodigi.proofchain.auth.security.AuthenticatedOperator;
import it.itsprodigi.proofchain.common.exception.ResourceNotFoundException;
import it.itsprodigi.proofchain.custodycase.api.CreateCaseRequest;
import it.itsprodigi.proofchain.custodycase.application.CaseMembershipService;
import it.itsprodigi.proofchain.custodycase.application.CaseMembershipTransactions;
import it.itsprodigi.proofchain.custodycase.application.CustodyCaseService;
import it.itsprodigi.proofchain.custodycase.application.LastCaseManagerRemovalException;
import it.itsprodigi.proofchain.custodycase.application.MembershipAssignmentResult;
import it.itsprodigi.proofchain.custodycase.domain.CaseMembership;
import it.itsprodigi.proofchain.custodycase.domain.CasePriority;
import it.itsprodigi.proofchain.custodycase.domain.CustodyCase;
import it.itsprodigi.proofchain.custodycase.persistence.CaseMembershipRepository;
import it.itsprodigi.proofchain.custodycase.persistence.CustodyCaseRepository;
import it.itsprodigi.proofchain.operator.api.UpdateOperatorRoleRequest;
import it.itsprodigi.proofchain.operator.api.UpdateOperatorStatusRequest;
import it.itsprodigi.proofchain.operator.application.OperatorAdminService;
import it.itsprodigi.proofchain.operator.application.OperatorInvariantException;
import it.itsprodigi.proofchain.operator.domain.Operator;
import it.itsprodigi.proofchain.operator.domain.OperatorRole;
import it.itsprodigi.proofchain.operator.domain.OperatorStatus;
import it.itsprodigi.proofchain.operator.persistence.OperatorRepository;
import it.itsprodigi.proofchain.support.PostgreSqlIntegrationTest;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class CaseMembershipConcurrencyIT extends PostgreSqlIntegrationTest {

    @Autowired
    private CaseMembershipService membershipService;

    @Autowired
    private CaseMembershipTransactions membershipTransactions;

    @Autowired
    private CustodyCaseService caseService;

    @Autowired
    private OperatorAdminService operatorService;

    @Autowired
    private CaseMembershipRepository memberships;

    @Autowired
    private CustodyCaseRepository custodyCases;

    @Autowired
    private OperatorRepository operators;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private ExecutorService executor;
    private TransactionTemplate transactions;
    private Operator admin;

    @BeforeEach
    void setUp() {
        cleanDatabase();
        admin = operators.saveAndFlush(operator("admin", OperatorRole.ADMIN));
        executor = Executors.newFixedThreadPool(4);
        transactions = new TransactionTemplate(transactionManager);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        try {
            if (executor != null) {
                executor.shutdownNow();
                assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
            }
        } finally {
            SecurityContextHolder.clearContext();
            cleanDatabase();
            executor = null;
            transactions = null;
        }
    }

    @Test
    void concurrentDuplicateAssignmentsProduceOneRowAndCreatedThenIdempotentOutcomes() throws Exception {
        Operator manager = operators.saveAndFlush(operator("manager", OperatorRole.CASE_MANAGER));
        Operator target = operators.saveAndFlush(operator("target", OperatorRole.AUDITOR));
        CustodyCase custodyCase = caseWithMembers("Duplicate assignment", manager);
        CyclicBarrier start = new CyclicBarrier(2);

        Future<Result<MembershipAssignmentResult>> first = executor.submit(() ->
                afterBarrier(start, () -> membershipService.assign(custodyCase.getId(), target.getId(), actor(admin))));
        Future<Result<MembershipAssignmentResult>> second = executor.submit(() ->
                afterBarrier(start, () -> membershipService.assign(custodyCase.getId(), target.getId(), actor(admin))));
        List<Result<MembershipAssignmentResult>> results =
                List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));

        assertThat(results).allMatch(Result::succeeded);
        assertThat(results).extracting(result -> result.value().created()).containsExactlyInAnyOrder(true, false);
        assertThat(memberships.findByCaseIdAndOperatorId(custodyCase.getId(), target.getId()))
                .isPresent();
        assertThat(memberships.findAllByCustodyCaseIdOrderByAssignedAtAscIdAsc(custodyCase.getId()))
                .hasSize(2);
    }

    @Test
    void competingResponsibleRemovalsCannotBothCommit() throws Exception {
        Operator firstManager = operators.saveAndFlush(operator("first-manager", OperatorRole.CASE_MANAGER));
        Operator secondManager = operators.saveAndFlush(operator("second-manager", OperatorRole.CASE_MANAGER));
        CustodyCase custodyCase = caseWithMembers("Competing removals", firstManager, secondManager);
        CyclicBarrier start = new CyclicBarrier(2);

        Future<Result<Void>> first = executor.submit(() -> afterBarrier(start, () -> {
            membershipService.remove(custodyCase.getId(), firstManager.getId(), actor(admin));
            return null;
        }));
        Future<Result<Void>> second = executor.submit(() -> afterBarrier(start, () -> {
            membershipService.remove(custodyCase.getId(), secondManager.getId(), actor(admin));
            return null;
        }));
        List<Result<Void>> results = List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));

        assertThat(results).filteredOn(Result::succeeded).hasSize(1);
        assertThat(results)
                .filteredOn(result -> !result.succeeded())
                .extracting(Result::failure)
                .allMatch(LastCaseManagerRemovalException.class::isInstance);
        assertThat(memberships.countResponsibleManagers(custodyCase.getId())).isEqualTo(1);
    }

    @Test
    void operatorResponsibilityReductionAndAlternativeRemovalSerializeWithoutBreakingTheInvariant() throws Exception {
        Operator target = operators.saveAndFlush(operator("target-manager", OperatorRole.CASE_MANAGER));
        Operator alternative = operators.saveAndFlush(operator("alternative-manager", OperatorRole.CASE_MANAGER));
        CustodyCase custodyCase = caseWithMembers("Cross feature race", target, alternative);
        CyclicBarrier start = new CyclicBarrier(2);

        Future<Result<Void>> roleReduction = executor.submit(() -> afterBarrier(start, () -> {
            operatorService.updateRole(
                    target.getId(), new UpdateOperatorRoleRequest(OperatorRole.AUDITOR), admin.getId());
            return null;
        }));
        Future<Result<Void>> alternativeRemoval = executor.submit(() -> afterBarrier(start, () -> {
            membershipService.remove(custodyCase.getId(), alternative.getId(), actor(admin));
            return null;
        }));
        List<Result<Void>> results =
                List.of(roleReduction.get(20, TimeUnit.SECONDS), alternativeRemoval.get(20, TimeUnit.SECONDS));

        assertThat(results).filteredOn(Result::succeeded).hasSize(1);
        assertThat(results)
                .filteredOn(result -> !result.succeeded())
                .extracting(Result::failure)
                .allMatch(failure -> failure instanceof OperatorInvariantException
                        || failure instanceof LastCaseManagerRemovalException);
        assertThat(memberships.countResponsibleManagers(custodyCase.getId())).isEqualTo(1);
    }

    @Test
    void multiCaseResponsibilityReductionsUseBoundedOrderedLocksWithoutDeadlock() throws Exception {
        Operator firstManager = operators.saveAndFlush(operator("multi-first", OperatorRole.CASE_MANAGER));
        Operator secondManager = operators.saveAndFlush(operator("multi-second", OperatorRole.CASE_MANAGER));
        CustodyCase firstCase = caseWithMembers("First ordered case", firstManager, secondManager);
        CustodyCase secondCase = caseWithMembers("Second ordered case", firstManager, secondManager);
        CyclicBarrier start = new CyclicBarrier(2);

        Future<Result<Void>> first = executor.submit(() -> afterBarrier(start, () -> {
            operatorService.updateRole(
                    firstManager.getId(), new UpdateOperatorRoleRequest(OperatorRole.AUDITOR), admin.getId());
            return null;
        }));
        Future<Result<Void>> second = executor.submit(() -> afterBarrier(start, () -> {
            operatorService.updateRole(
                    secondManager.getId(), new UpdateOperatorRoleRequest(OperatorRole.AUDITOR), admin.getId());
            return null;
        }));
        List<Result<Void>> results = List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));

        assertThat(results).filteredOn(Result::succeeded).hasSize(1);
        assertThat(results)
                .filteredOn(result -> !result.succeeded())
                .extracting(Result::failure)
                .allMatch(OperatorInvariantException.class::isInstance);
        assertThat(memberships.countResponsibleManagers(firstCase.getId())).isEqualTo(1);
        assertThat(memberships.countResponsibleManagers(secondCase.getId())).isEqualTo(1);
    }

    @Test
    void roleAndStatusAreEvaluatedFromCurrentOperatorsAndFailedReductionRollsBackCompletely() {
        Operator originalManager = operators.saveAndFlush(operator("original-manager", OperatorRole.CASE_MANAGER));
        Operator promotedMember = operators.saveAndFlush(operator("promoted-member", OperatorRole.AUDITOR));
        CustodyCase custodyCase = caseWithMembers("Current state", originalManager, promotedMember);

        invokeAsAdmin(() -> operatorService.updateRole(
                promotedMember.getId(), new UpdateOperatorRoleRequest(OperatorRole.CASE_MANAGER), admin.getId()));
        invokeAsAdmin(() -> operatorService.updateStatus(
                originalManager.getId(), new UpdateOperatorStatusRequest(OperatorStatus.SUSPENDED), admin.getId()));
        invokeAsAdmin(() -> membershipService.remove(custodyCase.getId(), originalManager.getId(), actor(admin)));

        assertThat(memberships.countResponsibleManagers(custodyCase.getId())).isEqualTo(1);
        assertThat(operators.findById(promotedMember.getId()).orElseThrow().getRole())
                .isEqualTo(OperatorRole.CASE_MANAGER);
        assertThat(operators.findById(originalManager.getId()).orElseThrow().getStatus())
                .isEqualTo(OperatorStatus.SUSPENDED);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> invokeAsAdmin(() -> operatorService.updateStatus(
                        promotedMember.getId(),
                        new UpdateOperatorStatusRequest(OperatorStatus.DISABLED),
                        admin.getId())))
                .isInstanceOf(OperatorInvariantException.class);
        assertThat(operators.findById(promotedMember.getId()).orElseThrow().getStatus())
                .isEqualTo(OperatorStatus.ACTIVE);
        assertThat(memberships.findByCaseIdAndOperatorId(custodyCase.getId(), promotedMember.getId()))
                .isPresent();
    }

    @Test
    void caseCreationCommitsBeforeQueuedReductionAndForcesStableSetRetry() throws Exception {
        Operator creator = operators.saveAndFlush(operator("phantom-creator", OperatorRole.CASE_MANAGER));
        LockBlocker blocker = holdOperatorLock(creator.getId());
        Result<it.itsprodigi.proofchain.custodycase.api.CaseResponse> creation;
        Result<Void> reduction;
        try {
            AuthenticatedOperator creatorPrincipal = actor(creator);
            Future<Result<it.itsprodigi.proofchain.custodycase.api.CaseResponse>> creationFuture =
                    executor.submit(() -> invokeAsResult(
                            creatorPrincipal,
                            OperatorRole.CASE_MANAGER,
                            () -> caseService.create(
                                    new CreateCaseRequest(
                                            "Creation phantom", null, null, null, null, CasePriority.HIGH),
                                    creatorPrincipal)));
            awaitLockWaiters(1);
            Future<Result<Void>> reductionFuture = executor.submit(() -> invokeAsAdminResult(() -> {
                operatorService.updateRole(
                        creator.getId(), new UpdateOperatorRoleRequest(OperatorRole.AUDITOR), admin.getId());
                return null;
            }));
            awaitLockWaiters(2);
            blocker.release();
            creation = creationFuture.get(20, TimeUnit.SECONDS);
            reduction = reductionFuture.get(20, TimeUnit.SECONDS);
        } finally {
            blocker.release();
            blocker.awaitCompletion();
        }

        assertThat(creation.succeeded()).isTrue();
        assertThat(reduction.failure()).isInstanceOf(OperatorInvariantException.class);
        UUID caseId = creation.value().id();
        assertThat(memberships.countResponsibleManagers(caseId)).isEqualTo(1);
        assertThat(operators.findById(creator.getId()).orElseThrow().getRole()).isEqualTo(OperatorRole.CASE_MANAGER);
    }

    @Test
    void newMembershipAndQueuedRemovalAreReconciledBeforeResponsibilityReduction() throws Exception {
        Operator original = operators.saveAndFlush(operator("phantom-original", OperatorRole.CASE_MANAGER));
        Operator target = operators.saveAndFlush(operator("phantom-target", OperatorRole.CASE_MANAGER));
        CustodyCase custodyCase = caseWithMembers("Membership phantom", original);
        LockBlocker blocker = holdOperatorLock(target.getId());
        Result<MembershipAssignmentResult> assignment;
        Result<Void> removal;
        Result<Void> reduction;
        try {
            Future<Result<MembershipAssignmentResult>> assignmentFuture = executor.submit(() -> invokeAsAdminResult(
                    () -> membershipService.assign(custodyCase.getId(), target.getId(), actor(admin))));
            awaitLockWaiters(1);
            Future<Result<Void>> reductionFuture = executor.submit(() -> invokeAsAdminResult(() -> {
                operatorService.updateRole(
                        target.getId(), new UpdateOperatorRoleRequest(OperatorRole.AUDITOR), admin.getId());
                return null;
            }));
            awaitLockWaiters(2);
            Future<Result<Void>> removalFuture = executor.submit(() -> invokeAsAdminResult(() -> {
                membershipService.remove(custodyCase.getId(), original.getId(), actor(admin));
                return null;
            }));
            awaitLockWaiters(3);
            blocker.release();
            assignment = assignmentFuture.get(20, TimeUnit.SECONDS);
            removal = removalFuture.get(20, TimeUnit.SECONDS);
            reduction = reductionFuture.get(20, TimeUnit.SECONDS);
        } finally {
            blocker.release();
            blocker.awaitCompletion();
        }

        assertThat(assignment.succeeded()).isTrue();
        assertThat(removal.succeeded()).isTrue();
        assertThat(reduction.failure()).isInstanceOf(OperatorInvariantException.class);
        assertThat(memberships.findByCaseIdAndOperatorId(custodyCase.getId(), original.getId()))
                .isEmpty();
        assertThat(memberships.countResponsibleManagers(custodyCase.getId())).isEqualTo(1);
        assertThat(operators.findById(target.getId()).orElseThrow().getRole()).isEqualTo(OperatorRole.CASE_MANAGER);
    }

    @Test
    void demotionCommittedWhilePutWaitsForCaseLockInvalidatesTheStaleAdminPrincipal() throws Exception {
        Operator secondAdmin = operators.saveAndFlush(operator("second-admin", OperatorRole.ADMIN));
        Operator manager = operators.saveAndFlush(operator("stale-put-manager", OperatorRole.CASE_MANAGER));
        Operator target = operators.saveAndFlush(operator("stale-put-target", OperatorRole.AUDITOR));
        CustodyCase custodyCase = caseWithMembers("Stale PUT actor", manager);
        AuthenticatedOperator staleAdmin = actor(admin);
        LockBlocker blocker = holdCaseLock(custodyCase.getId());
        Result<MembershipAssignmentResult> assignment;
        try {
            Future<Result<MembershipAssignmentResult>> assignmentFuture = executor.submit(() -> invokeAsResult(
                    staleAdmin,
                    OperatorRole.ADMIN,
                    () -> membershipService.assign(custodyCase.getId(), target.getId(), staleAdmin)));
            awaitLockWaiters(1);
            invokeAsAdmin(() -> operatorService.updateRole(
                    admin.getId(), new UpdateOperatorRoleRequest(OperatorRole.AUDITOR), secondAdmin.getId()));
            blocker.release();
            assignment = assignmentFuture.get(20, TimeUnit.SECONDS);
        } finally {
            blocker.release();
            blocker.awaitCompletion();
        }

        assertThat(assignment.failure()).isInstanceOf(ResourceNotFoundException.class);
        assertThat(memberships.findByCaseIdAndOperatorId(custodyCase.getId(), target.getId()))
                .isEmpty();
    }

    @Test
    void deactivationCommittedWhileDeleteWaitsForCaseLockInvalidatesTheStaleAdminPrincipal() throws Exception {
        Operator secondAdmin = operators.saveAndFlush(operator("status-second-admin", OperatorRole.ADMIN));
        Operator manager = operators.saveAndFlush(operator("stale-delete-manager", OperatorRole.CASE_MANAGER));
        CustodyCase custodyCase = caseWithMembers("Stale DELETE actor", manager);
        AuthenticatedOperator staleAdmin = actor(admin);
        LockBlocker blocker = holdCaseLock(custodyCase.getId());
        Result<Void> removal;
        try {
            Future<Result<Void>> removalFuture =
                    executor.submit(() -> invokeAsResult(staleAdmin, OperatorRole.ADMIN, () -> {
                        membershipService.remove(custodyCase.getId(), manager.getId(), staleAdmin);
                        return null;
                    }));
            awaitLockWaiters(1);
            invokeAsAdmin(() -> operatorService.updateStatus(
                    admin.getId(), new UpdateOperatorStatusRequest(OperatorStatus.SUSPENDED), secondAdmin.getId()));
            blocker.release();
            removal = removalFuture.get(20, TimeUnit.SECONDS);
        } finally {
            blocker.release();
            blocker.awaitCompletion();
        }

        assertThat(removal.failure()).isInstanceOf(AccessDeniedException.class);
        assertThat(memberships.findByCaseIdAndOperatorId(custodyCase.getId(), manager.getId()))
                .isPresent();
    }

    @Test
    void namedUniqueViolationRollsBackBeforeNewTransactionRecoversTheExistingMembership() {
        Operator manager = operators.saveAndFlush(operator("unique-manager", OperatorRole.CASE_MANAGER));
        Operator target = operators.saveAndFlush(operator("unique-target", OperatorRole.AUDITOR));
        CustodyCase custodyCase = caseWithMembers("Named unique violation", manager, target);
        CaseMembership existing = memberships
                .findByCaseIdAndOperatorId(custodyCase.getId(), target.getId())
                .orElseThrow();

        DataIntegrityViolationException violation = assertThrows(
                DataIntegrityViolationException.class,
                () -> transactions.executeWithoutResult(status -> {
                    CustodyCase managedCase =
                            custodyCases.findById(custodyCase.getId()).orElseThrow();
                    Operator managedTarget = operators.findById(target.getId()).orElseThrow();
                    Operator managedAdmin = operators.findById(admin.getId()).orElseThrow();
                    memberships.saveAndFlush(CaseMembership.assign(managedCase, managedTarget, managedAdmin));
                }));

        ConstraintViolationException constraint = findConstraintViolation(violation);
        assertThat(constraint.getConstraintName()).isEqualTo("uk_case_memberships_case_operator");
        assertThat(constraint.getSQLException().getSQLState()).isEqualTo("23505");
        Result<Optional<it.itsprodigi.proofchain.custodycase.api.MembershipResponse>> recovery = invokeAsAdminResult(
                () -> membershipTransactions.findExistingAfterDuplicate(custodyCase.getId(), target.getId()));
        assertThat(recovery.succeeded()).isTrue();
        Optional<it.itsprodigi.proofchain.custodycase.api.MembershipResponse> recovered = recovery.value();
        assertThat(recovered).isPresent().get().extracting("id").isEqualTo(existing.getId());
        assertThat(memberships.findAllByCustodyCaseIdOrderByAssignedAtAscIdAsc(custodyCase.getId()))
                .hasSize(2);
    }

    private <T> Result<T> afterBarrier(CyclicBarrier barrier, Supplier<T> operation) {
        try {
            barrier.await(10, TimeUnit.SECONDS);
            return invokeAsAdminResult(operation);
        } catch (Exception exception) {
            return new Result<>(null, exception);
        }
    }

    private <T> Result<T> invokeAsAdminResult(Supplier<T> operation) {
        return invokeAsResult(actor(admin), OperatorRole.ADMIN, operation);
    }

    private <T> Result<T> invokeAsResult(
            AuthenticatedOperator principal, OperatorRole authenticatedRole, Supplier<T> operation) {
        try {
            authenticate(principal, authenticatedRole);
            return new Result<>(operation.get(), null);
        } catch (RuntimeException exception) {
            return new Result<>(null, exception);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private void invokeAsAdmin(Runnable operation) {
        try {
            authenticateAdmin();
            operation.run();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private void authenticateAdmin() {
        authenticate(actor(admin), OperatorRole.ADMIN);
    }

    private void authenticate(AuthenticatedOperator principal, OperatorRole authenticatedRole) {
        SecurityContextHolder.setContext(SecurityContextHolder.createEmptyContext());
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(
                        principal, null, List.of(new SimpleGrantedAuthority("ROLE_" + authenticatedRole.name()))));
    }

    private LockBlocker holdOperatorLock(java.util.UUID operatorId) throws Exception {
        return holdLock(() -> operators.findByIdForUpdate(operatorId).orElseThrow());
    }

    private LockBlocker holdCaseLock(java.util.UUID caseId) throws Exception {
        return holdLock(() -> custodyCases.findByIdForUpdate(caseId).orElseThrow());
    }

    private LockBlocker holdLock(Runnable lockOperation) throws Exception {
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<Void> future = executor.submit(() -> {
            transactions.executeWithoutResult(status -> {
                lockOperation.run();
                locked.countDown();
                awaitLatch(release);
            });
            return null;
        });
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
        return new LockBlocker(release, future);
    }

    private void awaitLockWaiters(int expected) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            Integer waiters = jdbc.queryForObject("""
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

    private static void awaitLatch(CountDownLatch latch) {
        try {
            if (!latch.await(15, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out while holding a deterministic test lock");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while holding a deterministic test lock", exception);
        }
    }

    private static ConstraintViolationException findConstraintViolation(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof ConstraintViolationException violation) {
                return violation;
            }
            current = current.getCause();
        }
        throw new AssertionError("Expected a nested Hibernate ConstraintViolationException");
    }

    private CustodyCase caseWithMembers(String title, Operator first, Operator... additional) {
        CustodyCase custodyCase =
                custodyCases.saveAndFlush(CustodyCase.create(title, null, null, null, null, CasePriority.HIGH, first));
        memberships.saveAndFlush(CaseMembership.assign(custodyCase, first, admin));
        for (Operator member : additional) {
            memberships.saveAndFlush(CaseMembership.assign(custodyCase, member, admin));
        }
        return custodyCase;
    }

    private void cleanDatabase() {
        memberships.deleteAllInBatch();
        custodyCases.deleteAllInBatch();
        operators.deleteAllInBatch();
    }

    private Operator operator(String username, OperatorRole role) {
        return Operator.create(
                username, username + "@example.com", passwordEncoder.encode("correct-password"), "First", "Last", role);
    }

    private static AuthenticatedOperator actor(Operator operator) {
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

    private record Result<T>(T value, Exception failure) {
        boolean succeeded() {
            return failure == null;
        }
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
