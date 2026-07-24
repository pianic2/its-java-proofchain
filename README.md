# ProofChain

## Project overview

ProofChain is a time-bounded ITS project implemented as a Spring Boot modular monolith. It provides the foundation for recording evidence and custody events with a clear, reviewable technical baseline.

## MVP boundaries

Sprint 0 establishes the build, persistence, HTTP, security, documentation, and quality foundations. Domain use cases, authentication and authorization, file processing, and production operations are delivered only by later approved subtasks.

## Technology stack

- Java 25 LTS
- Spring Boot 4.0.7
- Maven Wrapper 3.9.9
- Spring MVC through `spring-boot-starter-webmvc`
- OpenAPI through `springdoc-openapi-starter-webmvc-ui:3.0.2`
- PostgreSQL 18.4 through Docker Compose
- Flyway for schema migrations
- PostgreSQL Testcontainers for integration tests

The application is organized as a feature-first modular monolith. See [ADR-001](docs/adr/ADR-001-foundation-baseline.md) for the cumulative foundation decisions.

## Prerequisites

Install Java 25 and Docker Engine with Docker Compose v2 support. Docker must be able to run `postgres:18.4-trixie`.

## Local setup

Create a local environment file and replace both password placeholders with the same local-only value:

```bash
cp .env.example .env
```

`.env` is ignored and must never be committed. The application uses externalized Spring configuration; it does not read environment variables directly from application code. Available variables are documented in `.env.example` and include PostgreSQL connection values, the storage root, and the multipart size limit.

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

## Tests and quality gate

The canonical verification command is:

```bash
./mvnw --batch-mode --no-transfer-progress clean verify
```

It formats, compiles, runs fast tests, runs Docker-backed `*IT.java` tests, packages the application, and writes reports. See [CONTRIBUTING.md](CONTRIBUTING.md) for test naming and evidence expectations.

## OpenAPI and Swagger

During Sprint 0, the generated OpenAPI document and Swagger UI are available at `/v3/api-docs` and `/swagger-ui/index.html`. JWT authentication is documented but is not operational until its approved implementation task; no application API endpoint is opened by the temporary configuration.

Application errors use the Spring Problem Details media type `application/problem+json`. Security-layer failures may use a different response format.

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

- [Contributing rules](CONTRIBUTING.md)
- [ADR-001 — Foundation baseline](docs/adr/ADR-001-foundation-baseline.md)
- [MIT license](LICENSE)

## License

ProofChain is distributed under the [MIT License](LICENSE).
