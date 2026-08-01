# ProofChain 1.0.0-rc.1 — known limitations

Release candidate: `ProofChain 1.0.0-rc.1`.
Branch: `ijpc-8-sprint-6-final-delivery`.
Certification commit: `739d980c6256b4b7b321424741aa87808b7d3277`.

This is the canonical, consolidated list of what `1.0.0` does **not** do, does wrong, or could not prove. It is
written so a reviewer does not have to discover any of it. Nothing here is smoothed over and nothing is presented as
a future commitment.

Cross-references: [Technical report §16](../../Technical-Report.md#16-known-limitations-and-future-work),
[ITS compliance](../../ITS-Compliance.md), [security and dependency review](./Security-And-Dependency-Review.md),
[certification report](./Certification-Report.md).

## 1. Blocking or acknowledgement-requiring items

### 1.1 PostgreSQL is the only supported database — approved deviation, needs teacher acknowledgement

The supplied ITS rubric asks for MySQL. This delivery uses PostgreSQL 18.4 exclusively. There is no MySQL driver, no
MySQL dialect, no alternate migration set and no compatibility layer. The dependency is architectural, not a
configuration switch: `JSONB` payload storage, partial and expression indexes, `FOR SHARE` / `FOR UPDATE` lock
semantics, `pg_stat_activity`-based concurrency proofs and PL/pgSQL triggers appear in migrations, production code and
tests.

**This is not presented as compliance.** It requires explicit teacher / Project Owner acknowledgement.
Detail: [ITS compliance §2](../../ITS-Compliance.md).

### 1.2 JPA cardinality deviation — needs teacher acknowledgement

* **No `@OneToOne` exists** anywhere in the model. No pair of entities in this domain is in a one-to-one relationship
  and none was invented to satisfy the rubric.
* **No `@ManyToMany` exists.** The case↔operator relationship *is* many-to-many and is modelled with the explicit
  attributed join entity `CaseMembership` (`assignedBy`, `assignedAt`, unique `(case_id, operator_id)`), which
  `@ManyToMany` cannot express.

Detail: [ITS compliance §3](../../ITS-Compliance.md).

### 1.3 No vulnerability analysis was performed

OWASP Dependency-Check is configured, pinned and gated (`org.owasp:dependency-check-maven:12.2.2`,
`failBuildOnCVSS=7`) but **has never successfully run**. The NVD API, the hosted suppression file and the CISA KEV
catalogue are egress-blocked in this environment; Dependency-Check aborts with `NoDataException: No documents exist`
rather than emitting a partial report.

**The release carries no vulnerability analysis and no "zero vulnerabilities" claim may be inferred from any document
in this repository.** The NVD check was deliberately not disabled and the CVSS gate was deliberately not lowered to
force a green run. The exact command a reviewer must execute on a network where the NVD is reachable is recorded
verbatim in [the certification report](./Certification-Report.md) and in
[the security review §3](./Security-And-Dependency-Review.md).

### 1.4 The Sprint 4 independent AI review is complete — resolved

The independent AI review of Sprint 4 was launched three times: the first two runs were terminated by resource limits
before producing anything, and the third completed with a verdict of **fit to certify**.

Its single MAJOR finding was a documentation defect, not a code defect: ADR-006 and the custody event guide both
claimed the append-only trigger "rejects every mutation", which is untrue of `TRUNCATE` because PostgreSQL never fires
row-level triggers for it. Corrected in commit `b69325e`; the schema is unchanged, because `TRUNCATE` is the mechanism
the disposable test databases rely on once `DELETE` is blocked, and erasing the table that way is still detected by the
count and head anchor on `digital_evidence`.

Six further MINOR and NOTE findings were accepted without code change and are listed in
[the AI validation record](./AI-Validation-Record.md). None is a correctness or security defect.

The review gate for **IJPC-160, IJPC-161, IJPC-162 and IJPC-6** is therefore satisfied. Those issues had been left open
precisely because the gate was unmet; transitioning them is now a Jira action, pending a connector-enabled session
(see §1.5).

### 1.5 Jira is unreachable from the certification session

No Jira connector is available in the session that produced this certification. Every task comment that would
normally be posted is queued in `/tmp/proofchain-jira-pending.md` and awaits a connector-enabled session. No Jira
issue was transitioned by this work.

## 2. Functional defects present in 1.0.0

### 2.1 A readable zero-byte evidence file yields a 500, not `valid: false`

A stored evidence file that is readable but exactly zero bytes produces `evidence-file-unavailable` (HTTP 500) rather
than a completed verification with `valid: false`. The frozen `IntegrityVerifiedPayload` requires a strictly positive
`fileSize`, so truncation to exactly zero is classified as an inability to read the file rather than as observed
corruption. Fixing it would require a new payload version.

### 2.2 Descriptive metadata is not UTF-16 surrogate-validated

The operational `reason` field is validated for well-formed UTF-16 and fails closed with `400`. Descriptive metadata
fields are not. A `title` containing an unpaired surrogate mutates the aggregate in memory, then fails inside
canonicalization, and surfaces as a **generic, undeclared HTTP 500**. The transaction rolls back **fully** — no
partial state is committed and no custody event is appended — but the status code is wrong and the failure is late.

### 2.3 Integrity verification stamps `updatedAt`

`POST /api/v1/evidences/{id}/verify-integrity` is declared non-mutating, yet the workflow body calls
`stampCommandInstant`, so `updated_at` advances. Recorded explicitly so the column is not mistaken for a mutation
marker.

### 2.4 A wrong HTTP method returns a generic 500, not 405

The catch-all exception advice translates `HttpRequestMethodNotSupportedException` before Spring's own handling can
produce a `405`. The single exception is `GET /api/v1/auth/login`, which carries an explicit guard and answers `405`
correctly. Every other path is affected.

## 3. Verification and coverage gaps

### 3.1 The Postman collection cannot produce the INVALID integrity verdict

No approved endpoint can alter stored content, and the collection performs no filesystem or database edit, so it
cannot manufacture the state that makes `valid: false`. It asserts the invariant that produces the verdict instead
(`valid === (expectedContentSha256 === actualContentSha256 && expectedFileSize === actualFileSize)`).

Observing a real `valid: false` requires the documented **manual, out-of-band** step: corrupt one byte inside the
evidence volume and re-issue the request. The procedure is in [the Postman guide](../../../postman/README.md) and in
[the demo guide, Part B](../../Demo-Guide.md). It was executed as part of this certification and the observed result
is recorded in [the certification report](./Certification-Report.md).

### 3.2 Four Surefire tests skip in this environment

`FileSystemEvidenceStorageTest` and `FileSystemEvidenceStorageHardeningTest` assert behaviour on unreadable and
non-writable directories and abort through JUnit `Assumptions` because the build runs as `root`, for whom POSIX
permission bits are not enforced. **The assumptions are unfalsifiable as root; they are not disabled tests.** On a
build machine running as an unprivileged user the same four tests execute. No test was weakened to achieve this.

### 3.3 `shellcheck` is not installed in this environment

The three shell scripts under `scripts/demo/` could not be statically analysed here. They were exercised by actual
execution instead, and that execution is recorded. A reviewer with `shellcheck` available should run
`shellcheck scripts/demo/*.sh docker/healthcheck.sh docker/unzip-for-maven-wrapper.sh`.

### 3.4 Case-closure concurrency has no dedicated deterministic test

Closure runs through `CustodyCaseService` on a different lock path from the operational commands. Closed-case
rejection is covered non-concurrently for every command, but there is no concurrent closure-versus-command race test.

### 3.5 Seven Testcontainers-backed classes are named `*Test`

`ActuatorExposureWebMvcTest`, `OpenApiIntegrationTest`, `SecurityBoundaryWebMvcTest`, `AuthControllerWebMvcTest`,
`CaseControllerWebMvcTest`, `CaseMembershipControllerWebMvcTest` and `OperatorControllerWebMvcTest` extend
`PostgreSqlIntegrationTest` and therefore run under Surefire against a real container, loosening the "Surefire is for
fast tests" split. Renaming them to `*IT` is a follow-up decision for the Project Owner, not a unilateral change.

### 3.6 The bounded performance numbers establish no SLA

The observations in [the security review §7](./Security-And-Dependency-Review.md) come from one in-process MockMvc
context against one PostgreSQL container on a shared 4-vCPU machine, with no HTTP stack, no network, no concurrency
and no warm-up control. **No throughput, latency or capacity commitment is made anywhere in this release.**

### 3.7 `Server`-header absence is weak evidence

The assertion that no `Server` header is emitted was made under MockMvc, which does not run the embedded servlet
container. It says nothing about what a deployed Tomcat emits.

### 3.8 No HSTS guarantee

TLS terminates outside the application boundary and the container listens on plain HTTP. The verifiable property is
only that the application does not fabricate an HSTS header on a plaintext request. Enforcing HSTS belongs to the
terminating proxy.

## 4. Explicit non-goals — deliberately not implemented

None of the following exists in `1.0.0`, and their absence is a design decision rather than an omission:

generic command or event-append endpoints; bulk or asynchronous operations; custody restoration; un-sealing; evidence
deletion; file repair; background reconciliation; automatic migration repair, clean, drop or schema generation;
digital signatures; timestamping authority; malware or antivirus scanning; distributed ledger; multi-tenancy; user
self-registration; password reset; refresh tokens or token revocation lists; any database other than PostgreSQL; any
availability, throughput or durability commitment.

ProofChain is **not production-certified**. The custody chain is a local, per-evidence tamper-evidence mechanism
stored in one PostgreSQL database — not a distributed or externally anchored proof.

## 5. Operational limits recorded rather than fixed

* **The bounded crash window.** A crash between writing content and committing the transaction can leave an orphan
  file in the evidence volume. It is detected by the read-only orphan report and is never auto-deleted.
* **Hibernate's own logger** emits PostgreSQL constraint-violation text at `WARN` (`org.hibernate.orm.jdbc.error`).
  It is server-side only; the corresponding HTTP response is a generic Problem Detail. Suppressing it would also hide
  genuine diagnostics, so it was left in place and is recorded here instead.
* **`failureCategory` logs exception simple names.** Deliberate operational classification, log-only, never returned
  to a client.
* **`/v3/api-docs` and `/swagger-ui.html` are intentionally public** in this release. Springdoc's startup warning
  advising otherwise is expected.
