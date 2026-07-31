package it.itsprodigi.proofchain.evidence.domain;

import java.util.Locale;
import java.util.Objects;

public final class DigitalEvidenceNormalizer {

    private DigitalEvidenceNormalizer() {}

    public static String normalizeRequired(String value, String fieldName) {
        return Objects.requireNonNull(value, fieldName + " must not be null").strip();
    }

    public static String normalizeOptional(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.strip();
        return normalized.isEmpty() ? null : normalized;
    }

    public static String normalizeReferenceTag(String value) {
        String normalized = normalizeOptional(value);
        return normalized == null ? null : normalized.toUpperCase(Locale.ROOT);
    }

    public static String normalizeExtension(String value) {
        String normalized = normalizeOptional(value);
        return normalized == null ? null : normalized.toLowerCase(Locale.ROOT);
    }
}
