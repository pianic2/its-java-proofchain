package it.itsprodigi.proofchain.evidence.application;

public final class EvidenceHolderNotEligibleException extends RuntimeException {

    public EvidenceHolderNotEligibleException() {
        super("The requested initial holder is not eligible for this custody case.");
    }
}
