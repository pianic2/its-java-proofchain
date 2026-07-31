package it.itsprodigi.proofchain.migration;

import static org.assertj.core.api.Assertions.assertThat;

import it.itsprodigi.proofchain.migration.MigrationInventory.MigrationRecord;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Governance audit for the schema lifecycle.
 *
 * <p>Flyway is the only schema authority. The versioned migrations are the delivered SQL creation scripts, they are
 * immutable once applied, and no delivered code, configuration, image or Compose service may repair, clean, drop,
 * truncate or otherwise reset a database to make the application start. These are file-level facts, so they are proved
 * without a database.
 */
class MigrationGovernanceTest {

    private static final Path SQL_MIGRATIONS = Path.of("src", "main", "resources", "db", "migration");
    private static final Path JAVA_MIGRATIONS = Path.of("src", "main", "java", "db", "migration");
    private static final Path MIGRATION_GUIDE = SQL_MIGRATIONS.resolve("README.md");
    private static final Path LIFECYCLE_GUIDE = Path.of("docs", "Database-Schema-Lifecycle.md");

    /**
     * Destructive or self-healing schema operations. Each pattern targets an operation the delivered runtime must never
     * perform by itself; a Project Owner may still run any of them by hand after diagnosing a failure.
     */
    private static final List<Pattern> FORBIDDEN_OPERATIONS = List.of(
            Pattern.compile("\\.repair\\s*\\("),
            Pattern.compile("flyway[:\\s-]+repair", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bflyway\\.clean\\b|\\.clean\\s*\\(\\s*\\)"),
            Pattern.compile("clean-disabled\\s*:\\s*false"),
            Pattern.compile("baseline-on-migrate\\s*:\\s*true"),
            Pattern.compile("validate-on-migrate\\s*:\\s*false"),
            Pattern.compile("ddl-auto\\s*:\\s*(create|create-drop|update)"),
            Pattern.compile("DROP\\s+DATABASE", Pattern.CASE_INSENSITIVE),
            Pattern.compile("DROP\\s+SCHEMA", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bTRUNCATE\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("DROP\\s+TABLE", Pattern.CASE_INSENSITIVE),
            Pattern.compile("compose\\s+down\\s+-v"),
            Pattern.compile("volume\\s+rm"));

    @Test
    void theProductionMigrationDirectoryContainsExactlyTheCertifiedInventory() throws IOException {
        List<String> sqlFiles;
        try (Stream<Path> files = Files.list(SQL_MIGRATIONS)) {
            sqlFiles = files.map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith(".sql"))
                    .sorted()
                    .toList();
        }
        List<String> expectedSqlFiles = MigrationInventory.PRODUCTION_MIGRATIONS.stream()
                .filter(record -> "SQL".equals(record.type()))
                .map(MigrationRecord::script)
                .sorted()
                .toList();
        assertThat(sqlFiles)
                .as("adding or removing a migration must be a deliberate, reviewed inventory change")
                .isEqualTo(expectedSqlFiles);

        List<String> javaFiles;
        try (Stream<Path> files = Files.list(JAVA_MIGRATIONS)) {
            javaFiles =
                    files.map(path -> path.getFileName().toString()).sorted().toList();
        }
        assertThat(javaFiles).isEqualTo(List.of("V6__backfill_evidence_registration_events.java"));
        assertThat(MigrationInventory.PRODUCTION_MIGRATIONS.stream()
                        .map(MigrationRecord::version)
                        .toList())
                .as("versions are dense, gapless and ordered")
                .isEqualTo(List.of(1, 2, 3, 4, 5, 6, 7));
    }

    @Test
    void theRuntimeReadsExactlyOneMigrationLocationAndKeepsEverySafetySwitch() throws IOException {
        String configuration =
                Files.readString(Path.of("src", "main", "resources", "application.yml"), StandardCharsets.UTF_8);
        assertThat(configuration).contains("locations: classpath:db/migration");
        assertThat(configuration).contains("baseline-on-migrate: false");
        assertThat(configuration).contains("validate-on-migrate: true");
        assertThat(configuration).contains("out-of-order: false");
        assertThat(configuration).contains("clean-disabled: true");
        assertThat(configuration).contains("ddl-auto: validate");
    }

    @Test
    void noDeliveredCodeConfigurationOrRuntimeAssetRepairsCleansOrDropsTheDatabase() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path file : auditedFiles()) {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            for (Pattern forbidden : FORBIDDEN_OPERATIONS) {
                if (forbidden.matcher(content).find()) {
                    offenders.add(file + " matches " + forbidden.pattern());
                }
            }
        }
        assertThat(offenders)
                .as("no delivered artifact may repair, clean, drop, truncate or reset the database automatically")
                .isEmpty();
    }

    @Test
    void theMigrationDocumentationCoversEveryRequiredTopicAndNamesEveryMigration() throws IOException {
        String guide = Files.readString(MIGRATION_GUIDE, StandardCharsets.UTF_8);
        assertThat(guide)
                .contains("## Migration ordering")
                .contains("## Immutable checksum policy")
                .contains("## Creating a clean database")
                .contains("## Supported upgrade paths")
                .contains("## Failure and recovery procedure")
                .contains("## Prohibited in normal operation")
                .contains("official SQL creation scripts");
        for (MigrationRecord record : MigrationInventory.PRODUCTION_MIGRATIONS) {
            assertThat(guide)
                    .as("the migration index must document version %s", record.version())
                    .contains(record.script());
        }

        String lifecycle = Files.readString(LIFECYCLE_GUIDE, StandardCharsets.UTF_8);
        assertThat(lifecycle)
                .contains("## Certified baseline matrix")
                .contains("## Failure modes")
                .contains("## Manual recovery from a failed migration");
        for (CertifiedBaseline baseline : CertifiedBaseline.values()) {
            assertThat(lifecycle)
                    .as("the baseline matrix must record the commit that introduced V%s", baseline.version())
                    .contains(baseline.commit());
        }
    }

    private static List<Path> auditedFiles() throws IOException {
        List<Path> files = new ArrayList<>(List.of(Path.of("compose.yml"), Path.of("Dockerfile")));
        for (Path root : List.of(Path.of("src", "main"), Path.of("docker"))) {
            try (Stream<Path> tree = Files.walk(root)) {
                tree.filter(Files::isRegularFile)
                        .filter(MigrationGovernanceTest::isAuditable)
                        .forEach(files::add);
            }
        }
        return files.stream().filter(Files::isRegularFile).toList();
    }

    private static boolean isAuditable(Path path) {
        String name = path.getFileName().toString();
        // V7 legitimately uses DROP TRIGGER IF EXISTS to stay replayable; the migrations themselves are the schema
        // authority and are reviewed as such, so the destructive-operation audit targets everything around them.
        if (path.startsWith(SQL_MIGRATIONS) || path.startsWith(JAVA_MIGRATIONS)) {
            return false;
        }
        return name.endsWith(".java")
                || name.endsWith(".yml")
                || name.endsWith(".yaml")
                || name.endsWith(".sh")
                || name.endsWith(".sql")
                || name.endsWith(".xml");
    }
}
