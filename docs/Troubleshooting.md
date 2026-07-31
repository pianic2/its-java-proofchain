# Troubleshooting

Symptom-driven diagnosis for ProofChain `1.0.0`. Each entry states what you see, why it happens in this codebase, and
what to do. Where the cause is an environment property rather than a defect, that is said plainly.

Before anything else, confirm the baseline:

```bash
java -version          # must report 25
docker info            # must succeed
./mvnw -v              # must report Apache Maven 3.9.9
```

---

## Java and Maven

**`Fatal error compiling: invalid target release: 25`, or the build uses the wrong JDK.**
Maven is running on an older JDK. `JAVA_HOME` wins over whatever `java` is first on `PATH`:

```bash
export JAVA_HOME=/path/to/jdk-25
export PATH="$JAVA_HOME/bin:$PATH"
./mvnw -v
```

**`./mvnw: Permission denied`.** The executable bit was lost (common after unzipping an archive):
`chmod +x mvnw`.

**The wrapper tries to download Maven and fails.** `.mvn/wrapper/maven-wrapper.properties` pins Maven `3.9.9` by URL
and SHA-256. Behind a proxy, configure Maven's `settings.xml` or set `MAVEN_OPTS` proxy properties. Do not change the
pinned distribution URL or the checksum.

**`spotless:check` fails during `validate`.** Formatting drift. Fix it locally and commit the result:

```bash
./mvnw spotless:apply
```

Never run `spotless:apply` in CI — the workflow is required not to modify sources.

**Dependency resolution fails behind a TLS-inspecting proxy.** For the Docker build only, point
`PROOFCHAIN_BUILD_CA_FILE` at a PEM certificate; Compose passes it as a build secret that is trusted inside the
throwaway build stage and never reaches the runtime image. For host builds, add the certificate to your JDK truststore.

---

## Docker

**`Could not find a valid Docker environment` during `verify`.** Failsafe needs a Docker daemon for Testcontainers.
Start Docker and confirm with `docker info`. This is an environment problem, never a code defect.

**Testcontainers hangs on the first integration test.** It is pulling `postgres:18.4-trixie` and the Ryuk reaper image.
Pre-pull once:

```bash
docker pull postgres:18.4-trixie
```

**`docker compose build` fails resolving Maven Central.** See the proxy note above; use `PROOFCHAIN_BUILD_CA_FILE`.

**`POSTGRES_PASSWORD` / `PROOFCHAIN_JWT_SECRET` "must be set" error from Compose.** `compose.yml` uses
`${VAR:?message}` for both, so Compose refuses to start without them. Create `.env` from `.env.example` and fill in
real values.

**The application container is unhealthy but the logs look fine.** The healthcheck reads
`/actuator/health/readiness`, which is green only once the context is ready, PostgreSQL answers **and** the evidence
root is provably writable. A read-only or wrongly owned evidence volume keeps readiness red even though the process is
running.

**The container restarted and lost data.** It did not restart by itself — there is deliberately **no restart policy**,
so a fail-fast condition stays down and visible. If data is gone, check that `docker compose down -v` was not used: the
`-v` flag destroys both named volumes permanently.

---

## Ports

**`bind: address already in use` on 8080 or 5432.** Another process holds the port. Either stop it or publish
different host ports:

```bash
APP_PORT=8081 POSTGRES_PORT=55432 docker compose up -d
```

The container always listens on `8080` internally regardless of `APP_PORT`.

**Host-mode application cannot reach the database.** With the `local` profile use `DB_HOST=localhost` and the published
`POSTGRES_PORT`. `DB_HOST=postgres` only resolves inside the Compose network and belongs to the `container` profile.

---

## PostgreSQL

**`Connection refused` at startup.** The database is not up yet, or the port is wrong. `docker compose ps` should show
the `postgres` service healthy. Note that `initialization-fail-timeout` is `1` ms by design — one connection is proven
at startup and the application then fails closed rather than starting degraded.

**`FATAL: password authentication failed`.** `DB_PASSWORD` and `POSTGRES_PASSWORD` must match. They are separate
variables and it is easy to change one and not the other.

**A command fails after roughly ten seconds with a lock timeout.** Every pooled connection is initialized with
`SET lock_timeout` (default `10s`, `PROOFCHAIN_DB_LOCK_TIMEOUT`). Another transaction is holding the case or evidence
row. This is intended: a contended command fails in bounded time instead of blocking forever. Retry the request — the
server never retries silently.

**`409 custody-event-concurrency-conflict`.** Two operators acted on the same evidence item concurrently and one lost
the race. Nothing is corrupted; no partial state was committed. Re-issue the request if the intent still applies.

**Very slow queries or exhausted connections.** Connection acquisition is bounded by
`PROOFCHAIN_DB_CONNECTION_TIMEOUT_MS` (default 10 000). Exhaustion fails a request rather than blocking it. Check for
long-running transactions with `pg_stat_activity`.

---

## Flyway and schema

**`Validate failed: Migration checksum mismatch`.** A migration file was edited after it had been applied. Historical
migrations are immutable. Restore the original content and add a new migration instead; `MigrationGovernanceTest`
exists to catch this before it reaches a database.

**`Found non-empty schema(s) without schema history table`.** The database has objects but no Flyway history, and
`baseline-on-migrate` is deliberately `false`. Either point at an empty database or follow the recovery runbook in
[Database schema lifecycle](./Database-Schema-Lifecycle.md). Do not enable baselining to make the error go away.

**`Schema-validation: missing table / wrong column type` from Hibernate.** Flyway did not run, or ran against a
different database than the one JPA is using. Hibernate is `ddl-auto: validate` and will never create or repair schema.

**`clean` is refused.** `clean-disabled: true` is deliberate. Drop and recreate the database explicitly if you really
intend to destroy it.

**Migration failed mid-way.** The application refuses to start rather than continuing on a partially migrated schema
(`MigrationFailureCertificationIT` proves it). Fix the cause, then recreate the database or repair the history
following the runbook.

---

## JWT and authentication

**Startup fails with a JWT secret error.** `PROOFCHAIN_JWT_SECRET` must be standard RFC 4648 Base64 decoding to at
least 32 bytes. There is no default and no fallback. Generate one:

```bash
openssl rand -base64 32
```

**`401 invalid-token` on every request.** The token was signed with a different secret (typically the stack was
restarted with a new `.env`), or it is malformed. Log in again.

**`401 expired-token`.** The access token TTL elapsed — `PROOFCHAIN_JWT_ACCESS_TOKEN_TTL`, default `PT30M`. There is no
refresh token; log in again.

**A previously working token suddenly returns `403`.** Authorization is database-authoritative: role and status are
re-read on every request. The operator was suspended, disabled or had its role changed. This is intended behaviour, not
a caching bug.

**`404` where you expected `403`.** Contextual case access hides inaccessible identifiers as not found so that case
existence is not leaked. If you expect access, check the operator's `CaseMembership`.

**Startup fails on a CORS value.** The allowlist is deny-by-default. An empty value emits no CORS header at all, and a
`*` entry is rejected at startup. Use explicit origins, comma-separated.

---

## Bootstrap administrator

**No operator exists, so no one can log in.** The bootstrap administrator is opt-in and disabled by default. Set all
four variables and restart:

```bash
PROOFCHAIN_BOOTSTRAP_ADMIN_ENABLED=true
PROOFCHAIN_BOOTSTRAP_ADMIN_USERNAME=proofchain-admin
PROOFCHAIN_BOOTSTRAP_ADMIN_EMAIL=admin@example.org
PROOFCHAIN_BOOTSTRAP_ADMIN_PASSWORD=<a password satisfying the policy>
```

**The bootstrap runs but no operator appears.** It is idempotent and does nothing when an active `ADMIN` already
exists. That is the designed behaviour.

**The bootstrap password is rejected.** It must satisfy the configured policy: length between
`PROOFCHAIN_PASSWORD_MIN_LENGTH` (default 12) and `PROOFCHAIN_PASSWORD_MAX_LENGTH` (default 128). See
[Authentication](./Auth.md).

---

## Permissions and storage

**Startup fails because the storage root is unusable.** `PROOFCHAIN_STORAGE_ROOT` must be an existing writable
directory, or creatable as one. There is no fallback and no degraded mode.

**The container cannot write evidence.** The image runs as `10001:10001` on a read-only root filesystem. Only the
evidence volume and the `/tmp` `tmpfs` are writable. If you replaced the named volume with a host bind mount, `chown`
it to `10001:10001`.

**`413 payload-too-large` on registration.** The upload exceeded `PROOFCHAIN_MAX_FILE_SIZE` (default `50MB`) or the
whole multipart request exceeded `PROOFCHAIN_MAX_REQUEST_SIZE` (default `51MB`). Raise both consistently — the request
limit must stay above the file limit to leave room for the JSON metadata part and multipart framing.

**`500 evidence-file-unavailable` on download or integrity verification.** The stored file is missing, not a regular
file, unreadable, resolves outside the storage root, or is exactly zero bytes. The zero-byte case is a
[known limitation](./Technical-Report.md#16-known-limitations-and-future-work): it is reported as a technical inability
rather than as `valid: false`.

**`500 storage-failure`.** A filesystem-level failure such as a full disk. Check free space on the storage volume; see
[Operations](./Operations.md) for disk-full behaviour and recovery boundaries.

**Content exists on disk but no database row references it.** Use the offline, read-only orphan report — it only
reports and never deletes. See [Operations](./Operations.md).

---

## Tests

**`Could not find a valid Docker environment`.** See [Docker](#docker) above.

**Four Surefire tests are skipped.** Expected. `FileSystemEvidenceStorageTest` and
`FileSystemEvidenceStorageHardeningTest` abort POSIX-permission assertions because the build runs as `root`, for whom
permission bits are not enforced. No test is `@Disabled`.

**`Coverage checks have not been met`.** Line coverage fell below the `0.51` gate. Add tests. Do not lower the gate and
do not add JaCoCo `<excludes>`.

**`DocumentationLinkAuditTest` fails.** A relative link points at a missing file, an anchor does not match a heading, an
ADR is missing from or duplicated in `docs/adr/README.md`, or ADR numbering has a gap.

**`ApiSurfaceContractIT` fails.** The live request mappings, the generated OpenAPI document, the Problem Details
catalogue and the Postman collection no longer agree with the approved surface table. Reconcile them deliberately.

**Tests pass locally but fail under a different locale.** Reproduce the hostile run:

```bash
TZ=Pacific/Kiritimati ./mvnw --batch-mode --no-transfer-progress \
  -Duser.language=tr -Duser.country=TR clean verify
```

More detail in [Testing](./Testing.md).

---

## Health probes

**`/actuator/health` returns `404`.** Only `health` is exposed and the actuator discovery index is disabled by design.
The three public paths are `/actuator/health`, `/actuator/health/liveness` and `/actuator/health/readiness`. Anything
else under `/actuator` requires authentication or does not exist.

**Health returns only `{"status":"UP"}` with no components.** Intended. `show-details` and `show-components` are
`never`, so no component, version, driver, URL or path is ever rendered.

**Readiness is `DOWN` while liveness is `UP`.** The process is alive but not serving: either PostgreSQL is not
answering or the evidence storage root is not writable. Those are the only two contributors readiness depends on.

---

## Postman

**Every request returns `401`.** Run the collection in order — the login request stores the bearer token as a
collection variable. Running a single request out of order leaves it unset.

**`baseUrl` does not resolve.** Select the `ProofChain.local` environment and match `baseUrl` to your published
`APP_PORT`.

**The bootstrap login fails.** `bootstrapAdminPassword` in the environment is a **placeholder, not a credential**. It
authenticates nothing until you set the identical string as `PROOFCHAIN_BOOTSTRAP_ADMIN_PASSWORD` in your own untracked
`.env` and restart the stack.

**The collection never reports an `INVALID` integrity verdict.** It cannot: no approved endpoint can alter stored
content and the collection performs no filesystem or database edit. It asserts the invariant that produces the verdict
instead. To observe a real `valid: false`, follow the documented manual out-of-band step in
[the Postman guide](../postman/README.md).

**A request that expects `405` gets `500`.** Known limitation: the catch-all advice translates an unsupported HTTP
method into the sanitized generic `500` on every path except `GET /api/v1/auth/login`, which carries an explicit guard.
See [Technical report §16](./Technical-Report.md#16-known-limitations-and-future-work).

---

## Related documents

- [Operations](./Operations.md) — startup, shutdown, logs, backup and restore, recovery boundaries
- [Configuration](./Configuration.md) — every environment variable with its validation rules
- [Testing](./Testing.md) — the quality gate, categories and reports
- [Database schema lifecycle](./Database-Schema-Lifecycle.md) — migration failure modes and the manual recovery runbook
- [Technical report](./Technical-Report.md) — the complete system and its known limitations
