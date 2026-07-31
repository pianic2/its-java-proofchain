package it.itsprodigi.proofchain.evidence.maintenance;

import java.util.List;

/**
 * Read-only view over the storage keys recorded in the database.
 *
 * <p>The interface deliberately exposes no write operation at all, so an orphan report has no vocabulary in which to
 * insert, update or delete an evidence row even by mistake.
 */
public interface EvidenceStorageKeyCatalog {

    /** Every evidence row, ordered by identifier so a scan is reproducible. */
    List<EvidenceStorageKeyEntry> storageKeys();
}
