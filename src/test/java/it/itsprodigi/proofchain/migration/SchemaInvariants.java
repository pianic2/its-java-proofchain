package it.itsprodigi.proofchain.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

/**
 * Frozen database-level invariants that must survive every upgrade path: foreign keys, unique constraints, the
 * append-only custody event trigger and the evidence lifecycle trigger. Each probe runs inside a transaction that is
 * always rolled back, so proving an invariant never changes the upgraded fixture.
 */
final class SchemaInvariants {

    private static final String INSERT_EVENT = """
            INSERT INTO custody_events (
                id, case_id, evidence_id, operator_id, actor_role, sequence_number, event_type,
                occurred_at, payload_version, payload_json, previous_hash, event_hash, hash_version
            ) VALUES (?, ?, ?, ?, 'EVIDENCE_OFFICER', ?, 'EVIDENCE_REGISTERED', ?, 1, CAST('{}' AS jsonb), ?, ?, 1)
            """;

    private SchemaInvariants() {}

    static void assertOperatorUsernameIsUnique(MigrationSchemaHarness harness) {
        String failure = rejection(
                harness,
                """
                INSERT INTO operators (
                    id, username, email, password_hash, first_name, last_name, role, status,
                    created_at, updated_at, version
                ) VALUES (?, 'legacy.admin', 'duplicate@example.test', ?, 'Dup', 'Licate', 'AUDITOR', 'ACTIVE',
                          ?, ?, 0)
                """,
                UUID.randomUUID(),
                LegacyDataFixture.BCRYPT_HASH,
                LegacyDataFixture.utc(LegacyDataFixture.CREATED_AT),
                LegacyDataFixture.utc(LegacyDataFixture.CREATED_AT));
        assertThat(failure).contains("uk_operators_username");
    }

    static void assertCustodyEventForeignKeyIsEnforced(MigrationSchemaHarness harness) {
        String failure = rejection(
                harness,
                INSERT_EVENT,
                UUID.randomUUID(),
                LegacyDataFixture.CASE_ID,
                LegacyDataFixture.EVIDENCE_ID,
                UUID.fromString("00000000-0000-4000-8000-0000000000ff"),
                99L,
                LegacyDataFixture.utc(LegacyDataFixture.CREATED_AT),
                "0".repeat(64),
                "e".repeat(64));
        assertThat(failure).contains("fk_custody_events_operator");
    }

    static void assertCustodyEventSequenceIsUnique(MigrationSchemaHarness harness) {
        String failure = rejection(
                harness,
                INSERT_EVENT,
                UUID.randomUUID(),
                LegacyDataFixture.CASE_ID,
                LegacyDataFixture.EVIDENCE_ID,
                LegacyDataFixture.OFFICER_ID,
                1L,
                LegacyDataFixture.utc(LegacyDataFixture.CREATED_AT),
                "0".repeat(64),
                "f".repeat(64));
        assertThat(failure).contains("uk_custody_events_evidence_sequence");
    }

    static void assertCustodyEventsAreAppendOnly(MigrationSchemaHarness harness) {
        assertThat(rejection(harness, "UPDATE custody_events SET actor_role = 'ADMIN'"))
                .contains("custody_events are append-only");
        assertThat(rejection(harness, "DELETE FROM custody_events")).contains("custody_events are append-only");
    }

    /** Releases the evidence, then attempts to seal it again: the terminal state must be enforced by the database. */
    static void assertEvidenceLifecycleGraphIsEnforced(MigrationSchemaHarness harness) {
        try (Connection connection = harness.open()) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate(
                        "UPDATE digital_evidence SET status = 'RELEASED', current_holder_operator_id = NULL");
                statement.executeUpdate("UPDATE digital_evidence SET status = 'SEALED', current_holder_operator_id = '"
                        + LegacyDataFixture.OFFICER_ID + "'");
                throw new AssertionError("The evidence lifecycle trigger accepted RELEASED -> SEALED");
            } catch (SQLException expected) {
                assertThat(expected.getMessage()).contains("illegal digital evidence lifecycle transition");
            } finally {
                connection.rollback();
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to probe the evidence lifecycle trigger", exception);
        }
    }

    private static String rejection(MigrationSchemaHarness harness, String sql, Object... parameters) {
        try (Connection connection = harness.open()) {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                for (int index = 0; index < parameters.length; index++) {
                    statement.setObject(index + 1, parameters[index]);
                }
                statement.executeUpdate();
                connection.rollback();
                throw new AssertionError("The database accepted a statement a frozen invariant must reject: " + sql);
            } catch (SQLException expected) {
                connection.rollback();
                return expected.getMessage();
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to probe a schema invariant", exception);
        }
    }
}
