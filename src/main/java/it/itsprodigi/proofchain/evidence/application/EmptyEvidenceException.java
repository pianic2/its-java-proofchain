package it.itsprodigi.proofchain.evidence.application;

public final class EmptyEvidenceException extends EvidenceStorageException {

    public EmptyEvidenceException() {
        super("Evidence content must not be empty");
    }
}
