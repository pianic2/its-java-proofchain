# ProofChain 1.0.0 — technical report

This is the reviewer-facing technical report for the ProofChain delivery. Every statement below describes code, tests,
migrations or configuration that exist in this repository at release `1.0.0`. Where a behaviour is imperfect, the
imperfection is stated rather than smoothed over: see [Known limitations](#16-known-limitations-and-future-work).

| Item | Value |
| --- | --- |
| Artifact | `it.itsprodigi:proofchain:1.0.0` |
| Runtime | Java 25, Spring Boot 4.0.7 |
| Build | Maven Wrapper 3.9.9 (`./mvnw`) |
| Database | PostgreSQL 18.4 only |
| Schema authority | Flyway `V1`–`V7`, Hibernate `ddl-auto: validate` |
| Approved HTTP operations | 27, pinned by `ApiSurfaceContractIT` |
| Canonical gate | `./mvnw --batch-mode --no-transfer-progress clean verify` |

---

## 1. Problem and project objective

A chain of custody is the auditable record of who held a piece of evidence, when, and what was done to it. For digital
evidence the record has an additional obligation: it must also demonstrate that the bytes themselves did not change
while they were held. A spreadsheet or a mutable database row cannot demonstrate that, because both can be edited after
the fact without leaving a trace.

ProofChain is the backend of a system that addresses exactly this. Its objective is narrow and deliberate:

1. persist custody cases, operators, evidence metadata and evidence content under explicit authorization;
2. bind each stored file to its metadata with reproducible SHA-256 values computed at registration;
3. record every operational act against a piece of evidence as an append-only, hash-linked custody event;
4. make both the file and the event history verifiable on demand, with a machine-readable verdict.

The project is an ITS delivery with a fixed time budget. It is a working backend, not a product. It is explicitly not
certified for production use, makes no availability or throughput commitment, and provides no legal guarantee about the
admissibility of the records it produces.

## 2. Functional scope and explicit exclusions

### Implemented

| Area | Capability |
| --- | --- |
| Authentication | Username/password login, stateless JWT bearer tokens, current-principal endpoint |
| Operators | ADMIN-only creation, listing, inspection, role change and status change |
| Custody cases | Creation, listing, inspection, metadata update, status transition (`OPEN`/`CLOSED`) |
| Case membership | Explicit `CaseMembership` join entity: list, assign, remove |
| Digital evidence | Multipart registration, paged listing per case, item inspection, content download |
| Custody events | Per-evidence append-only timeline, event detail, on-demand chain verification |
| Operational commands | Transfer, descriptive metadata update, file-integrity verification, seal, release |
| Runtime | Docker Compose stack, non-root read-only container, restricted health probes |
| Maintenance | Offline, read-only, non-destructive orphan-file report (command line only, no HTTP surface) |

### Deliberately excluded

Not implemented, and not described anywhere as implemented:

- generic command or generic event-append endpoints — the API exposes only named workflows;
- bulk or asynchronous operations, job queues, schedulers;
- custody restoration, un-sealing, evidence deletion, file repair or content rewriting;
- background reconciliation or automatic orphan cleanup — the orphan report only reports;
- digital signatures, timestamping authorities, antivirus or malware scanning of uploads;
- multi-tenancy, external identity providers, refresh tokens, password reset flows;
- any database other than PostgreSQL (see §16 and [ITS compliance](./ITS-Compliance.md));
- any cloud deployment target, horizontal scaling story or service-level objective.

## 3. Modular monolith architecture

ProofChain is one deployable Spring Boot application partitioned by business feature, not by technical layer and not
into services. Six top-level packages live under `it.itsprodigi.proofchain`:

| Module | Responsibility |
| --- | --- |
| `auth` | Credential verification, JWT issue/validate, request authentication filter, authentication audit log |
| `operator` | Operator aggregate, administrative use cases, password policy, opt-in bootstrap administrator |
| `custodycase` | Case aggregate, lifecycle, `CaseMembership`, contextual access decisions |
| `evidence` | Evidence aggregate, registration, reads, the five operational commands, storage port and adapter, offline maintenance |
| `custodyevent` | Custody-event domain, canonical hashing protocol, the single append-only writer, reads, chain verification |
| `common` | Spring Security wiring, OpenAPI, CORS, password encoder, Problem Details catalogue and handler |

Each feature module repeats the same internal shape: `api` (HTTP records and controllers), `application` (use cases and
transaction boundaries), `domain` (aggregate state and invariants), `persistence` (Spring Data JPA repositories).
Controllers never expose entities; API records are separate types. Controllers never own transactions.

The dependency direction is drawn in [Architecture — diagram 1](./Architecture.md#1-module-dependencies). The
notable edges are that `evidence` depends on `custodyevent` (every operational command appends an event) and on
`custodycase` (case visibility and locking), while `custodyevent` depends on `evidence` for the aggregate that anchors
the chain. That mutual dependency is real and is why the two remain packages inside one deployable rather than separate
modules.

Why a monolith: the entire delivery is one team, one release, one database, and the hardest correctness requirement —
"the aggregate mutation and its custody event either both commit or neither does" — is a single-database transaction.
Splitting it across services would have replaced a transaction with a distributed protocol for no benefit within scope.

## 4. Domain model and invariants

Five persistent aggregates, drawn with cardinalities in
[Architecture — diagram 2](./Architecture.md#2-domain-model-and-aggregate-ownership).

**Operator** — canonical lowercase username and email, BCrypt hash, one of `ADMIN`, `CASE_MANAGER`,
`EVIDENCE_OFFICER`, `AUDITOR`, one of `ACTIVE`, `SUSPENDED`, `DISABLED`, `@Version` optimistic lock. Invariant: the
system can never lose its last active administrator — enforced in the application and backed by the partial index
`ix_operators_active_admin_id`.

**CustodyCase** — descriptive metadata, `LOW`/`MEDIUM`/`HIGH`/`CRITICAL` priority, `OPEN`/`CLOSED` status, creator
reference, `@Version`. Invariant: `closed_at` is non-null exactly when the status is `CLOSED`
(`ck_custody_cases_closed_at_by_status`). A closed case rejects every mutating command against it and against its
evidence.

**CaseMembership** — the explicit join entity between a case and an operator, carrying who assigned it and when. It is
a first-class entity, not a `@ManyToMany` collection, precisely because the assignment carries its own attributes.
Unique on `(case_id, operator_id)`.

**DigitalEvidence** — the aggregate root of a piece of evidence: descriptive metadata, source and acquisition
metadata, immutable file facts (`original_filename`, `media_type`, `file_size`, `content_sha256`, `contextual_sha256`,
`storage_key`), lifecycle status, current holder, and the two chain-anchor columns `custody_event_count` and
`custody_chain_head_hash`. Invariants: `file_size > 0`; both digests match `^[0-9a-f]{64}$`; a holder is present for
`IN_CUSTODY` and `SEALED` and absent for `RELEASED` (`ck_digital_evidence_status_holder`); `updated_at >= created_at`;
the head hash is the zero hash whenever the event count is zero.

**CustodyEvent** — one immutable row per recorded act: case, evidence, actor operator, actor role at the time,
`sequence_number`, event type, `occurred_at`, `payload_version`, JSONB payload, `previous_hash`, `event_hash`,
`hash_version`. Invariants: `(evidence_id, sequence_number)` unique; `(evidence_id, event_hash)` unique;
`sequence_number > 0`; `payload_version = 1`; `hash_version = 1`; the payload is a JSON object. Every mapped column is
`updatable = false`, and a database trigger rejects any `UPDATE` or `DELETE` outright.

The evidence lifecycle graph is exactly:

```text
IN_CUSTODY -> SEALED
IN_CUSTODY -> RELEASED
SEALED     -> RELEASED
```

`RELEASED` is terminal. This is enforced three times independently: in the domain aggregate, in the application command
foundation (a mutating command against released evidence raises `invalid-evidence-state`), and in the database by the
`tr_digital_evidence_lifecycle_transition` trigger added in `V7`. The database guard is never reached by the
application; it exists so that a repair script or a manual `psql` session cannot un-seal or re-open released evidence
either.

## 5. Authentication and authorization

`POST /api/v1/auth/login` verifies a username and a BCrypt-hashed password and returns a signed JWT. The token carries
the operator identifier; it does **not** carry the role. Every authenticated request reloads the operator from
PostgreSQL and derives role and status from the current row, so a suspension or a role change takes effect on the next
request rather than at token expiry. The filter chain is stateless: no HTTP session, no persisted security context.

Authorization has three independent layers:

1. **Filter chain.** Only `POST /api/v1/auth/login`, the OpenAPI and Swagger routes and the three enumerated health
   probes are public. Everything else requires authentication. The health probes are listed one by one rather than as
   `/actuator/**`, so a future actuator endpoint is authenticated by default instead of published by accident.
2. **Method security.** `@PreAuthorize` guards the application services (14 classes carry at least one).
   Administrative operator management is `ADMIN`-only.
3. **Contextual access.** `ADMIN` sees every case. Any other role sees only the cases it is a member of; an
   inaccessible identifier is reported as `404`, never `403`, so case existence is not leaked.

The operational command matrix is data on the `EvidenceOperationalCommand` enum rather than scattered `if` statements:
each command declares the member roles that may run it, whether an `EVIDENCE_OFFICER` must additionally be the current
holder, whether it mutates, and whether it requires an operational reason. `ADMIN` is allowed globally and is therefore
never listed in the member roles. The full matrix is in
[Operational Custody Workflows](./Operational-Custody-Workflows.md).

Password policy, BCrypt strength, token TTL and the opt-in bootstrap administrator are externalized configuration and
validated at startup; details in [Authentication](./Auth.md) and [Configuration](./Configuration.md).

## 6. Persistence and Flyway

Spring Data JPA over PostgreSQL. Hibernate runs with `ddl-auto: validate` and never generates or alters schema. Flyway
is the sole schema authority, with `baseline-on-migrate: false`, `validate-on-migrate: true`, `out-of-order: false` and
`clean-disabled: true`, so a checksum or ordering drift stops the application instead of silently repairing it.

| Version | Kind | Content |
| --- | --- | --- |
| `V1` | SQL | `operators` + constraints + partial active-admin index |
| `V2` | SQL | `custody_cases`, `case_memberships` |
| `V3` | SQL | `digital_evidence` + file/path/hash constraints + indexes |
| `V4` | SQL | `custody_events`, the composite FK `(evidence_id, case_id)`, the append-only trigger |
| `V5` | SQL | `custody_event_count` and `custody_chain_head_hash` anchors on `digital_evidence` |
| `V6` | **Java** | Deterministic genesis-event backfill for evidence registered before `V4` |
| `V7` | SQL | Database-level evidence lifecycle transition guard |

`V6` is a Java migration because it must recompute canonical event hashes with exactly the production hashing code; a
SQL script cannot reproduce the canonical JSON. `V6` and `V7` are both replay-safe. No historical migration has ever
been edited — the rules are in [the migration guide](../src/main/resources/db/migration/README.md) and the certified
baseline matrix in [Database schema lifecycle](./Database-Schema-Lifecycle.md).

Constraint strategy is deliberately redundant: Bean Validation rejects bad input at the API boundary, the domain
aggregate rejects it again on construction, and a `CHECK` constraint rejects it a third time at the database. Trimming,
case normalization, length bounds, enumerations, hash formats and storage-key safety all appear as `CHECK` constraints,
so a direct SQL session is held to the same rules as the application. The schema, with the constraints that matter, is
drawn in [Architecture — diagram 3](./Architecture.md#3-database-schema-from-the-flyway-migrations).

## 7. Filesystem storage and integrity controls

Evidence content is not stored in the database. It is written through the `EvidenceStoragePort` abstraction, whose only
implementation is `FileSystemEvidenceStorage`, into a configured storage root (`PROOFCHAIN_STORAGE_ROOT`, `./storage`
under the `local` profile, `/var/lib/proofchain/storage` under `container`).

- The storage key is derived by `EvidenceStorageKeyFactory` from the case and evidence identifiers. It is never taken
  from the client and never derived from the uploaded filename.
- The database independently constrains `storage_key`: relative, no backslash, no colon, no `//`, no `.`/`..` segment,
  no control character.
- Every resolved path is re-checked against the storage root before use, so traversal and symbolic-link escape are
  rejected rather than followed.
- Absolute paths, storage keys and file bytes never appear in a log line or an API response.

Two SHA-256 values are computed at registration:

- **`contentSha256`** — the digest of the file bytes, computed while streaming, never by loading the file in memory.
- **`contextualSha256`** — a digest binding the content digest to the owning case and evidence identifiers, so the same
  bytes registered in two different cases do not produce the same contextual value.

Neither is ever rewritten after registration. There is no API that can change stored content or a stored digest.

## 8. Custody events, canonical payload and the SHA-256 chain

Each piece of evidence owns its **own** chain. Chains are per-evidence and independent; there is no global ledger, no
network, no consensus and no replication. This is a tamper-evidence mechanism, not a distributed ledger.

**Sequence.** The chain starts at `sequence_number = 1`, which is always the `EVIDENCE_REGISTERED` genesis event
appended in the same transaction as registration. Every later event increments by exactly one.

**Linking.** The genesis event's `previous_hash` is the zero hash (64 `0` characters). Every later event's
`previous_hash` is the `event_hash` of its predecessor.

**Hashing.** `event_hash` is an unkeyed SHA-256 over:

```text
"proofchain:custody-event:v1" || LF || canonical-json-bytes(event)
```

The domain separator makes a digest computed by this protocol unusable as a digest of anything else. The hash is
**unkeyed**: it proves that stored events have not been altered relative to one another, and it does **not** prove
authorship. Anyone able to rewrite the whole table could recompute a consistent chain. There is no digital signature
and none is claimed.

**Canonical JSON.** `CustodyEventCanonicalizer` emits the JSON by hand rather than through a general-purpose mapper:
fields are written in a fixed lexicographic order, there is no insignificant whitespace, instants are rendered UTC with
exactly six fractional digits, and strings are escaped by an explicit table. An unpaired UTF-16 surrogate is rejected
outright rather than replaced, because a replacement character would make two different inputs hash identically.

**Anchor.** `digital_evidence.custody_event_count` and `digital_evidence.custody_chain_head_hash` mirror the chain
length and head hash on the aggregate row. This external anchor is what lets verification detect events that were
deleted from the tail — the chain alone could not. The structure is drawn in
[Architecture — diagram 8](./Architecture.md#8-custody-chain-structure).

**Payloads.** Six typed payload records, one per event type, all at `payload_version = 1`, stored as JSONB. The
reproducible fixed vector — canonical JSON string, preimage and the resulting digest
`71bd5e38f56d4a22228532372d058304246ed58e8634b8e58da37fd30e82fd2d` — is published in
[Custody Events](./Custody-Events.md) and asserted by `CustodyEventDocumentationVectorTest`, so a protocol change
breaks the build.

**Immutability.** Events are written by exactly one class, `CustodyEventAppender`, which is transaction-`MANDATORY` and
therefore cannot run outside a caller's transaction. Every mapped column is `updatable = false`. The
`custody_events_append_only` trigger raises SQLSTATE `55000` on any `UPDATE` or `DELETE`. No HTTP route can create,
modify or delete an event; `ApiSurfaceContractIT` asserts that no non-`GET` route under `/events` is mapped at all.

## 9. Operational workflows

Five named commands, and only five:

| Command | Route | Mutating | Reason required | Effect |
| --- | --- | --- | --- | --- |
| Transfer | `POST /api/v1/evidences/{id}/transfer` | yes | yes | New current holder |
| Metadata update | `PATCH /api/v1/evidences/{id}/metadata` | yes | yes | Descriptive/source/acquisition fields |
| Integrity verification | `POST /api/v1/evidences/{id}/verify-integrity` | no | no | Re-reads the file, records the verdict |
| Seal | `POST /api/v1/evidences/{id}/seal` | yes | yes | `IN_CUSTODY` → `SEALED` |
| Release | `POST /api/v1/evidences/{id}/release` | yes | yes | → `RELEASED`, holder cleared |

Each appends **exactly one** custody event in the same transaction and returns a `Location` header pointing at that
event. There is no command that appends zero events and none that appends two. A transfer to the current holder and a
metadata update that changes nothing are rejected as no-ops (`custody-transfer-no-op`, `metadata-update-no-op`) rather
than recorded as empty history.

All five share one transaction template, `EvidenceOperationalCommandTransaction`, drawn in
[Architecture — diagram 5](./Architecture.md#5-generic-operational-command). Each service supplies only its
workflow-specific body: the mutation and the payload. Authorization, locking, the shared instant, conflict translation
and the append are not re-implemented per command.

Integrity verification is the exception worth naming: it is declared non-mutating, and it streams the stored file once
under both locks, comparing the observed digest and byte count against the persisted values. A mismatch is a
**successful** verification with `valid: false`, not an error. Only a technical inability to read the exact bytes is an
error, and it aborts before any event is appended. See [Architecture — diagram 6](./Architecture.md#6-file-integrity-verification).

## 10. Transactions and lock order

The lock order is frozen and identical for every operational command:

```text
PESSIMISTIC_READ  CustodyCase
PESSIMISTIC_WRITE DigitalEvidence
append CustodyEvent
```

It is enforced **by construction**, not by convention: `EvidenceCommandLockService.lockEvidence(...)` requires a
`CaseReadLock` token, and the only way to obtain that token is `lockCase(...)`. Acquiring the evidence lock first does
not compile.

Additional properties, each verified by a test rather than asserted in prose:

- The case lock is never upgraded to a write lock, and at most one evidence row is locked per command.
- In the operational commands, operators and memberships are re-read and re-checked inside the transaction but are
  **never** pessimistically locked, because locking an operator row would serialize unrelated cases that merely share a
  member. (Evidence *registration* is a separate, earlier path and does lock the case and the operator rows for update;
  it is not one of the five commands.)
- One server instant, `Instant.now(clock).truncatedTo(MICROS)`, is generated once per command and shared by the
  aggregate `updatedAt` and the event `occurredAt`. Microsecond truncation matches the PostgreSQL `TIMESTAMP(6)`
  resolution, so the value that is compared is the value that was stored.
- `EvidenceOperationalCommandFoundationIT` proves the *order*, not merely the presence of locks, by probing the other
  row with `FOR UPDATE NOWAIT` while a command waits, and by observing real waiters in `pg_stat_activity`.

## 11. Concurrency and rollback

There is **no silent retry** anywhere. A lock or version conflict is surfaced to the caller as
`custody-event-concurrency-conflict` with HTTP `409`, and the caller decides whether to repeat the operation. Retrying
inside the server would risk appending a second event for one user intent.

Every pooled connection is initialized with `SET lock_timeout` (default `10s`), so a contended command fails in bounded
time instead of blocking forever. Connection acquisition and validation budgets are likewise bounded and configurable.

Rollback is all-or-nothing because the aggregate mutation and the event append share one transaction. Failure injection
tests (Mockito spies inside `EvidenceRegistrationWebMvcIT`, `CustodyEventAppenderIT`,
`EvidenceOperationalCommandFoundationIT`) force failures at aggregate mutation, event append, flush, storage
finalization and transaction completion, and assert that no partial state survives. Registration additionally cleans up
its staged file through a transaction synchronization when the transaction does not commit, so a rolled-back
registration does not leave content behind.

Determinism in the concurrency suite comes from `CountDownLatch`, `CyclicBarrier` and polling `pg_stat_activity` for
actual lock waiters — not from `Thread.sleep`.

## 12. API and Problem Details

27 approved operations across auth, operators, cases, memberships, evidence, custody events and the five commands. The
list is not maintained by hand: `ApiSurfaceContractIT` reconciles the **live Spring request mappings**, the generated
OpenAPI document, the Problem Details catalogue and the delivered Postman collection against one table of approved
endpoints. Adding a route without approving it fails the build.

The runtime-generated document at `/v3/api-docs` (Swagger UI at `/swagger-ui/index.html`) is the single API
specification; no static specification file exists that could drift from it. Published schemas are asserted to expose
no persistence entity, no storage information and no optimistic-lock version.

All errors use `application/problem+json` with a stable `type` URI drawn from the `ProblemTypes` catalogue —
29 types, including `validation-error` (400), `resource-not-found` (404), `access-denied` (403),
`invalid-credentials`/`invalid-token`/`expired-token`/`authentication-required` (401), the conflict family
(`case-closed`, `invalid-evidence-state`, `custody-transfer-no-op`, `metadata-update-no-op`,
`custody-event-concurrency-conflict`, …, all 409), `payload-too-large` (413), and the sanitized failure family
(`storage-failure`, `evidence-file-unavailable`, `custody-event-persistence-failure`, `custody-chain-read-failure`,
`internal-server-error`, all 500). Problem bodies never carry a stack trace, an exception class or a cause;
`SecurityLogAndResponseLeakAuditIT` asserts it.

## 13. Testing and coverage

Two runners with a clean split: Surefire runs `*Test.java`, Failsafe runs `*IT.java`. Integration tests provision their
own PostgreSQL 18.4 through Testcontainers and never touch the local Compose database. Categories, commands, fixed
vectors, failure-injection strategy, the JaCoCo gate and the known skips are documented in [Testing](./Testing.md).

The coverage gate is a JaCoCo `BUNDLE` / `LINE` / `COVEREDRATIO` rule at **0.51**, bound to `verify`. It has never been
lowered and no application class is excluded — the plugin configuration contains no `<excludes>` element at all. Actual
line coverage sits far above the gate; the exact figures from the release build are recorded in
[Testing](./Testing.md).

## 14. Container runtime

The `Dockerfile` is two-stage: an `eclipse-temurin:25-jdk` build stage that runs the Maven Wrapper, and an
`eclipse-temurin:25-jre` runtime stage that receives only the packaged jar and the health-probe script — no sources, no
Maven repository, no build cache, no wrapper.

Runtime hardening, all of it asserted by `ContainerRuntimeBaselineTest`:

- non-root numeric identity `10001:10001`, declared in the image and repeated in `compose.yml`;
- `read_only: true` root filesystem;
- `cap_drop: ALL` and `no-new-privileges:true`;
- the only writable paths are the evidence volume and a bounded `tmpfs` at `/tmp` mounted `noexec,nosuid,nodev`;
- the jar is owned by `root` and mounted read-only, so the application cannot rewrite its own code;
- two independent named volumes, `proofchain-postgres-data` and `proofchain-evidence-data`;
- the application container starts only after the PostgreSQL healthcheck passes — no sleep, no retry loop;
- **no restart policy**, deliberately: a bad secret or an unusable storage root must stay down and visible.

Actuator exposes `health` only, with `show-details: never` and `show-components: never`, plus the `liveness` and
`readiness` groups. Readiness is green only once the context is ready, PostgreSQL answers and the evidence root is
provably writable. Discovery is disabled, so the runtime advertises no endpoint index. Full runbook in
[Operations](./Operations.md).

## 15. Security hardening

- Secrets are read only from the environment. Nothing is generated, nothing is defaulted; a missing, malformed or
  under-32-byte JWT secret stops startup. `.env` is git-ignored and no credential is tracked.
- Fail-fast configuration binding: invalid token TTL, password policy, BCrypt strength, storage root, request-size,
  timeout or CORS value stops startup rather than degrading the runtime.
- CORS defaults to deny; an empty allowlist emits no CORS header and a `*` entry is rejected at startup.
- Every request size is bounded — file, whole multipart request, header, form post, parameter count, part count.
- Authentication events go to a dedicated `AUTH_AUDIT` appender; log values pass through `LogValueSanitizer`. Tokens,
  password hashes, file bytes, storage keys, absolute paths and canonical preimages are never logged.
- A CycloneDX SBOM can be produced from the pinned `release-sbom` profile.
- **OWASP Dependency-Check has not been executed.** The `dependency-check` profile exists and is pinned to
  `12.2.2` with `failBuildOnCVSS=7`, but the NVD feeds, `jeremylong.github.io` and the CISA KEV catalogue are
  egress-blocked in this environment. **No vulnerability analysis has been performed for this release and no
  zero-vulnerability claim may be inferred.** Details and the exact command a reviewer must run are in the
  [security and dependency review](./release/1.0.0/Security-And-Dependency-Review.md).

## 16. Known limitations and future work

These are real defects and gaps in the delivered `1.0.0`. They are stated here so a reviewer does not have to find
them.

1. **PostgreSQL is the only supported database.** The supplied ITS rubric asks for MySQL. This delivery uses
   PostgreSQL and was not redesigned around the rubric. It is an **approved deviation that requires explicit
   teacher/Project Owner acknowledgement**; it is not a compliant equivalent and is not presented as one. The
   dependency is genuine, not cosmetic: `JSONB` payload storage, partial and expression indexes, `FOR SHARE` /
   `FOR UPDATE` lock semantics, `pg_stat_activity`-based concurrency proofs and PL/pgSQL triggers all appear in
   migrations, code and tests. See [ITS compliance](./ITS-Compliance.md).
2. **No vulnerability scan.** As in §15 — Dependency-Check did not run; the release carries no vulnerability analysis.
3. **A zero-byte evidence file is reported as a technical inability.** A readable but zero-byte stored file yields
   `evidence-file-unavailable` (500) rather than a completed verification with `valid: false`, because the frozen
   `IntegrityVerifiedPayload` requires a positive `fileSize`. Truncation to exactly zero bytes is therefore classified
   as an inability to read rather than as observed corruption. Fixing it would change a frozen payload version.
4. **Descriptive metadata is not surrogate-validated.** The operational `reason` is validated for well-formed UTF-16
   and fails closed with `400`. Descriptive fields are not. A `title` containing an unpaired surrogate mutates the
   aggregate, then fails inside canonicalization, and surfaces as a generic, undeclared `500`. The transaction rolls
   back **fully**, so no partial state is committed — but the status code is wrong and the failure is late.
5. **Integrity verification stamps `updatedAt`** even though it is declared non-mutating, because the workflow body
   calls `stampCommandInstant`. Recorded explicitly so the column is not mistaken for a mutation marker.
6. **A wrong HTTP method returns the sanitized generic `500`, not `405`.** The catch-all advice translates
   `HttpRequestMethodNotSupportedException` before Spring's own handling produces a `405`. The single exception is
   `GET /api/v1/auth/login`, which carries an explicit guard so that probing the login path answers `405` correctly.
   Every other path is affected.
7. **The Postman collection cannot exercise the `INVALID` integrity verdict.** No approved endpoint can alter stored
   content and the collection performs no filesystem or database edit, so it asserts the invariant that produces the
   verdict instead. Observing a real `valid: false` requires the documented manual out-of-band step (corrupt one byte
   in the evidence volume, re-run the request) in [the Postman guide](../postman/README.md).
8. **Four Surefire tests skip.** `FileSystemEvidenceStorageTest` and `FileSystemEvidenceStorageHardeningTest` assert
   behaviour on unreadable and non-writable directories and abort through JUnit `Assumptions` because the build runs as
   `root`, for whom POSIX permission bits are not enforced. This is an environment property, not a product defect, and
   no test was disabled to achieve it.
9. **Case-closure concurrency is not covered by a dedicated deterministic test**, because closure runs through
   `CustodyCaseService` on a different lock path. Closed-case rejection is covered non-concurrently for every command.
10. **Seven Testcontainers-backed classes are named `*Test` and therefore run under Surefire**, loosening the
    "Surefire is for fast tests" split. Renaming them is a follow-up decision for the Project Owner.

Future work, none of it started: surrogate validation for descriptive metadata, a `405` fix, a payload version 2 that
tolerates zero-byte files, executing Dependency-Check where the NVD is reachable, and the `*Test` → `*IT` rename.

## 17. Delivery and release model

- **Version.** `1.0.0` in the POM, in `OpenApiConfig.API_VERSION`, in the image label and in the Compose image tag.
  `ReleaseBaselineTest` asserts all of them and fails on any file still referencing the retired snapshot coordinate.
- **Branches and pull requests.** One Jira subtask, one branch `ijpc-<n>-<slug>`, one pull request, Conventional
  Commits with the Jira key in the scope. Rules in [CONTRIBUTING](../CONTRIBUTING.md).
- **Gate.** `./mvnw --batch-mode --no-transfer-progress clean verify` is the single canonical command. GitHub Actions
  provisions Temurin Java 25 and invokes exactly that; CI never runs `spotless:apply` and never modifies sources.
- **Formatting.** Spotless `3.6.0` with `palantir-java-format 2.78.0`, checked at `validate`.
- **Human gate.** AI agents propose, implement and review. Final validation and approval are performed by the Project
  Owner and are never claimed by an agent. The delivery tag `uf14-final-2026` is created by the Project Owner at
  acceptance and does **not** exist in the repository at the time of writing.
- **Changes in this release.** [CHANGELOG](../CHANGELOG.md).
- **Decisions.** [ADR index](./adr/README.md).
- **Rubric mapping.** [ITS compliance](./ITS-Compliance.md).
