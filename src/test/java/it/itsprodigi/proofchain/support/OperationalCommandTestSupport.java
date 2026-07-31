package it.itsprodigi.proofchain.support;

import it.itsprodigi.proofchain.auth.security.AuthenticatedOperator;
import it.itsprodigi.proofchain.operator.domain.Operator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/** Shared harness for operational custody command tests: principals, method-security context and latch coordination. */
public final class OperationalCommandTestSupport {

    private static final long TIMEOUT_SECONDS = 20;

    private OperationalCommandTestSupport() {}

    public static AuthenticatedOperator principal(Operator operator) {
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

    /** Runs the action with the operator authenticated, so method security on the public services is exercised. */
    public static <T> T authenticated(Operator actor, Supplier<T> action) {
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

    /**
     * Blocks until PostgreSQL reports at least the expected number of sessions waiting on a lock. Concurrency proofs
     * observe the database lock state instead of relying on timing sleeps.
     */
    public static void awaitLockWaiters(JdbcTemplate jdbcTemplate, int expected) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
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

    /** Deterministic latch await; concurrency proofs never rely on timing sleeps. */
    public static void await(CountDownLatch latch) {
        try {
            if (!latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out while coordinating an operational custody command");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while coordinating an operational custody command", exception);
        }
    }
}
