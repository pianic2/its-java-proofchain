package it.itsprodigi.proofchain.evidence.application;

public final class EvidenceTargetExistsException extends EvidenceStorageException {

    public EvidenceTargetExistsException() {
        super("Evidence storage target already exists or is reserved");
    }
}
