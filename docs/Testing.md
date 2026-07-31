# Testing

How ProofChain `1.0.0` is tested, how to reproduce the quality gate, and what the numbers mean. Every figure below
comes from an actual `clean verify` run on the release baseline, not from an estimate.

## The canonical command

```bash
./mvnw --batch-mode --no-transfer-progress clean verify
```

This is the only quality gate. It runs, in order: Spotless format check (`validate`), compilation, Surefire, packaging,
Failsafe integration tests, the JaCoCo report and the JaCoCo coverage check. GitHub Actions provisions Temurin Java 25
and invokes exactly this command; CI never runs `spotless:apply` and never modifies sources.

Useful variants:

```bash
./mvnw spotless:apply                                   # fix formatting locally before committing
./mvnw spotless:check                                   # check formatting without changing files
./mvnw --batch-mode test                                # Surefire only, no Docker needed for most classes
./mvnw --batch-mode -Dtest=CustodyChainVerifierTest test
./mvnw --batch-mode -Dit.test=EvidenceOperationalCommandFoundationIT verify
./mvnw -Pperformance-smoke -Dit.test=BoundedPerformanceSmokeIT verify   # informative only, gates nothing
```

## Results on the release baseline

Executed on this repository at the `1.0.0` state:

| Suite | Classes | Tests | Failures | Errors | Skipped |
| --- | --- | --- | --- | --- | --- |
| Surefire | 53 | **443** | 0 | 0 | **4** |
| Failsafe | 47 | **377** | 0 | 0 | 0 |

Result: **BUILD SUCCESS**.

JaCoCo, bundle `proofchain`, 228 analyzed classes:

| Counter | Covered / total | Ratio |
| --- | --- | --- |
| INSTRUCTION | 18 797 / 20 469 | 91.83 % |
| LINE | 4 167 / 4 546 | **91.66 %** |
| BRANCH | 1 213 / 1 559 | 77.81 % |
| COMPLEXITY | 1 356 / 1 704 | 79.58 % |
| METHOD | 875 / 916 | 95.52 % |
| CLASS | 224 / 228 | 98.25 % |

## Test categories and naming

| Suffix | Runner | Meaning |
| --- | --- | --- |
| `*Test.java` | Surefire | Fast unit, domain, protocol, MVC-slice and configuration tests |
| `*IT.java` | Failsafe | Integration tests against a real PostgreSQL 18.4 through Testcontainers |

Categories in use, all of them present in the repository:

| Category | Examples |
| --- | --- |
| Unit / domain | `OperatorTest`, `CustodyCaseTest`, `DigitalEvidenceTest`, `CustodyEventTest`, `PasswordPolicyTest` |
| Canonical protocol and fixed vectors | `CustodyEventProtocolTest`, `CustodyEventDocumentationVectorTest` |
| MVC and serialization contract | `AuthControllerWebMvcTest`, `GlobalExceptionHandlerWebMvcTest`, `EvidenceLifecycleWebMvcIT`, `Sprint5ContractWebMvcIT` |
| Security boundary | `SecurityBoundaryWebMvcTest`, `JwtAuthenticationFilterTest`, `EvidenceOperationalAuthorizationMatrixTest`, `SecurityLogAndResponseLeakAuditIT` |
| Repository and database invariant | `OperatorRepositoryIT`, `CustodyCaseLockIT`, `DigitalEvidenceRepositoryIT`, `CustodyEventRepositoryIT` |
| Storage | `FileSystemEvidenceStorageTest`, `FileSystemEvidenceStorageHardeningTest` |
| Flyway migration | `MigrationGovernanceTest`, `EmptyDatabaseCertificationIT`, `BaselineUpgradeCertificationIT`, `CustodyEventBackfillMigrationIT`, `LegacyStateRejectionMigrationIT`, `MigrationFailureCertificationIT` |
| Deterministic concurrency | `CustodyTransferConcurrencyIT`, `EvidenceLifecycleConcurrencyIT`, `EvidenceCommandConcurrencyIT`, `CustodyChainVerificationConcurrencyIT`, `LastActiveAdminConcurrencyIT` |
| Release baseline and documentation audit | `ReleaseBaselineTest`, `ContainerRuntimeBaselineTest`, `DocumentationLinkAuditTest`, `ConfigurationBindingTest` |
| Whole-surface contract | `ApiSurfaceContractIT` |

Support types carry no `@Test` method and are not counted as test classes: `PostgreSqlIntegrationTest`,
`OperationalCommandTestSupport`, `CustodyEventFixtures`, `MigrationSchemaHarness`, `MigrationInventory`,
`MigrationHistoryAssertions`, `SchemaInvariants`, `BaselineReconstruction`, `CertifiedBaseline`, `LegacyDataFixture`,
`ExceptionFixtureController`, `ValidationFixtureRequest`.

## Testcontainers requirements

Integration tests provision their **own** PostgreSQL container. They never use the local Compose database, so a running
`docker compose` stack is neither required nor consulted.

Requirements:

- a reachable Docker daemon (`docker info` must succeed for the user running Maven);
- the ability to pull `postgres:18.4-trixie` and the Testcontainers Ryuk reaper image;
- enough free disk for one PostgreSQL container.

The container is started once from a static initializer in `PostgreSqlIntegrationTest` and shared for the whole JVM.
`org.testcontainers:postgresql` is the only Testcontainers module the project depends on; the JUnit 5 extension module
is deliberately absent because no test is annotated `@Testcontainers`.

## Deterministic concurrency

The concurrency suite contains **no timing sleeps**. Synchronization is built from `CountDownLatch`, `CyclicBarrier`
and `OperationalCommandTestSupport.awaitLockWaiters`, which polls `pg_stat_activity` for real PostgreSQL lock waiters
rather than waiting a fixed interval. A repository-wide search for `Thread.sleep`, `TimeUnit.*.sleep`, `Awaitility` and
bare `sleep(` across `src/main/java` and `src/test/java` returns exactly one hit, and it is a comment stating that a
test uses a `CyclicBarrier`.

Lock **order**, not merely lock presence, is proven: while a command waits on an externally held case lock, a
`FOR UPDATE NOWAIT` probe on the evidence row succeeds (no evidence lock was taken first); while it waits on an
externally held evidence lock, the same probe on the case row fails with SQLSTATE `55P03` (the case read lock is
already held). See `EvidenceOperationalCommandFoundationIT`.

Conflict behaviour is asserted rather than assumed: a losing command surfaces `409`
`custody-event-concurrency-conflict` and there is no silent retry anywhere.

## Rollback and failure-injection testing

Failures are injected with Mockito spies at five points, and each test asserts that no partial state survives:

| Injection point | Where |
| --- | --- |
| Aggregate mutation | `EvidenceOperationalCommandFoundationIT` |
| Custody event append | `CustodyEventAppenderIT`, `EvidenceOperationalCommandFoundationIT` |
| Flush | `EvidenceOperationalCommandFoundationIT` |
| Storage finalization | `EvidenceRegistrationWebMvcIT` |
| Transaction completion | `EvidenceRegistrationWebMvcIT` |

Migration failure is exercised separately by `MigrationFailureCertificationIT`, which proves the application refuses to
start rather than continuing on a partially migrated schema.

## Fixed vectors, timezone and locale

`CustodyEventDocumentationVectorTest` pins the canonical JSON, the hash preimage and the resulting digest
`71bd5e38f56d4a22228532372d058304246ed58e8634b8e58da37fd30e82fd2d` published in
[Custody Events](./Custody-Events.md). Any change to field order, escaping, instant formatting or the domain separator
breaks the build. `CustodyEventProtocolTest` covers the protocol rules themselves, including rejection of unpaired
UTF-16 surrogates.

The suite has been executed under a hostile timezone and locale (`Pacific/Kiritimati`, `tr-TR`). That run found and
fixed two genuine defects before the release; the record is in
[the security and dependency review §5](./release/1.0.0/Security-And-Dependency-Review.md). Reproduce with:

```bash
TZ=Pacific/Kiritimati ./mvnw --batch-mode --no-transfer-progress \
  -Duser.language=tr -Duser.country=TR clean verify
```

## Coverage gate

JaCoCo `0.8.15` is bound to `verify` with a single rule:

| Element | Counter | Value | Minimum |
| --- | --- | --- | --- |
| `BUNDLE` | `LINE` | `COVEREDRATIO` | **0.51** |

The gate has never been lowered and the plugin configuration contains **no `<excludes>` element at all**, so no class
is hidden from the measurement to inflate it. Actual line coverage is 91.66 %, far above the gate.

Reports generated by a successful run:

| Path | Contents |
| --- | --- |
| `target/surefire-reports/` | Per-class Surefire XML and text output |
| `target/failsafe-reports/` | Per-class Failsafe XML plus `failsafe-summary.xml` |
| `target/site/jacoco/index.html` | Browsable coverage report |
| `target/site/jacoco/jacoco.xml` | Machine-readable coverage data |

None of these are tracked in git; `target/` is ignored.

## Dependency and security evidence

- **Secret scan, dependency inventory, log and response leak audit, bounded performance smoke:** executed and recorded
  in [the security and dependency review](./release/1.0.0/Security-And-Dependency-Review.md).
- **CycloneDX SBOM:** available on demand from the pinned `release-sbom` profile.

  ```bash
  ./mvnw -Prelease-sbom org.cyclonedx:cyclonedx-maven-plugin:2.9.3:makeAggregateBom
  ```

- **OWASP Dependency-Check: NOT EXECUTED.** The pinned profile exists, but the NVD data feeds,
  `jeremylong.github.io` and the CISA KEV catalogue are egress-blocked in this environment. **No vulnerability
  analysis has been performed for this release, and no zero-vulnerability claim may be inferred from a green build.**
  A reviewer with NVD access must run:

  ```bash
  ./mvnw -Pdependency-check org.owasp:dependency-check-maven:12.2.2:check
  ```

  An applicable finding at CVSS ≥ 7 fails that run and is a release blocker.

## Known skips and caveats

1. **Four Surefire tests skip.** `FileSystemEvidenceStorageTest` and `FileSystemEvidenceStorageHardeningTest` assert
   behaviour on unreadable and non-writable directories and abort through JUnit `Assumptions` because the build runs as
   `root`, for whom POSIX permission bits are not enforced. This is an environment property, not a product defect. No
   test is `@Disabled`.
2. **`BoundedPerformanceSmokeIT` is excluded from the canonical build** and runs only under the `performance-smoke`
   profile. It asserts no timing, gates nothing and claims no SLA, throughput or capacity.
3. **Seven Testcontainers-backed classes are named `*Test`** (`ActuatorExposureWebMvcTest`, `OpenApiIntegrationTest`,
   `SecurityBoundaryWebMvcTest`, `AuthControllerWebMvcTest`, `CaseControllerWebMvcTest`,
   `CaseMembershipControllerWebMvcTest`, `OperatorControllerWebMvcTest`) and therefore run under Surefire, loosening
   the "Surefire is for fast tests" split. Renaming them is a follow-up decision, not a silent change.
4. **Case-closure concurrency has no dedicated deterministic test**, because closure runs through `CustodyCaseService`
   on a different lock path. Closed-case rejection is covered non-concurrently for every operational command.

## Common failures and what they mean

| Symptom | Cause | Fix |
| --- | --- | --- |
| `Could not find a valid Docker environment` | Docker daemon not reachable | Start Docker; confirm with `docker info`. Not a code defect |
| Failsafe hangs pulling an image | `postgres:18.4-trixie` or the Ryuk image not cached | `docker pull postgres:18.4-trixie` once, then re-run |
| `spotless:check` fails at `validate` | Formatting drift | Run `./mvnw spotless:apply` locally and commit the result |
| `Coverage checks have not been met` | Line coverage below 0.51 | Add tests. Do **not** lower the gate or add `<excludes>` |
| `DocumentationLinkAuditTest` fails | A broken relative link, a missing heading anchor, an ADR missing from or duplicated in the index, or an ADR numbering gap | Fix the link, the heading or `docs/adr/README.md` |
| `ApiSurfaceContractIT` fails | A route, schema, Problem Details type or Postman request drifted from the approved table | Reconcile the change, or add it to the approved surface deliberately |
| `ReleaseBaselineTest` fails | Version, Java version, Spring Boot version or wrapper version drifted | Restore the frozen baseline |
| `MigrationGovernanceTest` fails | A historical migration was edited or numbering broke | Never edit a released migration; add a new one |
| Flyway `ValidateOutput` / checksum error at startup | The database was migrated by a different migration content | Recreate the database or follow the recovery runbook in [Database schema lifecycle](./Database-Schema-Lifecycle.md) |

More symptom-driven diagnosis is in [Troubleshooting](./Troubleshooting.md).

## Related documents

- [Technical report §13](./Technical-Report.md#13-testing-and-coverage)
- [Contributing](../CONTRIBUTING.md) — naming, evidence expectations, the human validation gate
- [Security and dependency review 1.0.0](./release/1.0.0/Security-And-Dependency-Review.md)
- [Sprint 5 certification](./certification/Sprint-5-Certification.md)
