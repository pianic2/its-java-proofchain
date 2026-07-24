# ProofChain

ProofChain is a Spring Boot modular monolith. Its local database is PostgreSQL, while integration tests provision an independent PostgreSQL instance through Testcontainers.

## Prerequisites

Use Java 25 and Docker Engine with Docker Compose v2 support. The local baseline is Docker Engine 26.1.5 and Docker Compose 2.26.1; Docker must be able to run `postgres:18.4-trixie`.

## Local configuration

Create a local environment file and replace the two password placeholders with the same local-only value:

```bash
cp .env.example .env
```

`.env` is ignored and must never be committed. The approved environment variables are:

| Variable | Default | Purpose |
| --- | --- | --- |
| `POSTGRES_DB` | `proofchain` | Database initialized by PostgreSQL. |
| `POSTGRES_USER` | `proofchain` | Database user initialized by PostgreSQL. |
| `POSTGRES_PASSWORD` | Required | Local PostgreSQL password. |
| `POSTGRES_PORT` | `5432` | PostgreSQL host port published by Compose. |
| `DB_HOST` | `localhost` | Host used by the local Spring profile. |
| `DB_PORT` | `5432` | Port used by the local Spring profile. |
| `DB_NAME` | `proofchain` | Database used by the local Spring profile. |
| `DB_USERNAME` | `proofchain` | Database user used by the local Spring profile. |
| `DB_PASSWORD` | Required | Database password used by the local Spring profile. |
| `PROOFCHAIN_STORAGE_ROOT` | `./storage` | Future filesystem storage root. |
| `PROOFCHAIN_MAX_FILE_SIZE` | `50MB` | Multipart file and request limit. |

Spring defaults to the `local` profile. Configuration uses Spring placeholders only; application code does not read environment variables directly.

## Database lifecycle

Start, inspect, view logs, stop, or destructively reset the local PostgreSQL service:

```bash
docker compose up -d
docker compose ps
docker compose logs --no-color postgres
docker compose down --remove-orphans
docker compose down -v --remove-orphans
```

The final command removes the named `proofchain-postgres-data` volume and all local database data.

Compose initializes infrastructure only. Flyway owns the schema lifecycle and starts against an empty database with `baseline-on-migrate=false`; it creates `flyway_schema_history` automatically. Do not add ad-hoc schema changes or Hibernate DDL generation. The first domain migration belongs to Sprint 1.

## Ports

Compose deliberately has no automatic port fallback. If the configured host port is already occupied, Compose fails. Resolve it by stopping the conflicting service, or explicitly configure the same alternative port in both settings:

```text
POSTGRES_PORT=5433
DB_PORT=5433
```

If those values diverge, the application will not reach the Compose database. The actionable diagnosis is: `Port <configured port> is already in use. Stop the conflicting service or explicitly set POSTGRES_PORT and DB_PORT to the same alternative host port in .env.`

## Tests

`DatabaseBootstrapIT` starts `postgres:18.4-trixie` through Testcontainers and does not use the Compose service. It verifies that Flyway creates `flyway_schema_history` on an empty PostgreSQL database.

Run the canonical verification command:

```bash
./mvnw --batch-mode --no-transfer-progress clean verify
```

See [CONTRIBUTING.md](CONTRIBUTING.md) for the quality gate, test naming, report locations, and formatting workflow.

## API documentation and error responses

During Sprint 0, the OpenAPI document and Swagger UI are temporarily public at:

```text
/v3/api-docs
/swagger-ui/index.html
```

The API documentation declares a `bearerAuth` JWT scheme, but JWT authentication and authorization are not operational until Sprint 1. No application API endpoint is opened by this temporary configuration.

Application and controller errors use `application/problem+json` (security-layer failures such as 401/403 from Spring Security bypass the application handler and may use a different format). For example, a missing resource returns:

```json
{
  "type": "https://proofchain.dev/problems/resource-not-found",
  "title": "Resource not found",
  "status": 404,
  "detail": "The requested resource was not found.",
  "instance": "/api/example",
  "timestamp": "2026-07-24T12:00:00Z"
}
```

Validation failures include deterministic field details and never include rejected values:

```json
{
  "type": "https://proofchain.dev/problems/validation-error",
  "title": "Validation failed",
  "status": 400,
  "detail": "One or more request fields are invalid.",
  "instance": "/api/example",
  "timestamp": "2026-07-24T12:00:00Z",
  "errors": [
    {
      "field": "name",
      "message": "must not be blank",
      "code": "NotBlank"
    }
  ]
}
```
