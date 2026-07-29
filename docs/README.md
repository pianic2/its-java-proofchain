# ProofChain technical documentation

## Introduction

ProofChain is the backend of a system for managing the chain of custody of digital evidence. The application is delivered as a Spring Boot modular monolith: one deployable runtime contains feature-oriented packages with explicit boundaries between HTTP contracts, application services, domain state, persistence, and security.

This documentation describes the code that is present in the repository. The current implementation is concentrated on the project infrastructure, authentication, and operator management. The packages reserved for custody cases, case membership, custody events, and evidence do not yet contain their later-sprint workflows, so those capabilities are not presented as available features.

## How to read this documentation

The recommended path is:

1. Start with the [project README](../README.md) to set up the application and run the quality gate.
2. Read [Authentication](./Auth.md) to understand login, JWT validation, database-backed request authentication, and security errors.
3. Continue with [Operator Management](./Operators.md) for the persisted identity model, ADMIN API, and concurrency invariants.
4. Consult the [ADR index](./adr/README.md) for the decisions behind the implemented architecture.
5. Use the test links in each feature guide as the current executable testing reference. A separate testing guide has not been added yet.

## Documentation index

- [Authentication](./Auth.md) — login, JWTs, authenticated requests, password controls, bootstrap, audit logging, and security tests.
- [Operator Management](./Operators.md) — operator data, roles and statuses, administrative endpoints, persistence, and concurrency protection.
- [Architecture Decision Records](./adr/README.md) — accepted project and Sprint 1 architectural decisions.
- [Database migrations](../src/main/resources/db/migration/README.md) — rules for Flyway-managed schema evolution.
- [Project README](../README.md) — prerequisites, local startup, public API entry points, and the canonical Maven command.
- [Contributing to ProofChain](../CONTRIBUTING.md) — branch, commit, review, quality, and evidence conventions.

## Codebase overview

The Java source tree is organized feature-first. `auth` owns credential verification, token handling, the authenticated principal, and authentication event logging. `operator` owns the operator aggregate, administrative use cases, and PostgreSQL access. `common` contains cross-cutting Spring Security, OpenAPI, password configuration, and Problem Details support. Empty `custodycase`, `custodyevent`, and `evidence` package boundaries reserve names without claiming that those features exist.

Within an implemented feature, API records define input and output instead of exposing persistence entities. Application services define use cases and transactional boundaries. The operator domain enforces canonical identity and aggregate state, while Spring Data JPA repositories persist it in PostgreSQL. Spring Security combines a stateless servlet filter chain with method authorization. JWTs carry a signed operator identifier, but each authenticated request reloads current authorization state from the database.

Flyway is the schema authority; Hibernate validates rather than creates the schema. HTTP and security failures use `application/problem+json`. Springdoc publishes the OpenAPI document and Swagger UI. Fast unit and MVC tests run through Surefire, while `*IT.java` suites use PostgreSQL Testcontainers through Failsafe.

## Repository map

```text
.
├── docs/
│   ├── README.md                 # documentation home
│   ├── Auth.md                   # authentication guide
│   ├── Operators.md              # operator-management guide
│   └── adr/                      # accepted architecture decisions
├── src/main/java/it/itsprodigi/proofchain/
│   ├── auth/                     # login, JWT, request authentication, audit events
│   ├── operator/                 # API, application rules, domain, persistence
│   ├── common/                   # shared configuration and Problem Details
│   ├── custodycase/              # reserved feature boundary
│   ├── custodyevent/             # reserved feature boundary
│   └── evidence/                 # reserved feature boundary
├── src/main/resources/
│   ├── db/migration/             # immutable Flyway migrations
│   ├── application.yml           # shared externalized configuration
│   ├── application-local.yml     # local PostgreSQL profile
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
- **Stateless JWT authentication.** The server does not create an HTTP session or persist a Spring Security context between requests.
- **Flyway-managed schema.** Versioned SQL changes the schema and Hibernate uses `ddl-auto: validate`.
- **Problem Details.** MVC and security boundaries return stable problem types rather than ad hoc error bodies.
- **Reproducible quality checks.** Local development and GitHub Actions use `./mvnw --batch-mode --no-transfer-progress clean verify`.
- **ADRs for material decisions.** Accepted choices that affect architectural boundaries, persistence, or security are recorded under `docs/adr`.

## Documentation maintenance

Every sprint must update the guides for the features it actually introduces. The final certification subtask must compare code, tests, the project README, this documentation home, feature guides, and ADRs before reporting evidence. A material architectural decision requires an ADR; an implementation detail does not. Documentation must never describe a planned endpoint, permission, workflow, or operational capability as implemented before the corresponding code and tests exist.
