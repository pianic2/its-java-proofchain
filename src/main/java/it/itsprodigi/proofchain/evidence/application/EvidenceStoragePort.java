package it.itsprodigi.proofchain.evidence.application;

import java.io.InputStream;

public interface EvidenceStoragePort {

    StagedEvidence stage(String storageKey, InputStream content);

    void finalizeStaged(StagedEvidence stagedEvidence);

    void discardStaged(StagedEvidence stagedEvidence);

    void discardFinalized(String storageKey);

    OpenedEvidence open(String storageKey);
}
