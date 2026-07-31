package it.itsprodigi.proofchain.migration;

import static org.assertj.core.api.Assertions.assertThat;

import it.itsprodigi.proofchain.migration.MigrationInventory.MigrationRecord;
import it.itsprodigi.proofchain.migration.MigrationSchemaHarness.HistoryRow;
import java.util.List;

/** Assertions over {@code flyway_schema_history}: the recorded versions, checksums and their immutability. */
final class MigrationHistoryAssertions {

    private MigrationHistoryAssertions() {}

    /** The recorded history is exactly the frozen inventory through {@code throughVersion}, in order and successful. */
    static void assertMatchesInventory(List<HistoryRow> history, int throughVersion) {
        List<MigrationRecord> expected = MigrationInventory.through(throughVersion);
        assertThat(history).as("recorded migrations").hasSize(expected.size());
        for (int index = 0; index < expected.size(); index++) {
            HistoryRow row = history.get(index);
            MigrationRecord record = expected.get(index);
            assertThat(row.installedRank())
                    .as("installed rank of version %s", record.version())
                    .isEqualTo(index + 1);
            assertThat(row.version()).isEqualTo(String.valueOf(record.version()));
            assertThat(row.description()).isEqualTo(record.description());
            assertThat(row.type()).isEqualTo(record.type());
            assertThat(row.script()).isEqualTo(record.script());
            assertThat(row.checksum())
                    .as("recorded checksum of version %s", record.version())
                    .isEqualTo(record.checksum());
            assertThat(row.success()).isTrue();
        }
    }

    /**
     * Everything already applied at the baseline is byte-identical afterwards — same rank, checksum and installation
     * timestamp. Only pending migrations were applied; nothing was re-run, repaired or rewritten.
     */
    static void assertAlreadyAppliedMigrationsAreUntouched(List<HistoryRow> before, List<HistoryRow> after) {
        assertThat(after).as("history never shrinks").hasSizeGreaterThanOrEqualTo(before.size());
        assertThat(after.subList(0, before.size()))
                .as("migrations applied before the upgrade must be untouched")
                .isEqualTo(before);
    }
}
