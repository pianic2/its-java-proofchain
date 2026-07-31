# ITS compliance mapping — ProofChain 1.0.0

This document maps the delivered repository to the supplied ITS assessment rubric. It is written for the assessor, and
it is deliberately conservative: nothing is claimed that a file, a test or a command in this repository does not
support. Where the delivery departs from the rubric, the departure is stated as a departure and marked as requiring
explicit teacher / Project Owner acknowledgement — not softened into an equivalence.

**Read this first.** Two items are not compliant in the plain sense of the word:

1. **The database is PostgreSQL, not MySQL.** This is an approved deviation that requires explicit acknowledgement. It
   is architectural, not cosmetic, and the project was **not** redesigned around the rubric.
2. **OWASP Dependency-Check was never executed.** No vulnerability analysis exists for this release. Nothing in this
   document may be read as a statement that the dependencies are free of known vulnerabilities.

Classification vocabulary used in the first table:

| Classification | Meaning |
| --- | --- |
| `Compliant` | The rubric item is delivered as asked. |
| `Equivalent implementation` | The intent of the rubric item is delivered, by a different and defensible construct. |
| `Approved deviation required` | The delivery differs from the rubric and needs explicit teacher / Project Owner acknowledgement. |
| `Not applicable` | The rubric item does not apply to this delivery. |

---

## 1. Requirement compliance table

| # | Rubric requirement | Classification | Delivered implementation | Evidence in this repository |
| --- | --- | --- | --- | --- |
| 1 | Java / Maven project structure | `Compliant` | Single Maven project `it.itsprodigi:proofchain:1.0.0`, Java 25, Spring Boot 4.0.7, standard `src/main/java` + `src/test/java` layout, feature-first packages under `it.itsprodigi.proofchain`. Maven Wrapper 3.9.9 is committed so the build needs no local Maven. | [`pom.xml`](../pom.xml), [`.mvn/wrapper/maven-wrapper.properties`](../.mvn/wrapper/maven-wrapper.properties), `src/test/java/it/itsprodigi/proofchain/ReleaseBaselineTest.java` |
| 2 | CRUD / domain operations | `Equivalent implementation` | Create/read/update exist across operators, custody cases, memberships and evidence. **Delete does not exist for any domain aggregate except case membership removal**, and that is by design: evidence and custody events must not be deletable in a chain-of-custody system. Evidence is retired by `release`, not by `DELETE`. | Controllers under `*/api/`; the approved surface table in `src/test/java/it/itsprodigi/proofchain/ApiSurfaceContractIT.java`; [Digital Evidence](./DigitalEvidence.md), [Custody Cases](./CustodyCases.md) |
| 3 | JPA relationships (`@OneToMany` / `@ManyToOne`) | `Compliant` | Ten lazy `@ManyToOne` associations, all on the owning side with an explicit `@JoinColumn`, most `updatable = false`. The inverse `@OneToMany` collections are deliberately **not** mapped: every child is an aggregate root queried through its own repository with paging, so mapping a collection would invite unbounded loading. | `custodycase/domain/CustodyCase.java`, `custodycase/domain/CaseMembership.java`, `evidence/domain/DigitalEvidence.java`, `custodyevent/domain/CustodyEvent.java` |
| 4 | JPA `@OneToOne` relationship | `Approved deviation required` | **No `@OneToOne` exists in the model.** No pair of entities in this domain is in a one-to-one relationship, and adding one purely to satisfy the rubric would create a table with no reason to exist. Not added. | Verifiable with `grep -r "@OneToOne" src/main/java` — no match |
| 5 | JPA `@ManyToMany` relationship | `Approved deviation required` | The case↔operator relationship **is** many-to-many, and it is modelled with the explicit join entity `CaseMembership` instead of `@ManyToMany`, because the assignment itself carries attributes (`assignedBy`, `assignedAt`) and a unique `(case_id, operator_id)` constraint. This is the relationally correct construct for an attributed association; `@ManyToMany` cannot express it. Not added. | `custodycase/domain/CaseMembership.java`, `src/main/resources/db/migration/V2__create_custody_cases_and_memberships.sql`, [Architecture — diagram 2](./Architecture.md#2-domain-model-and-aggregate-ownership) |
| 6 | Bean Validation | `Compliant` | Jakarta Bean Validation on API request records and on `@ConfigurationProperties`: 53 `@NotNull`, 30 `@NotBlank`, 29 `@Size`, 11 `@Valid`, 8 `@Validated`, 7 `@Pattern`, 3 `@Min`, 2 `@Positive`, 1 `@Max`, 1 `@Email`, 1 `@AssertTrue` across 29 source files. Violations become `validation-error` (400) with a field-level error list. | `*/api/*Request.java`, `common/exception/GlobalExceptionHandler.java`, `common/exception/ValidationError.java`, `ConfigurationBindingTest` (58 tests) |
| 7 | Exceptions and error handling | `Compliant` | One `@RestControllerAdvice` (`GlobalExceptionHandler`) maps every domain and security exception to RFC 9457 Problem Details on `application/problem+json`, with 29 stable `type` URIs. Bodies never carry a stack trace, exception class or cause. | `common/exception/GlobalExceptionHandler.java`, `common/exception/ProblemTypes.java`, `GlobalExceptionHandlerWebMvcTest`, `SecurityLogAndResponseLeakAuditIT` |
| 8 | Collections and Streams API | `Equivalent implementation` | The Collections API is used throughout (50 source files use `java.util.List`/`Set`/`Map`/`EnumSet`; the operational authorization matrix is an immutable `EnumSet` on an enum). The Stream API is used where a pipeline is the clearest expression — 10 source files call `.stream()`, mostly mappers, the CORS allowlist and the chain read path. It is **not** used pervasively: hot paths that stream file bytes deliberately use explicit loops with fixed buffers to keep memory bounded. | `evidence/application/EvidenceOperationalCommand.java`, `*/application/*Mapper.java`, `custodyevent/application/CustodyChainVerificationService.java`, `common/config/CorsProperties.java` |
| 9 | SQL creation scripts | `Compliant` | Seven Flyway migrations are the official creation scripts: `V1`–`V5` and `V7` are SQL, `V6` is a Java migration. They create every table, constraint, index, function and trigger. Flyway runs with `baseline-on-migrate=false`, `validate-on-migrate=true`, `out-of-order=false`, `clean-disabled=true`; Hibernate is `ddl-auto: validate` and never generates schema. | [`src/main/resources/db/migration/`](../src/main/resources/db/migration/README.md), `src/main/java/db/migration/V6__backfill_evidence_registration_events.java`, [Database schema lifecycle](./Database-Schema-Lifecycle.md) |
| 10 | Relational database | `Approved deviation required` | **PostgreSQL 18.4 is the only supported database. The rubric asks for MySQL. MySQL is not supported and no MySQL compatibility layer exists.** See §2 below for why this is architectural rather than a configuration choice. | `pom.xml` (`org.postgresql:postgresql`, `flyway-database-postgresql`), [`compose.yml`](../compose.yml), every migration, every `*IT.java` through `PostgreSQLContainer` |
| 11 | Containerized application | `Compliant` | Two-stage `Dockerfile` (Temurin 25 JDK build → Temurin 25 JRE runtime), image `proofchain:1.0.0`, non-root `10001:10001`, read-only root filesystem, `cap_drop: ALL`, `no-new-privileges`, bounded `tmpfs`, no restart policy. | [`Dockerfile`](../Dockerfile), [`compose.yml`](../compose.yml), `ContainerRuntimeBaselineTest`, [Operations](./Operations.md) |
| 12 | Containerized database | `Compliant` | `postgres:18.4-trixie` service in the same Compose file, with a `pg_isready` healthcheck the application service waits on, and its own named volume `proofchain-postgres-data` separate from the evidence volume. | [`compose.yml`](../compose.yml), [Operations](./Operations.md) |
| 13 | Postman collection | `Compliant` | `ProofChain.postman_collection.json`: 14 ordered modules, 97 requests, 200 assertions, covering the complete approved surface. A placeholder-only environment file is delivered with it; no token, password or credential is tracked. `ApiSurfaceContractIT` reconciles the collection against the live request mappings, so it cannot drift. | [`postman/`](../postman/README.md), `ApiSurfaceContractIT` |
| 14 | Test coverage above 50% | `Compliant` | JaCoCo enforces `BUNDLE` / `LINE` / `COVEREDRATIO` ≥ **0.51** at `verify`, with no `<excludes>`. The release build reports **91.66 % line coverage** (4 167 / 4 546) over 228 classes. | [`pom.xml`](../pom.xml) `jacoco-maven-plugin`, `target/site/jacoco/index.html` after `clean verify`, [Testing](./Testing.md) |
| 15 | Technical report | `Compliant` | [`docs/Technical-Report.md`](./Technical-Report.md), written as the report itself rather than generated JavaDoc, with eight version-controlled Mermaid diagrams in [`docs/Architecture.md`](./Architecture.md). | This documentation set |
| 16 | Delivery tag `uf14-final-2026` | `Approved deviation required` — **pending human action** | The tag **does not exist in the repository yet** (`git tag -l` returns nothing). Tagging is the Project Owner's final acceptance action and is deliberately not performed by an implementation agent. | `git tag -l`; [CONTRIBUTING](../CONTRIBUTING.md) — final human validation gate |
| 17 | Dependency vulnerability evidence | `Approved deviation required` | **Not executed.** The pinned `dependency-check` profile exists (`org.owasp:dependency-check-maven:12.2.2`, `failBuildOnCVSS=7`), but the NVD feeds, `jeremylong.github.io` and the CISA KEV catalogue are egress-blocked in the build environment. No vulnerability analysis was performed and no zero-vulnerability claim may be inferred. | [`pom.xml`](../pom.xml) profile `dependency-check`, [Security and dependency review §3](./release/1.0.0/Security-And-Dependency-Review.md) |
| 18 | Version control and repository hygiene | `Compliant` | Git with one branch and one pull request per Jira subtask, Conventional Commits, MIT `LICENSE`. `.gitignore` covers `target/`, `.env`, `*.log`, the runtime `/storage/` directory, IDE files and the wrapper jar; no secret, evidence file, generated report or runtime storage directory is tracked. | [`.gitignore`](../.gitignore), [`LICENSE`](../LICENSE), [CONTRIBUTING](../CONTRIBUTING.md), `git ls-files` |
| 19 | API documentation | `Compliant` | Runtime-generated OpenAPI at `/v3/api-docs` with Swagger UI at `/swagger-ui/index.html`. No static specification file exists that could drift; the generated document is reconciled against the live mappings by `ApiSurfaceContractIT`. | `common/config/OpenApiConfig.java`, `ApiSurfaceContractIT`, `OpenApiIntegrationTest` |
| 20 | Security (authentication / authorization) | `Compliant` | Stateless JWT bearer authentication, BCrypt password hashing, database-authoritative role and status on every request, `@PreAuthorize` method security in 14 classes, contextual case access, deny-by-default CORS, fail-fast secret validation. | `auth/`, `common/config/SecurityConfig.java`, [Authentication](./Auth.md), `SecurityBoundaryWebMvcTest`, `OperatorSecurityVerticalSliceIT` |
| 21 | Frontend / user interface | `Not applicable` | The approved scope is a backend API. There is no frontend in this repository and none is claimed. Swagger UI and the Postman collection are the review interfaces. | Approved Jira scope |

## 2. The PostgreSQL deviation, in detail

This is the one deviation an assessor should weigh carefully, so it is spelled out rather than summarised.

**What the rubric asks for:** MySQL.

**What is delivered:** PostgreSQL 18.4, exclusively. There is no MySQL driver, no MySQL dialect, no alternate migration
set and no abstraction layer that would let one be added without redesign.

**Why it is not a configuration switch.** The delivery depends on PostgreSQL-specific behaviour in at least six places
that are load-bearing rather than incidental:

| Dependency | Where | Why it matters |
| --- | --- | --- |
| `JSONB` column type with `jsonb_typeof` check | `custody_events.payload_json`, `V4` | The custody-event payload is stored as a validated JSON object, not as text |
| Partial and expression indexes | `ix_operators_active_admin_id`, `uk_digital_evidence_case_reference_tag` | The "last active administrator" invariant and the per-case-nullable unique reference tag |
| `CHECK` constraints with POSIX regular expressions | `V1`–`V5` | Hash format, storage-key safety, username pattern and normalization are enforced in the database |
| PL/pgSQL triggers | `custody_events_append_only` (`V4`), `tr_digital_evidence_lifecycle_transition` (`V7`) | Physical append-only enforcement and the last-resort lifecycle guard |
| `FOR SHARE` / `FOR UPDATE` semantics and `SET lock_timeout` | `EvidenceCommandLockService`, `application.yml` | The frozen `PESSIMISTIC_READ` case → `PESSIMISTIC_WRITE` evidence lock order with a bounded wait |
| `pg_stat_activity` | `OperationalCommandTestSupport.awaitLockWaiters` | The concurrency suite proves lock ordering by observing real waiters instead of sleeping |

Porting to MySQL would mean re-specifying the storage of custody payloads, replacing two structural indexes with
application-level checks, rewriting both triggers, re-certifying the lock ordering under different semantics, and
rebuilding the deterministic concurrency proofs. That is a redesign of the integrity model, which the delivery
contract explicitly forbids ("no code or domain redesign to improve documentation").

**What is asked of the assessor:** acknowledge the deviation explicitly. It is not presented as compliance and not
presented as an equivalent implementation. The delivery is otherwise a complete, relational, migration-managed,
containerized database application; the engine is simply a different one.

## 3. The JPA cardinality deviation, in detail

The rubric asks for a set of relationship annotations including `@OneToOne` and `@ManyToMany`.

- **`@ManyToMany`.** The domain *does* contain a many-to-many relationship — operators belong to many cases and a case
  has many operators. It is modelled as the explicit join entity `CaseMembership`, because the association carries its
  own attributes: who assigned it (`assigned_by_operator_id`) and when (`assigned_at`), plus a unique
  `(case_id, operator_id)` constraint and its own identifier. A `@ManyToMany` with a plain join table cannot carry
  those attributes. Replacing `CaseMembership` with `@ManyToMany` would lose the assignment audit trail, which is part
  of the chain of custody.
- **`@OneToOne`.** No pair of entities in this domain is one-to-one. Evidence to case is many-to-one; evidence to
  holder is many-to-one and optional; event to evidence is many-to-one. Introducing a one-to-one association would mean
  inventing a satellite table with no independent reason to exist.

Neither annotation was added. The rubric's underlying skill — modelling and mapping relational associations correctly
in JPA — is demonstrated by ten explicit owning-side `@ManyToOne` mappings, a composite foreign key
`(evidence_id, case_id)` that makes it impossible for an event to point at evidence from another case, and an
attributed join entity. **Explicit teacher / Project Owner acknowledgement is required for this item.**

## 4. Assessment evidence table

One row per rubric section, with what a reviewer should look at, what level the delivery can reasonably support, and
what remains open. "Expected level" is the delivery team's own assessment, not a claim of an awarded grade.

| Rubric section | Delivered implementation | Evidence file / test / step | Expected assessment level | Residual gap or human validation item |
| --- | --- | --- | --- | --- |
| Project setup and build | Maven project, committed wrapper, pinned formatter, one canonical gate | `./mvnw --batch-mode --no-transfer-progress clean verify` → BUILD SUCCESS; `pom.xml`; `ReleaseBaselineTest` | Full | None |
| Domain modelling | 5 aggregates, explicit invariants, lifecycle graph enforced in domain + application + database | `*/domain/*.java`; `V7`; [Architecture — diagram 2](./Architecture.md#2-domain-model-and-aggregate-ownership) | Full | None |
| JPA mapping and relationships | 10 owning-side `@ManyToOne`, composite FK, attributed join entity; no `@OneToOne`, no `@ManyToMany` | `CaseMembership.java`, `DigitalEvidence.java`, `CustodyEvent.java`, `V2`, `V4` | Full **only with acknowledgement** of §3 | Teacher / Project Owner must accept the `CaseMembership` join entity in place of `@ManyToMany`, and the absence of `@OneToOne` |
| Persistence and SQL scripts | 7 Flyway migrations, `validate` only Hibernate, certified empty-database and upgrade paths | `src/main/resources/db/migration/`; `EmptyDatabaseCertificationIT`, `BaselineUpgradeCertificationIT`, `MigrationFailureCertificationIT`, `MigrationGovernanceTest` | Full | None |
| Database engine | PostgreSQL 18.4 | `compose.yml`; every `*IT.java` | **Deviation** — see §2 | Teacher / Project Owner must accept PostgreSQL instead of MySQL |
| REST API design | 27 approved operations, resource-oriented paths, named workflows only, no generic command route | `ApiSurfaceContractIT`; `/v3/api-docs`; Swagger UI | Full | None |
| Validation and error handling | Bean Validation at the boundary + domain checks + database `CHECK`; RFC 9457 Problem Details, 29 stable types | `GlobalExceptionHandler.java`; `GlobalExceptionHandlerWebMvcTest`; Postman module asserting error contracts | Full, with two known defects | A wrong HTTP method returns a generic 500 instead of 405 on every path except `GET /api/v1/auth/login`; an unpaired surrogate in a descriptive field surfaces as an undeclared 500 after a full rollback |
| Security | JWT, BCrypt, database-authoritative authorization, method security, contextual access, deny-by-default CORS | `SecurityConfig.java`; `SecurityBoundaryWebMvcTest`; `DatabaseBackedAuthenticationIT`; `SecurityLogAndResponseLeakAuditIT` | Full | None |
| Integrity and chain of custody | Per-evidence SHA-256 chain, append-only trigger, external anchor, on-demand verification, published fixed vector | `custodyevent/protocol/`; `CustodyEventDocumentationVectorTest`; `CustodyChainVerifierTest`; `POST /verify-chain` | Full | Unkeyed hash: tamper evidence, **not** authorship. No signature is implemented or claimed |
| Testing | 443 Surefire + 377 Failsafe tests, Testcontainers, deterministic concurrency, failure injection, fixed vectors | `clean verify` output; [Testing](./Testing.md) | Full | 4 Surefire tests skip (POSIX assumptions unfalsifiable as `root`); case-closure concurrency has no dedicated deterministic test |
| Coverage | JaCoCo gate ≥ 0.51, actual 91.66 % line | `target/site/jacoco/index.html` | Full | None |
| Containerization | App + database in Compose, hardened non-root read-only runtime, health-gated startup | `Dockerfile`, `compose.yml`, `ContainerRuntimeBaselineTest`, [Operations](./Operations.md) | Full | Compose runtime must be started by a human on the assessment machine; no CI job runs it |
| API client collection | 14 modules, 97 requests, 200 assertions, secret-free environment | [`postman/README.md`](../postman/README.md) | Full | The `INVALID` integrity verdict cannot be produced by the collection; the manual out-of-band step is documented |
| Documentation | Root README, documentation home, technical report, 8 diagrams, configuration, operations, testing, troubleshooting, ADRs, changelog | [`docs/README.md`](./README.md) | Full | Demo guide and presentation are owned by a later subtask and are **PENDING** — they are not part of this delivery yet |
| Dependency and security review | Secret scan, SBOM profile, dependency inventory, log/leak audit, locale and timezone run | [Security and dependency review](./release/1.0.0/Security-And-Dependency-Review.md) | Partial | **Dependency-Check not executed.** A reviewer with NVD access must run it and classify the findings before release |
| Delivery and tagging | Version `1.0.0` frozen and build-asserted; changelog; ADR trail | [`CHANGELOG.md`](../CHANGELOG.md); [ADR index](./adr/README.md) | Partial | The tag `uf14-final-2026` has not been created; Project Owner action |

## 5. Open items requiring a human decision

1. Acknowledge the **PostgreSQL-instead-of-MySQL** deviation (§2).
2. Acknowledge the **`CaseMembership` join entity instead of `@ManyToMany`**, and the absence of `@OneToOne` (§3).
3. Run **OWASP Dependency-Check** where the NVD is reachable, and classify or waive the findings.
4. Create the delivery tag **`uf14-final-2026`** at acceptance.
5. Accept or schedule the two known API defects listed under "Validation and error handling" above.
6. Note that the **demo guide and presentation are PENDING** and are not part of this delivery.
