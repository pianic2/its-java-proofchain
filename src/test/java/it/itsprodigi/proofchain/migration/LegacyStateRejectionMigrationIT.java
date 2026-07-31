package it.itsprodigi.proofchain.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import it.itsprodigi.proofchain.evidence.domain.EvidenceStatus;
import it.itsprodigi.proofchain.migration.LegacyDataFixture.EvidenceSeed;
import it.itsprodigi.proofchain.migration.MigrationSchemaHarness.HistoryRow;
import it.itsprodigi.proofchain.support.PostgreSqlIntegrationTest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Ambiguous or inconsistent legacy state must fail the migration.
 *
 * <p>The V6 custody-event backfill is the only migration that derives new domain data from existing rows, so it is the
 * place where guessing would be possible and is therefore forbidden. Every defect below stops the chain at V5: nothing
 * is guessed, nothing is skipped, no row is deleted and no legacy value is rewritten to make the migration pass. The
 * V5 baseline that carries the defect is still exactly as it was when the migration was refused.
 */
class LegacyStateRejectionMigrationIT extends PostgreSqlIntegrationTest {

    private static final String NON_ZERO_HASH = "c".repeat(64);
    private static final String FOREIGN_EVENT_HASH = "d".repeat(64);

    /** Each defect and the exact reason the backfill must report for it. */
    enum LegacyDefect {
        STRAY_EVENT_ON_AN_EMPTY_CHAIN("empty-chain-mismatch"),
        EVENT_COUNT_WITHOUT_MATCHING_EVENTS("count-event-mismatch"),
        RELEASED_EVIDENCE_WITHOUT_A_HOLDER("missing-holder-reference"),
        EVIDENCE_IN_AN_UNSUPPORTED_STATUS("unsupported-evidence-status"),
        EXISTING_EVENT_THAT_DOES_NOT_MATCH("existing-backfill-mismatch"),
        TEXT_THE_PROTOCOL_CANNOT_NORMALIZE("malformed-evidence-snapshot");

        private final String reason;

        LegacyDefect(String reason) {
            this.reason = reason;
        }

        String reason() {
            return reason;
        }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(LegacyDefect.class)
    void inconsistentLegacyStateFailsTheMigrationInsteadOfBeingGuessed(LegacyDefect defect) {
        try (MigrationSchemaHarness harness = harness()) {
            arrange(harness, defect);

            List<HistoryRow> historyBeforeUpgrade = harness.history();
            List<Map<String, Object>> evidenceBefore = evidence(harness);
            List<Map<String, Object>> eventsBefore = events(harness);

            assertThatThrownBy(harness::migrateToFinalVersion)
                    .as("the backfill must refuse the ambiguous state")
                    .hasStackTraceContaining("reason=" + defect.reason());

            assertThat(harness.history())
                    .as("no migration is recorded when the backfill refuses")
                    .isEqualTo(historyBeforeUpgrade);
            MigrationHistoryAssertions.assertMatchesInventory(harness.history(), 5);
            assertThat(evidence(harness))
                    .as("the legacy evidence row is neither rewritten nor deleted")
                    .isEqualTo(evidenceBefore);
            assertThat(events(harness))
                    .as("no custody event is invented, altered or removed")
                    .isEqualTo(eventsBefore);

            // Restarting the application reproduces the same refusal; nothing repairs or resets the database.
            assertThatThrownBy(() -> harness.startApplication().close())
                    .hasStackTraceContaining("reason=" + defect.reason());
            assertThat(harness.history()).isEqualTo(historyBeforeUpgrade);
            assertThat(evidence(harness)).isEqualTo(evidenceBefore);
            assertThat(events(harness)).isEqualTo(eventsBefore);
        }
    }

    /**
     * A chain head without events cannot even be stored: the V5 check constraint rejects it, so this ambiguous state
     * never reaches the backfill in the first place.
     */
    @Test
    void aNonZeroChainHeadWithoutEventsIsRejectedByTheDatabaseItself() {
        try (MigrationSchemaHarness harness = harness()) {
            BaselineReconstruction.reconstruct(harness, 5, EvidenceSeed.consistent());

            assertThatThrownBy(() ->
                            harness.execute("UPDATE digital_evidence SET custody_chain_head_hash = ?", NON_ZERO_HASH))
                    .hasStackTraceContaining("ck_digital_evidence_custody_chain_empty_head");

            harness.migrateToFinalVersion();
            MigrationHistoryAssertions.assertMatchesInventory(harness.history(), MigrationInventory.FINAL_VERSION);
        }
    }

    /**
     * The {@code missing-case-reference} and {@code missing-uploader-reference} guards are unreachable while the V3
     * foreign keys exist: evidence can never point at a case or uploader that is not there. The guards stay as
     * defence in depth, and this test proves the database, not the migration, is what makes them unreachable.
     */
    @Test
    void danglingCaseOrUploaderReferencesCannotExistInAReconstructedBaseline() {
        try (MigrationSchemaHarness harness = harness()) {
            BaselineReconstruction.reconstruct(harness, 5, EvidenceSeed.consistent());

            assertThatThrownBy(() -> harness.execute("UPDATE digital_evidence SET case_id = ?", UUID.randomUUID()))
                    .hasStackTraceContaining("fk_digital_evidence_case");
            assertThatThrownBy(() -> harness.execute(
                            "UPDATE digital_evidence SET uploaded_by_operator_id = ?", UUID.randomUUID()))
                    .hasStackTraceContaining("fk_digital_evidence_uploaded_by");
        }
    }

    private static void arrange(MigrationSchemaHarness harness, LegacyDefect defect) {
        switch (defect) {
            case RELEASED_EVIDENCE_WITHOUT_A_HOLDER ->
                BaselineReconstruction.reconstruct(
                        harness, 5, EvidenceSeed.consistent().released());
            case EVIDENCE_IN_AN_UNSUPPORTED_STATUS ->
                BaselineReconstruction.reconstruct(
                        harness, 5, EvidenceSeed.consistent().withStatus(EvidenceStatus.SEALED.name()));
            case TEXT_THE_PROTOCOL_CANNOT_NORMALIZE ->
                BaselineReconstruction.reconstruct(
                        harness,
                        5,
                        EvidenceSeed.consistent().withTitle(LegacyDataFixture.TITLE_REJECTED_BY_THE_PROTOCOL));
            case STRAY_EVENT_ON_AN_EMPTY_CHAIN -> {
                BaselineReconstruction.reconstruct(harness, 5, EvidenceSeed.consistent());
                insertForeignEvent(harness);
            }
            case EVENT_COUNT_WITHOUT_MATCHING_EVENTS -> {
                BaselineReconstruction.reconstruct(harness, 5, EvidenceSeed.consistent());
                harness.execute(
                        "UPDATE digital_evidence SET custody_event_count = 2, custody_chain_head_hash = ?",
                        NON_ZERO_HASH);
            }
            case EXISTING_EVENT_THAT_DOES_NOT_MATCH -> {
                BaselineReconstruction.reconstruct(harness, 5, EvidenceSeed.consistent());
                insertForeignEvent(harness);
                harness.execute(
                        "UPDATE digital_evidence SET custody_event_count = 1, custody_chain_head_hash = ?",
                        FOREIGN_EVENT_HASH);
            }
        }
    }

    /** An EVIDENCE_REGISTERED event whose payload and hash the backfill can never have produced. */
    private static void insertForeignEvent(MigrationSchemaHarness harness) {
        harness.execute(
                """
                INSERT INTO custody_events (
                    id, case_id, evidence_id, operator_id, actor_role, sequence_number, event_type,
                    occurred_at, payload_version, payload_json, previous_hash, event_hash, hash_version
                ) VALUES (?, ?, ?, ?, 'EVIDENCE_OFFICER', 1, 'EVIDENCE_REGISTERED', ?, 1,
                          CAST('{"backfilled": true}' AS jsonb), ?, ?, 1)
                """,
                UUID.fromString("94000000-0000-4000-8000-000000000001"),
                LegacyDataFixture.CASE_ID,
                LegacyDataFixture.EVIDENCE_ID,
                LegacyDataFixture.OFFICER_ID,
                LegacyDataFixture.utc(LegacyDataFixture.CREATED_AT),
                "0".repeat(64),
                FOREIGN_EVENT_HASH);
    }

    private static List<Map<String, Object>> evidence(MigrationSchemaHarness harness) {
        return harness.rows("""
                SELECT id, title, status, current_holder_operator_id, custody_event_count, custody_chain_head_hash
                FROM digital_evidence
                ORDER BY id
                """);
    }

    private static List<Map<String, Object>> events(MigrationSchemaHarness harness) {
        return harness.rows(
                "SELECT id, sequence_number, event_type, previous_hash, event_hash FROM custody_events ORDER BY id");
    }

    private static MigrationSchemaHarness harness() {
        return new MigrationSchemaHarness(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}
