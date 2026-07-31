package it.itsprodigi.proofchain.common.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Browser origin allowlist for cross-origin requests.
 *
 * <p>The list is empty by default, which means the API is default-deny: no {@code Access-Control-Allow-Origin} header
 * is ever produced. A wildcard entry is not a supported configuration and fails startup, so the allowlist can only ever
 * be widened to explicit, scheme-qualified origins.
 */
@Validated
@ConfigurationProperties(prefix = "proofchain.cors")
public record CorsProperties(List<String> allowedOrigins) {

    public CorsProperties {
        allowedOrigins = normalize(allowedOrigins);
    }

    /** Convenience factory for the default-deny configuration. */
    public static CorsProperties denyAll() {
        return new CorsProperties(List.of());
    }

    /** {@code true} when no origin is allowed, which is the frozen default. */
    public boolean deniesEveryOrigin() {
        return allowedOrigins.isEmpty();
    }

    private static List<String> normalize(List<String> configured) {
        if (configured == null) {
            return List.of();
        }
        return configured.stream().map(CorsProperties::requireExplicitOrigin).toList();
    }

    private static String requireExplicitOrigin(String origin) {
        if (origin == null || origin.isBlank()) {
            throw new IllegalStateException("proofchain.cors.allowed-origins must not contain a blank origin");
        }
        String normalized = origin.strip();
        if (normalized.indexOf('*') >= 0) {
            throw new IllegalStateException(
                    "proofchain.cors.allowed-origins must not contain a wildcard origin: " + normalized);
        }
        int schemeEnd = schemeEnd(normalized);
        String authority = normalized.substring(schemeEnd);
        if (authority.isEmpty() || authority.indexOf('/') >= 0) {
            throw new IllegalStateException(
                    "proofchain.cors.allowed-origins entries must be scheme://host[:port] without a path: "
                            + normalized);
        }
        return normalized;
    }

    private static int schemeEnd(String origin) {
        if (origin.startsWith("https://")) {
            return "https://".length();
        }
        if (origin.startsWith("http://")) {
            return "http://".length();
        }
        throw new IllegalStateException(
                "proofchain.cors.allowed-origins entries must start with http:// or https://: " + origin);
    }
}
