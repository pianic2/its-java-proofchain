package it.itsprodigi.proofchain.common.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Validation view over the datasource coordinates used by the runtime profiles.
 *
 * <p>PostgreSQL is the only supported database, and its credentials are supplied only through the environment. When a
 * runtime profile ({@code local} or {@code container}) is active and the URL, the username or the password is missing,
 * the context fails to start rather than falling back to an anonymous or embedded connection. The {@code test} profile
 * does not enable this binding: automated tests own their datasource through Testcontainers.
 */
@Validated
@ConfigurationProperties(prefix = "spring.datasource")
public record RuntimeDatasourceProperties(
        @NotBlank(message = "spring.datasource.url must be configured in an active runtime profile")
        String url,

        @NotBlank(message = "spring.datasource.username must be provided through the environment")
        String username,

        @NotBlank(message = "spring.datasource.password must be provided through the environment")
        String password) {

    /** The only supported JDBC scheme; a different database is an unsupported deployment, not a fallback. */
    public static final String REQUIRED_URL_PREFIX = "jdbc:postgresql://";

    public RuntimeDatasourceProperties {
        if (url != null && !url.isBlank() && !url.startsWith(REQUIRED_URL_PREFIX)) {
            throw new IllegalStateException("spring.datasource.url must start with " + REQUIRED_URL_PREFIX);
        }
    }
}
