package it.itsprodigi.proofchain.custodyevent.protocol;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

final class ProtocolValidation {

    private static final String SHA_256_PATTERN = "[0-9a-f]{64}";
    private static final String REFERENCE_TAG_PATTERN = "[A-Z0-9][A-Z0-9._-]{0,63}";

    private ProtocolValidation() {}

    static String requiredText(String value, int minimum, int maximum, String fieldName) {
        String normalized =
                Objects.requireNonNull(value, fieldName + " must not be null").strip();
        if (normalized.length() < minimum || normalized.length() > maximum) {
            throw new IllegalArgumentException(fieldName + " must be " + minimum + " to " + maximum + " characters");
        }
        return normalized;
    }

    static String optionalText(String value, int maximum, String fieldName) {
        if (value == null) {
            return null;
        }
        String normalized = value.strip();
        if (normalized.isEmpty()) {
            return null;
        }
        if (normalized.length() > maximum) {
            throw new IllegalArgumentException(fieldName + " must not exceed " + maximum + " characters");
        }
        return normalized;
    }

    static String referenceTag(String value) {
        String normalized = optionalText(value, 64, "referenceTag");
        if (normalized == null) {
            return null;
        }
        normalized = normalized.toUpperCase(Locale.ROOT);
        if (!normalized.matches(REFERENCE_TAG_PATTERN)) {
            throw new IllegalArgumentException("referenceTag is invalid");
        }
        return normalized;
    }

    static String fileExtension(String value) {
        String normalized = optionalText(value, 32, "fileExtension");
        return normalized == null ? null : normalized.toLowerCase(Locale.ROOT);
    }

    static String sha256(String value, String fieldName) {
        String hash = Objects.requireNonNull(value, fieldName + " must not be null");
        if (!hash.matches(SHA_256_PATTERN)) {
            throw new IllegalArgumentException(fieldName + " must be exactly 64 lowercase hexadecimal characters");
        }
        return hash;
    }

    static String reason(String value) {
        return requiredText(value, 1, 1000, "reason");
    }

    static long positive(long value, String fieldName) {
        if (value <= 0) {
            throw new IllegalArgumentException(fieldName + " must be positive");
        }
        return value;
    }

    static Instant microsecondInstant(Instant value, String fieldName) {
        Instant instant = Objects.requireNonNull(value, fieldName + " must not be null");
        if (!instant.equals(instant.truncatedTo(ChronoUnit.MICROS))) {
            throw new IllegalArgumentException(fieldName + " must have microsecond precision");
        }
        return instant;
    }

    static UUID uuidV4(UUID value, String fieldName) {
        UUID id = Objects.requireNonNull(value, fieldName + " must not be null");
        if (id.version() != 4 || id.variant() != 2) {
            throw new IllegalArgumentException(fieldName + " must be a UUID v4");
        }
        return id;
    }
}
