package it.itsprodigi.proofchain.migration;

import java.util.List;

/**
 * The frozen production migration inventory.
 *
 * <p>Every value below is a fact recorded by Flyway itself in {@code flyway_schema_history}: the version, the derived
 * description, the migration type and, for SQL migrations, the CRC-32 checksum of the file. Applied migrations are
 * immutable, so these checksums may never change. Editing one character of an applied migration changes its checksum,
 * fails this inventory, and — in a real deployment — stops startup with a validation error instead of silently
 * diverging the schema.
 *
 * <p>{@code V6} is a Java migration. {@code BaseJavaMigration} publishes no checksum, so Flyway records {@code NULL}
 * and validates it by class name and version only; its behaviour is instead pinned by the backfill integration tests.
 */
final class MigrationInventory {

    static final List<MigrationRecord> PRODUCTION_MIGRATIONS = List.of(
            new MigrationRecord(1, "create operators", "SQL", "V1__create_operators.sql", 925901100),
            new MigrationRecord(
                    2,
                    "create custody cases and memberships",
                    "SQL",
                    "V2__create_custody_cases_and_memberships.sql",
                    1622561369),
            new MigrationRecord(3, "create digital evidence", "SQL", "V3__create_digital_evidence.sql", 1796431700),
            new MigrationRecord(4, "create custody events", "SQL", "V4__create_custody_events.sql", 512925251),
            new MigrationRecord(5, "add custody chain head", "SQL", "V5__add_custody_chain_head.sql", 1183820603),
            new MigrationRecord(
                    6,
                    "backfill evidence registration events",
                    "JDBC",
                    "db.migration.V6__backfill_evidence_registration_events",
                    null),
            new MigrationRecord(
                    7,
                    "enforce evidence lifecycle transitions",
                    "SQL",
                    "V7__enforce_evidence_lifecycle_transitions.sql",
                    899273302));

    static final int FINAL_VERSION = 7;

    private MigrationInventory() {}

    /** The inventory entries up to and including {@code version}, in application order. */
    static List<MigrationRecord> through(int version) {
        return PRODUCTION_MIGRATIONS.stream()
                .filter(record -> record.version() <= version)
                .toList();
    }

    record MigrationRecord(int version, String description, String type, String script, Integer checksum) {}
}
