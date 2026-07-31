# ProofChain

Chain-of-custody backend for digital evidence. Spring Boot modular monolith, released at version `1.0.0`.

## What it is

ProofChain records who holds a piece of digital evidence, what was done to it, and proves that the stored bytes have
not changed since registration. Every piece of evidence owns an independent, append-only, hash-linked custody history
that can be verified on demand.

It is an ITS delivery: a complete, tested backend, not a product. It is **not** production-certified, makes no
availability or throughput commitment, and implements no digital signature, timestamping authority, malware scanning or
distributed ledger. The custody chain is a local, per-evidence tamper-evidence mechanism stored in one PostgreSQL
database.

## MVP boundaries

Delivered in `1.0.0`:

- username/password login and stateless JWT authentication, with role and status re-read from the database on every
  request;
- ADMIN-protected operator management;
- custody case lifecycle and contextual case membership through the explicit `CaseMembership` join entity;
- digital evidence: multipart registration, paged listing, item inspection and content download;
- an append-only custody-event hash chain per evidence item, with timeline and detail reads and on-demand chain
  verification;
- five named operational commands — transfer, descriptive metadata update, file-integrity verification, seal, release —
  each appending exactly one custody event in the same transaction;
- a hardened Docker Compose runtime and an offline, read-only orphan-file report.

Deliberately outside scope and not implemented: generic command or event-append endpoints, bulk and asynchronous
operations, custody restoration, un-sealing, evidence deletion, file repair, background reconciliation, digital
signatures, antivirus scanning, multi-tenancy, and any database other than PostgreSQL.

## Technology stack

| Component | Version |
| --- | --- |
| Java | 25 |
| Spring Boot | 4.0.7 |
| Maven Wrapper | 3.9.9 |
| Spring MVC | `spring-boot-starter-webmvc` |
| OpenAPI | `springdoc-openapi-starter-webmvc-ui:3.0.2` |
| PostgreSQL | 18.4 (`postgres:18.4-trixie`) — the only supported database |
| Schema | Flyway `V1`–`V7`, Hibernate `ddl-auto: validate` |
| Tests | JUnit 5, PostgreSQL Testcontainers 1.21.4, JaCoCo 0.8.15 |

Java 25 is the canonical runtime and build baseline. The application is a feature-first modular monolith; see the
[ADR index](./docs/adr/README.md) for the decisions that govern it.

## Prerequisites

Java 25 and Docker Engine with Docker Compose v2. Docker must be able to run `postgres:18.4-trixie`,
`eclipse-temurin:25-jdk` and `eclipse-temurin:25-jre`.

## Environment preparation and secret generation

```bash
cp .env.example .env
```

Replace both `<local-only-secret>` password placeholders with the same local database password, then generate the JWT
secret:

```bash
openssl rand -base64 32
```

Paste the output as `PROOFCHAIN_JWT_SECRET`. Base64 is only an encoding — the value is a secret and must never be
committed. `.env` is git-ignored.

Docker Compose reads `.env` automatically. To run on the host instead, export it first:

```bash
set -a
source .env
set +a
```

No secret is ever generated or defaulted by the application. Startup fails — it never degrades — when the JWT secret is
missing, malformed or weaker than 32 bytes, when the token TTL is not positive, when the password policy or BCrypt
strength is invalid, when a runtime profile has no datasource credentials, when the storage root is unusable, or when a
request-size, timeout or CORS value is invalid. Every supported variable is listed with a safe placeholder in
[.env.example](./.env.example) and documented in the [configuration baseline](./docs/Configuration.md).

## Quick start with Docker Compose

```bash
docker compose build
docker compose up -d
docker compose ps
curl -s http://localhost:8080/actuator/health/readiness
```

Compose waits for the PostgreSQL healthcheck before creating the application container, so no fixed sleep is needed.
The application runs as the non-root user `10001:10001` on a read-only root filesystem with `cap_drop: ALL`; the
evidence volume and a bounded `tmpfs` are its only writable paths. `${APP_PORT}` and `${POSTGRES_PORT}` select the
published host ports.

Stop with `docker compose stop`. Remove containers and network while keeping both volumes with
`docker compose down --remove-orphans`. Use `docker compose down -v --remove-orphans` only to destroy the demo data —
it deletes the evidence volume and the database volume permanently. Full runbook:
[container operations](./docs/Operations.md).

## Host and Maven setup

Start only the database, then run the application on the host:

```bash
docker compose up -d postgres
./mvnw spring-boot:run
```

Exactly three profiles exist: `local` (host execution, active by default), `container` (activated by the application
image) and `test` (automated tests). Flyway owns the schema lifecycle with `baseline-on-migrate=false`; never use
Hibernate DDL generation or ad-hoc schema changes.

## Canonical verification command

```bash
./mvnw --batch-mode --no-transfer-progress clean verify
```

This is the single quality gate: formatting check, compilation, Surefire fast tests, Docker-backed `*IT.java` tests via
Testcontainers, packaging, JaCoCo report and the coverage gate. GitHub Actions provisions Temurin Java 25 and invokes
exactly this command; CI never runs `spotless:apply` and never modifies sources.

Apply formatting locally with `./mvnw spotless:apply`. See [Testing](./docs/Testing.md) for the test categories,
reports and known skips, and [Troubleshooting](./docs/Troubleshooting.md) when the build or the stack misbehaves.

## API entry points

- **OpenAPI document:** `GET /v3/api-docs` (public)
- **Swagger UI:** `/swagger-ui/index.html` (public)
- **Health probes:** `/actuator/health`, `/actuator/health/liveness`, `/actuator/health/readiness` — status only, no
  component detail
- **Postman:** ready-to-run collection and placeholder-only environment in [postman/](./postman/README.md)
- **Demo:** deterministic, synthetic-data-only walkthrough in the [demo guide](./docs/Demo-Guide.md)

The runtime-generated document is the single API specification; no static specification file exists that could diverge
from it. `ApiSurfaceContractIT` reconciles the live Spring request mappings, the generated document, the Problem
Details catalogue and the Postman collection against one table of 27 approved operations.

All errors use `application/problem+json` with a stable `type` URI from the Problem Details catalogue.

The API surface, in brief:

| Area | Routes |
| --- | --- |
| Authentication | `POST /api/v1/auth/login`, `GET /api/v1/auth/me` |
| Operators (ADMIN) | `POST` and `GET /api/v1/operators`, `GET /api/v1/operators/{id}`, `PATCH .../role`, `PATCH .../status` |
| Custody cases | `POST` and `GET /api/v1/cases`, `GET` and `PATCH /api/v1/cases/{caseId}`, `PATCH /api/v1/cases/{caseId}/status` |
| Membership | `GET /api/v1/cases/{caseId}/members`, `PUT` and `DELETE .../members/{operatorId}` |
| Evidence | `POST` and `GET /api/v1/cases/{caseId}/evidences`, `GET /api/v1/evidences/{evidenceId}`, `GET .../download` |
| Custody events | `GET /api/v1/evidences/{evidenceId}/events`, `GET .../events/{eventId}`, `POST .../verify-chain` |
| Operational commands | `POST .../transfer`, `PATCH .../metadata`, `POST .../verify-integrity`, `POST .../seal`, `POST .../release` |

## Project structure

```text
src/main/java/it/itsprodigi/proofchain/
├── auth/                 # login, JWT, request authentication, audit events
├── operator/             # operator aggregate, admin use cases, password policy
├── custodycase/          # case lifecycle, CaseMembership, contextual access
├── evidence/             # evidence aggregate, registration, reads, 5 commands, storage, maintenance
├── custodyevent/         # event domain, canonical protocol, appender, reads, chain verification
└── common/               # security wiring, OpenAPI, CORS, Problem Details
```

Demonstration assets live outside the Java tree: [`scripts/demo/`](./scripts/demo/demo-preflight.sh) holds the safe
preflight, the explicitly destructive reset and the Postman/Newman smoke wrapper, and
[`presentation/ProofChain.md`](./presentation/ProofChain.md) is the deck source. None of them is referenced by
`pom.xml` or by any workflow.

Migrations live in `src/main/resources/db/migration` and are the official SQL creation scripts of the delivery; their
rules are in the [migration guide](./src/main/resources/db/migration/README.md) and their certified lifecycle in the
[schema lifecycle guide](./docs/Database-Schema-Lifecycle.md). Tests mirror the application packages; integration tests
use the `*IT.java` suffix. The container runtime is `Dockerfile`, `compose.yml` and the helpers under `docker/`.

## Documentation

Start at the [documentation home](./docs/README.md). The reviewer path is:

- [Technical report](./docs/Technical-Report.md) — the complete system, its invariants and its limitations.
- [Architecture](./docs/Architecture.md) — eight Mermaid diagrams: modules, domain model, schema, registration,
  operational command, integrity verification, chain verification, chain structure.
- [ITS compliance](./docs/ITS-Compliance.md) — factual rubric mapping, including the PostgreSQL and JPA cardinality
  deviations that require acknowledgement.
- [Configuration](./docs/Configuration.md), [Operations](./docs/Operations.md), [Testing](./docs/Testing.md),
  [Troubleshooting](./docs/Troubleshooting.md) — the operational set.
- Feature guides: [Authentication](./docs/Auth.md), [Operators](./docs/Operators.md),
  [Custody Cases](./docs/CustodyCases.md), [Digital Evidence](./docs/DigitalEvidence.md),
  [Custody Events](./docs/Custody-Events.md),
  [Operational Custody Workflows](./docs/Operational-Custody-Workflows.md).
- [Demo guide](./docs/Demo-Guide.md) — the authoritative 12–15 minute demonstration procedure, its human-gated
  tampering scenarios and the destructive reset; [reviewer checklist](./docs/Reviewer-Checklist.md) — the 20–30 minute
  self-service assessment path.
- [Presentation source](./presentation/ProofChain.md) — 12-slide Markdown/Mermaid deck. The Java build has no
  presentation dependency and no exported deck is tracked.
- [ADR index](./docs/adr/README.md), [CHANGELOG](./CHANGELOG.md), [CONTRIBUTING](./CONTRIBUTING.md).

## Release

Version `1.0.0`. The version is frozen in `pom.xml`, in the published OpenAPI document, in the image label and in the
Compose image tag, and `ReleaseBaselineTest` asserts all of them. Changes are recorded in
[CHANGELOG.md](./CHANGELOG.md).

The delivery tag `uf14-final-2026` is created by the Project Owner at final acceptance and does not exist in the
repository yet.

**Known limitations are documented, not hidden.** OWASP Dependency-Check has not been executed in this environment, so
the release carries no vulnerability analysis. That and every other known defect are listed in
[Technical report §16](./docs/Technical-Report.md#16-known-limitations-and-future-work).

## License

MIT — see [LICENSE](./LICENSE).
