package it.itsprodigi.proofchain.common.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

/**
 * Validation view over the non-file HTTP request limits and connector timeouts the embedded server enforces.
 *
 * <p>Only the limits that the delivered Spring Boot 4 servlet stack supports cleanly are bound here: header size, form
 * body size, swallowed body size, parameter and part counts, part header size, and the two connector timeouts. Every
 * value must be finite and strictly positive, so no setting can silently mean "unbounded".
 */
@Validated
@ConfigurationProperties(prefix = "server")
public record HttpRequestLimitsProperties(
        @NotNull(message = "server.shutdown must be configured")
        String shutdown,

        @NotNull(message = "server.max-http-request-header-size must be configured")
        DataSize maxHttpRequestHeaderSize,

        @NotNull(message = "server.tomcat must be configured") @Valid
        Tomcat tomcat) {

    public HttpRequestLimitsProperties {
        if (shutdown != null && !"graceful".equalsIgnoreCase(shutdown)) {
            throw new IllegalStateException("server.shutdown must be graceful");
        }
        requirePositive(maxHttpRequestHeaderSize, "server.max-http-request-header-size");
    }

    /** {@code true} when the connector is configured to drain in-flight requests before stopping. */
    public boolean gracefulShutdownEnabled() {
        return "graceful".equalsIgnoreCase(shutdown);
    }

    /** Connector-level limits. */
    public record Tomcat(
            @NotNull(message = "server.tomcat.max-http-form-post-size must be configured")
            DataSize maxHttpFormPostSize,

            @NotNull(message = "server.tomcat.max-swallow-size must be configured")
            DataSize maxSwallowSize,

            @NotNull(message = "server.tomcat.max-parameter-count must be configured")
            Integer maxParameterCount,

            @NotNull(message = "server.tomcat.max-part-count must be configured")
            Integer maxPartCount,

            @NotNull(message = "server.tomcat.max-part-header-size must be configured")
            DataSize maxPartHeaderSize,

            @NotNull(message = "server.tomcat.connection-timeout must be configured")
            Duration connectionTimeout,

            @NotNull(message = "server.tomcat.keep-alive-timeout must be configured")
            Duration keepAliveTimeout) {

        public Tomcat {
            requirePositive(maxHttpFormPostSize, "server.tomcat.max-http-form-post-size");
            requirePositive(maxSwallowSize, "server.tomcat.max-swallow-size");
            requirePositive(maxParameterCount, "server.tomcat.max-parameter-count");
            requirePositive(maxPartCount, "server.tomcat.max-part-count");
            requirePositive(maxPartHeaderSize, "server.tomcat.max-part-header-size");
            requirePositive(connectionTimeout, "server.tomcat.connection-timeout");
            requirePositive(keepAliveTimeout, "server.tomcat.keep-alive-timeout");
        }
    }

    private static void requirePositive(DataSize value, String key) {
        if (value != null && value.toBytes() <= 0) {
            throw new IllegalStateException(key + " must be a bounded, strictly positive size");
        }
    }

    private static void requirePositive(Integer value, String key) {
        if (value != null && value <= 0) {
            throw new IllegalStateException(key + " must be a bounded, strictly positive count");
        }
    }

    private static void requirePositive(Duration value, String key) {
        if (value != null && (value.isZero() || value.isNegative())) {
            throw new IllegalStateException(key + " must be a bounded, strictly positive duration");
        }
    }
}
