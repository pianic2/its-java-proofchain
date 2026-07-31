package it.itsprodigi.proofchain.evidence.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Objects;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

/**
 * Readiness contributor for the evidence storage root.
 *
 * <p>The container runs on a read-only root filesystem, so the only writable evidence location is the mounted volume.
 * A missing, replaced, symlinked or read-only mount would let the application accept uploads it cannot store, which is
 * why this contributor is part of the readiness group: it fails the probe instead of failing the operator.
 *
 * <p>The probe is a real write. Attribute checks alone cannot distinguish a writable directory from one that lives on
 * a filesystem remounted read-only, so a temporary file is created inside the staging directory the upload path
 * actually uses and is removed again immediately.
 *
 * <p>The result carries no detail whatsoever. A storage path is infrastructure information and must never reach an
 * unauthenticated health response, not even when detail rendering is later switched on by mistake.
 */
public final class EvidenceStorageHealthIndicator implements HealthIndicator {

    private static final LinkOption[] NOFOLLOW = {LinkOption.NOFOLLOW_LINKS};
    private static final String STAGING_DIRECTORY = ".staging";
    private static final String PROBE_PREFIX = "health-";
    private static final String PROBE_SUFFIX = ".probe";

    private final Path root;
    private final Path staging;

    public EvidenceStorageHealthIndicator(EvidenceStorageProperties properties) {
        Objects.requireNonNull(properties, "properties must not be null");
        this.root = properties.root().toAbsolutePath().normalize();
        this.staging = root.resolve(STAGING_DIRECTORY);
    }

    @Override
    public Health health() {
        return isUsable() ? Health.up().build() : Health.down().build();
    }

    private boolean isUsable() {
        return isTrustedWritableDirectory(root) && isTrustedWritableDirectory(staging) && probeWrite();
    }

    private static boolean isTrustedWritableDirectory(Path directory) {
        try {
            return !Files.isSymbolicLink(directory)
                    && Files.isDirectory(directory, NOFOLLOW)
                    && Files.isWritable(directory);
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private boolean probeWrite() {
        Path probe = null;
        try {
            probe = Files.createTempFile(staging, PROBE_PREFIX, PROBE_SUFFIX);
            return true;
        } catch (IOException | RuntimeException exception) {
            return false;
        } finally {
            deleteProbe(probe);
        }
    }

    private static void deleteProbe(Path probe) {
        if (probe == null) {
            return;
        }
        try {
            Files.deleteIfExists(probe);
        } catch (IOException | RuntimeException exception) {
            // A leftover probe file cannot corrupt evidence: canonical storage keys never collide with it.
        }
    }
}
