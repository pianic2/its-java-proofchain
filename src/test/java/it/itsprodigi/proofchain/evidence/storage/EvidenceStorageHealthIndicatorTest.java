package it.itsprodigi.proofchain.evidence.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.health.contributor.Status;
import org.springframework.util.unit.DataSize;

/**
 * The readiness contributor for the evidence storage root.
 *
 * <p>The container runs read-only, so an unusable evidence mount must turn readiness red instead of letting the
 * application accept uploads it cannot store. These tests pin both the decision and the fact that nothing about the
 * filesystem layout is ever published in the result.
 */
class EvidenceStorageHealthIndicatorTest {

    @TempDir
    Path root;

    @Test
    void reportsUpWhenTheRootAndItsStagingDirectoryAreWritable() throws IOException {
        Files.createDirectory(root.resolve(".staging"));

        assertThat(indicator().health().getStatus()).isEqualTo(Status.UP);
    }

    @Test
    void leavesNoProbeFileBehindWhenTheStorageIsHealthy() throws IOException {
        Files.createDirectory(root.resolve(".staging"));

        indicator().health();
        indicator().health();

        try (Stream<Path> entries = Files.walk(root)) {
            assertThat(entries.filter(Files::isRegularFile)).isEmpty();
        }
    }

    @Test
    void neverPublishesAnyDetailInEitherDirection() throws IOException {
        Files.createDirectory(root.resolve(".staging"));
        assertThat(indicator().health().getDetails()).isEmpty();

        Path missing = root.resolve("absent");
        assertThat(indicatorFor(missing).health().getDetails()).isEmpty();
    }

    @Test
    void reportsDownWhenTheRootDoesNotExist() {
        EvidenceStorageHealthIndicator indicator = indicatorFor(root.resolve("absent"));

        assertThat(indicator.health().getStatus()).isEqualTo(Status.DOWN);
    }

    @Test
    void reportsDownWhenTheStagingDirectoryIsMissing() {
        assertThat(indicator().health().getStatus()).isEqualTo(Status.DOWN);
    }

    @Test
    void reportsDownWhenTheRootIsAFileInsteadOfADirectory() throws IOException {
        Path file = root.resolve("not-a-directory");
        Files.writeString(file, "x");

        assertThat(indicatorFor(file).health().getStatus()).isEqualTo(Status.DOWN);
    }

    @Test
    void reportsDownWhenTheRootIsASymbolicLink() throws IOException {
        Path target = root.resolve("target");
        Files.createDirectory(target);
        Files.createDirectory(target.resolve(".staging"));
        Path link = root.resolve("link");
        Files.createSymbolicLink(link, target);

        assertThat(indicatorFor(link).health().getStatus()).isEqualTo(Status.DOWN);
    }

    @Test
    void reportsDownWhenTheStagingLocationIsAFile() throws IOException {
        // An unwritable directory is the other DOWN case. It is certified against the running container instead of
        // here, because a test process running as root can write into a directory with no permission bits at all.
        Files.writeString(root.resolve(".staging"), "x");

        assertThat(indicator().health().getStatus()).isEqualTo(Status.DOWN);
    }

    private EvidenceStorageHealthIndicator indicator() {
        return indicatorFor(root);
    }

    private static EvidenceStorageHealthIndicator indicatorFor(Path storageRoot) {
        return new EvidenceStorageHealthIndicator(new EvidenceStorageProperties(storageRoot, DataSize.ofMegabytes(50)));
    }
}
