package it.itsprodigi.proofchain.custodyevent.protocol;

import it.itsprodigi.proofchain.custodyevent.domain.EventType;

/**
 * Frozen result of one file-integrity verification.
 *
 * <p>{@code fileSize} is the number of bytes actually observed during the verification, not the persisted evidence
 * size, and {@code valid} is the conjunction of hash equality and observed-size equality. A verification therefore
 * stays invalid when the recomputed digest matches but the observed byte count contradicts the persisted metadata,
 * which is why {@code valid} only implies hash equality instead of being equivalent to it: {@code valid=true} still
 * requires identical hashes, while {@code valid=false} is always a legitimate observation.
 */
public record IntegrityVerifiedPayload(
        String algorithm, String expectedContentSha256, String actualContentSha256, boolean valid, long fileSize)
        implements CustodyEventPayload {

    public static final String SHA_256 = "SHA-256";

    public IntegrityVerifiedPayload {
        if (!SHA_256.equals(algorithm)) {
            throw new IllegalArgumentException("algorithm must be SHA-256");
        }
        expectedContentSha256 = ProtocolValidation.sha256(expectedContentSha256, "expectedContentSha256");
        actualContentSha256 = ProtocolValidation.sha256(actualContentSha256, "actualContentSha256");
        if (valid && !expectedContentSha256.equals(actualContentSha256)) {
            throw new IllegalArgumentException("valid must not be true when the compared hashes differ");
        }
        fileSize = ProtocolValidation.positive(fileSize, "fileSize");
    }

    @Override
    public EventType eventType() {
        return EventType.INTEGRITY_VERIFIED;
    }
}
