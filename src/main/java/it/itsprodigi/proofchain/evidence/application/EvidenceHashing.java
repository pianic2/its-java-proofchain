package it.itsprodigi.proofchain.evidence.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

public final class EvidenceHashing {

    private static final String LOWERCASE_SHA_256 = "[0-9a-f]{64}";

    private EvidenceHashing() {}

    public static String contentSha256(byte[] content) {
        Objects.requireNonNull(content, "content must not be null");
        return sha256(content);
    }

    /**
     * Fresh SHA-256 accumulator for callers that must digest content in one bounded-memory streaming pass instead of
     * materializing it as a byte array.
     */
    public static MessageDigest newContentDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available");
        }
    }

    /** Renders a completed digest as the canonical 64-character lowercase hexadecimal representation. */
    public static String hex(byte[] digest) {
        Objects.requireNonNull(digest, "digest must not be null");
        return HexFormat.of().formatHex(digest);
    }

    public static String contextualSha256(UUID caseId, UUID evidenceId, String contentSha256) {
        Objects.requireNonNull(caseId, "caseId must not be null");
        Objects.requireNonNull(evidenceId, "evidenceId must not be null");
        if (contentSha256 == null || !contentSha256.matches(LOWERCASE_SHA_256)) {
            throw new IllegalArgumentException("contentSha256 must be exactly 64 lowercase hexadecimal characters");
        }
        String context = "proofchain:evidence:v1\n" + caseId + '\n' + evidenceId + '\n' + contentSha256;
        return sha256(context.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available");
        }
    }
}
