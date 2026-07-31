package it.itsprodigi.proofchain.common.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Validation view over the database pool timeouts the connection pool itself enforces.
 *
 * <p>Three independent budgets are bounded here and none of them introduces a retry:
 *
 * <ul>
 *   <li>connection acquisition — a caller waits at most {@code connectionTimeout} for a pooled connection;
 *   <li>startup — {@code initializationFailTimeout} must be zero or positive so a database that is unreachable at boot
 *       fails the context instead of letting the application start in a degraded state;
 *   <li>lock waiting — the per-connection initialization statement sets a finite PostgreSQL {@code lock_timeout}, so a
 *       contended row lock fails fast rather than blocking a request forever.
 * </ul>
 */
@Validated
@ConfigurationProperties(prefix = "spring.datasource.hikari")
public record DatabaseTimeoutProperties(
        @NotNull(message = "spring.datasource.hikari.connection-timeout must be configured")
        Long connectionTimeout,

        @NotNull(message = "spring.datasource.hikari.validation-timeout must be configured")
        Long validationTimeout,

        @NotNull(message = "spring.datasource.hikari.initialization-fail-timeout must be configured")
        Long initializationFailTimeout,

        @NotBlank(message = "spring.datasource.hikari.connection-init-sql must be configured")
        String connectionInitSql) {

    /** The pool refuses acquisition budgets below this value, so anything smaller is a configuration error. */
    public static final long MINIMUM_ACQUISITION_TIMEOUT_MS = 250L;

    /** An acquisition budget above this value is indistinguishable from an unbounded wait for an API request. */
    public static final long MAXIMUM_ACQUISITION_TIMEOUT_MS = 60_000L;

    /** A lock wait above this value is indistinguishable from an unbounded wait. */
    public static final Duration MAXIMUM_LOCK_TIMEOUT = Duration.ofMinutes(5);

    private static final Pattern LOCK_TIMEOUT_STATEMENT =
            Pattern.compile("^SET lock_timeout = '(\\d+)(ms|s|min)'$", Pattern.CASE_INSENSITIVE);

    public DatabaseTimeoutProperties {
        requireAcquisitionBudget(connectionTimeout, "spring.datasource.hikari.connection-timeout");
        requireAcquisitionBudget(validationTimeout, "spring.datasource.hikari.validation-timeout");
        if (connectionTimeout != null && validationTimeout != null && validationTimeout >= connectionTimeout) {
            throw new IllegalStateException(
                    "spring.datasource.hikari.validation-timeout must be smaller than connection-timeout");
        }
        if (initializationFailTimeout != null && initializationFailTimeout < 0) {
            throw new IllegalStateException("spring.datasource.hikari.initialization-fail-timeout must not be negative;"
                    + " a negative value would start the application without a usable database");
        }
        if (connectionInitSql != null && !connectionInitSql.isBlank()) {
            requireBoundedLockTimeout(connectionInitSql);
        }
    }

    /** The finite PostgreSQL lock wait carried by the per-connection initialization statement. */
    public Duration lockTimeout() {
        return parseLockTimeout(connectionInitSql);
    }

    private static void requireAcquisitionBudget(Long value, String key) {
        if (value == null) {
            return;
        }
        if (value < MINIMUM_ACQUISITION_TIMEOUT_MS || value > MAXIMUM_ACQUISITION_TIMEOUT_MS) {
            throw new IllegalStateException(key + " must be between " + MINIMUM_ACQUISITION_TIMEOUT_MS + " and "
                    + MAXIMUM_ACQUISITION_TIMEOUT_MS + " milliseconds");
        }
    }

    private static void requireBoundedLockTimeout(String statement) {
        Duration lockTimeout = parseLockTimeout(statement);
        if (lockTimeout.isZero() || lockTimeout.isNegative() || lockTimeout.compareTo(MAXIMUM_LOCK_TIMEOUT) > 0) {
            throw new IllegalStateException("spring.datasource.hikari.connection-init-sql must set a lock_timeout"
                    + " between 1 millisecond and " + MAXIMUM_LOCK_TIMEOUT);
        }
    }

    private static Duration parseLockTimeout(String statement) {
        Matcher matcher = LOCK_TIMEOUT_STATEMENT.matcher(statement.strip());
        if (!matcher.matches()) {
            throw new IllegalStateException("spring.datasource.hikari.connection-init-sql must be exactly"
                    + " \"SET lock_timeout = '<value>'\" with a millisecond, second or minute unit");
        }
        long amount = Long.parseLong(matcher.group(1));
        return switch (matcher.group(2).toLowerCase(Locale.ROOT)) {
            case "ms" -> Duration.ofMillis(amount);
            case "s" -> Duration.ofSeconds(amount);
            default -> Duration.ofMinutes(amount);
        };
    }
}
