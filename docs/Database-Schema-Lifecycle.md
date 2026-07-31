# Database schema lifecycle

This guide records how the ProofChain schema is created, upgraded and recovered, and what was actually certified. The rules themselves — ordering, the immutable checksum policy, the prohibited operations and the migration index — live next to the migrations in [Database migrations](../src/main/resources/db/migration/README.md). This page is the evidence and the runbook.

Flyway is the only schema authority. The versioned migrations are the official SQL creation scripts of the delivery: there is no `schema.sql`, no Hibernate generation and no committed dump used as a model. PostgreSQL is the only supported database; MySQL compatibility is deliberately not implemented and is recorded as a deviation for human approval.

## How a baseline is reconstructed factually

Every production migration file was added by exactly one commit and never modified afterwards. `git log --diff-filter=A` and `git log` return the same single commit for each of `V1` … `V7`, so the migration directory only ever grew. Two consequences follow, and the whole certification rests on them:

- The schema shipped at a historical commit is exactly the schema produced by applying, in order, the versions that existed at that commit — using today's unchanged files.
- The checksum recorded for a version at that commit is the checksum today's file still produces, because the file content never changed.

Reconstructing a baseline therefore needs no dump and no invented data: it is `flyway migrate` with `target` set to that version, with representative rows inserted at the point of the timeline where their tables existed. That is exactly what `BaselineUpgradeCertificationIT` does.

## Certified baseline matrix

| Baseline | Schema version | Introduced by | Commit | Tables present | Pending on upgrade |
| --- | --- | --- | --- | --- | --- |
| Empty database | none | Sprint 0 shipped no migration | — | none | 1 → 7 |
| Sprint 1 | V1 | IJPC-133 | `d679fa7` | `operators` | 2 → 7 |
| Sprint 2 | V2 | IJPC-146 | `4383deb` | + `custody_cases`, `case_memberships` | 3 → 7 |
| Sprint 3 | V3 | IJPC-151 | `c86fcff` | + `digital_evidence` | 4 → 7 |
| Sprint 4 (events) | V4 | IJPC-157 | `2182898` | + `custody_events`, append-only trigger | 5 → 7 |
| Sprint 4 (chain head) | V5 | IJPC-158 | `6c87969` | + chain head columns | 6 → 7 |
| Sprint 4 certified | V6 | IJPC-159 | `b0a6ecc` | genesis backfill applied | 7 |
| Sprint 5 certified | V7 | IJPC-167 | `240108f` | + lifecycle trigger | none |

Each baseline is certified end to end: the recorded versions and checksums are confirmed before the upgrade, the application then applies only the pending migrations, and afterwards every representative row is byte-identical, the backfilled genesis event matches the recomputed hash, the resulting schema is structurally identical to one built from an empty database, and the append-only trigger, the lifecycle trigger, the foreign keys and the unique constraints all still hold.

### Recorded checksums

| Version | Type | Checksum |
| --- | --- | --- |
| 1 | SQL | `925901100` |
| 2 | SQL | `1622561369` |
| 3 | SQL | `1796431700` |
| 4 | SQL | `512925251` |
| 5 | SQL | `1183820603` |
| 6 | Java | none — `BaseJavaMigration` publishes no checksum |
| 7 | SQL | `899273302` |

These values are asserted by the certification suite. A change to any migration file changes its checksum and fails the build before it can ever reach a database.

### States that are not reconstructible

- **Intermediate states within a single migration.** PostgreSQL applies DDL transactionally, so a migration is either fully applied or not applied at all. There is no half-applied version to reconstruct.
- **Databases carrying rows the current constraints reject.** Nothing in repository history records such data, so any fixture for it would be invented. Such a database is a manual investigation, not a supported upgrade path.
- **Any state before `V1`.** Sprint 0 shipped no migration, so the only pre-`V1` state is an empty database, which the empty-database certification already covers.

These are documented rather than guessed, as required.

## Creating a clean database

Provision an empty PostgreSQL database and user, then start the application. Flyway applies `V1` … `V7`, Hibernate validates the mapped entities against the result, and readiness turns green only once both gates pass. Nothing is restored and no DDL is run by hand.

Under Docker Compose this is the state after a first `docker compose up -d` on empty volumes; see [Container operations](./Operations.md).

## Upgrading an existing database

1. Take a backup. There is no undo migration; a rollback is a restore.
2. Deploy the new application version against the existing database. Do not pre-create or pre-alter anything.
3. Flyway validates the checksums it already recorded, applies only the pending versions in order, and records each one.
4. Hibernate validates the resulting schema; readiness follows.

Rows are never rewritten, deleted or normalized by an upgrade. The only data a migration creates is the genesis custody event that `V6` derives from each pre-existing evidence row, together with the matching `custody_event_count` and `custody_chain_head_hash`.

## Failure modes

Every failure below stops startup. Readiness never turns green, and nothing self-repairs: the recorded history is byte-identical after a failed attempt, and restarting reproduces exactly the same failure.

| Failure | What triggers it | Observed behaviour |
| --- | --- | --- |
| Changed checksum | an applied migration file was edited | startup fails with `Migration checksum mismatch for migration version <n>`; the recorded checksum is left as it is |
| Missing migration | the deployment no longer carries a version the database recorded as applied | startup fails with `Detected applied migration not resolved locally`; the history row is left as it is |
| Invalid migration | a migration contains failing SQL | startup fails naming the failing script; PostgreSQL rolls the migration back, so it is never recorded as applied and no partial object survives |
| Inconsistent legacy data | `V6` finds evidence and custody-event state it cannot reconcile | startup fails with `reason=<defect>`; no event is created, no row is altered, the chain stops before the final version |
| Failure part-way through the chain | earlier pending migrations succeed, a later one fails | the successful versions are recorded, the failing one is not, and the schema stays at the last good version |
| Restart after a failure | the operator restarts without fixing anything | the same failure, with no repair, no clean and no destructive reset |

### Why the backfill refuses instead of guessing

`V6` is the only migration that derives domain data from existing rows, so it is the only place where guessing would be possible. It refuses, by design, with an explicit reason:

| Reason | Legacy state |
| --- | --- |
| `empty-chain-mismatch` | the evidence records no events but custody events exist for it |
| `count-event-mismatch` | the recorded event count does not match the stored events |
| `missing-holder-reference` | the evidence has no resolvable current holder |
| `unsupported-evidence-status` | the evidence is not `IN_CUSTODY`, so no genesis event can be derived |
| `existing-backfill-mismatch` | an existing genesis event does not match the one the protocol would produce |
| `malformed-evidence-snapshot` | a stored value the database accepts cannot be represented by the custody-event protocol |

`missing-case-reference` and `missing-uploader-reference` are unreachable while the `V3` foreign keys exist; they remain as defence in depth.

A non-zero chain head with no events cannot even be stored: the `V5` check constraint rejects it before any migration sees it.

## Manual recovery from a failed migration

Recovery is manual, deliberate and Project Owner controlled. No application code, startup script, container entrypoint or Compose service may repair, clean, drop or reset anything.

1. **Stop and leave the database alone.** Do not loop restarts and do not reach for `repair`, `clean` or a volume reset.
2. **Read the failure** in the startup log: it names the failing version and the underlying cause.
3. **Verify the schema state independently** by inspecting `flyway_schema_history` and the affected tables. Confirm which version the schema is actually at.
4. **Fix the cause.** Restore the original migration file for a checksum mismatch, deploy the correct application version for a missing migration, add a new higher-versioned migration for failing SQL, or correct the legacy data with a reviewed, audited statement for a backfill rejection.
5. **Flyway `repair` only by Project Owner decision**, run by a human after the root cause and the schema state have been independently verified — for example to clear the history row of a migration that was intentionally withdrawn.
6. **Re-run the deployment** and confirm every expected version is recorded with its expected checksum.

Never edit a recorded checksum to make validation pass, and never delete data to make the backfill succeed. Both falsify the custody record.

## Certification tests

| Test | What it certifies |
| --- | --- |
| `EmptyDatabaseCertificationIT` | an empty database reaches the final schema, passes Flyway validation and Hibernate `ddl-auto: validate`, becomes ready and serves a representative API smoke |
| `BaselineUpgradeCertificationIT` | every certified baseline upgrades, preserving rows, backfilled data, constraints, foreign keys, unique constraints, indexes, both triggers and the hash chain |
| `MigrationFailureCertificationIT` | changed checksum, missing migration, invalid migration and a part-way failure all stop startup, and a restart reproduces the failure with no automatic repair |
| `LegacyStateRejectionMigrationIT` | inconsistent legacy state fails the migration instead of being guessed, skipped, deleted or rewritten |
| `CustodyEventBackfillMigrationIT` | the exact genesis event the backfill produces, and its idempotent revalidation |
| `MigrationGovernanceTest` | the migration inventory, the runtime configuration and the absence of any automatic repair, clean, drop or reset in the delivered artifacts |
