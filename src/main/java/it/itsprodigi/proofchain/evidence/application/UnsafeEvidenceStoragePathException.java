package it.itsprodigi.proofchain.evidence.application;

public final class UnsafeEvidenceStoragePathException extends EvidenceStorageException {

    public UnsafeEvidenceStoragePathException() {
        super("Evidence storage path is unsafe");
    }
}
