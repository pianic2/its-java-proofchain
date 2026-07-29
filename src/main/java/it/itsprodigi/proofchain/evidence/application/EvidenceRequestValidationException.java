package it.itsprodigi.proofchain.evidence.application;

public final class EvidenceRequestValidationException extends RuntimeException {

    public EvidenceRequestValidationException() {
        super("Evidence registration request is invalid.");
    }
}
