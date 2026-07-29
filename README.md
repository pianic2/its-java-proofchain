# ProofChain

## Project overview

ProofChain is a time-bounded ITS project implemented as a Spring Boot modular monolith. It provides the foundation for recording evidence and custody events with a clear, reviewable technical baseline.

## MVP boundaries

The current Sprint 1 slice provides username/password login, stateless JWT authentication, database-authoritative operator authorization and ADMIN-protected operator management. Evidence and custody workflows, file processing and production operations remain outside the implemented scope.

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

The application uses externalized Spring configuration; it does not read environment variables directly from application code. `PROOFCHAIN_STORAGE_ROOT` defaults to `./storage`. The MVP upload limit defaults to `50MB` and is configurable through `PROOFCHAIN_MAX_FILE_SIZE`. JWT and optional bootstrap-admin settings are listed in [.env.example](./.env.example) and described in [ADR-003](./docs/adr/ADR-003-authentication-and-operator-security.md).

## Database startup

Start and inspect the local PostgreSQL service with:

```bash
docker compose up -d
docker compose ps
```

Stop it with `docker compose down --remove-orphans`. Use `docker compose down -v --remove-orphans` only when intentionally removing local database data. Flyway owns the schema lifecycle and starts with `baseline-on-migrate=false`; do not use Hibernate DDL generation or ad-hoc schema changes.

## Application startup

The `local` Spring profile is enabled by default. After PostgreSQL is running, start the application with:

```bash
./mvnw spring-boot:run
```

Authentication is available at `POST /api/v1/auth/login` and `GET /api/v1/auth/me`. Operator administration is exposed under `/api/v1/operators` for authenticated ADMIN operators. Authentication events are written to the ignored local file `auth.log`; the complete security boundary is recorded in [ADR-003](./docs/adr/ADR-003-authentication-and-operator-security.md).

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

- [Authentication](./docs/Auth.md) — login, JWT validation, database-backed request authentication, password controls, and audit events.
- [Operator Management](./docs/Operators.md) — operator data, ADMIN endpoints, persistence, and concurrency invariants.
- [Architecture Decision Records](./docs/adr/README.md) — accepted decisions that govern the implemented architecture.
- [Contributing rules](./CONTRIBUTING.md) — repository workflow, quality checks, and evidence expectations.
- [MIT license](./LICENSE) — project licensing terms.

## License

ProofChain is distributed under the [MIT License](./LICENSE).
