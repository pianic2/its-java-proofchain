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
