package it.itsprodigi.proofchain.auth.application;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.Base64;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Strongly typed JWT configuration.
 *
 * <p>The secret is never defaulted and never generated: it is supplied only through the environment. Binding fails, and
 * therefore the application context fails to start, when the secret is missing, is not standard Base64 or decodes to
 * fewer than {@value #MINIMUM_SECRET_BYTES} bytes, and when the access-token TTL is not strictly positive. There is no
 * degraded mode.
 */
@Validated
@ConfigurationProperties(prefix = "proofchain.jwt")
public record JwtProperties(
        @NotBlank(message = "proofchain.jwt.secret must be provided through the environment")
        String secret,

        @NotNull(message = "proofchain.jwt.access-token-ttl must be provided")
        Duration accessTokenTtl) {

    /** HS256 requires a key of at least the digest size; anything shorter is rejected as weak. */
    public static final int MINIMUM_SECRET_BYTES = 32;

    public JwtProperties {
        if (secret != null && !secret.isBlank()) {
            requireStrongBase64Secret(secret);
        }
        if (accessTokenTtl != null && (accessTokenTtl.isZero() || accessTokenTtl.isNegative())) {
            throw new IllegalStateException("proofchain.jwt.access-token-ttl must be positive");
        }
    }

    /** Decodes the configured secret. It is only reachable once binding succeeded, so the value is always strong. */
    public byte[] decodedSecret() {
        return Base64.getDecoder().decode(secret);
    }

    private static void requireStrongBase64Secret(String secret) {
        final byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(secret);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("proofchain.jwt.secret must be standard Base64", exception);
        }
        if (decoded.length < MINIMUM_SECRET_BYTES) {
            throw new IllegalStateException(
                    "proofchain.jwt.secret must decode to at least " + MINIMUM_SECRET_BYTES + " bytes");
        }
    }
}
