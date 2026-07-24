package it.itsprodigi.proofchain.operator.domain;

import java.util.Locale;
import java.util.Objects;

public final class OperatorNormalizer {

    private OperatorNormalizer() {}

    public static String normalizeUsername(String username) {
        return normalizeIdentity(username, "username");
    }

    public static String normalizeEmail(String email) {
        return normalizeIdentity(email, "email");
    }

    public static String normalizeName(String name, String fieldName) {
        return Objects.requireNonNull(name, fieldName + " must not be null").trim();
    }

    private static String normalizeIdentity(String value, String fieldName) {
        return Objects.requireNonNull(value, fieldName + " must not be null")
                .trim()
                .toLowerCase(Locale.ROOT);
    }
}
