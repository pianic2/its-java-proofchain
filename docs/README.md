# ProofChain technical documentation

## Introduction

ProofChain is the backend of a system for managing the chain of custody of digital evidence. The application is delivered as a Spring Boot modular monolith: one deployable runtime contains feature-oriented packages with explicit boundaries between HTTP contracts, application services, domain state, persistence, and security.

This documentation describes the code that is present in the repository. The current implementation covers project infrastructure, authentication, operator management, custody case lifecycle, contextual case membership, the Sprint 3 digital-evidence registration and read slice, the Sprint 4 custody-event hash chain with its read and verification APIs, and the Sprint 5 operational custody workflows — transfer, descriptive metadata update, file-integrity verification, sealing, and release.

## How to read this documentation

The recommended path is:

1. Start with the [project README](../README.md) to set up the application and run the quality gate.
2. Read the [configuration baseline](./Configuration.md) for the release version, the three supported profiles, and the fail-fast startup contract.
3. Read [Authentication](./Auth.md) to understand login, JWT validation, database-backed request authentication, and security errors.
4. Continue with [Operator Management](./Operators.md) for the persisted identity model, ADMIN API, and concurrency invariants.
5. Read [Custody Cases](./CustodyCases.md) for case metadata, lifecycle, membership, contextual authorization, and concurrency protection.
6. Read [Digital Evidence](./DigitalEvidence.md) for typed metadata, registration, integrity hashes, filesystem storage, and read/download behavior.
7. Read [Custody Events](./Custody-Events.md) for the custody-event model, canonical hashing protocol, append-only chain, timeline and detail APIs, and chain verification.
8. Read [Operational Custody Workflows](./Operational-Custody-Workflows.md) for transfer, metadata update, file-integrity verification, sealing, release, the authorization matrix, the lifecycle graph, and the locking and concurrency contract.
9. Consult the [ADR index](./adr/README.md) for the decisions behind the implemented architecture.
10. Use the test links in each feature guide as the current executable testing reference. A separate testing guide has not been added yet.

## Documentation index

- [Configuration baseline](./Configuration.md) — release version, the three profiles, secrets, startup validation, request limits, timeouts, and CORS.
- [Authentication](./Auth.md) — login, JWTs, authenticated requests, password controls, bootstrap, audit logging, and security tests.
- [Operator Management](./Operators.md) — operator data, roles and statuses, administrative endpoints, persistence, and concurrency protection.
- [Custody Cases](./CustodyCases.md) — case metadata, lifecycle, contextual membership, REST contracts, persistence, and concurrency.
- [Digital Evidence](./DigitalEvidence.md) — evidence domain, multipart registration, hashes, filesystem storage, paging, download, and operational limits.
- [Custody Events](./Custody-Events.md) — event model, typed payloads, canonical JSON and hash chain, reproducible fixed vector, timeline and detail APIs, and chain verification.
- [Operational Custody Workflows](./Operational-Custody-Workflows.md) — transfer, metadata update, file-integrity verification, sealing, release, authorization matrix, lifecycle graph, locking, concurrency, and Problem Details.
- [Architecture Decision Records](./adr/README.md) — accepted project, Sprint 1, Sprint 2, Sprint 3, Sprint 4, and Sprint 5 architectural decisions.
- [Database migrations](../src/main/resources/db/migration/README.md) — rules for Flyway-managed schema evolution.
- [Project README](../README.md) — prerequisites, local startup, public API entry points, and the canonical Maven command.
- [Contributing to ProofChain](../CONTRIBUTING.md) — branch, commit, review, quality, and evidence conventions.

## Codebase overview

The Java source tree is organized feature-first. `auth` owns credential verification, token handling, the authenticated principal, and authentication event logging. `operator` owns the operator aggregate, administrative use cases, and PostgreSQL access. `custodycase` owns case metadata, lifecycle, membership, contextual access, and its PostgreSQL mappings. `evidence` owns typed evidence metadata, PostgreSQL persistence, registration and query use cases, the five operational custody commands and their shared locking and authorization foundation, integrity hashes, and the filesystem adapter. `custodyevent` owns the custody-event domain, the canonical hashing protocol, the single append-only writer, the timeline and detail read APIs, and chain verification. `common` contains cross-cutting Spring Security, OpenAPI, password configuration, and Problem Details support.

Within an implemented feature, API records define input and output instead of exposing persistence entities. Application services define use cases and transactional boundaries. The operator domain enforces canonical identity and aggregate state, while Spring Data JPA repositories persist it in PostgreSQL. Spring Security combines a stateless servlet filter chain with method authorization. JWTs carry a signed operator identifier, but each authenticated request reloads current authorization state from the database.

Flyway is the schema authority; Hibernate validates rather than creates the schema. HTTP and security failures use `application/problem+json`. Springdoc publishes the OpenAPI document and Swagger UI. Fast unit and MVC tests run through Surefire, while `*IT.java` suites use PostgreSQL Testcontainers through Failsafe.

## Repository map

```text
.
├── docs/
│   ├── README.md                 # documentation home
│   ├── Configuration.md          # release baseline, profiles, and startup validation
│   ├── Auth.md                   # authentication guide
│   ├── Operators.md              # operator-management guide
│   ├── CustodyCases.md           # custody-case and membership guide
│   ├── DigitalEvidence.md        # evidence registration, storage, and read guide
│   ├── Custody-Events.md         # custody-event chain, protocol, and verification guide
│   ├── Operational-Custody-Workflows.md  # Sprint 5 transfer, metadata, integrity, seal, release guide
│   └── adr/                      # accepted architecture decisions
├── src/main/java/it/itsprodigi/proofchain/
│   ├── auth/                     # login, JWT, request authentication, audit events
│   ├── operator/                 # API, application rules, domain, persistence
│   ├── common/                   # shared configuration and Problem Details
│   ├── custodycase/              # case API, lifecycle, membership, access, persistence
│   ├── custodyevent/             # event domain, protocol, appender, read and verification APIs
│   └── evidence/                 # evidence API, domain, persistence, and storage
├── src/main/resources/
│   ├── db/migration/             # immutable Flyway migrations
│   ├── application.yml           # shared externalized configuration
│   ├── application-local.yml     # host-execution profile
│   ├── application-container.yml # Docker Compose profile
│   └── logback-spring.xml        # console and AUTH_AUDIT destinations
├── src/test/                     # fast and Testcontainers-backed tests
├── compose.yml                   # local PostgreSQL service
├── pom.xml                       # build, test, formatting, and coverage lifecycle
└── .github/workflows/quality.yml # canonical Maven gate in CI
```

## Architectural principles

- **Modular monolith.** One application is divided by business feature rather than by deployable service.
- **Feature-first organization.** Authentication and operator management own their API and application code; shared concerns remain in `common`.
- **Explicit transactional boundaries.** Application services mark read-only queries and state-changing transactions; controllers do not own transactions.
- **Database-backed authorization state.** A valid JWT identifies an operator, but PostgreSQL supplies the current role and status for every authenticated request.
- **Contextual case access.** ADMIN operators see every case; other roles see only their memberships, and inaccessible identifiers are hidden as not found.
- **Evidence integrity and storage boundary.** Content and contextual SHA-256 values bind immutable file metadata to a case and evidence identifier; filesystem paths remain internal to a hardened storage adapter.
- **Append-only custody history.** Custody events are written only by one server-side appender inside the business transaction they record, are hash-linked per evidence item, and are protected against update or deletion by the database, the persistence layer, and the API surface.
- **Named operational commands.** Evidence is changed only through the five explicit Sprint 5 commands, each appending exactly one custody event under the frozen `PESSIMISTIC_READ` case then `PESSIMISTIC_WRITE` evidence lock order, with one shared server instant and no silent retry.
- **Stateless JWT authentication.** The server does not create an HTTP session or persist a Spring Security context between requests.
- **Flyway-managed schema.** Versioned SQL changes the schema and Hibernate uses `ddl-auto: validate`.
- **Problem Details.** MVC and security boundaries return stable problem types rather than ad hoc error bodies.
- **Reproducible quality checks.** Local development and GitHub Actions use `./mvnw --batch-mode --no-transfer-progress clean verify`.
- **ADRs for material decisions.** Accepted choices that affect architectural boundaries, persistence, or security are recorded under `docs/adr`.

## Documentation maintenance

Every sprint must update the guides for the features it actually introduces. The final certification subtask must compare code, tests, the project README, this documentation home, feature guides, and ADRs before reporting evidence. A material architectural decision requires an ADR; an implementation detail does not. Documentation must never describe a planned endpoint, permission, workflow, or operational capability as implemented before the corresponding code and tests exist.
