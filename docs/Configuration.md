# Configuration baseline

This guide describes the frozen `1.0.0` runtime configuration. It covers only configuration; feature behavior is documented in the feature guides linked from the [documentation home](./README.md).

## Release baseline

| Element | Frozen value |
| --- | --- |
| Maven project version | `1.0.0` |
| Java | 25 |
| Spring Boot | 4.0.7 |
| Maven Wrapper | 3.9.9 |
| Database | PostgreSQL 18.4, the only supported dialect |
| Schema authority | Flyway, with Hibernate `ddl-auto: validate` |

The published OpenAPI `info.version` is the same `1.0.0` string, and a test compares it with the Maven coordinate so the artifact and the documented contract cannot drift apart. No Git tag and no GitHub Release are created by this change.

PostgreSQL is a deliberate deviation from the supplied ITS rubric. It is not redesigned away: it requires an explicit Project Owner or teacher acknowledgement.

## Profiles

Exactly three profiles exist. Everything they do not override lives in `application.yml`, which is always loaded.

| Profile | Purpose | Datasource host | Evidence storage root |
| --- | --- | --- | --- |
| `local` | host execution, the default | `${DB_HOST:localhost}` | `./storage` |
| `container` | Docker Compose execution, set by the image | `${DB_HOST:postgres}` | `/var/lib/proofchain/storage` |
| `test` | automated tests | overridden by Testcontainers | isolated temporary directory |

`local` and `container` declare exactly the same five keys, so the only intended differences between running on the host and running under Compose are the database host and the storage root. `test` additionally lowers the BCrypt cost and generates its own JWT secret. There is no cloud or production profile: none was delivered, so none is invented.

The `container` profile is what the application image activates. It points at the Compose service hostname and at the canonical container storage root, and it is also the profile that routes the authentication audit trail to standard output instead of to a local file, because the container root filesystem is read-only. See [container operations](./Operations.md).

## Secrets

Secrets come only from the environment. The application never generates a secret, never defaults one and never falls back to a weaker one.

- `PROOFCHAIN_JWT_SECRET` has no default. A missing, non-Base64 or short value stops the application.
- Datasource credentials have no usable default in the runtime profiles.
- `.env.example` documents every supported variable with placeholders only. No credential is committed.
- The `test` profile generates a fresh 48-byte secret for every test context from `${random.value}${random.value}`, so no fixed secret ships in the repository.

## Startup validation

Configuration is bound through strongly typed `@ConfigurationProperties` with jakarta validation. Binding failures fail the application context; nothing degrades to insecure operation and nothing is only logged.

| Condition | Bound by |
| --- | --- |
| Missing, blank, malformed or weak (< 32 byte) JWT secret | `JwtProperties` |
| Zero or negative access-token TTL | `JwtProperties` |
| Password length range inverted or non-positive | `PasswordSecurityProperties` |
| BCrypt strength outside 4..31 | `PasswordSecurityProperties` |
| Missing datasource URL, username or password in `local`/`container` | `RuntimeDatasourceProperties` |
| Non-PostgreSQL JDBC URL | `RuntimeDatasourceProperties` |
| Unusable evidence storage root — a file, a symbolic link or an unwritable directory | `EvidenceStorageProperties` and the filesystem adapter |
| Non-positive or inconsistent multipart limits | `MultipartLimitsProperties` |
| Non-positive header, form, swallow, parameter, part or connector timeout values | `HttpRequestLimitsProperties` |
| Connection acquisition, validation, startup or lock timeout out of range | `DatabaseTimeoutProperties` |
| Non-finite graceful-shutdown budget | `GracefulShutdownProperties` |
| Wildcard or malformed CORS origin | `CorsProperties` |
| Flyway checksum or version drift | Flyway with `validate-on-migrate: true` |

## Request limits

The 50 MB evidence file limit is preserved, with a 51 MB multipart envelope that leaves room for framing and the JSON metadata part. Bounded non-file limits are added only where the servlet stack supports them cleanly:

| Key | Default |
| --- | --- |
| `server.max-http-request-header-size` | `16KB` |
| `server.tomcat.max-http-form-post-size` | `256KB` |
| `server.tomcat.max-swallow-size` | `2MB` |
| `server.tomcat.max-parameter-count` | `256` |
| `server.tomcat.max-part-count` | `16` |
| `server.tomcat.max-part-header-size` | `8KB` |

## Timeouts and shutdown

Every wait is finite and no retry is introduced.

| Budget | Default | Meaning |
| --- | --- | --- |
| `spring.datasource.hikari.connection-timeout` | `10000` ms | connection acquisition; exhaustion fails the request |
| `spring.datasource.hikari.validation-timeout` | `5000` ms | must be smaller than the acquisition budget |
| `spring.datasource.hikari.initialization-fail-timeout` | `1` ms | proves one connection at startup, then fails closed |
| `spring.datasource.hikari.connection-init-sql` | `SET lock_timeout = '10s'` | bounded PostgreSQL lock wait on every pooled connection |
| `server.tomcat.connection-timeout` | `20s` | connector read budget |
| `server.tomcat.keep-alive-timeout` | `20s` | idle keep-alive budget |
| `spring.lifecycle.timeout-per-shutdown-phase` | `30s` | graceful shutdown drain window |

`server.shutdown` is `graceful`, so in-flight requests are drained within a finite window instead of being cut off or waited on forever.

## Health and readiness

Spring Boot Actuator is present for one purpose only: giving Docker Compose a readiness signal. Its surface is closed by default and opened one path at a time.

| Key | Value | Effect |
| --- | --- | --- |
| `management.endpoints.access.default` | `none` | no endpoint is reachable unless it is granted explicitly |
| `management.endpoints.access.max-permitted` | `read-only` | no actuator endpoint can ever accept a write |
| `management.endpoints.web.exposure.include` | `health` | health is the only endpoint published over HTTP |
| `management.endpoints.web.discovery.enabled` | `false` | the `/actuator` index enumerates nothing |
| `management.endpoint.health.show-details` | `never` | responses carry a bare status |
| `management.endpoint.health.show-components` | `never` | contributor names are never rendered |
| `management.health.defaults.enabled` | `false` | only the contributors readiness needs exist |

`/actuator/health`, `/actuator/health/liveness` and `/actuator/health/readiness` are unauthenticated because an orchestrator must read them before any operator exists. Every other actuator path — environment, beans, metrics, heap dump, config props, loggers, thread dump, mappings, info, shutdown and the individual health components — is not published at all: an authenticated ADMIN receives `404` and an anonymous caller the standard `401` problem document. No actuator path appears in the OpenAPI document.

The readiness group aggregates `readinessState`, `db` and the project's own `evidenceStorage` contributor, so readiness is green only once startup completed, PostgreSQL answers, Flyway and Hibernate validation passed, and the evidence storage root is provably writable. Liveness reports only the application's liveness state, so a database outage removes the instance from service without restarting a healthy process. Group membership is validated at startup, so a renamed contributor fails the context instead of silently shrinking readiness.

The full runtime contract, the operational commands and the recorded certification evidence are in [container operations](./Operations.md).

## CORS

Cross-origin access is default-deny. `proofchain.cors.allowed-origins` is empty, and an empty allowlist resolves to no CORS configuration at all, so no `Access-Control-Allow-Origin` header is ever produced. The allowlist accepts only explicit `scheme://host[:port]` entries; `*`, a wildcard subdomain, a non-HTTP scheme or an entry with a path stops the application. Credentials are never allowed, because the API authenticates with a bearer token.

## Bootstrap administrator

The bootstrap administrator remains opt-in, disabled by default and idempotent: it does nothing when an active ADMIN already exists, and it refuses to run with an incomplete configuration. See [Authentication](./Auth.md) for the operational contract.

## Environment variables

Every supported variable, with safe placeholders and comments, is listed in [.env.example](../.env.example). Load it into the shell before starting the application:

```bash
set -a
source .env
set +a
```

Docker Compose reads `.env` automatically and needs no export step.

The complete table. **Sensitive** marks a value that is a real secret and must never be committed, logged or pasted into an issue. **Profile** names where the variable applies: *compose* variables are consumed by `compose.yml` itself, *application* variables by the running application.

| Variable | Required | Default | Sensitive | Profile | Validation | Safe example |
| --- | --- | --- | --- | --- | --- | --- |
| `POSTGRES_DB` | no | `proofchain` | no | compose | none | `proofchain` |
| `POSTGRES_USER` | no | `proofchain` | no | compose | none | `proofchain` |
| `POSTGRES_PASSWORD` | **yes** | none | **yes** | compose | Compose refuses to start without it (`${VAR:?}`) | `<local-only-secret>` |
| `POSTGRES_PORT` | no | `5432` | no | compose | must be a free host port | `5432` |
| `APP_PORT` | no | `8080` | no | compose | must be a free host port | `8080` |
| `PROOFCHAIN_TMPFS_SIZE` | no | `256m` | no | compose | Docker size syntax | `256m` |
| `PROOFCHAIN_BUILD_CA_FILE` | no | `/dev/null` | no | compose, build only | readable PEM file; never reaches the runtime image | `/path/to/corporate-ca.crt` |
| `SPRING_PROFILES_ACTIVE` | no | `local` | no | application | one of `local`, `container`, `test` | `local` |
| `DB_HOST` | **yes** in `local` and `container` | none | no | application | reachable host; `localhost` under `local`, `postgres` under `container` | `localhost` |
| `DB_PORT` | **yes** in `local` and `container` | none | no | application | port number | `5432` |
| `DB_NAME` | **yes** in `local` and `container` | none | no | application | non-empty | `proofchain` |
| `DB_USERNAME` | **yes** in `local` and `container` | none | no | application | non-empty; startup fails when blank | `proofchain` |
| `DB_PASSWORD` | **yes** in `local` and `container` | none | **yes** | application | non-empty; startup fails when blank | `<local-only-secret>` |
| `PROOFCHAIN_STORAGE_ROOT` | no | `./storage` (`local`), `/var/lib/proofchain/storage` (`container`) | no | application | must be, or be creatable as, a writable directory; startup fails otherwise | `./storage` |
| `PROOFCHAIN_JWT_SECRET` | **yes** | none | **yes** | application | standard RFC 4648 Base64 decoding to ≥ 32 bytes; never generated, never defaulted | `<base64-of-at-least-32-random-bytes>` |
| `PROOFCHAIN_JWT_ACCESS_TOKEN_TTL` | no | `PT30M` | no | application | ISO-8601 duration, strictly positive | `PT30M` |
| `PROOFCHAIN_MAX_FILE_SIZE` | no | `50MB` | no | application | positive data size | `50MB` |
| `PROOFCHAIN_MAX_REQUEST_SIZE` | no | `51MB` | no | application | positive data size; keep above the file limit for metadata and framing | `51MB` |
| `PROOFCHAIN_MAX_HTTP_HEADER_SIZE` | no | `16KB` | no | application | finite and strictly positive | `16KB` |
| `PROOFCHAIN_MAX_FORM_POST_SIZE` | no | `256KB` | no | application | finite and strictly positive | `256KB` |
| `PROOFCHAIN_MAX_SWALLOW_SIZE` | no | `2MB` | no | application | finite and strictly positive | `2MB` |
| `PROOFCHAIN_MAX_PARAMETER_COUNT` | no | `256` | no | application | positive integer | `256` |
| `PROOFCHAIN_MAX_PART_COUNT` | no | `16` | no | application | positive integer | `16` |
| `PROOFCHAIN_MAX_PART_HEADER_SIZE` | no | `8KB` | no | application | finite and strictly positive | `8KB` |
| `PROOFCHAIN_HTTP_CONNECTION_TIMEOUT` | no | `20s` | no | application | positive duration | `20s` |
| `PROOFCHAIN_HTTP_KEEP_ALIVE_TIMEOUT` | no | `20s` | no | application | positive duration | `20s` |
| `PROOFCHAIN_SHUTDOWN_TIMEOUT` | no | `30s` | no | application | positive, at most 5 minutes | `30s` |
| `PROOFCHAIN_DB_CONNECTION_TIMEOUT_MS` | no | `10000` | no | application | 250..60000 milliseconds | `10000` |
| `PROOFCHAIN_DB_VALIDATION_TIMEOUT_MS` | no | `5000` | no | application | milliseconds, smaller than the acquisition budget | `5000` |
| `PROOFCHAIN_DB_STARTUP_TIMEOUT_MS` | no | `1` | no | application | non-negative; `1` proves one connection then fails closed | `1` |
| `PROOFCHAIN_DB_LOCK_TIMEOUT` | no | `10s` | no | application | positive, at most 5 minutes | `10s` |
| `PROOFCHAIN_PASSWORD_MIN_LENGTH` | no | `12` | no | application | positive, not greater than the maximum | `12` |
| `PROOFCHAIN_PASSWORD_MAX_LENGTH` | no | `128` | no | application | positive, not smaller than the minimum | `128` |
| `PROOFCHAIN_BCRYPT_STRENGTH` | no | `12` | no | application | 4..31 | `12` |
| `PROOFCHAIN_CORS_ALLOWED_ORIGINS` | no | empty (deny all) | no | application | comma-separated explicit origins; a `*` entry is rejected at startup | `https://console.example.org` |
| `PROOFCHAIN_BOOTSTRAP_ADMIN_ENABLED` | no | `false` | no | application | boolean; when `true` the other three become required | `false` |
| `PROOFCHAIN_BOOTSTRAP_ADMIN_USERNAME` | only when the bootstrap is enabled | empty | no | application | must satisfy the operator username rules | `proofchain-admin` |
| `PROOFCHAIN_BOOTSTRAP_ADMIN_EMAIL` | only when the bootstrap is enabled | empty | no | application | valid email | `admin@example.org` |
| `PROOFCHAIN_BOOTSTRAP_ADMIN_PASSWORD` | only when the bootstrap is enabled | empty | **yes** | application | must satisfy the configured password policy | `<local-only-secret>` |

Every invalid value in the *Validation* column stops startup. The application never falls back to a default for a
secret, never lowers a limit to make a request succeed, and never starts in a degraded mode.

Symptom-driven help for a configuration that will not start is in [Troubleshooting](./Troubleshooting.md).
