package it.itsprodigi.proofchain.evidence.application;

import java.util.Objects;
import java.util.UUID;

public final class EvidenceStorageKeyFactory {

    private static final String PREFIX = "cases/";
    private static final String EVIDENCE_SEGMENT = "/evidences/";
    private static final String CONTENT_SUFFIX = "/content.bin";

    private EvidenceStorageKeyFactory() {}

    public static String forEvidence(UUID caseId, UUID evidenceId) {
        return PREFIX
                + Objects.requireNonNull(caseId, "caseId must not be null")
                + EVIDENCE_SEGMENT
                + Objects.requireNonNull(evidenceId, "evidenceId must not be null")
                + CONTENT_SUFFIX;
    }

    public static String requireCanonical(String storageKey) {
        if (storageKey == null
                || storageKey.isEmpty()
                || storageKey.startsWith("/")
                || storageKey.indexOf('\\') >= 0
                || storageKey.codePoints().anyMatch(Character::isISOControl)) {
            throw new UnsafeEvidenceStoragePathException();
        }

        String[] segments = storageKey.split("/", -1);
        if (segments.length != 5
                || !segments[0].equals("cases")
                || !segments[2].equals("evidences")
                || !segments[4].equals("content.bin")
                || !isCanonicalUuid(segments[1])
                || !isCanonicalUuid(segments[3])) {
            throw new UnsafeEvidenceStoragePathException();
        }
        return storageKey;
    }

    private static boolean isCanonicalUuid(String value) {
        try {
            return UUID.fromString(value).toString().equals(value);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }
}
