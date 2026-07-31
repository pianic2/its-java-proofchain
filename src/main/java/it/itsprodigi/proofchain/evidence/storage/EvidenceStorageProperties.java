package it.itsprodigi.proofchain.evidence.storage;

import java.nio.file.Path;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

@ConfigurationProperties(prefix = "proofchain.storage")
public record EvidenceStorageProperties(Path root, DataSize maxFileSize) {

    public EvidenceStorageProperties {
        Objects.requireNonNull(root, "proofchain.storage.root must not be null");
        Objects.requireNonNull(maxFileSize, "proofchain.storage.max-file-size must not be null");
        if (maxFileSize.toBytes() <= 0) {
            throw new IllegalArgumentException("proofchain.storage.max-file-size must be greater than zero");
        }
    }
}
