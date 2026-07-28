package it.itsprodigi.proofchain.operator;

import static org.assertj.core.api.Assertions.assertThat;

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
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;

class LastActiveAdminConcurrencyIT extends PostgreSqlIntegrationTest {

    private static final String PASSWORD = "secure-password";

    @Autowired
    private OperatorAdminService service;

    @Autowired
    private OperatorRepository operators;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private ExecutorService executor;
    private Operator firstAdmin;
    private Operator secondAdmin;

    @BeforeEach
    void setUp() {
        operators.deleteAll();
        firstAdmin = operators.saveAndFlush(operator("first-admin"));
        secondAdmin = operators.saveAndFlush(operator("second-admin"));
        executor = Executors.newFixedThreadPool(2);
    }

    @AfterEach
    void tearDown() {
        if (executor != null) {
            executor.shutdownNow();
        }
        SecurityContextHolder.clearContext();
    }

    @Test
    void twoConcurrentSelfDemotionsLeaveOneActiveAdminAndOneInvariantConflict() throws Exception {
        CyclicBarrier start = new CyclicBarrier(2);
        Future<Result> first = executor.submit(() -> demoteAfter(start, firstAdmin));
        Future<Result> second = executor.submit(() -> demoteAfter(start, secondAdmin));

        List<Result> results = List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));

        assertThat(results).extracting(Result::success).containsExactlyInAnyOrder(true, false);
        assertThat(results)
                .filteredOn(result -> result.failure() != null)
                .extracting(Result::failure)
                .allMatch(OperatorInvariantException.class::isInstance);
        assertThat(operators.countActiveAdmins()).isEqualTo(1);
        assertThat(operators.findAll())
                .filteredOn(operator -> operator.getStatus() == OperatorStatus.ACTIVE)
                .filteredOn(operator -> operator.getRole() == OperatorRole.ADMIN)
                .hasSize(1);
    }

    @Test
    void twoConcurrentStatusChangesCannotLeaveZeroActiveAdmins() throws Exception {
        CyclicBarrier start = new CyclicBarrier(2);
        Future<Result> first = executor.submit(() -> suspendAfter(start, firstAdmin, secondAdmin));
        Future<Result> second = executor.submit(() -> suspendAfter(start, secondAdmin, firstAdmin));

        List<Result> results = List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));

        assertThat(results)
                .allMatch(result -> result.success() || result.failure() instanceof OperatorInvariantException);
        assertThat(operators.countActiveAdmins()).isGreaterThanOrEqualTo(1);
        assertThat(operators.findAll())
                .filteredOn(operator -> operator.getRole() == OperatorRole.ADMIN)
                .filteredOn(operator -> operator.getStatus() == OperatorStatus.ACTIVE)
                .hasSize(1);
    }

    private Result demoteAfter(CyclicBarrier start, Operator actor) {
        await(start);
        return invokeAsAdmin(() ->
                service.updateRole(actor.getId(), new UpdateOperatorRoleRequest(OperatorRole.AUDITOR), actor.getId()));
    }

    private Result suspendAfter(CyclicBarrier start, Operator actor, Operator target) {
        await(start);
        return invokeAsAdmin(() -> service.updateStatus(
                target.getId(), new UpdateOperatorStatusRequest(OperatorStatus.SUSPENDED), actor.getId()));
    }

    private Result invokeAsAdmin(ThrowingOperation operation) {
        try {
            SecurityContextHolder.setContext(SecurityContextHolder.createEmptyContext());
            SecurityContextHolder.getContext()
                    .setAuthentication(new UsernamePasswordAuthenticationToken(
                            "test-admin", null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
            operation.run();
            return new Result(true, null);
        } catch (RuntimeException exception) {
            return new Result(false, exception);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private static void await(CyclicBarrier barrier) {
        try {
            barrier.await(10, TimeUnit.SECONDS);
        } catch (Exception exception) {
            throw new IllegalStateException("Concurrent test coordination failed", exception);
        }
    }

    private Operator operator(String username) {
        return Operator.create(
                username,
                username + "@example.com",
                passwordEncoder.encode(PASSWORD),
                "First",
                "Last",
                OperatorRole.ADMIN);
    }

    private record Result(boolean success, RuntimeException failure) {}

    @FunctionalInterface
    private interface ThrowingOperation {
        void run();
    }
}
