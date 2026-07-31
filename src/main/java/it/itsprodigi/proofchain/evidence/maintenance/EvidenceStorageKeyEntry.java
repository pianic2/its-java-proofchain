package it.itsprodigi.proofchain.evidence.maintenance;

import java.util.Objects;
import java.util.UUID;

/**
 * The only two columns the offline report is allowed to read from an evidence row.
 *
 * <p>Nothing else is selected: no title, no reference tag, no original file name, no media type, no hash and no
 * custody state. A projection this narrow is what keeps the report sanitized by construction rather than by review.
 */
public record EvidenceStorageKeyEntry(UUID evidenceId, String storageKey) {

    public EvidenceStorageKeyEntry {
        Objects.requireNonNull(evidenceId, "evidenceId must not be null");
        Objects.requireNonNull(storageKey, "storageKey must not be null");
    }
}
