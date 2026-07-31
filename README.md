# ProofChain

## Project overview

ProofChain is a time-bounded ITS project implemented as a Spring Boot modular monolith. The released baseline is version `1.0.0`. It provides a reviewable foundation for custody cases and the registration, integrity hashing, discovery, and retrieval of digital evidence.

## MVP boundaries

The current Sprint 5 slice provides username/password login, stateless JWT authentication, database-authoritative operator authorization, ADMIN-protected operator management, custody case lifecycle, contextual case membership, four digital-evidence operations (register, list, inspect, and download), an append-only custody-event hash chain with two read operations and on-demand chain verification, and five operational custody commands (transfer, descriptive metadata update, file-integrity verification, sealing, and release). Content is stored on the local filesystem and bound to persisted metadata by reproducible SHA-256 values, while every evidence item owns an independent, hash-linked custody chain that starts with its registration event and grows by exactly one event per operational command. Generic command or event-append endpoints, bulk and asynchronous operations, custody restoration, file repair, background reconciliation, and production storage operations remain outside the implemented scope.

## Technology stack

- Java 25
- Spring Boot 4.0.7
- Maven Wrapper 3.9.9
- Spring MVC through `spring-boot-starter-webmvc`
- OpenAPI through `springdoc-openapi-starter-webmvc-ui:3.0.2`
- PostgreSQL 18.4 through Docker Compose
- Flyway for schema migrations
- PostgreSQL Testcontainers for integration tests

Java 25 is the canonical project runtime and build baseline. The application is organized as a feature-first modular monolith. See the [ADR index](./docs/adr/README.md) for the implemented architecture decisions.

## Prerequisites

Install Java 25 and Docker Engine with Docker Compose v2 support. Docker must be able to run `postgres:18.4-trixie`.

## Local setup

Create a local environment file:

```bash
cp .env.example .env
```

Replace both `<local-only-secret>` password placeholders with the same local database password. Then replace `<base64-encoded-local-only-secret-at-least-32-bytes>` with a standard RFC 4648 Base64 value that decodes to at least 32 random bytes. For example, generate a suitable local JWT secret with:

```bash
openssl rand -base64 32
```

Paste the command output as the value of `PROOFCHAIN_JWT_SECRET`. Base64 is only an encoding: the generated value remains a secret and must not be committed.

`.env` is ignored and must never be committed. Docker Compose reads this file automatically, while the application requires its variables to be exported in the shell. Before starting the application, load the file with:

```bash
set -a
source .env
set +a
```

The application uses externalized Spring configuration; it does not read environment variables directly from application code. `PROOFCHAIN_STORAGE_ROOT` defaults to `./storage`. The evidence file limit defaults to `50MB` through `PROOFCHAIN_MAX_FILE_SIZE`; the complete multipart request defaults to `51MB` through `PROOFCHAIN_MAX_REQUEST_SIZE` so JSON metadata and framing have bounded overhead. Every supported variable is listed with a safe placeholder in [.env.example](./.env.example); the complete baseline — profiles, secrets, startup validation, request limits, timeouts, and CORS — is documented in the [configuration baseline](./docs/Configuration.md), and the authentication rationale in [ADR-003](./docs/adr/ADR-003-authentication-and-operator-security.md).

Configuration is bound through validated configuration properties and the application fails to start — it never degrades — when the JWT secret is missing, malformed or weaker than 32 bytes, when the token TTL is not positive, when the password policy or BCrypt strength is invalid, when a runtime profile has no datasource credentials, when the storage root is unusable, or when a request-size, timeout or CORS value is invalid. No secret is ever generated or defaulted.

## Database startup

Start and inspect the local PostgreSQL service with:

```bash
docker compose up -d
docker compose ps
```

Stop it with `docker compose down --remove-orphans`. Use `docker compose down -v --remove-orphans` only when intentionally removing local database data. Flyway owns the schema lifecycle and starts with `baseline-on-migrate=false`; do not use Hibernate DDL generation or ad-hoc schema changes.

## Application startup

Exactly three profiles exist: `local` for host execution, `container` for Docker Compose execution, and `test` for automated tests. `local` is enabled by default; select another one with `SPRING_PROFILES_ACTIVE`. After PostgreSQL is running, start the application with:

```bash
./mvnw spring-boot:run
```

Authentication is available at `POST /api/v1/auth/login` and `GET /api/v1/auth/me`. Operator administration is exposed under `/api/v1/operators` for authenticated ADMIN operators. Authentication events are written to the ignored local file `auth.log`; the complete security boundary is recorded in [ADR-003](./docs/adr/ADR-003-authentication-and-operator-security.md).

Custody case lifecycle and membership are exposed under `/api/v1/cases`. ADMIN operators have global case access; other authenticated roles can read their assigned cases, while case mutations are restricted to ADMIN operators and assigned CASE_MANAGER operators. See [Custody Cases](./docs/CustodyCases.md) for the exact contract.

Digital evidence is registered with `POST /api/v1/cases/{caseId}/evidences`, listed with `GET /api/v1/cases/{caseId}/evidences`, inspected with `GET /api/v1/evidences/{evidenceId}`, and downloaded with `GET /api/v1/evidences/{evidenceId}/download`. See [Digital Evidence](./docs/DigitalEvidence.md) for the multipart contract, access rules, hashes, filesystem safety, paging, downloads, and residual limits.

The immutable custody-event timeline is read with `GET /api/v1/evidences/{evidenceId}/events`, one event is inspected with `GET /api/v1/evidences/{evidenceId}/events/{eventId}`, and the complete chain of one evidence item is verified with `POST /api/v1/evidences/{evidenceId}/verify-chain`. Events are never created, updated, or deleted through the API. See [Custody Events](./docs/Custody-Events.md) for the event model, canonical hashing protocol, reproducible fixed vector, verification semantics, and limits.

Operational custody commands are exactly five: `POST /api/v1/evidences/{evidenceId}/transfer`, `PATCH /api/v1/evidences/{evidenceId}/metadata`, `POST /api/v1/evidences/{evidenceId}/verify-integrity`, `POST /api/v1/evidences/{evidenceId}/seal`, and `POST /api/v1/evidences/{evidenceId}/release`. Each appends exactly one custody event in the same transaction and returns `Location` for it. See [Operational Custody Workflows](./docs/Operational-Custody-Workflows.md) for the authorization matrix, lifecycle graph, request and response contracts, locking and concurrency behavior, and Problem Details.

## Tests and quality gate

The canonical verification command is:

```bash
./mvnw --batch-mode --no-transfer-progress clean verify
```

Maven owns quality orchestration: formatting checks, compilation, fast tests, Docker-backed `*IT.java` tests, packaging, and report generation. GitHub Actions only provisions Temurin Java 25 and the runner environment, then invokes the same Maven command; CI never runs `spotless:apply` or modifies source files.

Java formatting is frozen to Spotless `3.6.0` with `palantir-java-format 2.78.0`, verified under Java 25. See [CONTRIBUTING.md](CONTRIBUTING.md) for test naming, local formatting commands, and evidence expectations.

## OpenAPI and Swagger

The generated OpenAPI document and Swagger UI are public at `/v3/api-docs` and `/swagger-ui/index.html`. Login and documentation routes are public; protected application routes use the documented bearer authentication scheme.

Application and security errors use the Spring Problem Details media type `application/problem+json` and the repository's existing problem-type contracts.

## Project structure

```text
src/main/java/it/itsprodigi/proofchain/
├── auth/                 # authentication feature boundary
├── custodycase/          # custody case feature boundary
├── custodyevent/         # custody event feature boundary
├── evidence/             # evidence feature boundary
├── operator/             # operator feature boundary
└── common/               # shared configuration and cross-cutting contracts
```

Database migrations live under `src/main/resources/db/migration`. Tests mirror the application package structure; integration tests use the `*IT.java` suffix.

## Documentation

Start with the [technical documentation home](./docs/README.md), then follow the feature guides for implementation details:

- [Configuration baseline](./docs/Configuration.md) — release version, the three profiles, secrets, fail-fast startup validation, request limits, timeouts, and CORS.
- [Authentication](./docs/Auth.md) — login, JWT validation, database-backed request authentication, password controls, and audit events.
- [Operator Management](./docs/Operators.md) — operator data, ADMIN endpoints, persistence, and concurrency invariants.
- [Custody Cases](./docs/CustodyCases.md) — case lifecycle, contextual membership, REST contracts, persistence, and concurrency.
- [Digital Evidence](./docs/DigitalEvidence.md) — domain metadata, registration, integrity hashes, filesystem storage, read APIs, and failure contracts.
- [Custody Events](./docs/Custody-Events.md) — custody-event model, typed payloads, canonical hash chain, timeline and detail APIs, and chain verification.
- [Operational Custody Workflows](./docs/Operational-Custody-Workflows.md) — transfer, metadata update, file-integrity verification, sealing, release, authorization matrix, lifecycle graph, locking, and concurrency.
- [Architecture Decision Records](./docs/adr/README.md) — accepted decisions that govern the implemented architecture.
- [Contributing rules](./CONTRIBUTING.md) — repository workflow, quality checks, and evidence expectations.
- [MIT license](./LICENSE) — project licensing terms.

## License

ProofChain is distributed under the [MIT License](./LICENSE).
