# ProofChain 1.0.0 — release notes

ProofChain is a chain-of-custody backend for digital evidence. It records who held a piece of
evidence, what was done to it and when, in an append-only history that can be verified afterwards.

It is a **modular monolith** with an **unkeyed SHA-256 hash chain per evidence item**. It is not a
blockchain, it uses no digital signatures and it performs no distributed consensus.

## Delivered scope

**Authentication and operators.** Stateless JWT authentication, BCrypt password storage, an opt-in
idempotent bootstrap administrator disabled by default, and ADMIN-protected operator management with
role and status transitions.

**Custody cases and membership.** Case creation, descriptive metadata, priority and status, member
assignment with a responsible-case-manager invariant, and irreversible closure.

**Digital evidence.** Multipart upload with one-pass hashing, atomic finalization on the same
filesystem, `CREATE_NEW` semantics with no overwrite, content and contextual SHA-256, paged reads
and byte-exact download. Storage keys are canonical and never derived from the uploaded filename.

**Custody events and hash chain.** One independent append-only chain per evidence item.
`EVIDENCE_REGISTERED` is the only genesis event. Each event carries an immutable actor identity plus
a historical role snapshot, a typed payload and a server-generated microsecond timestamp. The event
hash covers the domain separator `proofchain:custody-event:v1`, a line feed and a deterministic
canonical JSON rendering. Genesis links to a zero hash, sequence numbers start at 1, and the evidence
row carries the external count and head anchor so tail deletion is detectable. A reproducible fixed
vector is published in `docs/Custody-Events.md`.

**Operational workflows.** Custody transfer, presence-aware descriptive metadata update, file
integrity verification, sealing and release. Each appends exactly one typed custody event inside the
same transaction as the aggregate change.

**Lifecycle.** `IN_CUSTODY → SEALED`, `IN_CUSTODY → RELEASED`, `SEALED → RELEASED`. `RELEASED` is
terminal and enforced in the domain, in the application and in the database by a trigger. Released
evidence stays readable, downloadable, timeline-visible and chain-verifiable.

**Container runtime.** Multi-stage build on pinned Eclipse Temurin 25, a non-root runtime user
`10001:10001`, a read-only root filesystem, all capabilities dropped, a bounded `noexec` temporary
mount, separate persistent volumes for database and evidence, and health-gated startup ordering.
Actuator exposes only sanitized health, liveness and readiness.

**Delivery artifacts.** Runtime-generated OpenAPI pinned by an endpoint allowlist over the live
request mappings, a Postman collection of 97 requests and 200 assertions proven repeatable across a
destructive reset, a reviewer documentation set, an ITS compliance mapping and a deterministic demo.

## API surface

Exactly 27 approved operations. No generic command endpoint, no generic event append, no bulk
operation, no alias, no reopen or unseal route, and no repair endpoint. The surface is pinned by
`ApiSurfaceContractIT`, so adding or removing an operation fails the build.

## Technical baseline

Java 25 · Spring Boot 4.0.7 · Maven Wrapper 3.9.9 · PostgreSQL 18.4 · Flyway V1–V7 as the sole
schema authority · Hibernate `ddl-auto=validate` · Testcontainers with a real PostgreSQL.

**PostgreSQL is the only supported database.** This is a deliberate deviation from the supplied ITS
rubric, recorded in `docs/ITS-Compliance.md`, and it requires explicit teacher acknowledgement. The
application was not redesigned to satisfy the rubric.

## Verification summary

Certified at commit `739d980c6256b4b7b321424741aa87808b7d3277` from a separate clean clone:

| Metric | Value |
| --- | --- |
| `clean verify`, twice consecutively, no modification between runs | BUILD SUCCESS both times |
| Surefire | 443 tests, 0 failures, 0 errors, 4 skipped |
| Failsafe | 377 tests, 0 failures, 0 errors, 0 skipped |
| JaCoCo LINE | 91.66% (gate 0.51, unchanged) |
| JaCoCo BRANCH | 77.81% |
| Postman, twice from a destructive reset | 98 requests, 200 assertions, 0 failures, both runs identical |

The four skipped tests are POSIX permission assumptions that cannot be falsified while the build runs
as `root`.

## Defects found and fixed during Sprint 5 and Sprint 6

- The Sprint 3 OpenAPI endpoint allowlist was never extended when Sprint 4 added its endpoints, so
  the published branch head did not build.
- `CaseAccessService` loaded the case aggregate before the lock query, so the post-lock reload
  observed a stale snapshot.
- The operational `reason` accepted unpaired UTF-16 surrogates, turning malformed client input into
  a sanitized 500 after the aggregate had already been mutated. It now fails closed with 400.
- `ADR-006` stated a lock order that the implementation does not use; corrected in place.
- Four test call sites used locale-dependent case conversion and failed under a Turkish locale.
- An unanchored `.gitignore` rule silently excluded new files under the evidence storage source
  package.

## Known limitations

Summarised here; the full list with reasoning is in `Known-Limitations.md`.

- **OWASP Dependency-Check has never been executed.** The NVD and CISA feeds are unreachable from
  the build environment. No vulnerability analysis has been performed and no zero-vulnerability
  claim may be inferred.
- A readable zero-byte evidence file is reported as a technical failure rather than as
  `valid=false`, because the frozen payload requires a positive file size.
- Descriptive metadata is not UTF-16 validated, so a malformed title fails at canonicalization and
  surfaces as an undeclared generic 500 after a full rollback.
- Integrity verification updates `updatedAt` although it is not a mutating command.
- An unsupported HTTP method returns a sanitized generic 500 rather than 405.
- The Postman collection cannot produce the invalid integrity verdict; a manual step is documented.
- No TLS, clustering, reverse proxy or rate limiting is delivered. No SLA, capacity or throughput is
  claimed.

## Validation status

Produced through the delegated automated gate under Project Owner authorization for autonomous AI
delivery. **No human validation was performed.** Teacher approval has not yet been performed.
