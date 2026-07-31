# ProofChain technical documentation

Documentation home for ProofChain `1.0.0`. Everything linked from here describes code, migrations, tests or
configuration that exist in this repository. Nothing planned is described as delivered.

ProofChain is the backend of a chain-of-custody system for digital evidence, delivered as one Spring Boot modular
monolith over one PostgreSQL database. The complete narrative is in the [technical report](./Technical-Report.md).

## Reviewer path

If you are assessing this delivery, read in this order:

1. [Project README](../README.md) — purpose, stack, prerequisites, setup, the canonical command.
2. [Technical report](./Technical-Report.md) — the whole system in one document, including its known defects.
3. [Architecture](./Architecture.md) — the eight Mermaid diagrams the report refers to.
4. [ITS compliance](./ITS-Compliance.md) — the rubric mapping, the PostgreSQL deviation and the JPA cardinality
   deviation, and the open items that need a human decision.
5. [Testing](./Testing.md) — how to reproduce the quality gate and what the numbers mean.
6. [Operations](./Operations.md) and [Troubleshooting](./Troubleshooting.md) — running the stack and fixing it.
7. [Postman package](../postman/README.md) — exercise the API end to end.
8. [Reviewer checklist](./Reviewer-Checklist.md) — the 20–30 minute self-service assessment path, or the
   [demo guide](./Demo-Guide.md) if you want the full guided walkthrough.
9. Feature guides, for the exact contract of any area you want to inspect closely.
10. [ADR index](./adr/README.md) and [CHANGELOG](../CHANGELOG.md) — why the architecture is what it is, and what
    changed.

## Documentation index

### Delivery documents

| Document | Contents |
| --- | --- |
| [Technical report](./Technical-Report.md) | Problem, scope, architecture, domain, security, persistence, storage, custody chain, workflows, locking, concurrency, API, testing, container, hardening, limitations, release model |
| [Architecture](./Architecture.md) | Module dependencies, domain model and ownership, database schema, evidence registration, generic operational command, integrity verification, chain verification, chain structure |
| [ITS compliance](./ITS-Compliance.md) | Requirement compliance table, PostgreSQL and JPA cardinality deviations, assessment evidence table, open human-decision items |
| [CHANGELOG](../CHANGELOG.md) | Keep a Changelog structure, final `1.0.0` content |

### Operating the system

| Document | Contents |
| --- | --- |
| [Configuration](./Configuration.md) | Release version, the three profiles, every environment variable, secrets, fail-fast startup validation, request limits, timeouts, CORS |
| [Operations](./Operations.md) | Docker Compose runtime, image layout, non-root and read-only guarantees, named volumes, health and readiness contract, startup, shutdown, backup and restore, orphan reporting, recovery boundaries |
| [Testing](./Testing.md) | Test categories and naming, Surefire/Failsafe split, Testcontainers, deterministic concurrency, failure injection, fixed vectors, JaCoCo gate, reports, canonical commands, common failures |
| [Troubleshooting](./Troubleshooting.md) | Java and Maven, Docker, ports, PostgreSQL, Flyway, JWT, bootstrap, permissions, storage, tests, health probes, Postman |
| [Database schema lifecycle](./Database-Schema-Lifecycle.md) | Certified baseline matrix, recorded checksums, clean creation, supported upgrade paths, failure modes, manual recovery runbook |
| [Database migrations](../src/main/resources/db/migration/README.md) | Rules for Flyway-managed schema evolution |

### Feature guides

| Document | Contents |
| --- | --- |
| [Authentication](./Auth.md) | Login, JWT issue and validation, database-backed request authentication, password controls, bootstrap administrator, audit logging |
| [Operator Management](./Operators.md) | Operator data, roles and statuses, ADMIN endpoints, persistence, concurrency invariants |
| [Custody Cases](./CustodyCases.md) | Case metadata, lifecycle, contextual membership, REST contracts, persistence, concurrency |
| [Digital Evidence](./DigitalEvidence.md) | Domain metadata, multipart registration, integrity hashes, filesystem storage, paging, download, failure contracts |
| [Custody Events](./Custody-Events.md) | Event model, typed payloads, canonical JSON and hash chain, reproducible fixed vector, timeline and detail APIs, chain verification |
| [Operational Custody Workflows](./Operational-Custody-Workflows.md) | Transfer, metadata update, integrity verification, seal, release, authorization matrix, lifecycle graph, locking, concurrency, Problem Details |

### Decisions and release evidence

| Document | Contents |
| --- | --- |
| [Architecture Decision Records](./adr/README.md) | ADR-001 to ADR-008, the accepted decisions that govern the implementation |
| [Sprint 5 certification](./certification/Sprint-5-Certification.md) | Certified operational-workflow surface, lock-order proof, totals and limitations at the Sprint 5 boundary |
| [Security and dependency review 1.0.0](./release/1.0.0/Security-And-Dependency-Review.md) | Secret scan, CycloneDX SBOM, the environment-blocked OWASP Dependency-Check run, dependency inventory, locale and timezone execution, log and API leak audit, bounded performance smoke, test categorisation |

### Repository governance

| Document | Contents |
| --- | --- |
| [Project README](../README.md) | Setup, quick start, API entry points, canonical command, navigation |
| [Contributing](../CONTRIBUTING.md) | Branch, commit, PR, review, migration, secret, evidence and human-gate rules |
| [Postman package](../postman/README.md) | Ordered collection, placeholder-only environment, preconditions, GUI and Newman execution, secret-hygiene gate, known limits |

### Demonstration and assessment

| Document | Contents |
| --- | --- |
| [Demo guide](./Demo-Guide.md) | The authoritative demonstration procedure: preparation, the 28-step canonical flow, the human-gated tampering scenarios, the destructive reset, the semi-automated Postman alternative, failure recovery |
| [Reviewer checklist](./Reviewer-Checklist.md) | Self-service assessment path in 20–30 minutes: clone, environment, Maven gate, coverage, Compose health, OpenAPI, workflow, security examples, ITS mapping, release artifacts |
| [Presentation source](../presentation/ProofChain.md) | 12-slide Markdown/Mermaid deck source; the Java build has no presentation dependency and no exported deck is tracked |
| [Demo scripts](../scripts/demo/demo-preflight.sh) | `demo-preflight.sh` (safe startup and fixtures), `demo-reset.sh` (explicitly destructive, scoped and confirmed), `demo-smoke.sh` (Postman/Newman wrapper) |

## Codebase overview

The Java source tree is organized feature-first under `it.itsprodigi.proofchain`.

`auth` owns credential verification, token handling, the authenticated principal and authentication event logging.
`operator` owns the operator aggregate, administrative use cases, the password policy and the opt-in bootstrap
administrator. `custodycase` owns case metadata, lifecycle, the explicit `CaseMembership` join entity and contextual
access. `evidence` owns typed evidence metadata, persistence, registration and query use cases, the five operational
custody commands with their shared locking and authorization foundation, the integrity hashes, the hardened filesystem
adapter and the offline maintenance command. `custodyevent` owns the custody-event domain, the canonical hashing
protocol, the single append-only writer, the timeline and detail read APIs and chain verification. `common` contains
cross-cutting Spring Security, OpenAPI, CORS, password configuration and Problem Details support.

Within a feature, API records define input and output instead of exposing persistence entities. Application services
define use cases and transactional boundaries; controllers do not own transactions. Spring Data JPA repositories
persist the domain in PostgreSQL. Spring Security combines a stateless servlet filter chain with method authorization:
a JWT carries a signed operator identifier, but every authenticated request reloads current authorization state from
the database.

Flyway is the schema authority and Hibernate runs `ddl-auto: validate`. HTTP and security failures use
`application/problem+json`. Springdoc publishes the OpenAPI document and Swagger UI. Fast unit and MVC tests run
through Surefire; `*IT.java` suites use PostgreSQL Testcontainers through Failsafe.

## Repository map

```text
.
├── docs/
│   ├── README.md                 # this documentation home
│   ├── Technical-Report.md       # the ITS technical report
│   ├── Architecture.md           # eight Mermaid architecture diagrams
│   ├── ITS-Compliance.md         # rubric mapping and approved deviations
│   ├── Configuration.md          # release baseline, profiles, startup validation
│   ├── Testing.md                # test model, gate, reports, common failures
│   ├── Operations.md             # Compose runtime, health, backup, recovery
│   ├── Troubleshooting.md        # diagnosis by symptom
│   ├── Auth.md                   # authentication guide
│   ├── Operators.md              # operator-management guide
│   ├── CustodyCases.md           # custody-case and membership guide
│   ├── DigitalEvidence.md        # evidence registration, storage and read guide
│   ├── Custody-Events.md         # custody-event chain, protocol and verification
│   ├── Operational-Custody-Workflows.md  # the five operational commands
│   ├── Database-Schema-Lifecycle.md      # certified baselines and recovery runbook
│   ├── Demo-Guide.md             # the authoritative demonstration procedure
│   ├── Reviewer-Checklist.md     # 20-30 minute self-service assessment path
│   ├── adr/                      # ADR-001 .. ADR-008 and the index
│   ├── certification/            # Sprint 5 certification record
│   └── release/1.0.0/            # security, dependency and performance review
├── src/main/java/it/itsprodigi/proofchain/
│   ├── auth/  operator/  custodycase/  evidence/  custodyevent/  common/
├── src/main/java/db/migration/   # V6, the Java Flyway migration
├── src/main/resources/
│   ├── db/migration/             # immutable Flyway SQL migrations
│   ├── application.yml           # shared externalized configuration
│   ├── application-local.yml     # host-execution profile
│   ├── application-container.yml # Docker Compose profile
│   └── logback-spring.xml        # console and AUTH_AUDIT destinations
├── src/test/                     # fast and Testcontainers-backed tests
├── postman/                      # collection, placeholder environment, guide
├── presentation/ProofChain.md    # 12-slide Markdown/Mermaid deck source
├── scripts/demo/                 # preflight, destructive reset, Newman smoke wrapper
├── Dockerfile                    # multi-stage image, non-root runtime stage
├── docker/                       # image build and runtime helper scripts
├── compose.yml                   # PostgreSQL and ProofChain services
├── pom.xml                       # build, test, formatting and coverage lifecycle
├── CHANGELOG.md                  # release history
└── .github/workflows/quality.yml # canonical Maven gate in CI
```

## Architectural principles

- **Modular monolith.** One application divided by business feature rather than by deployable service.
- **Feature-first organization.** Each feature owns its API, application, domain and persistence code; shared concerns
  stay in `common`.
- **Explicit transactional boundaries.** Application services mark read-only queries and state-changing transactions;
  controllers own none.
- **Database-backed authorization state.** A valid JWT identifies an operator; PostgreSQL supplies the current role and
  status for every authenticated request.
- **Contextual case access.** ADMIN sees every case; other roles see only their memberships, and inaccessible
  identifiers are hidden as not found rather than forbidden.
- **Evidence integrity and a storage boundary.** Content and contextual SHA-256 values bind immutable file metadata to
  a case and evidence identifier; filesystem paths stay inside a hardened storage adapter.
- **Append-only custody history.** Custody events are written by one server-side appender inside the business
  transaction they record, are hash-linked per evidence item, and are protected against update or deletion by the
  database trigger, the persistence mappings and the absence of any write route.
- **Named operational commands.** Evidence changes only through the five explicit commands, each appending exactly one
  custody event under the frozen `PESSIMISTIC_READ` case then `PESSIMISTIC_WRITE` evidence lock order, with one shared
  server instant and no silent retry.
- **Stateless JWT authentication.** No HTTP session, no persisted security context.
- **Flyway-managed schema.** Versioned migrations change the schema; Hibernate only validates it.
- **Problem Details.** MVC and security boundaries return stable problem types, never ad hoc error bodies.
- **Reproducible quality checks.** Local development and CI both run
  `./mvnw --batch-mode --no-transfer-progress clean verify`.
- **ADRs for material decisions.** Choices affecting architectural boundaries, persistence or security are recorded
  under `docs/adr`.

## Documentation maintenance

Documentation must never describe a planned endpoint, permission, workflow or operational capability as implemented
before the corresponding code and tests exist. A material architectural decision requires an ADR; an implementation
detail does not. `DocumentationLinkAuditTest` fails the build when an internal link breaks, when an ADR is missing from
the index or listed twice, or when ADR numbering develops a gap — so the navigation above cannot silently rot.
