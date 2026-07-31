# Database migrations

Flyway is the only source of truth for the ProofChain database schema. PostgreSQL creates only the database and its user; every table, column, constraint, index, trigger and function is created by a versioned migration in this directory. Hibernate runs with `ddl-auto: validate` and therefore never creates or alters anything.

**The versioned Flyway migrations in this directory are the official SQL creation scripts of the ITS delivery.** There is no separate `schema.sql`, no generated dump used as a model, and no second schema source of any kind. To read the delivered schema, read `V1` through `V7` in order; to create it, run the application against an empty database.

Sprint 0 intentionally contained no migration, so the only state before `V1` is an empty database. The certified lifecycle evidence — the baseline matrix, the upgrade procedure, the failure modes and the recovery runbook — is in [Database schema lifecycle](../../../../../docs/Database-Schema-Lifecycle.md).

## Migration index

| Version | Script | Type | Introduced by | Contents |
| --- | --- | --- | --- | --- |
| 1 | `V1__create_operators.sql` | SQL | IJPC-133 (`d679fa7`) | `operators` with its identity, role, status and normalization constraints |
| 2 | `V2__create_custody_cases_and_memberships.sql` | SQL | IJPC-146 (`4383deb`) | `custody_cases` and `case_memberships` |
| 3 | `V3__create_digital_evidence.sql` | SQL | IJPC-151 (`c86fcff`) | `digital_evidence` with typed metadata, integrity hashes and storage key |
| 4 | `V4__create_custody_events.sql` | SQL | IJPC-157 (`2182898`) | `custody_events`, its uniqueness rules and the append-only trigger |
| 5 | `V5__add_custody_chain_head.sql` | SQL | IJPC-158 (`6c87969`) | `custody_event_count` and `custody_chain_head_hash` on the evidence row |
| 6 | `db.migration.V6__backfill_evidence_registration_events` | Java | IJPC-159 (`b0a6ecc`) | genesis `EVIDENCE_REGISTERED` event for evidence registered before the chain existed |
| 7 | `V7__enforce_evidence_lifecycle_transitions.sql` | SQL | IJPC-167 (`240108f`) | database-enforced evidence lifecycle graph |

The Java migration lives in `src/main/java/db/migration/` because Flyway resolves Java migrations from the `db.migration` package; it belongs to the same ordered chain as the SQL files.

## Migration ordering

- Migrations are applied strictly in ascending version order, once each, and recorded in `flyway_schema_history`.
- Out-of-order application is disabled (`spring.flyway.out-of-order: false`). A migration whose version is lower than the highest applied version is a validation failure, not something Flyway quietly slots in.
- New work always takes the next free version. Versions are never reused, renumbered, reordered, squashed or split once applied anywhere.
- `spring.flyway.locations` is `classpath:db/migration` and nothing else. Any other location would be a second schema source and is not allowed.

## Immutable checksum policy

- An applied migration is immutable. Flyway stores a CRC-32 checksum of every SQL migration in `flyway_schema_history` and re-verifies it on every startup (`spring.flyway.validate-on-migrate: true`).
- Editing one character of an applied migration changes its checksum. Startup then fails with `Migration checksum mismatch for migration version <n>` and the application never becomes ready.
- Corrections are made by adding a new, higher-versioned migration. Never by editing an applied one, and never by editing the recorded history.
- Java migrations publish no checksum, so Flyway validates `V6` by version and class name. Its behaviour is pinned by integration tests instead, including the exact reason it reports for each kind of inconsistent legacy data.
- `spring.flyway.baseline-on-migrate` is `false`. A non-empty database without a schema history is an error to investigate, never something to baseline away.

## Creating a clean database

1. Provision an empty PostgreSQL database and user. Nothing else is prepared by hand.
2. Start the application. Flyway creates `flyway_schema_history` and applies `V1` … `V7` in order.
3. Hibernate then validates the mapped entities against the resulting schema. A mismatch stops startup.
4. Readiness turns green only after both gates pass and the evidence storage root is writable.

No dump is restored and no manual DDL is executed at any point.

## Supported upgrade paths

Every schema version from `V1` to `V7` upgrades to the final schema by starting the delivered application against it: Flyway applies only the pending migrations and leaves the already-recorded ones untouched. Each of these baselines is reproducible from repository history alone, because every migration file was added by exactly one commit and never modified afterwards.

| From | Pending migrations | Data effect |
| --- | --- | --- |
| empty database | 1 → 7 | none |
| V1 | 2 → 7 | none |
| V2 | 3 → 7 | none |
| V3 | 4 → 7 | genesis custody event backfilled for every existing evidence row |
| V4 | 5 → 7 | genesis custody event backfilled |
| V5 | 6 → 7 | genesis custody event backfilled |
| V6 | 7 | none |
| V7 | none | none |

Upgrading never rewrites, deletes or normalizes an existing row. The only data any migration creates is the genesis custody event derived from the evidence row itself, together with the matching `custody_event_count` and `custody_chain_head_hash`.

Downgrade is not supported. There is no undo migration; a rollback is a restore from backup.

## Failure and recovery procedure

A failed migration stops startup and leaves the schema at the last version that fully succeeded. PostgreSQL applies DDL transactionally, so a failing migration is rolled back and is not recorded as applied. The application never retries, never repairs and never resets anything.

Recovery is manual, deliberate and Project Owner controlled:

1. **Stop.** Leave the database as it is. Do not restart in a loop, and do not run `repair`, `clean` or a volume reset to unblock the deployment.
2. **Read the failure.** The startup log names the failing version and the underlying error — a checksum mismatch, an applied version that no longer resolves, a SQL error, or a rejection from the `V6` backfill such as `reason=count-event-mismatch`.
3. **Verify the schema state independently.** Inspect `flyway_schema_history` and the affected tables directly. Establish which version the schema is actually at and whether any partial change survived.
4. **Fix the cause, not the symptom.**
   - Checksum mismatch: restore the original migration file. Its content is in git and must be byte-identical. Rewriting the recorded checksum instead falsifies the delivery record.
   - Applied version not resolved locally: restore the missing migration file or deploy the correct application version. A deployment that lost a migration must be rebuilt, not repaired.
   - Failing SQL: add a new, higher-versioned migration that corrects the situation.
   - Inconsistent legacy data rejected by `V6`: correct the data with a reviewed, audited statement, or add a new migration that handles the case explicitly. The backfill deliberately refuses to guess.
5. **Flyway `repair` is a Project Owner decision only.** A human may run it by hand, after the root cause and the actual schema state have been independently verified — for example to clear the history row of a migration that was intentionally withdrawn. No application code, startup script, container entrypoint or Compose service may ever invoke it.
6. **Re-run the deployment** and confirm the history contains every expected version with its expected checksum.

## Prohibited in normal operation

- Editing, renaming, renumbering, reordering, squashing or deleting an applied migration.
- `spring.flyway.baseline-on-migrate: true`.
- Hibernate schema creation or update (`ddl-auto` other than `validate`).
- Any second schema source: `schema.sql`, `data.sql`, ad-hoc startup DDL, or a committed dump treated as the model.
- Automatic Flyway `repair` or `clean`, or an automatic `DROP DATABASE`, `DROP SCHEMA`, `TRUNCATE` or named-volume deletion from application code, startup scripts, images or Compose services.
- Any database other than PostgreSQL. MySQL compatibility is deliberately not implemented; the deviation is recorded for human approval rather than worked around.

`spring.flyway.clean-disabled` is `true`, so Flyway itself refuses `clean` even if something called it.

## Adding a migration

1. Take the next free version number.
2. Write forward-only SQL in a single descriptively named file.
3. Never touch an existing migration.
4. Update the migration index above, and extend the certification tests whenever the change affects a frozen invariant.
