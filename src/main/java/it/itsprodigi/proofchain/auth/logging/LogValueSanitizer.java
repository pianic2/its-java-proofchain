package it.itsprodigi.proofchain.auth.logging;

public final class LogValueSanitizer {
    public static final int MAX_USERNAME_LENGTH = 64;
    public static final int MAX_PATH_LENGTH = 512;

    private LogValueSanitizer() {}

    public static String sanitizeUsername(String value) {
        String sanitized = sanitize(value, MAX_USERNAME_LENGTH);
        return sanitized.contains("@") ? "-" : sanitized;
    }

    public static String sanitizePath(String value) {
        return sanitize(value, MAX_PATH_LENGTH);
    }

    public static String sanitize(String value, int maxLength) {
        if (maxLength <= 0) {
            throw new IllegalArgumentException("maxLength must be positive");
        }
        if (value == null || value.isEmpty()) {
            return "-";
        }

        StringBuilder sanitized = new StringBuilder(Math.min(value.length(), maxLength));
        value.codePoints().forEach(codePoint -> {
            if (!Character.isISOControl(codePoint) && sanitized.codePointCount(0, sanitized.length()) < maxLength) {
                sanitized.appendCodePoint(codePoint);
            }
        });
        return sanitized.isEmpty() ? "-" : sanitized.toString();
    }
}
