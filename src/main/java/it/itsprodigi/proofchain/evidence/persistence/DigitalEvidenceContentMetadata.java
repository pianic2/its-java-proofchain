package it.itsprodigi.proofchain.evidence.persistence;

import java.util.UUID;

public interface DigitalEvidenceContentMetadata {

    UUID getEvidenceId();

    UUID getCaseId();

    String getOriginalFilename();

    String getMediaType();

    long getFileSize();

    String getContentSha256();

    String getContextualSha256();

    String getStorageKey();
}
