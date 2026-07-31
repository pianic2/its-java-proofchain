# Changelog

All notable changes to ProofChain are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

This changelog describes delivered capability, not commit history. Each entry corresponds to work that is merged,
tested and documented in this repository.

## [Unreleased]

Nothing. `1.0.0` is the delivered baseline.

Deliberately not started, and not to be read as planned commitments: descriptive-metadata surrogate validation, a
correct `405` for unsupported HTTP methods, a custody-event payload version tolerating zero-byte files, and the
`*Test` → `*IT` rename of the seven Testcontainers-backed Surefire classes. All are recorded as
[known limitations](./docs/Technical-Report.md#16-known-limitations-and-future-work).

## [1.0.0]

The release date is the date the Project Owner creates the `uf14-final-2026` tag at final acceptance. The version
itself is already frozen in `pom.xml`, in the published OpenAPI document, in the image label and in the Compose image
tag, and `ReleaseBaselineTest` asserts all four.

First and final release of the ITS delivery: a complete, tested chain-of-custody backend for digital evidence.

### Added

**Foundation and build**

- Maven project `it.itsprodigi:proofchain` on Java 25 and Spring Boot 4.0.7, with the Maven Wrapper `3.9.9` committed.
- Spotless `3.6.0` with `palantir-java-format 2.78.0` as the formatting authority, checked at `validate`.
- JaCoCo `0.8.15` with a `BUNDLE` / `LINE` / `COVEREDRATIO` ≥ `0.51` gate bound to `verify`, no class excluded.
- One canonical quality gate, `./mvnw --batch-mode --no-transfer-progress clean verify`, invoked unchanged by GitHub
  Actions.

**Authentication and operators**

- `POST /api/v1/auth/login` and `GET /api/v1/auth/me`: username/password login, BCrypt hashing, stateless JWT bearer
  tokens with a configurable TTL.
- Database-authoritative authorization: the token carries only the operator identifier, and role and status are
  re-read from PostgreSQL on every authenticated request.
- ADMIN-protected operator management: create, list, inspect, change role, change status.
- Password policy with configurable length bounds and BCrypt strength; opt-in, idempotent bootstrap administrator.
- Dedicated `AUTH_AUDIT` logging destination with sanitized values.

**Custody cases and membership**

- Custody case lifecycle (`OPEN` / `CLOSED`) with descriptive metadata, priority and a creator reference.
- `CaseMembership` as an explicit join entity carrying `assignedBy` and `assignedAt`, unique per
  `(case, operator)`.
- Contextual case access: ADMIN sees every case, other roles see only their memberships, and inaccessible identifiers
  are reported as not found rather than forbidden.
- Protection of the last responsible case manager and of the last active administrator.

**Digital evidence**

- Multipart registration with typed source and acquisition metadata, paged listing per case, item inspection and
  content download.
- Two SHA-256 values computed while streaming: the content digest and a contextual digest binding it to the owning case
  and evidence identifier. Neither is ever rewritten.
- A hardened filesystem storage adapter: server-derived storage keys, path re-checks against the storage root, no
  traversal, no symbolic-link escape, and no path or byte ever logged or returned.
- Staged-then-finalized writes with transaction-synchronized cleanup, so a rolled-back registration leaves no content
  behind.

**Custody events and the hash chain**

- An append-only, per-evidence custody-event history with six typed payloads at `payload_version = 1`, stored as JSONB.
- A canonical JSON protocol with fixed field order, explicit escaping, UTC microsecond instants and rejection of
  unpaired UTF-16 surrogates, hashed as unkeyed SHA-256 over the domain separator `proofchain:custody-event:v1` plus a
  line feed plus the canonical bytes.
- Zero-hash genesis at sequence 1 (always `EVIDENCE_REGISTERED`, appended in the registration transaction), strict
  `+1` sequencing, and `previousHash` linking.
- An external anchor on the evidence row (`custody_event_count`, `custody_chain_head_hash`) so a truncated tail is
  detectable.
- Read APIs for the timeline and for a single event, and `POST /api/v1/evidences/{id}/verify-chain` returning a
  deterministic verdict with a precise failure reason.
- A published, build-asserted fixed vector: `71bd5e38f56d4a22228532372d058304246ed58e8634b8e58da37fd30e82fd2d`.

**Operational custody workflows**

- Exactly five named commands — transfer, descriptive metadata update, file-integrity verification, seal, release —
  each appending exactly one custody event in the same transaction and returning its `Location`.
- A shared transaction template with a frozen lock order, `PESSIMISTIC_READ` custody case then `PESSIMISTIC_WRITE`
  evidence, enforced by construction: the evidence lock requires a token only the case lock can produce.
- One server instant per command, truncated to microseconds, shared by the aggregate `updatedAt` and the event
  `occurredAt`.
- A data-driven authorization matrix on the command enum, including the current-holder rule for evidence officers.
- Evidence lifecycle `IN_CUSTODY → SEALED`, `IN_CUSTODY → RELEASED`, `SEALED → RELEASED`, with `RELEASED` terminal,
  enforced independently in the domain, the application and the database.
- No-op rejection for a transfer to the current holder and for a metadata update that changes nothing.

**Persistence and schema**

- Seven Flyway migrations as the official creation scripts: `V1`–`V5` and `V7` in SQL, `V6` as a Java migration that
  backfills genesis events using the production hashing code.
- Database-level integrity: `CHECK` constraints for lengths, normalization, enumerations, hash formats and storage-key
  safety; a partial active-admin index; a per-case partial unique reference tag; a composite foreign key
  `(evidence_id, case_id)`.
- An append-only trigger on `custody_events` raising SQLSTATE `55000` on any `UPDATE` or `DELETE`.
- A lifecycle-transition trigger on `digital_evidence` (`V7`) rejecting any status change outside the graph.
- Flyway configured with `baseline-on-migrate=false`, `validate-on-migrate=true`, `out-of-order=false`,
  `clean-disabled=true`; Hibernate at `ddl-auto: validate`.

**API and error contract**

- 27 approved operations, reconciled by `ApiSurfaceContractIT` against the live Spring request mappings, the generated
  OpenAPI document, the Problem Details catalogue and the Postman collection.
- RFC 9457 Problem Details on `application/problem+json` with 29 stable `type` URIs, never carrying a stack trace,
  exception class or cause.
- Runtime-generated OpenAPI at `/v3/api-docs` and Swagger UI at `/swagger-ui/index.html` as the single API
  specification.

**Runtime and operations**

- A two-stage `Dockerfile` (Temurin 25 JDK build, Temurin 25 JRE runtime) producing `proofchain:1.0.0`.
- A Docker Compose stack with PostgreSQL `18.4-trixie`, health-gated startup, two independent named volumes, and no
  restart policy.
- Container hardening: non-root `10001:10001`, read-only root filesystem, `cap_drop: ALL`, `no-new-privileges`, a
  bounded `noexec` `tmpfs`, and a root-owned read-only application jar.
- A restricted actuator surface: `health` only, `show-details: never`, `show-components: never`, discovery disabled,
  with `liveness` and `readiness` groups where readiness depends on the database and on a provably writable evidence
  root.
- Graceful, bounded shutdown and bounded HTTP, multipart, connection and lock timeouts.
- An offline, read-only, non-destructive orphan-file report, reachable only from the command line with an explicit
  flag and its own minimal non-web Spring context — it has no controller, no actuator endpoint and no HTTP path.

**Configuration**

- Fully externalized, validated configuration with fail-fast startup: a missing, malformed or weak JWT secret, a
  non-positive TTL, an invalid password policy or BCrypt strength, missing datasource credentials, an unusable storage
  root, or an invalid request-size, timeout or CORS value all stop startup. No secret is ever generated or defaulted.
- Exactly three profiles: `local`, `container`, `test`.
- Deny-by-default CORS; an empty allowlist emits no header and `*` is rejected at startup.

**Testing and release evidence**

- 443 Surefire and 377 Failsafe tests, integration tests provisioning their own PostgreSQL through Testcontainers.
- Deterministic concurrency proofs built on `CountDownLatch`, `CyclicBarrier` and `pg_stat_activity` lock-waiter
  polling, with no timing sleeps; lock *order* is proven, not merely lock presence.
- Failure injection at aggregate mutation, event append, flush, storage finalization and transaction completion.
- Certified Flyway empty-database and upgrade paths, plus a migration-failure certification.
- A secret scan, a dependency inventory, a log and response leak audit, a hostile locale and timezone run, a bounded
  informative performance smoke, and a pinned CycloneDX SBOM profile.
- A Postman package: 14 ordered modules, 97 requests, 200 assertions, and a placeholder-only environment.

**Documentation**

- Root README, documentation home, technical report, an architecture document with eight Mermaid diagrams, an ITS
  compliance mapping, configuration, operations, testing and troubleshooting guides, six feature guides, ADR-001 to
  ADR-008, this changelog, and contribution rules.
- `DocumentationLinkAuditTest` fails the build on a broken internal link, a missing heading anchor, an ADR missing from
  or duplicated in the index, or a gap in ADR numbering.

### Changed

- The lock-order statement in ADR-006 was corrected during Sprint 5 certification: no operator or membership row is
  ever pessimistically locked by an operational command.
- `EvidenceCommandReason` now rejects unpaired UTF-16 surrogates with `400` before any lock is taken, instead of
  producing a sanitized `500` after the aggregate had been mutated.
- The OpenAPI endpoint allowlist was extended to pin every route's exact HTTP method.
- Two defects found only under a hostile locale and timezone (`Pacific/Kiritimati`, `tr-TR`) were fixed.

### Removed

- The unused `org.testcontainers:junit-jupiter` dependency: no test imports it and none is annotated
  `@Testcontainers`.

### Security

- Secrets are read only from the environment, never generated, never defaulted; `.env` is git-ignored and no credential
  is tracked.
- Tokens, password hashes, file bytes, storage keys, absolute paths and canonical hash preimages are never logged and
  never returned in an API response, asserted by `SecurityLogAndResponseLeakAuditIT`.
- Every request size is bounded: file, whole multipart request, header, form post, parameter count, part count.
- **OWASP Dependency-Check was not executed.** The pinned profile exists, but the NVD feeds,
  `jeremylong.github.io` and the CISA KEV catalogue are egress-blocked in the build environment. **This release carries
  no vulnerability analysis, and no zero-vulnerability claim may be inferred.**

### Known limitations

Listed in full, with their causes, in
[Technical report §16](./docs/Technical-Report.md#16-known-limitations-and-future-work). In brief:

1. PostgreSQL is the only supported database — an approved deviation from the ITS rubric requiring explicit
   teacher / Project Owner acknowledgement.
2. No vulnerability scan was performed.
3. A readable zero-byte evidence file yields `evidence-file-unavailable` (500) rather than `valid: false`.
4. Descriptive metadata is not UTF-16 surrogate-validated; a malformed title surfaces as an undeclared `500` after a
   full rollback.
5. Integrity verification stamps `updatedAt` although it is declared non-mutating.
6. A wrong HTTP method returns the sanitized generic `500` rather than `405` on every path except
   `GET /api/v1/auth/login`.
7. The Postman collection cannot produce the `INVALID` integrity verdict; a manual out-of-band step is documented.
8. Four Surefire tests skip because POSIX permission assumptions are unfalsifiable while the build runs as `root`.
9. Case-closure concurrency has no dedicated deterministic test.
10. Seven Testcontainers-backed classes are named `*Test` and run under Surefire.

### Not included

The delivery tag `uf14-final-2026` is created by the Project Owner at final acceptance and does not exist in the
repository. The demo guide and the presentation source are owned by a later subtask and are **PENDING**.
