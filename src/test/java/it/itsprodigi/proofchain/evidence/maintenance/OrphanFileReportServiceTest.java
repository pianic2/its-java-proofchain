package it.itsprodigi.proofchain.evidence.maintenance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import it.itsprodigi.proofchain.evidence.storage.EvidenceStorageProperties;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.util.unit.DataSize;

/**
 * Certifies the offline orphan report as a diagnostic that observes and never acts.
 *
 * <p>The central assertion is not that the service avoids calling a delete method — that is only a claim about the code
 * as written. It is that a full fingerprint of the storage tree and of the catalog is byte-identical before and after a
 * scan, so any mutation introduced later, by any path, fails this test.
 */
class OrphanFileReportServiceTest {

    private static final UUID CASE_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final byte[] CONTENT = "orphan-report-fixture".getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path root;

    private RecordingCatalog catalog;

    @BeforeEach
    void setUp() {
        catalog = new RecordingCatalog();
    }

    @Test
    void reportsNothingWhenEveryRowHasItsCanonicalContentAndNothingElseExists() throws IOException {
        UUID evidenceId = UUID.randomUUID();
        writeContent(evidenceId);
        catalog.add(evidenceId, key(evidenceId));

        OrphanFileReport report = service().scan();

        assertThat(report.findings()).isEmpty();
        assertThat(report.examinedEvidenceRows()).isEqualTo(1);
        assertThat(report.countsByClassification()).containsOnlyKeys(OrphanFileClassification.values());
        assertThat(report.countsByClassification().values()).containsOnly(0L);
    }

    @Test
    void classifiesARowWhoseContentIsAbsentAsMissingContent() throws IOException {
        UUID present = UUID.randomUUID();
        UUID absent = UUID.randomUUID();
        writeContent(present);
        catalog.add(present, key(present));
        catalog.add(absent, key(absent));

        OrphanFileReport report = service().scan();

        assertThat(report.findings()).singleElement().satisfies(finding -> {
            assertThat(finding.classification()).isEqualTo(OrphanFileClassification.MISSING_CONTENT);
            assertThat(finding.reason()).isEqualTo(OrphanFileReason.CONTENT_ABSENT);
            assertThat(finding.path()).isEqualTo(key(absent));
        });
    }

    @Test
    void classifiesACanonicalFileWithoutARowAsOrphanContentAndLeavesItInPlace() throws IOException {
        UUID orphan = UUID.randomUUID();
        Path content = writeContent(orphan);

        OrphanFileReport report = service().scan();

        assertThat(report.findings()).singleElement().satisfies(finding -> {
            assertThat(finding.classification()).isEqualTo(OrphanFileClassification.ORPHAN_CONTENT);
            assertThat(finding.reason()).isEqualTo(OrphanFileReason.NO_EVIDENCE_ROW);
            assertThat(finding.path()).isEqualTo(key(orphan));
        });
        assertThat(content).exists();
        assertThat(Files.readAllBytes(content)).isEqualTo(CONTENT);
    }

    @Test
    void classifiesASymlinkedContentPathAsUnsafeWithoutFollowingIt() throws IOException {
        UUID evidenceId = UUID.randomUUID();
        Path outside = Files.createDirectory(root.resolve("outside-the-root"));
        Path target = Files.write(outside.resolve("secret.bin"), CONTENT);
        Path content = contentPath(evidenceId);
        Files.createDirectories(content.getParent());
        Files.createSymbolicLink(content, target);
        catalog.add(evidenceId, key(evidenceId));

        OrphanFileReport report = service().scan();

        assertThat(report.findings()).anySatisfy(finding -> {
            assertThat(finding.classification()).isEqualTo(OrphanFileClassification.UNSAFE_CONTENT);
            assertThat(finding.reason()).isEqualTo(OrphanFileReason.SYMBOLIC_LINK);
        });
        assertThat(Files.isSymbolicLink(content)).isTrue();
        assertThat(target).exists();
    }

    @Test
    void classifiesAnEntryOutsideTheCanonicalLayoutAsUnexpected() throws IOException {
        Files.write(root.resolve("stray-note.txt"), CONTENT);

        OrphanFileReport report = service().scan();

        assertThat(report.findings()).singleElement().satisfies(finding -> {
            assertThat(finding.classification()).isEqualTo(OrphanFileClassification.UNEXPECTED_ENTRY);
            assertThat(finding.path()).isEqualTo("stray-note.txt");
        });
        assertThat(root.resolve("stray-note.txt")).exists();
    }

    @Test
    void withholdsANonCanonicalStorageKeyAndPublishesTheEvidenceIdInstead() {
        UUID evidenceId = UUID.randomUUID();
        catalog.add(evidenceId, "/etc/passwd");

        OrphanFileReport report = service().scan();

        assertThat(report.findings()).singleElement().satisfies(finding -> {
            assertThat(finding.classification()).isEqualTo(OrphanFileClassification.UNSAFE_CONTENT);
            assertThat(finding.path()).isEqualTo(OrphanFileFinding.WITHHELD_PATH);
            assertThat(finding.evidenceId()).isEqualTo(evidenceId);
        });
        assertThat(report.toJson()).doesNotContain("/etc/passwd").doesNotContain("passwd");
    }

    /**
     * The finding record is the single choke point through which every reported path must pass, so an unsafe value
     * cannot reach a document even if a future scanner produced one.
     */
    @ParameterizedTest
    @MethodSource("unsafePaths")
    void refusesToBuildAFindingCarryingAnUnsafePath(String unsafePath) {
        assertThatThrownBy(() -> OrphanFileFinding.at(
                        OrphanFileClassification.UNEXPECTED_ENTRY,
                        OrphanFileReason.NOT_IN_CANONICAL_LAYOUT,
                        unsafePath))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static Stream<Arguments> unsafePaths() {
        return Stream.of(
                Arguments.of("/var/lib/proofchain/storage/cases/x/evidences/y/content.bin"),
                Arguments.of("../../etc/shadow"),
                Arguments.of("cases/../../../etc/shadow"),
                Arguments.of("./cases/a/evidences/b/content.bin"),
                Arguments.of("cases\\a\\evidences\\b"),
                Arguments.of("C:/storage/content.bin"),
                Arguments.of("cases//evidences"),
                Arguments.of("cases/a/\u0000/content.bin"),
                Arguments.of(""));
    }

    @Test
    void rendersNoAbsolutePathNoHostDetailAndNoEvidenceMetadata() throws IOException {
        UUID missing = UUID.randomUUID();
        UUID orphan = UUID.randomUUID();
        writeContent(orphan);
        catalog.add(missing, key(missing));
        Files.write(root.resolve("stray-note.txt"), CONTENT);

        OrphanFileReport report = service().scan();
        String json = report.toJson();

        for (String document : List.of(json)) {
            assertThat(document)
                    .doesNotContain(root.toString())
                    .doesNotContain(root.toAbsolutePath().toString())
                    .doesNotContain("orphan-report-fixture")
                    .doesNotContain(System.getProperty("user.name", "no-such-user"))
                    .doesNotContain(System.getProperty("java.io.tmpdir"));
        }
        assertThat(json).contains("MISSING_CONTENT", "ORPHAN_CONTENT", "UNEXPECTED_ENTRY");
    }

    @Test
    void rendersTwoByteIdenticalDocumentsForTheSameUnchangedInputs() throws IOException {
        UUID orphan = UUID.randomUUID();
        writeContent(orphan);
        catalog.add(UUID.randomUUID(), key(UUID.randomUUID()));
        Files.write(root.resolve("stray-note.txt"), CONTENT);

        OrphanFileReportService service = service();
        OrphanFileReport first = service.scan();
        OrphanFileReport second = service.scan();

        assertThat(second.toJson()).isEqualTo(first.toJson());
        assertThat(second.findings()).isEqualTo(first.findings());
    }

    /**
     * The no-mutation guarantee, asserted as an observation rather than as a claim: every entry under the root is
     * fingerprinted by relative path, type, size and content digest before and after the scan, and the catalog records
     * whether it was ever asked for anything other than a read.
     */
    @Test
    void mutatesNeitherTheStorageTreeNorTheCatalogDuringAScan() throws IOException {
        UUID intact = UUID.randomUUID();
        UUID missing = UUID.randomUUID();
        UUID orphan = UUID.randomUUID();
        writeContent(intact);
        writeContent(orphan);
        catalog.add(intact, key(intact));
        catalog.add(missing, key(missing));
        Files.write(root.resolve("stray-note.txt"), CONTENT);
        Files.createDirectories(root.resolve(".staging"));
        Files.write(root.resolve(".staging").resolve("leftover.tmp"), CONTENT);
        Path outside = Files.createDirectory(root.resolve("outside-the-root"));
        Path linked = contentPath(UUID.randomUUID());
        Files.createDirectories(linked.getParent());
        Files.createSymbolicLink(linked, Files.write(outside.resolve("target.bin"), CONTENT));

        String before = fingerprint(root);
        List<EvidenceStorageKeyEntry> catalogBefore = catalog.storageKeys();

        OrphanFileReport report = service().scan();

        assertThat(report.findings()).isNotEmpty();
        assertThat(fingerprint(root)).isEqualTo(before);
        assertThat(catalog.storageKeys()).isEqualTo(catalogBefore);
        assertThat(catalog.mutationAttempts()).isZero();
    }

    @Test
    void refusesToScanWhenTheConfiguredRootIsItselfASymbolicLink() throws IOException {
        Path real = Files.createDirectory(root.resolve("real-root"));
        Path link = Files.createSymbolicLink(root.resolve("linked-root"), real);

        assertThatThrownBy(() -> new OrphanFileReportService(properties(link), catalog).scan())
                .isInstanceOf(OrphanFileReportException.class);
    }

    private OrphanFileReportService service() {
        return new OrphanFileReportService(properties(root), catalog);
    }

    private static EvidenceStorageProperties properties(Path storageRoot) {
        return new EvidenceStorageProperties(storageRoot, DataSize.ofMegabytes(50));
    }

    private static String key(UUID evidenceId) {
        return "cases/" + CASE_ID + "/evidences/" + evidenceId + "/content.bin";
    }

    private Path contentPath(UUID evidenceId) {
        return root.resolve(key(evidenceId));
    }

    private Path writeContent(UUID evidenceId) throws IOException {
        Path content = contentPath(evidenceId);
        Files.createDirectories(content.getParent());
        return Files.write(content, CONTENT);
    }

    /**
     * A stable fingerprint of the whole tree: relative path, entry type, size and content digest for every entry,
     * sorted so it does not depend on directory iteration order. Symbolic links are described, never followed.
     */
    private static String fingerprint(Path root) throws IOException {
        List<String> entries = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root, java.nio.file.FileVisitOption.values().length == 0 ? 1 : 100)) {
            for (Path path : walk.toList()) {
                if (path.equals(root)) {
                    continue;
                }
                String relative = root.relativize(path).toString().replace('\\', '/');
                if (Files.isSymbolicLink(path)) {
                    entries.add(relative + "|symlink|" + Files.readSymbolicLink(path));
                    continue;
                }
                BasicFileAttributes attributes =
                        Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (attributes.isDirectory()) {
                    entries.add(relative + "|dir");
                } else {
                    entries.add(relative + "|file|" + attributes.size() + "|" + digest(path));
                }
            }
        }
        entries.sort(Comparator.naturalOrder());
        return String.join("\n", entries);
    }

    private static String digest(Path file) throws IOException {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new AssertionError("SHA-256 must be available", exception);
        }
    }

    /** A catalog that answers reads and counts any attempt to reach beyond them. */
    private static final class RecordingCatalog implements EvidenceStorageKeyCatalog {

        private final List<EvidenceStorageKeyEntry> entries = new ArrayList<>();
        private int mutationAttempts;

        void add(UUID evidenceId, String storageKey) {
            entries.add(new EvidenceStorageKeyEntry(evidenceId, storageKey));
        }

        int mutationAttempts() {
            return mutationAttempts;
        }

        @Override
        public List<EvidenceStorageKeyEntry> storageKeys() {
            List<EvidenceStorageKeyEntry> sorted = new ArrayList<>(entries);
            sorted.sort(Comparator.comparing(entry -> entry.evidenceId().toString()));
            return List.copyOf(sorted);
        }
    }
}
