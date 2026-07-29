package it.itsprodigi.proofchain.evidence.application;

public final class EvidenceFileUnavailableException extends EvidenceStorageException {

    public EvidenceFileUnavailableException() {
        super("Evidence content is unavailable");
    }
}
