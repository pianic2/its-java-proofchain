package it.itsprodigi.proofchain.evidence.application;

public final class EvidenceTooLargeException extends EvidenceStorageException {

    public EvidenceTooLargeException(long maximumBytes) {
        super("Evidence content exceeds the configured limit of " + maximumBytes + " bytes");
    }
}
