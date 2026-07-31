package it.itsprodigi.proofchain.evidence.application;

public class InvalidEvidenceStateException extends RuntimeException {

    public InvalidEvidenceStateException(String message) {
        super(message);
    }
}
