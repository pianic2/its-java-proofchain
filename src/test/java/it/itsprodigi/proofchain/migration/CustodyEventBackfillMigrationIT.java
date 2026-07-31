package it.itsprodigi.proofchain.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import it.itsprodigi.proofchain.custodyevent.domain.EventType;
import it.itsprodigi.proofchain.custodyevent.protocol.CanonicalCustodyEvent;
import it.itsprodigi.proofchain.custodyevent.protocol.CustodyEventCanonicalizer;
import it.itsprodigi.proofchain.custodyevent.protocol.CustodyEventHashing;
import it.itsprodigi.proofchain.custodyevent.protocol.EvidenceRegisteredPayload;
import it.itsprodigi.proofchain.evidence.domain.AcquisitionMethod;
import it.itsprodigi.proofchain.evidence.domain.EvidenceStatus;
import it.itsprodigi.proofchain.evidence.domain.SourceType;
import it.itsprodigi.proofchain.operator.domain.OperatorRole;
import it.itsprodigi.proofchain.support.PostgreSqlIntegrationTest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CustodyEventBackfillMigrationIT extends PostgreSqlIntegrationTest {

    private static final UUID OPERATOR_ID = UUID.fromString("80000000-0000-4000-8000-000000000001");
    private static final UUID CASE_ID = UUID.fromString("81000000-0000-4000-8000-000000000001");
    private static final UUID EVIDENCE_ID = UUID.fromString("82000000-0000-4000-8000-000000000001");
    private static final Instant CREATED_AT = Instant.parse("2026-07-29T10:20:30.123456Z");
    private static final Instant ACQUIRED_AT = Instant.parse("2026-07-29T09:10:20.654321Z");
    private static final String CONTENT_SHA256 = "a".repeat(64);
    private static final String CONTEXTUAL_SHA256 = "b".repeat(64);
    private static final String NON_ZERO_HASH = "c".repeat(64);
    private static final String BCRYPT_HASH = "$2a$10$01234567890123456789012345678901234567890123456789012";

    private String schema;

    @BeforeEach
    void createIsolatedSchema() throws SQLException {
        schema = "backfill_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = connection();
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA \"" + schema + "\"");
        }
    }

    @AfterEach
    void dropIsolatedSchema() throws SQLException {
        if (schema == null || !schema.matches("backfill_[0-9a-f]{32}")) {
            return;
        }
        try (Connection connection = connection();
                Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA \"" + schema + "\" CASCADE");
        }
    }

    @Test
    void freshDatabaseMigrationIsANoOpForCustodyData() throws SQLException {
        fullFlyway().migrate();

        assertThat(v6HistoryCount()).isOne();
        assertThat(count("custody_events")).isZero();
        assertThat(count("digital_evidence")).isZero();
    }

    @Test
    void backfillsTheExactSprint3GenesisAndValidatesItIdempotently() throws SQLException {
        migrateThroughV5();
        insertSprint3Fixture();

        fullFlyway().migrate();

        StoredBackfill first = storedBackfill();
        EvidenceRegisteredPayload payload = expectedPayload();
        CanonicalCustodyEvent canonicalEvent = new CanonicalCustodyEvent(
                first.eventId(),
                CASE_ID,
                EVIDENCE_ID,
                OPERATOR_ID,
                OperatorRole.EVIDENCE_OFFICER,
                1,
                EventType.EVIDENCE_REGISTERED,
                CREATED_AT,
                CanonicalCustodyEvent.PAYLOAD_VERSION,
                payload,
                CustodyEventHashing.ZERO_HASH);
        String expectedHash = CustodyEventHashing.eventHash(canonicalEvent);

        assertThat(first)
                .isEqualTo(new StoredBackfill(
                        first.eventId(),
                        CASE_ID,
                        EVIDENCE_ID,
                        OPERATOR_ID,
                        OperatorRole.EVIDENCE_OFFICER.name(),
                        1,
                        EventType.EVIDENCE_REGISTERED.name(),
                        CREATED_AT,
                        CanonicalCustodyEvent.PAYLOAD_VERSION,
                        first.payloadJson(),
                        CustodyEventHashing.ZERO_HASH,
                        expectedHash,
                        CustodyEventHashing.HASH_VERSION,
                        1,
                        expectedHash));
        assertThat(payloadMatches(CustodyEventCanonicalizer.canonicalizePayload(payload)))
                .isTrue();
        assertThat(first.eventId().version()).isEqualTo(4);
        assertThat(v6HistoryCount()).isOne();

        removeTrailingMigrationHistory();
        fullFlyway().migrate();

        assertThat(storedBackfill()).isEqualTo(first);
        assertThat(count("custody_events")).isOne();
        assertThat(v6HistoryCount()).isOne();
    }

    @Test
    void rejectsCountEventMismatchWithoutPartiallyBackfilling() throws SQLException {
        migrateThroughV5();
        insertSprint3Fixture();
        executeUpdate(
                "UPDATE digital_evidence SET custody_event_count = 1, custody_chain_head_hash = ? WHERE id = ?",
                NON_ZERO_HASH,
                EVIDENCE_ID);

        assertThatThrownBy(() -> fullFlyway().migrate()).hasStackTraceContaining("count-event-mismatch");

        assertThat(v6HistoryCount()).isZero();
        assertThat(count("custody_events")).isZero();
        try (Connection connection = schemaConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT custody_event_count, custody_chain_head_hash FROM digital_evidence WHERE id = ?")) {
            statement.setObject(1, EVIDENCE_ID);
            try (ResultSet resultSet = statement.executeQuery()) {
                assertThat(resultSet.next()).isTrue();
                assertThat(resultSet.getLong(1)).isOne();
                assertThat(resultSet.getString(2)).isEqualTo(NON_ZERO_HASH);
            }
        }
    }

    private void migrateThroughV5() {
        flyway(MigrationVersion.fromVersion("5")).migrate();
    }

    private Flyway fullFlyway() {
        return flyway(null);
    }

    private Flyway flyway(MigrationVersion target) {
        FluentConfiguration configuration = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .defaultSchema(schema)
                .schemas(schema)
                .locations("classpath:db/migration");
        if (target != null) {
            configuration.target(target);
        }
        return configuration.load();
    }

    private void insertSprint3Fixture() throws SQLException {
        try (Connection connection = schemaConnection()) {
            insertOperator(connection);
            insertCase(connection);
            insertEvidence(connection);
        }
    }

    private static void insertOperator(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO operators (
                    id, username, email, password_hash, first_name, last_name, role, status,
                    created_at, updated_at, version
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setObject(1, OPERATOR_ID);
            statement.setString(2, "sprint3.officer");
            statement.setString(3, "sprint3.officer@example.test");
            statement.setString(4, BCRYPT_HASH);
            statement.setString(5, "Sprint");
            statement.setString(6, "Officer");
            statement.setString(7, OperatorRole.EVIDENCE_OFFICER.name());
            statement.setString(8, "ACTIVE");
            statement.setObject(9, utc(CREATED_AT));
            statement.setObject(10, utc(CREATED_AT));
            statement.setLong(11, 0);
            statement.executeUpdate();
        }
    }

    private static void insertCase(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO custody_cases (
                    id, title, description, authority_name, external_reference, location,
                    priority, status, created_by_operator_id, created_at, updated_at, closed_at, version
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setObject(1, CASE_ID);
            statement.setString(2, "Sprint 3 migration case");
            statement.setString(3, "Legitimate pre-custody-event case fixture");
            statement.setString(4, "ProofChain Lab");
            statement.setString(5, "SPRINT3-CASE-01");
            statement.setString(6, "Rome");
            statement.setString(7, "MEDIUM");
            statement.setString(8, "OPEN");
            statement.setObject(9, OPERATOR_ID);
            statement.setObject(10, utc(CREATED_AT));
            statement.setObject(11, utc(CREATED_AT));
            statement.setObject(12, null);
            statement.setLong(13, 0);
            statement.executeUpdate();
        }
    }

    private static void insertEvidence(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO digital_evidence (
                    id, case_id, reference_tag, title, description, status,
                    current_holder_operator_id, uploaded_by_operator_id,
                    source_type, source_description, source_manufacturer, source_model,
                    source_serial_number, source_logical_identifier, acquisition_method,
                    acquisition_location, acquisition_tool_name, acquisition_tool_version,
                    acquisition_notes, acquired_at, original_filename, file_extension,
                    media_type, file_size, content_sha256, contextual_sha256, storage_key,
                    created_at, updated_at, version
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setObject(1, EVIDENCE_ID);
            statement.setObject(2, CASE_ID);
            statement.setString(3, "SPRINT3-01");
            statement.setString(4, "Sprint 3 disk image");
            statement.setString(5, "Exact legitimate evidence fixture created before custody events");
            statement.setString(6, EvidenceStatus.IN_CUSTODY.name());
            statement.setObject(7, OPERATOR_ID);
            statement.setObject(8, OPERATOR_ID);
            statement.setString(9, SourceType.DEVICE.name());
            statement.setString(10, "Workstation SSD");
            statement.setString(11, "Acme");
            statement.setString(12, "Forensic One");
            statement.setString(13, "SN-SPRINT3-01");
            statement.setString(14, "/dev/nvme0n1");
            statement.setString(15, AcquisitionMethod.PHYSICAL.name());
            statement.setString(16, "ProofChain Lab");
            statement.setString(17, "Forensic Imager");
            statement.setString(18, "3.0");
            statement.setString(19, "Write blocker used");
            statement.setObject(20, utc(ACQUIRED_AT));
            statement.setString(21, "disk-image.E01");
            statement.setString(22, "e01");
            statement.setString(23, "application/octet-stream");
            statement.setLong(24, 4096);
            statement.setString(25, CONTENT_SHA256);
            statement.setString(26, CONTEXTUAL_SHA256);
            statement.setString(27, "cases/" + CASE_ID + "/evidences/" + EVIDENCE_ID + "/content.bin");
            statement.setObject(28, utc(CREATED_AT));
            statement.setObject(29, utc(CREATED_AT));
            statement.setLong(30, 0);
            statement.executeUpdate();
        }
    }

    private static EvidenceRegisteredPayload expectedPayload() {
        return new EvidenceRegisteredPayload(
                true,
                "SPRINT3-01",
                "Sprint 3 disk image",
                "Exact legitimate evidence fixture created before custody events",
                EvidenceStatus.IN_CUSTODY,
                SourceType.DEVICE,
                "Workstation SSD",
                "Acme",
                "Forensic One",
                "SN-SPRINT3-01",
                "/dev/nvme0n1",
                AcquisitionMethod.PHYSICAL,
                ACQUIRED_AT,
                "ProofChain Lab",
                "Forensic Imager",
                "3.0",
                "Write blocker used",
                "disk-image.E01",
                "e01",
                "application/octet-stream",
                4096,
                CONTENT_SHA256,
                CONTEXTUAL_SHA256,
                OPERATOR_ID,
                OPERATOR_ID);
    }

    private StoredBackfill storedBackfill() throws SQLException {
        try (Connection connection = schemaConnection();
                PreparedStatement statement = connection.prepareStatement("""
                SELECT event.id,
                       event.case_id,
                       event.evidence_id,
                       event.operator_id,
                       event.actor_role,
                       event.sequence_number,
                       event.event_type,
                       event.occurred_at,
                       event.payload_version,
                       event.payload_json::text,
                       event.previous_hash,
                       event.event_hash,
                       event.hash_version,
                       evidence.custody_event_count,
                       evidence.custody_chain_head_hash
                FROM custody_events event
                JOIN digital_evidence evidence ON evidence.id = event.evidence_id
                WHERE event.evidence_id = ?
                """)) {
            statement.setObject(1, EVIDENCE_ID);
            try (ResultSet resultSet = statement.executeQuery()) {
                assertThat(resultSet.next()).isTrue();
                StoredBackfill result = new StoredBackfill(
                        resultSet.getObject(1, UUID.class),
                        resultSet.getObject(2, UUID.class),
                        resultSet.getObject(3, UUID.class),
                        resultSet.getObject(4, UUID.class),
                        resultSet.getString(5),
                        resultSet.getLong(6),
                        resultSet.getString(7),
                        resultSet.getObject(8, OffsetDateTime.class).toInstant(),
                        resultSet.getInt(9),
                        resultSet.getString(10),
                        resultSet.getString(11),
                        resultSet.getString(12),
                        resultSet.getInt(13),
                        resultSet.getLong(14),
                        resultSet.getString(15));
                assertThat(resultSet.next()).isFalse();
                return result;
            }
        }
    }

    private long v6HistoryCount() throws SQLException {
        try (Connection connection = schemaConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '6' AND success")) {
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getLong(1);
            }
        }
    }

    private boolean payloadMatches(String canonicalPayload) throws SQLException {
        try (Connection connection = schemaConnection();
                PreparedStatement statement = connection.prepareStatement("""
                SELECT payload_json = CAST(? AS jsonb)
                FROM custody_events
                WHERE evidence_id = ?
                """)) {
            statement.setString(1, canonicalPayload);
            statement.setObject(2, EVIDENCE_ID);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() && resultSet.getBoolean(1);
            }
        }
    }

    private long count(String table) throws SQLException {
        if (!table.equals("custody_events") && !table.equals("digital_evidence")) {
            throw new IllegalArgumentException("Unexpected table");
        }
        try (Connection connection = schemaConnection();
                Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            resultSet.next();
            return resultSet.getLong(1);
        }
    }

    /**
     * Drops the history of the backfill and of every migration applied after it, so a full {@code migrate()} replays
     * them. Flyway would otherwise refuse to re-run V6 while a later version is still recorded as applied.
     */
    private void removeTrailingMigrationHistory() throws SQLException {
        executeUpdate("""
                DELETE FROM flyway_schema_history
                WHERE version IS NOT NULL AND version ~ '^[0-9]+$' AND CAST(version AS integer) >= 6
                """, null, null);
    }

    private void executeUpdate(String sql, Object first, Object second) throws SQLException {
        try (Connection connection = schemaConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            if (first != null) {
                statement.setObject(1, first);
            }
            if (second != null) {
                statement.setObject(2, second);
            }
            statement.executeUpdate();
        }
    }

    private Connection schemaConnection() throws SQLException {
        Connection connection = connection();
        connection.setSchema(schema);
        return connection;
    }

    private static Connection connection() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private record StoredBackfill(
            UUID eventId,
            UUID caseId,
            UUID evidenceId,
            UUID operatorId,
            String actorRole,
            long sequenceNumber,
            String eventType,
            Instant occurredAt,
            int payloadVersion,
            String payloadJson,
            String previousHash,
            String eventHash,
            int hashVersion,
            long eventCount,
            String chainHeadHash) {}
}
