package it.itsprodigi.proofchain.evidence.application;

import java.util.Objects;
import java.util.UUID;

public record EvidenceDownloadDescriptor(
        UUID evidenceId, String originalFilename, String mediaType, long fileSize, String storageKey) {

    public EvidenceDownloadDescriptor {
        Objects.requireNonNull(evidenceId, "evidenceId must not be null");
        Objects.requireNonNull(originalFilename, "originalFilename must not be null");
        Objects.requireNonNull(mediaType, "mediaType must not be null");
        if (fileSize < 1) {
            throw new IllegalArgumentException("fileSize must be positive");
        }
        Objects.requireNonNull(storageKey, "storageKey must not be null");
    }
}
