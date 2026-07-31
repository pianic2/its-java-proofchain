# Container operations

This guide describes how the released `1.0.0` backend runs under Docker Compose: the image, the runtime identity, the writable surface, the health contract, and the exact commands for build, start, inspection, restart, shutdown, and an intentional destructive reset. Configuration values themselves are documented in the [configuration baseline](./Configuration.md).

## Runtime shape

| Element | Value |
| --- | --- |
| Build image | `eclipse-temurin:25-jdk`, building through the repository Maven Wrapper |
| Runtime image | `eclipse-temurin:25-jre`, receiving only the packaged jar and the health probe |
| Image tag | `proofchain:1.0.0` |
| Runtime user and group | `10001:10001` (`proofchain`), never root |
| Active profile | `container` |
| Container storage root | `/var/lib/proofchain/storage` |
| Root filesystem | read-only |
| Writable paths | the evidence volume and a bounded `tmpfs` at `/tmp` |
| Capabilities | all dropped, `no-new-privileges` set, never privileged |
| Database | `postgres:18.4-trixie`, the same image the tests use |
| Named volumes | `proofchain-evidence-data` and `proofchain-postgres-data`, independent |
| Published port | `${APP_PORT:-8080}` on the host, `8080` inside the container |

The image build needs nothing beyond the two pinned base images and Maven Central. The runtime needs nothing beyond the database: no registry, no reverse proxy, no orchestrator, no Docker socket.

### Why the build stage installs a small `unzip` stand-in

The Temurin JDK image ships no `unzip`. Without it the Maven Wrapper quietly switches from the `.zip` distribution to the `.tar.gz` one, whose checksum is not the SHA-256 pinned in `.mvn/wrapper/maven-wrapper.properties`, and the build stops with a checksum error. `docker/unzip-for-maven-wrapper.sh` maps the single invocation the wrapper makes onto `jar`, which is already part of the JDK. The wrapper therefore keeps verifying the frozen checksum, and the image build gains no dependency on an operating-system package archive.

## Environment preparation

Docker Compose reads `.env` from the repository root automatically. `.env` is git-ignored and must never be committed.

```bash
cp .env.example .env
```

Replace both `<local-only-secret>` password placeholders with the same local database password, and replace the JWT placeholder with a fresh value:

```bash
openssl rand -base64 32
```

`compose.yml` refuses to start when `POSTGRES_PASSWORD` or `PROOFCHAIN_JWT_SECRET` is missing. No secret has a default, no secret is generated, and no secret exists in the image, in `Dockerfile`, in `compose.yml` or in any tracked file.

To reach a first authenticated request, enable the idempotent bootstrap administrator in `.env` before the first start:

```bash
PROOFCHAIN_BOOTSTRAP_ADMIN_ENABLED=true
PROOFCHAIN_BOOTSTRAP_ADMIN_USERNAME=...
PROOFCHAIN_BOOTSTRAP_ADMIN_EMAIL=...
PROOFCHAIN_BOOTSTRAP_ADMIN_PASSWORD=...
```

## Commands

### Build

```bash
docker compose build
```

### Start

```bash
docker compose up -d
```

Compose starts PostgreSQL first and waits for its `pg_isready` healthcheck before creating the application container, through `depends_on: condition: service_healthy`. There is no fixed sleep anywhere in the startup path. `docker compose up --build` performs both steps in one command.

### Inspect health

```bash
docker compose ps
curl -s http://localhost:8080/actuator/health
curl -s http://localhost:8080/actuator/health/liveness
curl -s http://localhost:8080/actuator/health/readiness
docker compose logs -f proofchain
```

A healthy stack reports `Up (healthy)` for both services and `{"status":"UP"}` for all three probes.

### Restart with persisted data

```bash
docker compose restart
```

or, for a full recreate on the same volumes:

```bash
docker compose down --remove-orphans
docker compose up -d
```

Neither command touches the named volumes. Evidence content, the custody event chain, and every operator and case survive: after the restart the schema is already at the latest Flyway version, `POST /api/v1/evidences/{id}/verify-chain` still reports `"valid": true`, and `POST /api/v1/evidences/{id}/verify-integrity` still matches the recorded SHA-256.

### Normal shutdown

```bash
docker compose stop
```

`java` is PID 1, so `SIGTERM` reaches the JVM directly and Spring's shutdown hook runs: Tomcat drains in-flight requests within `spring.lifecycle.timeout-per-shutdown-phase`, then the JPA factory and the Hikari pool close. `stop_grace_period` is 60s, comfortably above the 30s drain window, so Docker never escalates to `SIGKILL`. The container exits with code 143 and PostgreSQL performs a shutdown checkpoint. Use `docker compose down --remove-orphans` to additionally remove the containers and the network while keeping both volumes.

### Intentional destructive demo reset

This is the only command that destroys data. It removes both named volumes, so every registered evidence file, every custody event and the entire database are permanently lost.

```bash
docker compose down -v --remove-orphans
```

The next `docker compose up -d` starts from empty volumes: Flyway applies every migration from scratch and, when enabled, the bootstrap administrator is recreated.

## Health and readiness contract

Spring Boot Actuator is the only runtime dependency this subtask adds, and it is present for exactly one reason: the orchestrator needs a readiness signal.

- Exactly three paths are published and unauthenticated: `/actuator/health`, `/actuator/health/liveness` and `/actuator/health/readiness`.
- Everything else — environment, beans, metrics, heap dump, config props, loggers, thread dump, mappings, info, shutdown, the discovery index and the individual health components — is not exposed at all. An authenticated ADMIN receives `404`, an anonymous caller receives the standard `401` problem document.
- Endpoint access is capped at read-only, so no actuator endpoint can ever accept a write.
- Details and components are never rendered. A probe response is a bare status; the aggregate additionally names its two groups. No version, driver, JDBC URL, filesystem path or free-space figure is ever published.
- No actuator path appears in the OpenAPI document or in Swagger UI.

Readiness is green only when all of the following hold:

| Condition | Enforced by |
| --- | --- |
| Application startup completed | `readinessState` availability probe |
| PostgreSQL reachable | `db` health contributor |
| Flyway migrations applied and validated | fail-fast startup; the context never becomes ready otherwise |
| Hibernate schema validation passed | fail-fast startup with `ddl-auto: validate` |
| Evidence storage root usable | `evidenceStorage` health contributor |

The `evidenceStorage` contributor performs a real write: it creates and immediately removes a temporary file in the staging directory the upload path itself uses, because attribute checks alone cannot tell a writable directory from one on a filesystem that has been remounted read-only.

Liveness is deliberately narrower. It reports the application's own liveness state only, so a database outage turns readiness red — removing the instance from service — without triggering a restart of a process that is perfectly healthy.

The Compose healthcheck runs `/usr/local/bin/proofchain-health`, which reads the readiness probe. The runtime image contains no `curl`, `wget` or netcat and installs none; the probe speaks just enough HTTP over Bash's `/dev/tcp` to read the status, which keeps the image build free of an operating-system package archive and the runtime free of extra attack surface.

## Fail-fast behaviour

The application never degrades and never retries indefinitely, and the Compose service declares no restart policy, so a misconfigured container exits once and stays visible instead of looping.

| Injected fault | Result |
| --- | --- |
| JWT secret shorter than 32 decoded bytes | context fails to start, exit code 1, `proofchain.jwt.secret must decode to at least 32 bytes`; the supplied value is never echoed |
| Wildcard CORS origin | context fails to start, exit code 1, `proofchain.cors.allowed-origins must not contain a wildcard origin` |
| Evidence storage root that cannot be created | context fails to start, exit code 1, `Evidence storage directory cannot be created` |
| Missing datasource password | one connection attempt, then fail closed; the message names the mechanism, never the credential |

## Security properties

- No root at runtime: the process runs as UID and GID `10001`, and `/proc/1/status` reports `Uid: 10001` with `CapEff: 0000000000000000` and `NoNewPrivs: 1`.
- The root filesystem is mounted read-only; writes to `/`, `/etc`, `/opt/proofchain`, `/usr/local/bin` and `/var/lib/proofchain` are refused with `Read-only file system` while uploads still succeed through the evidence volume.
- The `tmpfs` at `/tmp` is bounded and mounted `noexec,nosuid,nodev`, and is wiped on every restart.
- The application jar and the health probe are owned by root and not writable by the runtime user, so the process cannot rewrite its own code.
- The Docker socket is never mounted, no capability is added, and privileged mode is never used.
- Evidence storage paths are derived from case and evidence identifiers, never from an uploaded filename; see [Digital Evidence](./DigitalEvidence.md).
- Authentication audit events are written to `auth.log` on the host and to standard output under the `container` profile, so the read-only root filesystem needs no third writable mount.

## Known limitations

- The aggregate `/actuator/health` response includes the names of its two groups (`liveness`, `readiness`). Spring Boot offers no switch to suppress them; they name probes that are already public and reveal nothing about the deployment.
- A request with an unsupported HTTP method returns the sanitized generic `500` problem document rather than `405`. This is pre-existing behaviour of the global exception handler for every path, not something the actuator introduces.
- The single application instance keeps no shared state, but no clustering, no reverse proxy and no TLS termination are delivered; they are outside the released scope.
