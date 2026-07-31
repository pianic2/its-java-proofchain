package it.itsprodigi.proofchain.common.config;

import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Validation view over the graceful-shutdown budget.
 *
 * <p>The application always drains in-flight requests, and the drain window is always finite: an absent, zero, negative
 * or unreasonably long budget fails startup instead of turning shutdown into an unbounded wait.
 */
@Validated
@ConfigurationProperties(prefix = "spring.lifecycle")
public record GracefulShutdownProperties(
        @NotNull(message = "spring.lifecycle.timeout-per-shutdown-phase must be configured")
        Duration timeoutPerShutdownPhase) {

    /** A drain window longer than this is indistinguishable from a shutdown that never completes. */
    public static final Duration MAXIMUM_SHUTDOWN_TIMEOUT = Duration.ofMinutes(5);

    public GracefulShutdownProperties {
        if (timeoutPerShutdownPhase != null
                && (timeoutPerShutdownPhase.isZero()
                        || timeoutPerShutdownPhase.isNegative()
                        || timeoutPerShutdownPhase.compareTo(MAXIMUM_SHUTDOWN_TIMEOUT) > 0)) {
            throw new IllegalStateException("spring.lifecycle.timeout-per-shutdown-phase must be a positive duration"
                    + " of at most " + MAXIMUM_SHUTDOWN_TIMEOUT);
        }
    }
}
