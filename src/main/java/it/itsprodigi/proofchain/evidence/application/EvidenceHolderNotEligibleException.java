package it.itsprodigi.proofchain.evidence.application;

/**
 * Raised when the requested holder cannot take custody of evidence in a custody case.
 *
 * <p>The message is deliberately constant: a nonexistent, non-member, inactive, suspended, disabled or
 * disallowed-role target must be indistinguishable, so no cause is ever disclosed and the existence of an unrelated
 * operator outside the case is never revealed.
 */
public final class EvidenceHolderNotEligibleException extends RuntimeException {

    public EvidenceHolderNotEligibleException() {
        super("The requested holder is not eligible for this custody case.");
    }
}
