package it.itsprodigi.proofchain.common.config;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

/**
 * Validation view over the multipart limits Spring itself enforces.
 *
 * <p>This binds the very same canonical keys used by {@code spring.servlet.multipart}, so there is exactly one place to
 * configure the limits and one place that proves they are sane. The frozen default is a 50 MB evidence file inside a
 * slightly larger request envelope that leaves room for multipart framing and the JSON metadata part.
 */
@Validated
@ConfigurationProperties(prefix = "spring.servlet.multipart")
public record MultipartLimitsProperties(
        @NotNull(message = "spring.servlet.multipart.max-file-size must be configured")
        DataSize maxFileSize,

        @NotNull(message = "spring.servlet.multipart.max-request-size must be configured")
        DataSize maxRequestSize) {

    public MultipartLimitsProperties {
        requirePositive(maxFileSize, "spring.servlet.multipart.max-file-size");
        requirePositive(maxRequestSize, "spring.servlet.multipart.max-request-size");
        if (maxFileSize != null && maxRequestSize != null && maxRequestSize.toBytes() < maxFileSize.toBytes()) {
            throw new IllegalStateException(
                    "spring.servlet.multipart.max-request-size must not be smaller than max-file-size");
        }
    }

    private static void requirePositive(DataSize value, String key) {
        if (value != null && value.toBytes() <= 0) {
            throw new IllegalStateException(key + " must be greater than zero");
        }
    }
}
