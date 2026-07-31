package it.itsprodigi.proofchain.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import it.itsprodigi.proofchain.migration.LegacyDataFixture.EvidenceSeed;
import it.itsprodigi.proofchain.migration.MigrationSchemaHarness.HistoryRow;
import it.itsprodigi.proofchain.support.PostgreSqlIntegrationTest;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Failure certification.
 *
 * <p>A modified checksum, a migration the deployment no longer carries, an invalid migration and a data migration that
 * refuses inconsistent legacy state must all stop startup. None of them may be silently repaired: the recorded history
 * is byte-identical after every failed attempt, restarting fails exactly the same way, and no table, row or volume is
 * ever dropped, cleaned or reset to make the application start.
 */
class MigrationFailureCertificationIT extends PostgreSqlIntegrationTest {

    private static final String INVALID_LOCATIONS = "--spring.flyway.locations="
            + MigrationSchemaHarness.PRODUCTION_LOCATION + "," + MigrationSchemaHarness.INVALID_MIGRATION_LOCATION;

    @Test
    void aChangedChecksumStopsStartupAndIsNeverRepairedAutomatically() {
        try (MigrationSchemaHarness harness = harness()) {
            BaselineReconstruction.reconstruct(harness, MigrationInventory.FINAL_VERSION, EvidenceSeed.consistent());
            harness.execute("UPDATE flyway_schema_history SET checksum = checksum + 1 WHERE version = '5'");

            assertThatThrownBy(() -> harness.flyway(null).validate())
                    .as("flyway validate semantics run before the application becomes ready")
                    .hasStackTraceContaining("checksum mismatch for migration version 5");

            assertStartupIsBlockedOnEveryRestart(harness, "checksum mismatch for migration version 5");

            assertThat(harness.scalar(Integer.class, "SELECT checksum FROM flyway_schema_history WHERE version = '5'"))
                    .as("nothing rewrote the recorded checksum back to the resolved one")
                    .isEqualTo(1183820604);
            assertRepresentativeDataSurvived(harness);
        }
    }

    @Test
    void anAppliedMigrationThatTheDeploymentNoLongerCarriesStopsStartup() {
        try (MigrationSchemaHarness harness = harness()) {
            BaselineReconstruction.reconstruct(harness, MigrationInventory.FINAL_VERSION, EvidenceSeed.consistent());
            // A version the database recorded as applied but the deployed jar no longer carries. It sits inside the
            // applied range on purpose: a version above the highest applied one is a "future" migration, which is a
            // different situation from a migration file that disappeared from the middle of the chain.
            harness.execute("""
                    INSERT INTO flyway_schema_history (
                        installed_rank, version, description, type, script, checksum, installed_by,
                        installed_on, execution_time, success
                    ) VALUES (8, '4.5', 'lost migration', 'SQL', 'V4_5__lost_migration.sql', 1, current_user,
                              now(), 1, true)
                    """);

            assertThatThrownBy(() -> harness.flyway(null).validate())
                    .hasStackTraceContaining("applied migration not resolved locally");

            assertStartupIsBlockedOnEveryRestart(harness, "applied migration not resolved locally");

            assertThat(harness.count("SELECT COUNT(*) FROM flyway_schema_history WHERE version = '4.5'"))
                    .as("no automatic repair deleted the unresolved history row")
                    .isOne();
            assertRepresentativeDataSurvived(harness);
        }
    }

    @Test
    void anInvalidMigrationStopsStartupAndLeavesTheSchemaAtItsLastGoodVersion() {
        try (MigrationSchemaHarness harness = harness()) {
            BaselineReconstruction.reconstruct(harness, MigrationInventory.FINAL_VERSION, EvidenceSeed.consistent());

            assertStartupIsBlockedOnEveryRestart(harness, "V8__deliberately_invalid.sql", INVALID_LOCATIONS);

            assertThat(harness.count("SELECT COUNT(*) FROM flyway_schema_history WHERE version = '8' AND success"))
                    .as("a failed migration is never recorded as applied")
                    .isZero();
            assertThat(harness.tableNames())
                    .as("PostgreSQL rolls the failed migration back; nothing partial survives")
                    .doesNotContain("deliberately_invalid");
            assertRepresentativeDataSurvived(harness);
        }
    }

    @Test
    void aFailureAfterEarlierMigrationsStopsBeforeTheFinalVersionAndNeverSelfRepairs() {
        try (MigrationSchemaHarness harness = harness()) {
            // A Sprint 3 baseline whose evidence title the custody-event protocol cannot normalize.
            BaselineReconstruction.reconstruct(
                    harness, 3, EvidenceSeed.consistent().withTitle(" ab"));
            MigrationHistoryAssertions.assertMatchesInventory(harness.history(), 3);

            assertStartupIsBlockedOnEveryRestart(harness, "reason=malformed-evidence-snapshot");

            MigrationHistoryAssertions.assertMatchesInventory(harness.history(), 5);
            assertThat(harness.count("SELECT COUNT(*) FROM flyway_schema_history WHERE version IN ('6', '7')"))
                    .as("the chain stops at the failing migration and never reaches the final version")
                    .isZero();
            assertThat(harness.scalar(String.class, "SELECT title FROM digital_evidence"))
                    .as("the ambiguous legacy value is neither normalized nor rewritten")
                    .isEqualTo(" ab");
            assertThat(harness.count("SELECT COUNT(*) FROM custody_events"))
                    .as("no partial backfill is left behind")
                    .isZero();
            assertRepresentativeDataSurvived(harness);
        }
    }

    /**
     * Proves the failure stops startup, that restarting reproduces exactly the same failure, and that the recorded
     * history is byte-identical across restarts: nothing repaired, reordered, cleaned or dropped anything.
     */
    private static void assertStartupIsBlockedOnEveryRestart(
            MigrationSchemaHarness harness, String expectedFailure, String... extraArguments) {
        assertThatThrownBy(() -> harness.startApplication(extraArguments).close())
                .as("the application must not start")
                .hasStackTraceContaining(expectedFailure);

        List<HistoryRow> afterFirstFailure = harness.history();

        assertThatThrownBy(() -> harness.startApplication(extraArguments).close())
                .as("restarting must fail identically instead of self-repairing")
                .hasStackTraceContaining(expectedFailure);

        assertThat(harness.history())
                .as("the schema history must be untouched by a failed startup")
                .isEqualTo(afterFirstFailure);
    }

    private static void assertRepresentativeDataSurvived(MigrationSchemaHarness harness) {
        assertThat(harness.count("SELECT COUNT(*) FROM operators")).isEqualTo(2);
        assertThat(harness.count("SELECT COUNT(*) FROM custody_cases")).isOne();
        assertThat(harness.count("SELECT COUNT(*) FROM digital_evidence")).isOne();
        assertThat(harness.tableNames()).contains("operators", "custody_cases", "digital_evidence", "custody_events");
    }

    private static MigrationSchemaHarness harness() {
        return new MigrationSchemaHarness(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}
