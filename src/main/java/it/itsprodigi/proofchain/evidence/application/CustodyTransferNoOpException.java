package it.itsprodigi.proofchain.evidence.application;

/**
 * Raised when a custody transfer targets the operator that already holds the evidence.
 *
 * <p>The comparison is always made against the committed aggregate state resolved under the evidence write lock, so a
 * stale client view can never turn into a silent success or an appended event.
 */
public final class CustodyTransferNoOpException extends RuntimeException {

    public CustodyTransferNoOpException() {
        super("The requested holder already holds this evidence.");
    }
}
