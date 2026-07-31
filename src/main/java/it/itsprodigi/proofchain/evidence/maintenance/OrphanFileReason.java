package it.itsprodigi.proofchain.evidence.maintenance;

/**
 * The closed vocabulary a finding may use to explain itself.
 *
 * <p>Findings never carry free text. Every explanation is one of these constants, so a report can never leak a file
 * name, a media type, a hash, a database message or an operating-system error string, whatever the storage tree
 * contains.
 */
public enum OrphanFileReason {

    /** The canonical target of an evidence row does not exist. */
    CONTENT_ABSENT,

    /** The canonical target of an evidence row exists as a regular file but cannot be read by this process. */
    CONTENT_UNREADABLE,

    /** The path is a symbolic link; it is never followed and never trusted. */
    SYMBOLIC_LINK,

    /** The path exists but is neither a regular file nor an expected directory. */
    NON_REGULAR_FILE,

    /** A directory occupies a place where the canonical layout expects a regular file. */
    DIRECTORY_INSTEAD_OF_CONTENT,

    /** An evidence row carries a storage key that is not a canonical relative ProofChain key. */
    STORAGE_KEY_NOT_CANONICAL,

    /** An evidence row carries a storage key that resolves outside the resolved storage root. */
    STORAGE_KEY_OUTSIDE_ROOT,

    /** A canonical final content file exists and no evidence row references it. */
    NO_EVIDENCE_ROW,

    /** The entry does not belong to the canonical layout at all. */
    NOT_IN_CANONICAL_LAYOUT,

    /** A staged temporary file survived in the staging directory, typically after an abrupt termination. */
    STAGING_RESIDUE,

    /** A finalization reservation file survived next to a canonical content path. */
    RESERVATION_RESIDUE,

    /** A directory under the storage root could not be listed, so its contents could not be classified. */
    DIRECTORY_UNREADABLE
}
