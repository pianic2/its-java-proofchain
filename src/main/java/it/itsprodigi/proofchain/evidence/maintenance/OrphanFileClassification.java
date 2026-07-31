package it.itsprodigi.proofchain.evidence.maintenance;

/**
 * The four inconsistency classes the offline orphan report can report.
 *
 * <p>A classification is a diagnosis, never an instruction: nothing in this package repairs, moves, quarantines or
 * deletes the entry a finding describes.
 */
public enum OrphanFileClassification {

    /** A legitimate evidence row references content that is absent or unusable. */
    MISSING_CONTENT,

    /** A canonical final content file exists under the storage root and no evidence row references it. */
    ORPHAN_CONTENT,

    /** A path exists but is symlinked, non-regular, outside the resolved root or otherwise unsafe. */
    UNSAFE_CONTENT,

    /** An entry exists under the storage root but does not match the canonical ProofChain layout. */
    UNEXPECTED_ENTRY
}
