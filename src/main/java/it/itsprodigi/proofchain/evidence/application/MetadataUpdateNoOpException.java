package it.itsprodigi.proofchain.evidence.application;

/**
 * Raised when the complete normalized before and after descriptive metadata snapshots are equal.
 *
 * <p>The comparison always runs against the committed aggregate state resolved under the evidence write lock, so a
 * stale client view can never turn into a silent success, a bumped {@code updatedAt} or an appended custody event.
 */
public final class MetadataUpdateNoOpException extends RuntimeException {

    public MetadataUpdateNoOpException() {
        super("The requested metadata already matches the current evidence metadata.");
    }
}
