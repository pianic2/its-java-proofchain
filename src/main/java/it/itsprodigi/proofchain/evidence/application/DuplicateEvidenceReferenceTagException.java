package it.itsprodigi.proofchain.evidence.application;

public final class DuplicateEvidenceReferenceTagException extends RuntimeException {

    public DuplicateEvidenceReferenceTagException() {
        super("The evidence reference tag already exists in this custody case.");
    }
}
