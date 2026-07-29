package it.itsprodigi.proofchain.custodyevent.protocol;

import it.itsprodigi.proofchain.custodyevent.domain.EventType;

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
        if (valid != expectedContentSha256.equals(actualContentSha256)) {
            throw new IllegalArgumentException("valid must match the expected and actual hashes");
        }
        fileSize = ProtocolValidation.positive(fileSize, "fileSize");
    }

    @Override
    public EventType eventType() {
        return EventType.INTEGRITY_VERIFIED;
    }
}
