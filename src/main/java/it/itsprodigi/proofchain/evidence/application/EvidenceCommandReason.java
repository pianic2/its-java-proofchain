package it.itsprodigi.proofchain.evidence.application;

/**
 * Shared operational reason contract: trim, non-blank, 1 to 1000 characters after trimming, Unicode preserved.
 *
 * <p>Reason text is never logged.
 */
public final class EvidenceCommandReason {

    public static final int MIN_LENGTH = 1;
    public static final int MAX_LENGTH = 1000;

    private EvidenceCommandReason() {}

    public static String require(String reason) {
        if (reason == null) {
            throw new EvidenceRequestValidationException();
        }
        String trimmed = reason.strip();
        if (trimmed.length() < MIN_LENGTH || trimmed.length() > MAX_LENGTH) {
            throw new EvidenceRequestValidationException();
        }
        if (!isWellFormedUtf16(trimmed)) {
            throw new EvidenceRequestValidationException();
        }
        return trimmed;
    }

    /**
     * Rejects unpaired surrogates. Such a string has no UTF-8 encoding, so it cannot reach the canonical event
     * preimage: canonicalization would fail after the aggregate was already mutated and surface as a sanitized 500 for
     * what is really malformed client input. Failing closed here keeps it a 400.
     */
    private static boolean isWellFormedUtf16(String value) {
        int index = 0;
        while (index < value.length()) {
            char current = value.charAt(index);
            if (Character.isHighSurrogate(current)) {
                if (index + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(index + 1))) {
                    return false;
                }
                index += 2;
                continue;
            }
            if (Character.isLowSurrogate(current)) {
                return false;
            }
            index++;
        }
        return true;
    }
}
