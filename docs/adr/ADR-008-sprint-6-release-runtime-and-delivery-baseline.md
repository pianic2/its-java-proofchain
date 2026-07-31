# ADR-008: Sprint 6 release, runtime and delivery baseline

- Status: Accepted
- Date: 2026-07-31
- Scope: Sprint 6 delivery slice — the frozen Java 25 and `1.0.0` baseline, the release/version/tag model, the container image and its non-root read-only runtime, the restricted health model, externalized fail-fast configuration, the retention of PostgreSQL as the only supported database together with the ITS deviation it creates, non-destructive orphan reporting, and the final certification and human gate

## Context

Sprints 1 to 5 delivered the functional system: authentication and operators, custody cases and membership, digital evidence with filesystem storage, the append-only custody-event hash chain, and the five operational custody workflows. [ADR-007](./ADR-007-sprint-5-operational-custody-workflows.md) closed the functional surface and explicitly deferred release packaging, the container runtime and the version freeze to Sprint 6.

Sprint 6 adds no domain capability. Its job is to make the delivered system *deliverable*: a fixed artifact version, an image a reviewer can build and run from a clean clone, a configuration contract that fails loudly rather than degrading, an operational answer for content that outlives its database row, and an honest account of where the delivery departs from the supplied ITS rubric.

The pressure in this sprint is different from the previous five. Every decision here is a decision about what the system does when something is *wrong* — a missing secret, an unwritable volume, a half-applied migration, a file with no owning row — and about what the project is allowed to claim when a check could not be run. The temptation is to smooth those cases over. This ADR records the decision not to.

## Decisions

### Java 25 is the final baseline

- Java 25 is the canonical runtime, build baseline and container base for the release. `pom.xml` declares `<java.version>25</java.version>`, both image stages use pinned `eclipse-temurin:25-*` tags, and the CI workflow provisions Temurin 25.
- Spring Boot stays at `4.0.7` and the Maven Wrapper at `3.9.9`. All four values are asserted by `ReleaseBaselineTest`, so a drift is a build failure rather than a review finding.
- **Rejected: relaxing the baseline to an LTS release** to widen the set of machines that can build the project. The whole suite, including the concurrency proofs and the canonical hashing protocol, was certified on 25; re-certifying on a second runtime is work with no deliverable benefit inside this scope.
- **Rejected: a toolchain or multi-JDK matrix.** One runtime, one certified result.

### Release, version and tag model

- The artifact version is `1.0.0`, frozen. It appears in `pom.xml`, in `OpenApiConfig.API_VERSION`, in the OCI image label and in the Compose image tag `proofchain:1.0.0`, and `ReleaseBaselineTest` asserts that all of them agree and that no tracked file still references the retired snapshot coordinate.
- Semantic Versioning is the stated scheme; `CHANGELOG.md` follows Keep a Changelog and describes delivered capability rather than commit history.
- The delivery tag `uf14-final-2026` is created by the Project Owner at final acceptance. It is **not** created by an implementation agent and does not exist in the repository at the time this ADR is accepted.
- **Rejected: a snapshot version for the delivered artifact.** A snapshot is by definition reproducible only by accident.
- **Rejected: automated tagging or release publishing from CI.** Tagging is an acceptance statement, and acceptance is a human act (see the final gate below).

### Container image and non-root, read-only runtime

- The image is built in two stages: an `eclipse-temurin:25-jdk` stage that runs the Maven Wrapper, and an `eclipse-temurin:25-jre` runtime stage that receives only the packaged jar and the health-probe script. No sources, Maven repository, build cache or wrapper crosses the boundary.
- The runtime identity is the fixed numeric pair `10001:10001`, declared in the image and repeated in `compose.yml` so it is a reviewable property of the deployment rather than an image implementation detail.
- The root filesystem is read-only. The only writable paths are the evidence volume and a bounded `tmpfs` at `/tmp` mounted `noexec,nosuid,nodev`. `cap_drop: ALL` and `no-new-privileges:true` are set.
- The application jar is owned by `root` and mounted read-only, so the process cannot rewrite its own code or its own health probe.
- Evidence content and database state live in two independent named volumes, `proofchain-evidence-data` and `proofchain-postgres-data`. They have different lifecycles and are never entangled in one mount.
- The application service starts only after the PostgreSQL healthcheck passes. There is no sleep and no retry loop anywhere in the startup path.
- **There is deliberately no restart policy.** The application is fail-fast: an invalid secret or an unusable storage root must stay down and visible rather than be hidden behind a restart loop that makes a misconfiguration look like flapping.
- Tests are not run during the image build. The suite needs a Docker daemon for Testcontainers, and an image build must never require one; `./mvnw clean verify` is the gate.
- **Rejected: running as `root` "just for the demo".** The identity is what makes the read-only root filesystem and the volume ownership meaningful.
- **Rejected: a single-stage image.** It would ship the JDK, the sources and the Maven cache.
- All of the above is asserted by `ContainerRuntimeBaselineTest`, so the hardening cannot silently regress.

### Restricted health model

- Actuator exposes `health` and nothing else. `management.endpoints.access.default` is `none`, `max-permitted` is `read-only`, and the discovery index is disabled so the runtime advertises no endpoint list.
- `show-details` and `show-components` are `never`, on the root endpoint and on both groups. The public response is a bare status: no component, version, driver, URL or path is ever rendered.
- Two groups exist. `liveness` includes only `livenessState`. `readiness` includes `readinessState`, `db` and `evidenceStorage`, so readiness is green only once the context is ready, PostgreSQL answers and the evidence root is provably writable. Every other auto-configured health contributor is disabled.
- The three probe paths are enumerated one by one in `SecurityConfig.PUBLIC_HEALTH_PROBES` rather than permitted as `/actuator/**`, so any endpoint added later is authenticated by default instead of published by accident.
- The container healthcheck reads the readiness group through a script in the image, so the liveness of the container and the readiness of the application are the same statement.
- **Rejected: exposing `info`, `metrics`, `env`, `configprops`, `beans`, `loggers` or `heapdump`**, even authenticated. Each is a disclosure surface with no reviewer value in this delivery.
- `ActuatorExposureWebMvcTest` and `ApiSurfaceContractIT` assert that nothing beyond the sanitized probes is mapped.

### Externalized, validated, fail-fast configuration

- Every runtime value is externalized Spring configuration bound to validated `@ConfigurationProperties`. Application code never reads an environment variable directly.
- Startup fails — it never degrades — when the JWT secret is missing, malformed or decodes to fewer than 32 bytes; when the token TTL is not strictly positive; when the password policy or BCrypt strength is out of range; when a runtime profile has no datasource credentials; when the storage root is not a usable directory; or when a request-size, timeout or CORS value is invalid.
- **No secret is ever generated or defaulted.** There is no development fallback that would make a misconfigured deployment appear to work.
- CORS is deny-by-default: an empty allowlist emits no CORS header at all, and a `*` entry is rejected at startup rather than honoured.
- Every request dimension is bounded — file size, whole multipart request, HTTP header, form post, parameter count, part count, part header — as are the HTTP connection, keep-alive, shutdown, database connection, validation and lock timeouts. `initialization-fail-timeout: 1` proves one database connection at startup and then fails closed; a negative value, which would start degraded, is rejected.
- Exactly three profiles exist: `local`, `container`, `test`. `.env.example` lists every supported variable with a safe placeholder and no usable value.
- **Rejected: a "dev mode" profile with relaxed validation.** The failure modes this contract prevents are exactly the ones that are invisible until production.

### PostgreSQL is retained as the only supported database, and the ITS deviation is recorded

- PostgreSQL 18.4 remains the only supported database. No MySQL driver, dialect, alternate migration set or abstraction layer is added.
- The supplied ITS rubric asks for MySQL. This is therefore an **approved deviation that requires explicit teacher / Project Owner acknowledgement**. It is recorded as a deviation, not presented as an equivalent implementation.
- The dependency is architectural rather than incidental. `JSONB` with a `jsonb_typeof` check stores custody-event payloads; partial and expression indexes implement the last-active-administrator invariant and the per-case nullable unique reference tag; POSIX-regex `CHECK` constraints enforce hash format, storage-key safety and username normalization; two PL/pgSQL triggers provide the append-only guarantee and the lifecycle guard; `FOR SHARE` / `FOR UPDATE` semantics with `SET lock_timeout` implement the frozen lock order; and the concurrency suite proves lock ordering by observing `pg_stat_activity` waiters.
- **Rejected: adding a MySQL profile to tick the rubric box.** It would require re-specifying payload storage, replacing two structural indexes with application-level checks, rewriting both triggers, re-certifying the lock ordering under different semantics and rebuilding the deterministic concurrency proofs — a redesign of the integrity model, in a documentation-and-delivery sprint, to satisfy a checklist rather than a requirement.
- **Rejected: claiming database portability the code does not have.** The full argument and the acknowledgement request are in [ITS compliance](../ITS-Compliance.md).

### Non-destructive orphan reporting

- Content can outlive its database row — a rolled-back registration whose cleanup itself failed, or a restore that reinstated a volume without the matching database state. The delivery answers this with a report, not a cleaner.
- The orphan report is an offline command. It is unreachable over HTTP **by construction, not by authorization**: it has no controller, no handler mapping and no actuator endpoint. It is a `main`-time branch that starts a second, minimal Spring context with no servlet container, no security filter chain, no OpenAPI document, no JPA factory, no Flyway migration and no evidence write path.
- Three independent conditions must all hold before anything is scanned: the explicit `--proofchain.maintenance.orphan-report.enabled=true` argument on the command line, the `maintenance` profile active, and a non-web context. Its data source is opened read-only.
- The command **never deletes, moves, quarantines or repairs anything**. It writes only its own report document, only outside the evidence storage root, only with `CREATE_NEW` semantics so an existing file is never overwritten; with no destination configured it writes to standard output and touches no file at all. Exit codes distinguish clean, findings and failure.
- **Rejected: automatic cleanup, a scheduled reconciliation job, and a quarantine directory.** In a chain-of-custody system, a process that can delete evidence content on its own judgement is a larger risk than the orphan it removes. A human decides, with the report in hand.

### Final certification and the human gate

- The canonical gate is `./mvnw --batch-mode --no-transfer-progress clean verify`, and it must report BUILD SUCCESS. GitHub Actions provisions the environment and invokes exactly that command; it never runs `spotless:apply` and never modifies sources.
- Coverage is gated at `BUNDLE` / `LINE` / `COVEREDRATIO` ≥ `0.51` with no `<excludes>`. The gate was not lowered and no class was hidden to inflate the measurement.
- The approved HTTP surface is pinned by `ApiSurfaceContractIT`, which reconciles the live Spring request mappings, the generated OpenAPI document, the Problem Details catalogue and the delivered Postman collection against one table of 27 operations. Adding a route without approving it fails the build.
- Documentation is machine-audited: `DocumentationLinkAuditTest` fails on a broken internal link, a missing heading anchor, an ADR absent from or duplicated in the index, or a gap in ADR numbering.
- **OWASP Dependency-Check was not executed.** The pinned profile exists, but the NVD feeds, `jeremylong.github.io` and the CISA KEV catalogue are egress-blocked in this environment. The decision is to record this as an unmet acceptance criterion rather than to substitute a weaker check and present it as vulnerability analysis. **No zero-vulnerability claim may be inferred from a green build.**
- Final validation and approval are performed by the Project Owner. Merging a pull request, declaring the release accepted, creating the `uf14-final-2026` tag, acknowledging the PostgreSQL and JPA-cardinality deviations, and accepting the known limitations are human acts. An AI agent may propose, implement and review; it must never claim final human approval.
- **Rejected: certifying the delivery as production-ready.** The project makes no availability, throughput or capacity commitment, holds no production certification, and says so in the README, the technical report and this ADR.

## Consequences

- The delivery has one reproducible artifact, one canonical command and one certified runtime. A reviewer can go from a clean clone to a running, health-gated stack without reading anything but the README.
- Every failure mode this sprint touches is loud. A misconfigured deployment does not start; a contended command fails within a bounded lock wait; a half-applied migration stops the application; an unwritable evidence root keeps readiness red. Nothing degrades quietly.
- The cost of the no-restart-policy decision is that a transient dependency failure requires a human to restart the stack. That is accepted: in this system a silent restart loop is worse than a visible outage.
- The cost of non-destructive orphan reporting is that orphaned content accumulates until someone acts on a report. That is accepted for the same reason.
- The PostgreSQL decision leaves the delivery formally non-compliant with one rubric item, and that non-compliance is now documented in three places — this ADR, the technical report and the ITS compliance mapping — rather than argued away.
- The absence of a vulnerability scan is a real, stated gap in the release evidence. It must be closed by a reviewer with NVD access before any use beyond assessment.
- Nothing in this ADR changes the domain, the API surface, the custody protocol or the lock order established by ADR-001 to ADR-007.

## Evidence

| Decision | Where it lives | What proves it |
| --- | --- | --- |
| Java 25 and `1.0.0` baseline | `pom.xml`, `Dockerfile`, `.github/workflows/quality.yml` | `ReleaseBaselineTest` |
| Version freeze across artifact, API document, image | `pom.xml`, `OpenApiConfig`, `Dockerfile`, `compose.yml` | `ReleaseBaselineTest` |
| Container image and hardened runtime | `Dockerfile`, `compose.yml`, `docker/` | `ContainerRuntimeBaselineTest`, [Operations](../Operations.md) |
| Restricted health model | `application.yml`, `common/config/SecurityConfig.java` | `ActuatorExposureWebMvcTest`, `ApiSurfaceContractIT` |
| Fail-fast externalized configuration | `application.yml`, `application-*.yml`, `*Properties` classes, `.env.example` | `ConfigurationBindingTest`, `ConfigurationStartupFailureTest`, `ProfileConfigurationTest`, `JwtConfigurationTest` |
| PostgreSQL retention and the ITS deviation | `pom.xml`, `compose.yml`, `src/main/resources/db/migration/`, every `*IT.java` | [ITS compliance](../ITS-Compliance.md), [Technical report §16](../Technical-Report.md#16-known-limitations-and-future-work) |
| Non-destructive orphan reporting | `evidence/maintenance/` | `OrphanFileReportServiceTest`, [Operations](../Operations.md) |
| Approved API surface | `api/` controllers | `ApiSurfaceContractIT` |
| Documentation integrity | `README.md`, `docs/`, `CONTRIBUTING.md` | `DocumentationLinkAuditTest` |
| Certification and human gate | [CONTRIBUTING](../../CONTRIBUTING.md), [ITS compliance](../ITS-Compliance.md) | Project Owner acceptance, recorded in Jira |
