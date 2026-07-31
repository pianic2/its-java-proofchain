# ProofChain 1.0.0 — security, dependency and bounded performance review

Subtask: IJPC-174 — final regression, security and dependency hardening.
Branch: `ijpc-8-sprint-6-final-delivery`.

This document is release evidence. It records **what was actually executed**, with the observed output, and
**what could not be executed**, with the precise blocking reason. Nothing here is inferred, projected or assumed.
Where a check could not run, it is marked **NOT EXECUTED** and the exact command a reviewer must run in a network
where the required data source is reachable is given verbatim.

## 0. Execution environment

| Property | Value |
| --- | --- |
| OS / kernel | Linux 6.18.5 x86_64 (Ubuntu 24.04.4 LTS) |
| CPU | Intel(R) Xeon(R) @ 2.10 GHz, 4 vCPU |
| Memory | 15 GiB total |
| JDK | OpenJDK 25.0.3+9 (Temurin-equivalent Ubuntu build) |
| Maven | Apache Maven 3.9.9 (project wrapper) |
| Docker | Server 29.3.1, API 1.54 |
| Database under test | `postgres:18.4-trixie` via Testcontainers 1.21.4 |
| Canonical command | `./mvnw --batch-mode --no-transfer-progress clean verify` |

Network reachability observed from this environment, probed directly:

| Endpoint | Result |
| --- | --- |
| `https://repo1.maven.org/maven2/` | HTTP 200 — reachable |
| `https://services.nvd.nist.gov/rest/json/cves/2.0` | connection refused at the egress proxy |
| `https://jeremylong.github.io/DependencyCheck/suppressions/publishedSuppressions.xml` | connection refused at the egress proxy |
| `https://www.cisa.gov/sites/default/files/feeds/known_exploited_vulnerabilities.json` | HTTP 403 through the egress proxy |

## 1. Secret scan — EXECUTED, clean

### Scope

Three surfaces were scanned:

1. **Working tree, tracked files** — all 378 files tracked at scan time, via `git grep -I` (binary files excluded).
2. **Git history** — all 145 commits, every added line across `git log --all -p`, plus the set of every filename
   ever added on any branch.
3. **Build and runtime artifacts present in the working tree** — the untracked `auth.log` produced by the test
   suite (3 784 lines at scan time), plus `target/` and the ignored-file listing.

### Patterns searched

* Key material: `-----BEGIN … PRIVATE KEY`, `-----BEGIN RSA`, any `-----BEGIN` armour block.
* Cloud and vendor tokens: `AKIA[0-9A-Z]{16}`, `aws_secret_access_key`, `AIza[0-9A-Za-z_-]{35}`.
* Source-forge and chat tokens: `ghp_`, `gho_`, `ghs_`, `ghu_`, `github_pat_`, `xox[baprs]-`, `sk-…`.
* Compact JWTs: `eyJ…​.eyJ…​.` three-segment pattern.
* Credential-shaped assignments: `(password|passwd|pwd|secret|token|apikey|api_key|credential|private_key|passphrase)`
  followed by `=` or `:` and a quoted literal of six characters or more, in the tree and on every added line in history.
* Secret-bearing filenames ever committed: `*.env`, `*.pem`, `*.p12`, `*.jks`, `*.key`, `*.keystore`, `*.pfx`,
  `*.ppk`, `id_rsa`, `id_dsa`, `id_ecdsa`, `id_ed25519`, `credentials`, `secret(s)`.

### Result

**No credential, token, private key or password was found in the tree, in history or in any artifact.**
Three near-matches were reviewed and are not credentials:

| Hit | Verdict |
| --- | --- |
| `AuthenticationService.DUMMY_PASSWORD = "proofchain-dummy-credential"` | Not a credential. It is the constant fed to BCrypt when the operator is unknown or the supplied password exceeds the 72-byte BCrypt limit, so that failed logins cost the same as successful ones. It never authenticates anything: its hash is computed at startup and only ever compared against a rejected candidate. |
| `.env.example` (`POSTGRES_PASSWORD=<local-only-secret>`, `PROOFCHAIN_JWT_SECRET=<base64-…>`) | Placeholders. Every value is a literal angle-bracket token, not a usable secret. The file is the documented template; `.env` itself is git-ignored. |
| `src/test/resources/application-test.yml` (`password: test`) | Test-scoped only. It is the credential of the ephemeral Testcontainers PostgreSQL instance created and destroyed inside the build. The JWT secret in the same file is generated per context from `${random.value}${random.value}` and is never committed. |

The only filename ever committed that matches the secret-filename patterns is `.env.example`, which is the template
described above.

`auth.log` — the untracked audit file the `local`/host profile writes — was parsed line by line. **All 3 784 lines present at scan time
match the fixed audit schema exactly** (`event=… operatorId=… username=… role=… outcome=… reason=… path=…`);
there are zero occurrences of `password`, `Bearer ` or a `eyJ` JWT prefix. It is covered by the `*.log` rule in
`.gitignore` and is not tracked.

**Conclusion: no revocation or rotation is required and no history rewrite is proposed.**

## 2. CycloneDX SBOM — EXECUTED

Added as the **`release-sbom`** profile in `pom.xml`. The profile is inactive unless requested, declares no lifecycle
execution, and pins `org.cyclonedx:cyclonedx-maven-plugin:2.9.3`. It contributes nothing to `clean verify`, adds no
runtime dependency, and touches no GitHub Actions workflow.

```
./mvnw -Prelease-sbom org.cyclonedx:cyclonedx-maven-plugin:2.9.3:makeAggregateBom
```

Observed output: `CycloneDX: Creating BOM version 1.6 with 115 component(s)` — `BUILD SUCCESS`.

| Property | Value |
| --- | --- |
| Artifacts | `target/sbom/proofchain-1.0.0-sbom.json` (300 563 bytes), `target/sbom/proofchain-1.0.0-sbom.xml` (270 978 bytes) |
| CycloneDX spec version | 1.6 |
| Components | **115** |
| Components carrying a SHA-256 | 115 of 115 |
| Root component | `pkg:maven/it.itsprodigi/proofchain@1.0.0?type=jar` |
| Test-scope artifacts | excluded — they are not shipped |

The 115 components correspond exactly to the 115 `compile` + `runtime` artifacts in `mvn dependency:tree`.
The SBOM is informative release evidence; it is regenerated on demand and is not committed.

## 3. OWASP Dependency-Check — **NOT EXECUTED** (environment-blocked)

Added as the **`dependency-check`** profile, pinning `org.owasp:dependency-check-maven:12.2.2`, with
`failBuildOnCVSS=7` so that any applicable High or Critical finding fails the run. Like the SBOM profile it is
inactive by default, declares no lifecycle execution and does not touch GitHub Actions.

The command **was actually run** in this environment and **failed**. The real, unedited output:

```
[WARNING] An NVD API Key was not provided - it is highly recommended to use an NVD API key ...
[ERROR] Error updating the NVD Data; the NVD returned a 403 or 404 error
        org.owasp.dependencycheck.data.update.exception.UpdateException
          at org.owasp.dependencycheck.data.update.NvdApiDataSource.processApi(NvdApiDataSource.java:385)
[WARNING] Failed to update hosted suppressions file, results may contain false positives ...
        org.owasp.dependencycheck.data.update.exception.UpdateException: Failed to update the hosted suppressions file
[INFO] Updating CISA Known Exploited Vulnerability list: https://www.cisa.gov/.../known_exploited_vulnerabilities.json
[ERROR] org.owasp.dependencycheck.utils.ForbiddenException: https://www.cisa.gov/.../known_exploited_vulnerabilities.json
        - Server status: 403 - Server reason: Forbidden
[ERROR] Unable to continue dependency-check analysis.
[INFO] BUILD FAILURE
[ERROR] Failed to execute goal org.owasp:dependency-check-maven:12.2.2:check (default-cli) on project proofchain:
        Fatal exception(s) analyzing proofchain: One or more exceptions occurred during analysis:
[ERROR]   UpdateException: ... 403 - Forbidden
[ERROR]   NoDataException: No documents exist
```

### Status

**NOT EXECUTED. No vulnerability analysis was performed and no CVE was classified.**

Blocking reason, precisely: this environment's egress policy blocks `services.nvd.nist.gov`,
`jeremylong.github.io` and `www.cisa.gov`. Dependency-Check therefore cannot build or refresh its local
vulnerability database, and it aborts with `NoDataException: No documents exist` rather than producing an empty or
partial report.

**No claim of "zero vulnerabilities" is made here, and none may be inferred from this document.** The NVD check was
deliberately not disabled and the CVSS gate was deliberately not lowered to make the goal "succeed"; doing so would
have produced a report with no data behind it.

### What a reviewer must run

On a machine or runner where the NVD API is reachable, with an NVD API key (strongly recommended — the update is
extremely slow without one):

```
export NVD_API_KEY=<your-nvd-api-key>
./mvnw -Pdependency-check \
       -DnvdApiKey=$NVD_API_KEY \
       org.owasp:dependency-check-maven:12.2.2:check
```

The HTML and JSON reports appear in `target/dependency-check/`. The run fails on any finding at CVSS ≥ 7.
**Until that run is green, "no unresolved applicable Critical/High dependency vulnerability remains" is an open
acceptance criterion, not a satisfied one.** Every finding must then be classified by actual applicability and
runtime reachability, and every waiver recorded with factual reasoning, before release.

## 4. Dependency inventory and analysis — EXECUTED

### 4.1 Inventory

`./mvnw dependency:tree` resolved **159 artifacts** before the removal in §4.3 (102 `compile`, 13 `runtime`,
44 `test`) and **158 artifacts** after it (102 `compile`, 13 `runtime`, 43 `test`). The shipped surface — 115
`compile` + `runtime` artifacts — is unchanged. Direct declarations in `pom.xml`:

| Coordinate | Scope | Version source | Why it is present |
| --- | --- | --- | --- |
| `io.jsonwebtoken:jjwt-api` | compile | pinned `0.13.0` | JWT issue/parse API used directly by `JwtTokenService`. |
| `io.jsonwebtoken:jjwt-impl` | runtime | pinned `0.13.0` | JJWT implementation, resolved by `ServiceLoader` at runtime. |
| `io.jsonwebtoken:jjwt-jackson` | runtime | pinned `0.13.0` | JJWT JSON serializer, resolved by `ServiceLoader` at runtime. |
| `spring-boot-starter-actuator` | compile | managed by parent 4.0.7 | Health, liveness and readiness probes; `EvidenceStorageHealthIndicator`. |
| `spring-boot-starter-data-jpa` | compile | managed | Hibernate, Spring Data JPA, HikariCP, transaction management. |
| `org.postgresql:postgresql` | runtime | managed | JDBC driver, loaded by name at runtime. |
| `spring-boot-starter-flyway` | compile | managed | Flyway auto-configuration; Flyway is the schema authority. |
| `org.flywaydb:flyway-database-postgresql` | compile | managed | PostgreSQL dialect plugin, discovered by `ServiceLoader`. |
| `spring-boot-starter-validation` | compile | managed | Jakarta Bean Validation on request records. |
| `spring-boot-starter-security` | compile | managed | Filter chain, method security, BCrypt. |
| `spring-boot-starter-webmvc` | compile | managed | Servlet stack, embedded Tomcat, JSON binding. |
| `org.springdoc:springdoc-openapi-starter-webmvc-ui` | compile | pinned `3.0.2` | OpenAPI document and Swagger UI. |
| `spring-boot-starter-test` | test | managed | JUnit 5, AssertJ, Mockito, Spring test context. |
| `spring-boot-starter-webmvc-test` | test | managed | MockMvc slice support. |
| `org.testcontainers:postgresql` | test | pinned `1.21.4` | `PostgreSQLContainer` and, transitively, the Testcontainers core. |

Build plugins, all version-pinned:

| Plugin | Version |
| --- | --- |
| `com.diffplug.spotless:spotless-maven-plugin` | 3.6.0 (Palantir Java Format 2.78.0) |
| `org.jacoco:jacoco-maven-plugin` | 0.8.15 |
| `org.apache.maven.plugins:maven-surefire-plugin` | 3.5.4 |
| `org.apache.maven.plugins:maven-failsafe-plugin` | 3.5.4 |
| `org.springframework.boot:spring-boot-maven-plugin` | managed by parent 4.0.7 |
| `org.cyclonedx:cyclonedx-maven-plugin` | 2.9.3 — `release-sbom` profile only |
| `org.owasp:dependency-check-maven` | 12.2.2 — `dependency-check` profile only |

Container base images:

| Image | Where | Role |
| --- | --- | --- |
| `eclipse-temurin:25-jdk` | `Dockerfile` build stage | throwaway build stage; never shipped |
| `eclipse-temurin:25-jre` | `Dockerfile` runtime stage | runtime image, non-root, read-only root filesystem |
| `postgres:18.4-trixie` | `compose.yml` and `PostgreSqlIntegrationTest` | database, production Compose and tests use the same tag |
| `testcontainers/ryuk:0.12.0` | Testcontainers runtime | test-container reaper; test-time only |

### 4.2 `dependency:analyze`

`./mvnw dependency:analyze` reported 14 "unused declared" dependencies. **Thirteen of them are false positives** —
bytecode analysis cannot see aggregator POMs, `ServiceLoader` artifacts or reflectively loaded drivers — and were
kept. The reasoning per group:

* **Spring Boot starters** (`actuator`, `data-jpa`, `flyway`, `validation`, `security`, `webmvc`, `test`,
  `webmvc-test`) are dependency aggregators containing no classes at all. Nothing can ever reference them directly,
  and removing one would delete its entire transitive tree. Kept.
* **`jjwt-impl`, `jjwt-jackson`** are declared `runtime` precisely because no compile-time reference exists: JJWT
  discovers them through `ServiceLoader`. Removing them compiles fine and breaks every token operation at runtime.
  Kept.
* **`org.postgresql:postgresql`** is the `runtime` JDBC driver, loaded by class name. Kept.
* **`flyway-database-postgresql`** is discovered by Flyway through `ServiceLoader`. Kept.
* **`springdoc-openapi-starter-webmvc-ui`** contributes auto-configuration and the Swagger UI webjar; the
  `io.swagger.v3.oas.annotations.*` types the controllers do reference arrive transitively through it. Kept.

`dependency:analyze` also reported "used undeclared" for a long list of transitive Spring, Hibernate and Jackson
artifacts. Those were **not** promoted to direct declarations: they are managed by the Spring Boot parent BOM and
declaring them individually would pin a second, competing version surface. That is a deliberate decision, not an
oversight.

### 4.3 The single removal

`org.testcontainers:junit-jupiter` **was removed**, and it is the only dependency change in this subtask.

Proof it is unused:

* No test imports anything from `org.testcontainers.junit.jupiter`; the sole Testcontainers import in the entire test
  tree is `org.testcontainers.containers.PostgreSQLContainer`.
* No test is annotated `@Testcontainers`, so the JUnit 5 extension that module exists to provide is never activated.
  `PostgreSqlIntegrationTest` starts the single shared container from a `static` initializer instead.
* It is not a `ServiceLoader` or reflection artifact — its extension is reachable only through the `@Testcontainers`
  annotation.
* The Testcontainers core remains on the test classpath through
  `org.testcontainers:postgresql → jdbc → database-commons → testcontainers`, verified in the published
  `database-commons-1.21.4.pom`.

`./mvnw clean verify` is green after the removal.

**No dependency was upgraded.** No version was changed anywhere in this subtask. Nothing was upgraded merely because
a newer version exists, and no applicable vulnerability could be established (see §3), so the "update only for
applicable vulnerabilities" rule produced zero updates.

## 5. Timezone and locale run — EXECUTED, found and fixed a real defect

The full canonical suite was executed a second time under a hostile timezone and locale, injected at JVM start so
that `Locale.getDefault()` and `TimeZone.getDefault()` are actually affected in every forked test JVM:

```
export _JAVA_OPTIONS="-Duser.timezone=Pacific/Kiritimati -Duser.language=tr -Duser.country=TR"
./mvnw --batch-mode --no-transfer-progress clean verify
```

`Pacific/Kiritimati` is UTC+14 (dates roll a day ahead of UTC); `tr-TR` is the locale whose dotless `ı` breaks
locale-sensitive case conversion.

### First run: BUILD FAILURE — two genuine failures

```
[ERROR] OperatorAdminServiceTest.createsEveryFrozenRoleAsAnActiveOperatorWithEncodedPassword:116
        expected: "user-admın"  but was: "user-admin"
[ERROR] OperatorAdminServiceTest.sameRoleAndStatusAreIdempotentAndSuspendedOrDisabledCanReactivate:213->operator:394
        IllegalArgumentException: username must be 3 to 64 lowercase letters, digits, dots, underscores or hyphens
[ERROR] Tests run: 443, Failures: 1, Errors: 1, Skipped: 4
```

Diagnosis: **production code was correct; the tests were not.** `OperatorNormalizer`, `DigitalEvidenceNormalizer`,
`ProtocolValidation`, `DatabaseTimeoutProperties` and `OperatorAdminService` all already pass `Locale.ROOT` to every
case conversion. `OperatorAdminServiceTest` computed its *expected* values with the bare, locale-sensitive
`String.toLowerCase()`, so under `tr-TR` it expected `user-admın` and built an invalid username from
`"SUSPENDED".toLowerCase()`.

### Fix

Three call sites in `OperatorAdminServiceTest` and one in `EvidenceHashingAndStorageKeyTest` now pass `Locale.ROOT`.
No production code changed, no assertion was weakened, and no test was disabled or renamed.

### Second run: BUILD SUCCESS

After the fix the whole suite — Surefire and Failsafe, including every fixed-vector protocol test — passes under
`Pacific/Kiritimati` + `tr-TR`. See §9 for the exact totals.

A durable in-suite guard already existed and still holds:
`CustodyEventProtocolTest.canonicalFormAndHashIgnoreDefaultLocaleAndTimezoneAndReactToEveryFieldChange` swaps the
default `Locale` to `tr-TR` and the default `TimeZone` to `Pacific/Kiritimati`, re-derives the canonical form and the
event hash, and restores them. The fixed vectors are therefore protected inside the default build, not only by this
one-off run.

## 6. Security and log audit — EXECUTED

### 6.1 Application logging surface

Every logging call site in `src/main/java` was inventoried — 23 statements across 10 classes. They are, without
exception, structured `key=value` lines built from identifiers, enum names, counts and sequence numbers. No call site
passes a password, a token, reason text, metadata prose, payload JSON, a canonical preimage, file bytes, a hash, a
storage key or a filesystem path.

The `AUTH_AUDIT` logger emits exactly one fixed schema and sanitises the username, reason and path fields through
`LogValueSanitizer`. `GlobalExceptionHandler` logs an unexpected error's *class name* at `ERROR` and the throwable
itself only at `DEBUG`, which is below the configured `INFO` root level, so no stack trace reaches the production log.
The operational services log `exception.getClass().getSimpleName()` as a `failureCategory`. Those simple names are a
deliberate operational classification in a server-side log; they are never returned to a client (§6.2).

### 6.2 New leak-prevention tests

`src/test/java/it/itsprodigi/proofchain/SecurityLogAndResponseLeakAuditIT` (3 tests, Failsafe, PostgreSQL
Testcontainers). It attaches a Logback `ListAppender` to the two loggers the application owns —
`it.itsprodigi.proofchain` and `AUTH_AUDIT` — at the level production actually runs them at, drives a complete
custody lifecycle with deliberately recognisable canary material, and then fails on any leak.

| Test | What it fails on |
| --- | --- |
| `applicationLogsNeverLeakCredentialsTokensReasonsMetadataPayloadsHashesStorageKeysOrPaths` | The plaintext password, the issued bearer token, the literal `Bearer `, a `$2a$` BCrypt hash or the word `password` appearing in the log; any of the seven canary reason/metadata strings; the stored `payload_json`, or the canonical-preimage field names `"payloadVersion"`, `"previousHash"`, `"contentSha256"`, `"referenceTag"`; the uploaded file bytes; the content or contextual SHA-256; **any** 64-character hexadecimal string; the storage key, the absolute storage root, `content.bin` or `.staging`; and any log event carrying an attached throwable. |
| `problemDetailsNeverExposeStackTracesSqlPersistenceInternalsLockDetailsOrClassNames` | Eleven Problem Detail bodies — duplicate-tag conflict (a real database unique-constraint violation), no-op transfer, ineligible holder, unknown property, malformed JSON, type mismatch, missing auth, invalid token, forbidden, hidden resource, invalid credentials — containing any of 22 forbidden fragments: `it.itsprodigi.proofchain`, `org.springframework`, `org.hibernate`, `org.postgresql`, `jakarta.persistence`, `com.zaxxer.hikari`, stack-frame markers, `select `, `insert into`, `update set`, `delete from`, `constraint`, `sqlstate`, `psqlexception`, `optimisticlocking`, `pessimistic`, `lock_timeout`, `could not execute statement`, `content.bin`, `/tmp/`, `/var/lib/proofchain`, plus the storage key, the storage root, the password, and the substrings `exception`, `throwable`, `stacktrace`. |
| `responsesCarryMinimalSecurityHeadersStayUncacheableAndKeepCorsDefaultDeny` | See §6.3. |

The log test is explicitly **non-vacuous**: before the negative assertions it requires the capture to contain the
real success lines (`Evidence registration result=success`, `Custody transfer result=success`,
`Operational custody command result=success`, `Evidence seal result=success`, `Evidence release result=success`,
`Custody chain verification result=valid`) and the real audit events (`event=LOGIN_SUCCESS`, `event=LOGIN_FAILURE`,
`event=ACCESS_DENIED`, `event=INVALID_TOKEN`) plus the evidence identifier. An empty capture fails the test rather
than passing it silently.

The detector was **mutation-verified**. A deliberate leak was temporarily injected into
`CustodyTransferService` — `LOGGER.info("MUTATION-PROBE reason={}", request.reason())` — and the suite failed with
exactly the intended assertion:

```
[ERROR] SecurityLogAndResponseLeakAuditIT.applicationLogsNeverLeakCredentials...:272
        [operator-supplied reason text and metadata prose must never be logged]
[ERROR] Tests run: 3, Failures: 1, Errors: 0, Skipped: 0
```

The probe was reverted immediately; `src/main` is byte-for-byte unchanged in this subtask.

### 6.3 Headers, cache and CORS

Observed on real responses and asserted by the third test. All observations come from the full Spring Security
filter chain under MockMvc; every header below except `Server` is written by a filter that is genuinely in that
chain, so the evidence is about the application, not about the test harness.

| Header | Observed | Note |
| --- | --- | --- |
| `X-Content-Type-Options` | `nosniff` | asserted on API and download responses |
| `X-Frame-Options` | `DENY` | asserted |
| `X-XSS-Protection` | `0` | Spring Security default; the legacy filter is switched off, which is current best practice |
| `Cache-Control` | contains `no-store` on every authenticated and credential-bearing response (`no-cache, no-store, max-age=0, must-revalidate` in general, `no-store` on the login response) | asserted |
| `Pragma` / `Expires` | `no-cache` / `0` | present |
| `Set-Cookie` | absent | stateless; asserted |
| `Server` | absent | asserted, but **weak evidence**: MockMvc does not run the embedded servlet container, so this says nothing about what a deployed Tomcat emits |
| `Access-Control-Allow-Origin` | absent, including with a hostile `Origin` header | asserted |
| `Access-Control-Allow-Credentials` | absent | asserted |
| `Strict-Transport-Security` | **absent over plaintext HTTP** | see below |

**No HSTS guarantee is claimed.** TLS is terminated outside the application boundary; the container listens on plain
HTTP. Spring Security emits HSTS only on a request it considers secure, so the honest, verifiable property is that
the application does not fabricate an HSTS header on a plaintext request — which is what the test asserts. Enforcing
HSTS is the responsibility of the terminating proxy and is out of scope for this artifact.

CORS remains default-deny and wildcard-free. `CorsPolicyTest` already proves the frozen empty allowlist resolves to
*no* CORS configuration at all, that a widened allowlist can only ever hold explicit origins
(`getAllowedOriginPatterns()` is null, `getAllowedMethods()` never contains `*`) and that credentials are never
allowed. `SecurityBoundaryWebMvcTest` proves no `Access-Control-Allow-Origin` is written for a hostile origin on
public paths, protected paths or preflight. `RuntimeConfigurationValidation` rejects a `*` entry at startup.

### 6.4 Documented observations (not blockers)

1. **Hibernate's own logger.** `org.hibernate.orm.jdbc.error` emits, at `WARN`, the PostgreSQL text of a constraint
   violation — for example `duplicate key value violates unique constraint "uk_digital_evidence_case_reference_tag"`
   together with the conflicting business key. This is Hibernate's logger, not the application's, and it is
   server-side only: the corresponding HTTP response is a generic Problem Detail (proven in §6.2). It contains no
   credential, hash, payload, file byte or filesystem path. Recorded here so the reviewer sees it was found and
   judged, not missed. Suppressing it was not done, because it would also hide genuine diagnostics and would be an
   unrequested configuration change.
2. **`failureCategory` uses exception simple names** in operational service logs. Deliberate, log-only, never
   returned to a client.
3. **Springdoc startup warnings** advise disabling `/v3/api-docs` and `/swagger-ui.html` in production. Both are
   intentionally public in this release; no change was made in this subtask.

## 7. Bounded performance smoke — EXECUTED

`src/test/java/it/itsprodigi/proofchain/BoundedPerformanceSmokeIT`, run through the **`performance-smoke`** profile:

```
./mvnw -Pperformance-smoke -Dit.test=BoundedPerformanceSmokeIT verify
```

It is excluded from the default Failsafe run by an exclusion that names this one file, so the canonical build is
neither slower nor exposed to a machine-dependent step. **No pre-existing test is excluded or weakened by that
arrangement**, and the smoke asserts no timing whatsoever — only correctness invariants.

### Dataset — synthetic only, never real evidence

| Property | Value |
| --- | --- |
| Custody cases | 10 |
| Registered evidences | 50 (5 per case) |
| Custody events | **510** (50 genesis + 450 transfer/metadata commands + 10 from the measured integrity verifications) |
| Small file size | 1 024 B |
| Near-limit file size | 2 096 128 B |
| Upload limit configured **for this context** | 2 097 152 B (2 MiB) |
| Content | deterministic generated bytes; no real evidence data exists in the fixture |
| Seeding | 500 real HTTP calls through MockMvc, 9 887 ms |

The 2 MiB limit is the limit configured for the smoke context, not the 50 MB production default. It is set that way
so the near-limit case exercises the real limit-checking path without pushing a 50 MB multipart through an
in-process MockMvc call. The observed numbers therefore say nothing about a 50 MB upload.

### Observations — 10 repetitions per operation, wall-clock milliseconds

| Operation | min | median | max |
| --- | --- | --- | --- |
| Login (`POST /api/v1/auth/login`) | 4 | 4 | 8 |
| Case list (`GET /api/v1/cases?page=0&size=20`) | 5 | 6 | 38 |
| Evidence list (`GET /api/v1/cases/{id}/evidences?page=0&size=20`) | 6 | 8 | 21 |
| Timeline (`GET /api/v1/evidences/{id}/events?page=0&size=20`) | 10 | 12 | 45 |
| Chain verification (`POST /api/v1/evidences/{id}/verify-chain`) | 10 | 13 | 23 |
| Integrity verification (`POST /api/v1/evidences/{id}/verify-integrity`) | 13 | 15 | 19 |

Invariants asserted: exactly 10 cases and 50 evidences persisted; the custody-event count stays inside the bounded
range; the near-limit upload is stored whole (`content.bin` is exactly 2 096 128 B); and **all 50 hash chains still
verify as valid** after the full command load.

### Disclaimer

**These numbers are informative and establish no SLA, no production capacity and no throughput guarantee.** They are
a single observation of one Spring context talking in-process through MockMvc to one PostgreSQL Testcontainer on a
4-vCPU shared machine, with no HTTP stack, no network, no concurrency and no warm-up control. They exist so that a
correctness, memory, timeout or resource-exhaustion defect that only appears with a non-trivial amount of data has
somewhere to surface. No such defect appeared: no error status, no timeout, no pool exhaustion and no chain
corruption was observed.

## 8. Test categorisation

Surefire runs the fast suites; Failsafe runs `**/*IT.java`. That split is unchanged. Every test class is assigned to
exactly one contract category below.

| Category | Runner | Classes |
| --- | --- | --- |
| Unit / domain | Surefire | `OperatorTest`, `CustodyCaseTest`, `DigitalEvidenceTest`, `CustodyEventTest`, `PasswordPolicyTest`, `EvidenceHashingAndStorageKeyTest`, `EvidenceUploadNormalizerTest`, `EvidenceCommandReasonTest`, `EvidenceCommandConflictTranslatorTest`, `EvidenceCommandResponseMapperTest`, `CustodyEventMapperTest`, `LogValueSanitizerTest`, `AuthEventLoggerTest`, `JwtTokenServiceTest`, `AuthenticationServiceTest`, `BootstrapAdminServiceTest`, `OperatorAdminServiceTest`, `CaseAccessServiceTest`, `CaseMembershipServiceTest`, `CaseMembershipTransactionsTest`, `CustodyCaseServiceTest`, `ResponsibleCaseManagerGuardTest`, `CustodyEventQueryServiceTest`, `CustodyChainVerifierTest`, `CustodyChainVerificationServiceTest`, `EvidenceIntegrityVerificationServiceTest`, `EvidenceOperationalCommandTransactionTest`, `OrphanFileReportServiceTest`, `EvidenceStorageHealthIndicatorTest` |
| Canonical protocol and fixed vectors | Surefire | `CustodyEventProtocolTest`, `CustodyEventDocumentationVectorTest` |
| MVC and serialization contract | Surefire | `AuthControllerWebMvcTest`, `OperatorControllerWebMvcTest`, `CaseControllerWebMvcTest`, `CaseMembershipControllerWebMvcTest`, `GlobalExceptionHandlerWebMvcTest`, `OpenApiIntegrationTest`, `ActuatorExposureWebMvcTest` |
| MVC and serialization contract | Failsafe | `EvidenceRegistrationWebMvcIT`, `EvidenceReadWebMvcIT`, `CustodyTransferWebMvcIT`, `EvidenceMetadataUpdateWebMvcIT`, `EvidenceIntegrityVerificationWebMvcIT`, `EvidenceLifecycleWebMvcIT`, `CustodyEventReadWebMvcIT`, `CustodyChainVerificationWebMvcIT`, `Sprint5ContractWebMvcIT` |
| Method security and security boundary | Surefire | `SecurityBoundaryWebMvcTest`, `JwtAuthenticationFilterTest`, `CorsPolicyTest`, `EvidenceOperationalCommandSecurityTest`, `EvidenceOperationalAuthorizationMatrixTest` |
| Method security and security boundary | Failsafe | `AuthenticationVerticalSliceIT`, `OperatorSecurityVerticalSliceIT`, `DatabaseBackedAuthenticationIT`, `AuthenticationFlowIT`, **`SecurityLogAndResponseLeakAuditIT`** (new) |
| Repository and database invariant | Failsafe | `OperatorRepositoryIT`, `OperatorOptimisticLockIT`, `ActiveAdminLockIT`, `CustodyCaseRepositoryIT`, `CustodyCaseLockIT`, `DigitalEvidenceRepositoryIT`, `CustodyEventRepositoryIT` |
| Storage integration | Surefire | `FileSystemEvidenceStorageTest`, `FileSystemEvidenceStorageHardeningTest` |
| Flyway migration | Surefire | `MigrationGovernanceTest` |
| Flyway migration | Failsafe | `EmptyDatabaseCertificationIT`, `BaselineUpgradeCertificationIT`, `CustodyEventBackfillMigrationIT`, `LegacyStateRejectionMigrationIT`, `MigrationFailureCertificationIT` |
| Full Spring / Testcontainers integration | Failsafe | `DatabaseBootstrapIT`, `BootstrapAdminIT`, `OperatorAdministrationIT`, `CustodyCaseApplicationIT`, `CustodyEventAppenderIT`, `CustodyTransferServiceIT`, `EvidenceMetadataUpdateServiceIT`, `EvidenceIntegrityVerificationServiceIT`, `EvidenceSealServiceIT`, `EvidenceReleaseServiceIT`, `EvidenceOperationalCommandFoundationIT` |
| Deterministic concurrency | Failsafe | `AuthenticationConcurrencyIT`, `LastActiveAdminConcurrencyIT`, `CaseMembershipConcurrencyIT`, `CustodyTransferConcurrencyIT`, `EvidenceMetadataUpdateConcurrencyIT`, `EvidenceIntegrityVerificationConcurrencyIT`, `EvidenceLifecycleConcurrencyIT`, `EvidenceCommandConcurrencyIT`, `CustodyChainVerificationConcurrencyIT` |
| Rollback / failure injection | Failsafe | covered inside `EvidenceRegistrationWebMvcIT`, `CustodyEventAppenderIT`, `EvidenceOperationalCommandFoundationIT`, `MigrationFailureCertificationIT` (Mockito spy-driven failures at aggregate mutation, event append, flush, storage finalization and transaction completion) |
| Release baseline and documentation audit | Surefire | `ReleaseBaselineTest`, `ContainerRuntimeBaselineTest`, `DocumentationLinkAuditTest`, `ConfigurationBindingTest`, `ConfigurationStartupFailureTest`, `ProfileConfigurationTest`, `JwtConfigurationTest` |
| Bounded performance smoke (profile-gated, informative) | Failsafe, `performance-smoke` only | **`BoundedPerformanceSmokeIT`** (new) |

Non-test support types (`PostgreSqlIntegrationTest`, `OperationalCommandTestSupport`, `CustodyEventFixtures`,
`MigrationSchemaHarness`, `MigrationInventory`, `MigrationHistoryAssertions`, `SchemaInvariants`,
`BaselineReconstruction`, `CertifiedBaseline`, `LegacyDataFixture`, `ExceptionFixtureController`,
`ValidationFixtureRequest`) carry no `@Test` method.

### No timing sleeps in concurrency proofs — verified

A repository-wide search for `Thread.sleep`, `TimeUnit.*.sleep`, `Awaitility` and bare `sleep(` across
`src/test/java` and `src/main/java` returns **exactly one hit, and it is a comment** in
`CustodyChainVerificationConcurrencyIT` stating that the test uses a `CyclicBarrier` and no `Thread.sleep`.
Synchronisation is done with `CountDownLatch`, `CyclicBarrier` and `OperationalCommandTestSupport.awaitLockWaiters`,
which polls `pg_stat_activity` for real PostgreSQL lock waiters rather than waiting a fixed interval.

### Skipped tests

Four Surefire tests are skipped by JUnit `Assumptions` in `FileSystemEvidenceStorageTest` and
`FileSystemEvidenceStorageHardeningTest`. They assert behaviour on unreadable and non-writable directories and abort
because the build runs as `root`, for whom POSIX permission bits are not enforced. This is pre-existing, is an
environment property rather than a product defect, and no test was disabled in this subtask.

### Observation for the reviewer

Seven `*Test` classes — `ActuatorExposureWebMvcTest`, `OpenApiIntegrationTest`, `SecurityBoundaryWebMvcTest`,
`AuthControllerWebMvcTest`, `CaseControllerWebMvcTest`, `CaseMembershipControllerWebMvcTest`,
`OperatorControllerWebMvcTest` — extend `PostgreSqlIntegrationTest` and therefore run against a Testcontainer inside
Surefire. Strictly, "Surefire for fast tests" is loosened by them. They were **not** renamed: renaming a test class
is exactly the kind of change the subtask forbids, and the container is shared across the whole JVM so the marginal
cost is small. Flagged here for a Project Owner decision rather than changed unilaterally.

## 9. Gates

Two consecutive canonical clean verifications on the final commit state, plus the hostile-locale runs:

| Run | Command | Result |
| --- | --- | --- |
| Baseline, before any change | `./mvnw --batch-mode --no-transfer-progress clean verify` | BUILD SUCCESS, 2:23 |
| Locale/timezone, before the fix | same, under `Pacific/Kiritimati` + `tr-TR` | **BUILD FAILURE** — 1 failure, 1 error (§5) |
| Locale/timezone, after the fix | same, under `Pacific/Kiritimati` + `tr-TR` | BUILD SUCCESS, 2:25 |
| **Canonical run 1, final state** | `./mvnw --batch-mode --no-transfer-progress clean verify` | **BUILD SUCCESS, 2:37** |
| **Canonical run 2, final state** | `./mvnw --batch-mode --no-transfer-progress clean verify` | **BUILD SUCCESS, 2:23** |

Both consecutive runs were executed on the final source state, after `./mvnw spotless:apply`. Only this timing row
was edited afterwards; no source, test, POM or configuration file changed between those two runs and the delivered
state.

Totals on the final state:

| Metric | Before this subtask | After |
| --- | --- | --- |
| Surefire | 53 classes, 443 tests, 0 failures, 0 errors, 4 skipped | 53 classes, **443** tests, 0 failures, 0 errors, 4 skipped |
| Failsafe | 45 classes, 357 tests, 0 failures, 0 errors, 0 skipped | 46 classes, **360** tests, 0 failures, 0 errors, 0 skipped |
| JaCoCo bundle LINE | 91.62 % | **91.60 %** (4 164 / 4 546) |
| JaCoCo bundle BRANCH | 77.87 % | 77.68 % (1 211 / 1 559) |
| JaCoCo gate | LINE COVEREDRATIO ≥ 0.51 | unchanged, ≥ 0.51 |

The 4 skipped Surefire tests are the pre-existing POSIX-permission assumptions described in §8; no test was disabled
in this subtask. The +3 Failsafe tests are `SecurityLogAndResponseLeakAuditIT`.

JaCoCo remains at the frozen `BUNDLE` / `LINE` / `COVEREDRATIO` ≥ **0.51** gate. It was not lowered and no
application class was excluded to inflate the metric — the plugin configuration contains no `<excludes>` element at
all. Reports are produced under `target/site/jacoco/` as release evidence and are not committed.

`git diff --check` passes with no whitespace error.

GitHub Actions was not modified. `.github/workflows/quality.yml` is byte-for-byte unchanged.

## 10. Open items for the Project Owner

1. **Dependency-Check has not run.** §3 is the only unmet acceptance criterion in this subtask. The profile is in
   place and pinned; the run must be repeated where the NVD API is reachable before release, and its findings
   classified and waived or fixed.
2. The seven Testcontainers-backed `*Test` classes noted in §8 may deserve a rename to `*IT` in a follow-up.
