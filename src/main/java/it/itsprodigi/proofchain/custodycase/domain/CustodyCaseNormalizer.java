package it.itsprodigi.proofchain.custodycase.domain;

import java.util.Objects;

public final class CustodyCaseNormalizer {

    private CustodyCaseNormalizer() {}

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
}
