package it.itsprodigi.proofchain.evidence.application;

public interface StagedEvidence {

    String storageKey();

    long byteCount();

    String contentSha256();
}
