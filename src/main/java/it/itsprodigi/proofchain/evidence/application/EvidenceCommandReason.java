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
        return trimmed;
    }
}
