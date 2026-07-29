package it.itsprodigi.proofchain.evidence.application;

public abstract class EvidenceStorageException extends RuntimeException {

    protected EvidenceStorageException(String message) {
        super(message);
    }
}
