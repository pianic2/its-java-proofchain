package it.itsprodigi.proofchain.migration;

import static org.assertj.core.api.Assertions.assertThat;

import it.itsprodigi.proofchain.custodyevent.api.CustodyChainVerificationResponse;
import it.itsprodigi.proofchain.custodyevent.application.CustodyChainVerificationService;
import it.itsprodigi.proofchain.custodyevent.domain.EventType;
import it.itsprodigi.proofchain.custodyevent.protocol.CanonicalCustodyEvent;
import it.itsprodigi.proofchain.custodyevent.protocol.CustodyEventCanonicalizer;
import it.itsprodigi.proofchain.custodyevent.protocol.CustodyEventHashing;
import it.itsprodigi.proofchain.migration.LegacyDataFixture.EvidenceSeed;
import it.itsprodigi.proofchain.migration.MigrationSchemaHarness.HistoryRow;
import it.itsprodigi.proofchain.operator.domain.Operator;
import it.itsprodigi.proofchain.operator.domain.OperatorRole;
import it.itsprodigi.proofchain.operator.persistence.OperatorRepository;
import it.itsprodigi.proofchain.support.OperationalCommandTestSupport;
import it.itsprodigi.proofchain.support.PostgreSqlIntegrationTest;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Upgrade certification for every schema baseline that can be reconstructed factually from repository history.
 *
 * <p>Each baseline is rebuilt by replaying the immutable production migrations up to that version, with representative
 * rows inserted at the point of the timeline where their tables existed. The delivered application is then started
 * against that database: it applies only the pending migrations, validates the checksums it already recorded, passes
 * Hibernate's {@code ddl-auto: validate} gate, and must leave every row and every frozen invariant intact.
 */
class BaselineUpgradeCertificationIT extends PostgreSqlIntegrationTest {

    private static final String OPERATOR_COLUMNS =
            "id, username, email, password_hash, first_name, last_name, role, status, created_at, updated_at, version";
    private static final String CASE_COLUMNS = """
            id, title, description, authority_name, external_reference, location, priority, status,
            created_by_operator_id, created_at, updated_at, closed_at, version
            """;
    private static final String MEMBERSHIP_COLUMNS = "id, case_id, operator_id, assigned_by_operator_id, assigned_at";
    private static final String EVIDENCE_COLUMNS = """
            id, case_id, reference_tag, title, description, status, current_holder_operator_id,
            uploaded_by_operator_id, source_type, source_description, source_manufacturer, source_model,
            source_serial_number, source_logical_identifier, acquisition_method, acquisition_location,
            acquisition_tool_name, acquisition_tool_version, acquisition_notes, acquired_at, original_filename,
            file_extension, media_type, file_size, content_sha256, contextual_sha256, storage_key,
            created_at, updated_at, version
            """;

    /** The structural fingerprint of the schema an empty database produces; every upgrade must converge on it. */
    private static List<String> emptyDatabaseStructure;

    @BeforeAll
    static void captureTheEmptyDatabaseReferenceSchema() {
        try (MigrationSchemaHarness reference = harness()) {
            reference.migrateToFinalVersion();
            emptyDatabaseStructure = reference.structure();
        }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(CertifiedBaseline.class)
    void everyCertifiedBaselineUpgradesToTheFinalSchemaAndPreservesItsData(CertifiedBaseline baseline) {
        try (MigrationSchemaHarness harness = harness()) {
            BaselineReconstruction.reconstruct(harness, baseline, EvidenceSeed.consistent());

            List<HistoryRow> baselineHistory = harness.history();
            MigrationHistoryAssertions.assertMatchesInventory(baselineHistory, baseline.version());

            List<Map<String, Object>> operatorsBefore = operators(harness);
            List<Map<String, Object>> casesBefore = baseline.hasCustodyCases() ? cases(harness) : List.of();
            List<Map<String, Object>> membershipsBefore = baseline.hasCustodyCases() ? memberships(harness) : List.of();
            List<Map<String, Object>> evidenceBefore = baseline.hasDigitalEvidence() ? evidence(harness) : List.of();

            try (ConfigurableApplicationContext context = harness.startApplication()) {
                assertThat(context.isRunning())
                        .as("the upgraded application must pass Flyway and Hibernate validation")
                        .isTrue();

                List<HistoryRow> upgradedHistory = harness.history();
                MigrationHistoryAssertions.assertMatchesInventory(upgradedHistory, MigrationInventory.FINAL_VERSION);
                MigrationHistoryAssertions.assertAlreadyAppliedMigrationsAreUntouched(baselineHistory, upgradedHistory);

                assertThat(harness.structure())
                        .as("an upgraded baseline must be structurally identical to a schema built from empty")
                        .isEqualTo(emptyDatabaseStructure);

                assertThat(operators(harness)).isEqualTo(operatorsBefore);
                if (baseline.hasCustodyCases()) {
                    assertThat(cases(harness)).isEqualTo(casesBefore);
                    assertThat(memberships(harness)).isEqualTo(membershipsBefore);
                } else {
                    assertThat(harness.count("SELECT COUNT(*) FROM custody_cases"))
                            .isZero();
                }
                if (baseline.hasDigitalEvidence()) {
                    assertThat(evidence(harness)).isEqualTo(evidenceBefore);
                    assertBackfilledGenesisEvent(harness);
                } else {
                    assertThat(harness.count("SELECT COUNT(*) FROM digital_evidence"))
                            .isZero();
                    assertThat(harness.count("SELECT COUNT(*) FROM custody_events"))
                            .isZero();
                }

                assertFrozenInvariants(harness, baseline);
                assertFocusedApiSmoke(context, baseline);
            }

            // The finished schema passes the same validation the runtime performs before it becomes ready.
            harness.flyway(null).validate();
        }
    }

    private static void assertBackfilledGenesisEvent(MigrationSchemaHarness harness) {
        assertThat(harness.count("SELECT COUNT(*) FROM custody_events")).isOne();
        Map<String, Object> event = harness.rows("""
                        SELECT id, case_id, evidence_id, operator_id, actor_role, sequence_number, event_type,
                               occurred_at, payload_version, previous_hash, event_hash, hash_version
                        FROM custody_events
                        """).getFirst();

        UUID eventId = (UUID) event.get("id");
        CanonicalCustodyEvent expected = new CanonicalCustodyEvent(
                eventId,
                LegacyDataFixture.CASE_ID,
                LegacyDataFixture.EVIDENCE_ID,
                LegacyDataFixture.OFFICER_ID,
                OperatorRole.EVIDENCE_OFFICER,
                1,
                EventType.EVIDENCE_REGISTERED,
                LegacyDataFixture.CREATED_AT,
                CanonicalCustodyEvent.PAYLOAD_VERSION,
                LegacyDataFixture.expectedGenesisPayload(),
                CustodyEventHashing.ZERO_HASH);
        String expectedHash = CustodyEventHashing.eventHash(expected);

        assertThat(eventId.version())
                .as("backfilled event identifiers are UUID v4")
                .isEqualTo(4);
        assertThat(event.get("case_id")).isEqualTo(LegacyDataFixture.CASE_ID);
        assertThat(event.get("evidence_id")).isEqualTo(LegacyDataFixture.EVIDENCE_ID);
        assertThat(event.get("operator_id")).isEqualTo(LegacyDataFixture.OFFICER_ID);
        assertThat(event.get("actor_role")).isEqualTo(OperatorRole.EVIDENCE_OFFICER.name());
        assertThat(event.get("sequence_number")).isEqualTo(1L);
        assertThat(event.get("event_type")).isEqualTo(EventType.EVIDENCE_REGISTERED.name());
        assertThat(event.get("payload_version")).isEqualTo(CanonicalCustodyEvent.PAYLOAD_VERSION);
        assertThat(event.get("previous_hash")).isEqualTo(CustodyEventHashing.ZERO_HASH);
        assertThat(event.get("event_hash")).isEqualTo(expectedHash);
        assertThat(event.get("hash_version")).isEqualTo(CustodyEventHashing.HASH_VERSION);
        assertThat(harness.scalar(OffsetDateTime.class, "SELECT occurred_at FROM custody_events")
                        .toInstant())
                .isEqualTo(LegacyDataFixture.CREATED_AT);
        assertThat(harness.scalar(
                        Boolean.class,
                        "SELECT payload_json = CAST(? AS jsonb) FROM custody_events",
                        canonicalPayload()))
                .isTrue();

        assertThat(harness.count("SELECT custody_event_count FROM digital_evidence WHERE id = ?", evidenceId()))
                .isOne();
        assertThat(harness.scalar(
                        String.class,
                        "SELECT custody_chain_head_hash FROM digital_evidence WHERE id = ?",
                        evidenceId()))
                .isEqualTo(expectedHash);
    }

    private static void assertFrozenInvariants(MigrationSchemaHarness harness, CertifiedBaseline baseline) {
        SchemaInvariants.assertOperatorUsernameIsUnique(harness);
        if (!baseline.hasDigitalEvidence()) {
            return;
        }
        SchemaInvariants.assertCustodyEventForeignKeyIsEnforced(harness);
        SchemaInvariants.assertCustodyEventSequenceIsUnique(harness);
        SchemaInvariants.assertCustodyEventsAreAppendOnly(harness);
        SchemaInvariants.assertEvidenceLifecycleGraphIsEnforced(harness);
    }

    private static void assertFocusedApiSmoke(ConfigurableApplicationContext context, CertifiedBaseline baseline) {
        Operator admin = context.getBean(OperatorRepository.class)
                .findById(LegacyDataFixture.ADMIN_ID)
                .orElseThrow();
        assertThat(admin.getUsername()).isEqualTo("legacy.admin");
        if (!baseline.hasDigitalEvidence()) {
            return;
        }
        CustodyChainVerificationResponse response = OperationalCommandTestSupport.authenticated(
                admin, () -> context.getBean(CustodyChainVerificationService.class)
                        .verifyChain(evidenceId(), OperationalCommandTestSupport.principal(admin)));
        assertThat(response.valid())
                .as("the hash chain must verify after the upgrade")
                .isTrue();
        assertThat(response.storedEventCount()).isOne();
        assertThat(response.calculatedHeadHash()).isEqualTo(response.storedHeadHash());
    }

    private static String canonicalPayload() {
        return CustodyEventCanonicalizer.canonicalizePayload(LegacyDataFixture.expectedGenesisPayload());
    }

    private static UUID evidenceId() {
        return LegacyDataFixture.EVIDENCE_ID;
    }

    private static List<Map<String, Object>> operators(MigrationSchemaHarness harness) {
        return harness.rows("SELECT " + OPERATOR_COLUMNS + " FROM operators ORDER BY id");
    }

    private static List<Map<String, Object>> cases(MigrationSchemaHarness harness) {
        return harness.rows("SELECT " + CASE_COLUMNS + " FROM custody_cases ORDER BY id");
    }

    private static List<Map<String, Object>> memberships(MigrationSchemaHarness harness) {
        return harness.rows("SELECT " + MEMBERSHIP_COLUMNS + " FROM case_memberships ORDER BY id");
    }

    private static List<Map<String, Object>> evidence(MigrationSchemaHarness harness) {
        return harness.rows("SELECT " + EVIDENCE_COLUMNS + " FROM digital_evidence ORDER BY id");
    }

    private static MigrationSchemaHarness harness() {
        return new MigrationSchemaHarness(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}
