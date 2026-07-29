package it.itsprodigi.proofchain.evidence.application;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

public final class OpenedEvidence implements AutoCloseable {

    private final String storageKey;
    private final long byteCount;
    private final InputStream content;

    public OpenedEvidence(String storageKey, long byteCount, InputStream content) {
        this.storageKey = Objects.requireNonNull(storageKey, "storageKey must not be null");
        if (byteCount < 0) {
            throw new IllegalArgumentException("byteCount must not be negative");
        }
        this.byteCount = byteCount;
        this.content = Objects.requireNonNull(content, "content must not be null");
    }

    public String storageKey() {
        return storageKey;
    }

    public long byteCount() {
        return byteCount;
    }

    public InputStream content() {
        return content;
    }

    @Override
    public void close() {
        try {
            content.close();
        } catch (IOException exception) {
            throw new EvidenceStorageFailureException("Unable to close stored evidence content");
        }
    }
}
