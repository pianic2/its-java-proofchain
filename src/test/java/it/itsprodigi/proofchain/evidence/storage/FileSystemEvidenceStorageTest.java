package it.itsprodigi.proofchain.evidence.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import it.itsprodigi.proofchain.evidence.application.EmptyEvidenceException;
import it.itsprodigi.proofchain.evidence.application.EvidenceFileUnavailableException;
import it.itsprodigi.proofchain.evidence.application.EvidenceHashing;
import it.itsprodigi.proofchain.evidence.application.EvidenceStorageFailureException;
import it.itsprodigi.proofchain.evidence.application.EvidenceStorageKeyFactory;
import it.itsprodigi.proofchain.evidence.application.EvidenceTargetExistsException;
import it.itsprodigi.proofchain.evidence.application.EvidenceTooLargeException;
import it.itsprodigi.proofchain.evidence.application.OpenedEvidence;
import it.itsprodigi.proofchain.evidence.application.StagedEvidence;
import it.itsprodigi.proofchain.evidence.application.UnsafeEvidenceStoragePathException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.util.unit.DataSize;

class FileSystemEvidenceStorageTest {

    private static final UUID CASE_ID = UUID.fromString("123e4567-e89b-42d3-a456-426614174000");
    private static final UUID EVIDENCE_ID = UUID.fromString("123e4567-e89b-42d3-a456-426614174001");
    private static final String STORAGE_KEY = EvidenceStorageKeyFactory.forEvidence(CASE_ID, EVIDENCE_ID);

    @TempDir
    private Path temporaryDirectory;

    @Test
    void stagesFinalizesAndOpensUnicodeAndBinaryContentLargerThanTheBuffer() throws IOException {
        Path root = temporaryDirectory.resolve("roundtrip");
        FileSystemEvidenceStorage storage = storage(root, 64 * 1024);
        byte[] unicode = "Prova digitale — 東京 — 🔐\n".getBytes(StandardCharsets.UTF_8);
        byte[] binary = new byte[20_000];
        for (int index = 0; index < binary.length; index++) {
            binary[index] = (byte) (index * 31);
        }
        byte[] content = new byte[unicode.length + binary.length];
        System.arraycopy(unicode, 0, content, 0, unicode.length);
        System.arraycopy(binary, 0, content, unicode.length, binary.length);

        StagedEvidence staged = storage.stage(STORAGE_KEY, new ByteArrayInputStream(content));

        assertThat(staged.storageKey()).isEqualTo(STORAGE_KEY);
        assertThat(staged.byteCount()).isEqualTo(content.length);
        assertThat(staged.contentSha256()).isEqualTo(EvidenceHashing.contentSha256(content));

        storage.finalizeStaged(staged);
        try (OpenedEvidence opened = storage.open(STORAGE_KEY)) {
            assertThat(opened.storageKey()).isEqualTo(STORAGE_KEY);
            assertThat(opened.byteCount()).isEqualTo(content.length);
            assertThat(opened.content().readAllBytes()).containsExactly(content);
        }
        assertNoTemporaryOrLockArtifacts(root);

        storage.discardFinalized(STORAGE_KEY);
        assertThatThrownBy(() -> storage.open(STORAGE_KEY)).isInstanceOf(EvidenceFileUnavailableException.class);
    }

    @Test
    void rejectsZeroAndFirstExcessByteWhileAcceptingTheExactLimit() throws IOException {
        Path root = temporaryDirectory.resolve("limits");
        FileSystemEvidenceStorage storage = storage(root, 16);
        String exactKey = keyFor("123e4567-e89b-42d3-a456-426614174002");
        String tooLargeKey = keyFor("123e4567-e89b-42d3-a456-426614174003");

        assertThatThrownBy(() -> storage.stage(STORAGE_KEY, new ByteArrayInputStream(new byte[0])))
                .isInstanceOf(EmptyEvidenceException.class);
        assertStagingEmpty(root);

        byte[] exact = new byte[16];
        Arrays.fill(exact, (byte) 7);
        StagedEvidence staged = storage.stage(exactKey, new ByteArrayInputStream(exact));
        assertThat(staged.byteCount()).isEqualTo(16);
        storage.finalizeStaged(staged);

        CountingInputStream excess = new CountingInputStream(new byte[17]);
        assertThatThrownBy(() -> storage.stage(tooLargeKey, excess)).isInstanceOf(EvidenceTooLargeException.class);
        assertThat(excess.bytesRead()).isEqualTo(17);

        String discardedKey = keyFor("123e4567-e89b-42d3-a456-426614174005");
        StagedEvidence discarded = storage.stage(discardedKey, new ByteArrayInputStream(new byte[] {1}));
        storage.discardStaged(discarded);
        assertThatThrownBy(() -> storage.open(discardedKey)).isInstanceOf(EvidenceFileUnavailableException.class);
        assertStagingEmpty(root);
        assertNoTemporaryOrLockArtifacts(root);
    }

    @Test
    void cleansTheTemporaryFileWhenTheInputStreamFails() throws IOException {
        Path root = temporaryDirectory.resolve("broken-stream");
        FileSystemEvidenceStorage storage = storage(root, 1024);
        InputStream broken = new InputStream() {
            private boolean firstRead = true;

            @Override
            public int read() throws IOException {
                throw new IOException("raw failure");
            }

            @Override
            public int read(byte[] buffer, int offset, int length) throws IOException {
                if (firstRead) {
                    firstRead = false;
                    buffer[offset] = 42;
                    return 1;
                }
                throw new IOException("raw failure");
            }
        };

        assertThatThrownBy(() -> storage.stage(STORAGE_KEY, broken))
                .isInstanceOf(EvidenceStorageFailureException.class)
                .hasMessage("Unable to stage evidence content")
                .hasNoCause()
                .message()
                .doesNotContain(root.toString());
        assertStagingEmpty(root);
    }

    @Test
    void neverOverwritesAnExistingTargetAndUsesAtomicFinalizeWithoutArtifacts() throws IOException {
        Path root = temporaryDirectory.resolve("existing-target");
        FileSystemEvidenceStorage storage = storage(root, 1024);
        StagedEvidence staged =
                storage.stage(STORAGE_KEY, new ByteArrayInputStream("new".getBytes(StandardCharsets.UTF_8)));
        Path target = root.resolve(STORAGE_KEY);
        Files.createDirectories(target.getParent());
        Files.writeString(target, "existing", StandardCharsets.UTF_8);

        assertThatThrownBy(() -> storage.finalizeStaged(staged)).isInstanceOf(EvidenceTargetExistsException.class);
        assertThat(Files.readString(target, StandardCharsets.UTF_8)).isEqualTo("existing");
        assertNoTemporaryOrLockArtifacts(root);

        String otherKey = keyFor("123e4567-e89b-42d3-a456-426614174004");
        StagedEvidence other =
                storage.stage(otherKey, new ByteArrayInputStream("atomic".getBytes(StandardCharsets.UTF_8)));
        Path otherTarget = root.resolve(otherKey);
        Path existingReservation = otherTarget.resolveSibling(otherTarget.getFileName() + ".lock");
        Files.createDirectories(existingReservation.getParent());
        Files.createFile(existingReservation);
        assertThatThrownBy(() -> storage.finalizeStaged(other)).isInstanceOf(EvidenceTargetExistsException.class);
        assertThat(existingReservation).exists();
        assertStagingEmpty(root);
        Files.delete(existingReservation);

        StagedEvidence retry =
                storage.stage(otherKey, new ByteArrayInputStream("atomic".getBytes(StandardCharsets.UTF_8)));
        storage.finalizeStaged(retry);
        assertThat(Files.readString(root.resolve(otherKey), StandardCharsets.UTF_8))
                .isEqualTo("atomic");
        assertNoTemporaryOrLockArtifacts(root);
    }

    @Test
    void rejectsUnsafeKeysAtEveryPublicPathBoundary() throws IOException {
        Path root = temporaryDirectory.resolve("unsafe-keys");
        FileSystemEvidenceStorage storage = storage(root, 1024);
        List<String> unsafeKeys = List.of(
                "../content.bin",
                "/absolute/content.bin",
                "cases\\123e4567-e89b-42d3-a456-426614174000\\content.bin",
                "cases/123E4567-E89B-42D3-A456-426614174000/evidences/123e4567-e89b-42d3-a456-426614174001/content.bin",
                "cases/123e4567-e89b-42d3-a456-426614174000/evidences/123e4567-e89b-42d3-a456-426614174001/content.bin\u0000");

        for (String unsafeKey : unsafeKeys) {
            assertThatThrownBy(() -> storage.stage(unsafeKey, new ByteArrayInputStream(new byte[] {1})))
                    .isInstanceOf(UnsafeEvidenceStoragePathException.class);
            assertThatThrownBy(() -> storage.open(unsafeKey)).isInstanceOf(UnsafeEvidenceStoragePathException.class);
            assertThatThrownBy(() -> storage.discardFinalized(unsafeKey))
                    .isInstanceOf(UnsafeEvidenceStoragePathException.class);
        }
        assertStagingEmpty(root);
    }

    @Test
    void rejectsSymlinksForTheRootIntermediateSegmentsAndFinalTarget() throws IOException {
        assumeSymlinksSupported();
        Path actualRoot = Files.createDirectory(temporaryDirectory.resolve("actual-root"));
        Path linkedRoot = temporaryDirectory.resolve("linked-root");
        Files.createSymbolicLink(linkedRoot, actualRoot);
        assertThatThrownBy(() -> storage(linkedRoot, 1024)).isInstanceOf(UnsafeEvidenceStoragePathException.class);

        Path segmentRoot = temporaryDirectory.resolve("segment-root");
        FileSystemEvidenceStorage segmentStorage = storage(segmentRoot, 1024);
        StagedEvidence segmentStaged =
                segmentStorage.stage(STORAGE_KEY, new ByteArrayInputStream("segment".getBytes(StandardCharsets.UTF_8)));
        Path outside = Files.createDirectory(temporaryDirectory.resolve("outside-segment"));
        Files.createSymbolicLink(segmentRoot.resolve("cases"), outside);
        assertThatThrownBy(() -> segmentStorage.finalizeStaged(segmentStaged))
                .isInstanceOf(UnsafeEvidenceStoragePathException.class);
        assertStagingEmpty(segmentRoot);

        Path finalRoot = temporaryDirectory.resolve("final-root");
        FileSystemEvidenceStorage finalStorage = storage(finalRoot, 1024);
        StagedEvidence finalStaged =
                finalStorage.stage(STORAGE_KEY, new ByteArrayInputStream("final".getBytes(StandardCharsets.UTF_8)));
        Path finalTarget = finalRoot.resolve(STORAGE_KEY);
        Files.createDirectories(finalTarget.getParent());
        Path outsideFile = Files.writeString(temporaryDirectory.resolve("outside-file"), "outside");
        Files.createSymbolicLink(finalTarget, outsideFile);
        assertThatThrownBy(() -> finalStorage.finalizeStaged(finalStaged))
                .isInstanceOf(UnsafeEvidenceStoragePathException.class);
        assertThatThrownBy(() -> finalStorage.open(STORAGE_KEY)).isInstanceOf(UnsafeEvidenceStoragePathException.class);
        assertThat(Files.readString(outsideFile)).isEqualTo("outside");
        assertStagingEmpty(finalRoot);
    }

    @Test
    void reportsMissingDirectoryAndUnreadableTargetsAsUnavailable() throws IOException {
        Path root = temporaryDirectory.resolve("unavailable");
        FileSystemEvidenceStorage storage = storage(root, 1024);
        Path target = root.resolve(STORAGE_KEY);

        assertThatThrownBy(() -> storage.open(STORAGE_KEY)).isInstanceOf(EvidenceFileUnavailableException.class);

        Files.createDirectories(target);
        assertThatThrownBy(() -> storage.open(STORAGE_KEY)).isInstanceOf(EvidenceFileUnavailableException.class);
        Files.delete(target);

        Files.writeString(target, "unreadable");
        Assumptions.assumeTrue(Files.getFileStore(target).supportsFileAttributeView("posix"));
        Set<PosixFilePermission> original = Files.getPosixFilePermissions(target);
        try {
            Files.setPosixFilePermissions(target, Set.of());
            Assumptions.assumeFalse(Files.isReadable(target));
            assertThatThrownBy(() -> storage.open(STORAGE_KEY)).isInstanceOf(EvidenceFileUnavailableException.class);
        } finally {
            Files.setPosixFilePermissions(target, original);
        }
    }

    @Test
    void allowsOnlyOneConcurrentFinalizeForTheSameTargetWithoutCorruption() throws Exception {
        Path root = temporaryDirectory.resolve("concurrent");
        FileSystemEvidenceStorage storage = storage(root, 1024);
        byte[] firstContent = "first-content".getBytes(StandardCharsets.UTF_8);
        byte[] secondContent = "second-content".getBytes(StandardCharsets.UTF_8);
        StagedEvidence first = storage.stage(STORAGE_KEY, new ByteArrayInputStream(firstContent));
        StagedEvidence second = storage.stage(STORAGE_KEY, new ByteArrayInputStream(secondContent));
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<String> firstResult = executor.submit(() -> finalizeAfterBarrier(storage, first, ready, start));
            Future<String> secondResult = executor.submit(() -> finalizeAfterBarrier(storage, second, ready, start));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            assertThat(List.of(firstResult.get(10, TimeUnit.SECONDS), secondResult.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("SUCCESS", "CONFLICT");
        } finally {
            start.countDown();
            executor.shutdownNow();
        }

        byte[] stored = Files.readAllBytes(root.resolve(STORAGE_KEY));
        assertThat(Arrays.equals(stored, firstContent) || Arrays.equals(stored, secondContent))
                .isTrue();
        assertNoTemporaryOrLockArtifacts(root);
    }

    @Test
    void failsFastForInvalidUnresolvableAndNonWritableRoots() throws IOException {
        Path fileRoot = Files.writeString(temporaryDirectory.resolve("root-file"), "not a directory");
        assertThatThrownBy(() -> storage(fileRoot, 1024)).isInstanceOf(EvidenceStorageFailureException.class);
        assertThatThrownBy(() -> storage(fileRoot.resolve("child"), 1024))
                .isInstanceOf(EvidenceStorageFailureException.class);

        Path nonWritable = Files.createDirectory(temporaryDirectory.resolve("non-writable"));
        Assumptions.assumeTrue(Files.getFileStore(nonWritable).supportsFileAttributeView("posix"));
        Set<PosixFilePermission> original = Files.getPosixFilePermissions(nonWritable);
        try {
            Files.setPosixFilePermissions(nonWritable, Set.of());
            Assumptions.assumeFalse(Files.isWritable(nonWritable));
            assertThatThrownBy(() -> storage(nonWritable, 1024)).isInstanceOf(EvidenceStorageFailureException.class);
        } finally {
            Files.setPosixFilePermissions(nonWritable, original);
        }
    }

    private FileSystemEvidenceStorage storage(Path root, long maximumBytes) {
        return new FileSystemEvidenceStorage(new EvidenceStorageProperties(root, DataSize.ofBytes(maximumBytes)));
    }

    private static String keyFor(String evidenceId) {
        return EvidenceStorageKeyFactory.forEvidence(CASE_ID, UUID.fromString(evidenceId));
    }

    private static String finalizeAfterBarrier(
            FileSystemEvidenceStorage storage, StagedEvidence staged, CountDownLatch ready, CountDownLatch start)
            throws InterruptedException {
        ready.countDown();
        if (!start.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Finalize barrier timed out");
        }
        try {
            storage.finalizeStaged(staged);
            return "SUCCESS";
        } catch (EvidenceTargetExistsException exception) {
            return "CONFLICT";
        }
    }

    private void assumeSymlinksSupported() throws IOException {
        Path target = Files.createDirectory(temporaryDirectory.resolve("symlink-capability-target"));
        Path link = temporaryDirectory.resolve("symlink-capability-link");
        try {
            Files.createSymbolicLink(link, target);
            Files.delete(link);
        } catch (UnsupportedOperationException | IOException | SecurityException exception) {
            Assumptions.abort("Symbolic links are not supported");
        }
    }

    private static void assertStagingEmpty(Path root) throws IOException {
        try (Stream<Path> entries = Files.list(root.resolve(".staging"))) {
            assertThat(entries).isEmpty();
        }
    }

    private static void assertNoTemporaryOrLockArtifacts(Path root) throws IOException {
        assertStagingEmpty(root);
        try (Stream<Path> entries = Files.walk(root)) {
            assertThat(entries.filter(path -> {
                        String name = path.getFileName().toString();
                        return name.endsWith(".tmp") || name.endsWith(".lock");
                    }))
                    .isEmpty();
        }
    }

    private static final class CountingInputStream extends ByteArrayInputStream {

        private long bytesRead;

        private CountingInputStream(byte[] content) {
            super(content);
        }

        @Override
        public synchronized int read(byte[] buffer, int offset, int length) {
            int read = super.read(buffer, offset, length);
            if (read > 0) {
                bytesRead += read;
            }
            return read;
        }

        private long bytesRead() {
            return bytesRead;
        }
    }
}
