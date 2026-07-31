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

## Evidence storage integrity

### The bounded crash window

Registration finalizes the evidence file and then commits the database transaction. Between those two
steps there is a window, bounded by the length of the commit, in which the process can die with the
final file already in place and no committed row referencing it. The reverse is impossible: the row
is never committed before the file exists.

A crash inside that window therefore leaves a file without a row, never a row without a file. That
file is intact evidence content, not garbage. Nothing in ProofChain deletes it, and nothing ever
will: an automatic sweep would destroy exactly the material a chain of custody exists to preserve,
and it cannot distinguish a crash remnant from content whose row was removed by an attacker.

A row without a file can still occur, but only through action outside the application: a restore that
covered the database and not the storage volume, a manual deletion, or a failing disk.

### The offline orphan report

The report is a read-only diagnostic. It has no HTTP endpoint, it never runs at startup, during a
health probe or on a schedule, and it creates, moves, rewrites, quarantines and deletes nothing. It
runs only when the process is started with the exact enabling argument, in a non-web context, with
read-only database access.

```bash
java -jar target/proofchain-1.0.0.jar --proofchain.maintenance.orphan-report.enabled=true
```

Write the document to a file instead of standard output with
`--proofchain.maintenance.orphan-report.output=/path/report.json`. The destination must lie outside
the evidence storage root and is never overwritten.

Exit codes: `0` clean, `2` findings present, `1` the report could not be produced.

Findings are classified as:

| Classification | Meaning |
| --- | --- |
| `MISSING_CONTENT` | a legitimate evidence row references content that is absent or unusable |
| `ORPHAN_CONTENT` | a canonical final content file exists and no evidence row references it |
| `UNSAFE_CONTENT` | the path is symlinked, non-regular, outside the resolved root or otherwise unsafe |
| `UNEXPECTED_ENTRY` | an entry under the storage root does not match the canonical layout |

The document carries only a classification, a closed-vocabulary reason and a storage-root relative
path. It can never carry an absolute host path, an original upload file name, a media type, a hash,
a byte count or any other evidence metadata: the finding type rejects anything else at construction.
When an evidence row holds a storage key that is not canonical, the key is attacker-influenced and is
withheld entirely; the report publishes the evidence identifier instead so an investigation still has
a database key to start from.

Two scans of an unchanged database and an unchanged storage tree render byte-identical documents, so
reports are directly comparable across an investigation.

### Non-destructive investigation

The report tells you what is inconsistent. It never tells the system to act, and no ProofChain
command reconciles storage automatically.

1. Take the report and keep it. Do not modify the storage tree first.
2. For `MISSING_CONTENT`, leave the row untouched. Download and integrity verification already fail
   for that evidence with the approved sanitized technical problem documents, which is the correct
   operational signal. Treat it as blocking evidence of a real incident and look for a restore that
   covered only one of the two stores.
3. For `ORPHAN_CONTENT`, preserve the file. It is never exposed through the API, and it must not be
   deleted on the assumption that it is a crash remnant. Correlate its case and evidence identifiers
   with the custody event history and with the deployment timeline before concluding anything.
4. For `UNSAFE_CONTENT`, do not follow or open the path. A symbolic link under the storage root was
   not created by this application.
5. For `UNEXPECTED_ENTRY`, establish who wrote it. The runtime user can only write the evidence
   volume and the bounded temporary mount.
6. Any corrective action — moving, deleting or reinserting anything — is a Project Owner decision,
   taken by hand, after the root cause is understood.

### Coordinated backup and restore

The database and the evidence volume are one logical unit. A backup that covers one and not the
other produces exactly the inconsistencies the report classifies.

ProofChain deliberately ships no backup endpoint, no scheduler and no automated restore. Backups are
an operator responsibility, performed with the stack stopped:

```bash
docker compose stop proofchain
docker compose exec -T postgres pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Fc > proofchain-db.dump
docker run --rm -v proofchain-evidence-data:/data -v "$PWD":/backup alpine \
  tar czf /backup/proofchain-evidence.tar.gz -C /data .
docker compose start proofchain
```

Both artifacts must be taken from the same stopped state and restored together. After any restore,
run the orphan report before returning the system to service: a clean report is the evidence that the
two stores agree.

## Known limitations

- The aggregate `/actuator/health` response includes the names of its two groups (`liveness`, `readiness`). Spring Boot offers no switch to suppress them; they name probes that are already public and reveal nothing about the deployment.
- A request with an unsupported HTTP method returns the sanitized generic `500` problem document rather than `405`. This is pre-existing behaviour of the global exception handler for every path, not something the actuator introduces.
- The single application instance keeps no shared state, but no clustering, no reverse proxy and no TLS termination are delivered; they are outside the released scope.
