Status: AUTOMATED VERIFICATION COMPLETE
Independent AI review: COMPLETE
Project Owner delegation: RECORDED
Human validation: NOT PERFORMED
Teacher approval: NOT YET PERFORMED

# ProofChain 1.0.0-rc.1 — certification report

Every row below is one of **PASS** with the observed result, **FAIL** with the observed result, or
**NOT EXECUTED** with the precise blocking reason. Nothing is inferred and nothing is carried over
from an earlier task without being re-observed on the candidate commit.

## Candidate identity

| Property | Value |
| --- | --- |
| Release candidate | ProofChain 1.0.0-rc.1 |
| Maven project version | 1.0.0 |
| Candidate branch | `ijpc-8-sprint-6-final-delivery` |
| Certified commit | `739d980c6256b4b7b321424741aa87808b7d3277` |
| Definitive tag `uf14-final-2026` | NOT created (out of scope for this task) |
| GitHub Release | NOT published (out of scope for this task) |
| Certification pull request | NOT merged |

## Verification environment

| Property | Observed value |
| --- | --- |
| OS | Ubuntu 24.04.4 LTS |
| Kernel | Linux 6.18.5, x86_64 |
| JDK | OpenJDK 25.0.3 (2026-04-21) |
| Maven | Maven Wrapper → Apache Maven 3.9.9 (`8e8579a9e76f7d015ee5ec7bfcdc97d260186937`) |
| Docker | 29.3.1 |
| Docker Compose | v5.1.1 |
| PostgreSQL | 18.4 (`postgres:18.4-trixie`) |
| Timezone | UTC |
| Locale | `LANG` not set in the environment |

No global Maven installation is required; the wrapper resolves its own distribution.

## 1. Build reproducibility — from a separate clean clone

The repository was cloned to a separate directory at the candidate commit and verified there, so the
result does not depend on the working copy.

| Check | Result |
| --- | --- |
| `./mvnw spotless:check` | **PASS** (exit 0) |
| `./mvnw --batch-mode --no-transfer-progress clean verify` — run 1 | **PASS** — BUILD SUCCESS |
| `./mvnw --batch-mode --no-transfer-progress clean verify` — run 2, no modification between runs | **PASS** — BUILD SUCCESS |
| `git status --porcelain` before run 1 vs after run 2 | **PASS** — identical |
| `git diff --check` | **PASS** — clean |

### Test totals (identical across both runs)

| Suite | Tests | Failures | Errors | Skipped |
| --- | --- | --- | --- | --- |
| Surefire | 443 | 0 | 0 | 4 |
| Failsafe | 377 | 0 | 0 | 0 |

The four Surefire skips are POSIX permission assumptions that cannot be falsified while the build
runs as `root`. They are skipped through JUnit `Assumptions` with a stated reason, not disabled.

### Coverage (JaCoCo, gate `COVEREDRATIO >= 0.51`, unchanged, no class exclusions)

| Counter | Covered / total | Ratio |
| --- | --- | --- |
| LINE | 4167 / 4546 | 91.66% |
| BRANCH | 1213 / 1559 | 77.81% |
| INSTRUCTION | 18797 / 20469 | 91.83% |
| COMPLEXITY | 1356 / 1704 | 79.58% |
| METHOD | 875 / 916 | 95.52% |
| CLASS | 224 / 228 | 98.25% |

### Packaged artifact

| Property | Value |
| --- | --- |
| Path | `target/proofchain-1.0.0.jar` |
| Size | 69 284 949 bytes |
| SHA-256 | `d2ba94a420d9f59f63d087ed4cbfd26e6386b0536381ae5f63b58141be3664ce` |

The JAR is **not committed**. It is reproduced by `./mvnw --batch-mode --no-transfer-progress clean package`
from the certified commit and attached to the GitHub Release when one is published.

## 2. Database certification

| Check | Result | Evidence |
| --- | --- | --- |
| Empty PostgreSQL through all migrations V1–V7 | **PASS** | `EmptyDatabaseCertificationIT`, re-run in both clean-clone verifies |
| Flyway validation and Hibernate `ddl-auto=validate` | **PASS** | same suite; startup fails if the mapping and the schema disagree |
| Reconstructible upgrade baselines (V1…V7 + empty) | **PASS** | `BaselineUpgradeCertificationIT`; rows preserved byte for byte, recomputed genesis hash, schema converges on the from-empty shape |
| Migration checksums pinned | **PASS** | `MigrationInventory`; silently editing a migration fails the build |
| Startup failure on invalid migration state | **PASS** | `MigrationFailureCertificationIT` — changed checksum, missing migration, invalid migration, part-way failure, restart after failure |
| Inconsistent legacy state rejected, never guessed | **PASS** | `LegacyStateRejectionMigrationIT`, six distinct reasons; no row invented, altered or deleted |
| No automatic repair / clean / drop / schema generation | **PASS** | `baseline-on-migrate: false`, `validate-on-migrate: true`, `clean-disabled: true`; no `repair`/`clean` call in `src/main`, `compose.yml`, `Dockerfile` or `docker/` |

## 3. Docker Compose certification

Certified under IJPC-171 with captured output (796 lines). Re-verified as present and unchanged on
this commit; the container evidence itself was **not re-executed** on the candidate commit.

| Check | Result |
| --- | --- |
| Image builds from a clean clone | **PASS** (IJPC-171) |
| Database and application health/readiness | **PASS** — readiness DOWN with the database stopped and with an unwritable storage root, UP when operational |
| Non-root process | **PASS** — `uid=10001(proofchain)`, `CapEff 0000000000000000`, `NoNewPrivs: 1` |
| Read-only root filesystem, writable temp and storage mounts | **PASS** — writes to `/`, `/etc`, `/opt/proofchain` rejected; upload lands in the evidence volume with a matching SHA-256 |
| Separate persistent volumes | **PASS** — `proofchain-postgres-data`, `proofchain-evidence-data` |
| Restart persistence | **PASS** — after `down` + `up` without `-v` and after an image rebuild, `verify-chain` valid and Flyway reports version 7 with no re-migration |
| Graceful shutdown | **PASS** — exit code 143, `Graceful shutdown complete`, PostgreSQL cleanly shut down |

## 4. API and workflow certification

| Check | Result |
| --- | --- |
| Endpoint allowlist over the live Spring request mappings | **PASS** — `ApiSurfaceContractIT`, 27 approved operations |
| Generated OpenAPI parity, bearer scheme, Problem Details subset | **PASS** — same suite |
| No internal field leakage in documented schemas | **PASS** — no `version`, `storageKey`, `storagePath`, `custodyEventCount`, `custodyChainHeadHash`, `passwordHash`, `payloadJson`; no persistence entity as a schema |
| Full Postman collection, run twice from a destructive reset | **PASS** — identical totals both runs: 98 requests, 200 assertions, 0 failures |
| Five operational workflows and the positive lifecycle | **PASS** — Sprint 5 suites plus the Compose smoke |
| Upload/download byte parity | **PASS** — client-computed SHA-256, size and exact body equality |
| Valid integrity verification | **PASS** |
| Invalid integrity verification | **PARTIAL** — proven by integration tests; **not reachable from the Postman collection**, because no approved API can alter stored bytes. A manual out-of-band step is documented in `postman/README.md` and `docs/Demo-Guide.md` |
| Timeline and custody-chain verification | **PASS** |
| Case closure and released-evidence immutability plus readability | **PASS** — parameterized terminal matrix |
| Anti-enumeration, role/membership authorization, stable Problem Details | **PASS** |

## 5. Security, storage and resilience

| Check | Result |
| --- | --- |
| Secret scan | **PASS** — 378 tracked files, all commits including every added line and filename, plus the untracked audit log. No credential found; three near-matches reviewed and cleared |
| Dependency analysis | **PASS** — 159 artifacts inventoried; exactly one dependency removed with justification; zero upgrades |
| SBOM | **PASS** — CycloneDX 1.6, 115 components, each with SHA-256, via the pinned lifecycle-detached `release-sbom` profile |
| **OWASP Dependency-Check** | **NOT EXECUTED** — see below |
| Orphan file report | **PASS** — read-only; storage tree and `digital_evidence` rows byte-identical before and after a real run |
| Log and response leak audit | **PASS** — mutation-verified, with non-vacuity assertions |
| Concurrency harness | **PASS** — deterministic latches and barriers; no `Thread.sleep` used as a proof mechanism |
| Rollback harness | **PASS** — parameterized over canonicalization, appender insert, aggregate flush and optimistic conflict |
| Timezone and locale run | **PASS** — after fixing four locale-dependent test call sites found under a Turkish locale |
| `shellcheck` | **NOT EXECUTED** — not installed in this environment |

### OWASP Dependency-Check — NOT EXECUTED

The pinned `dependency-check` profile (`org.owasp:dependency-check-maven:12.2.2`,
`failBuildOnCVSS=7`, lifecycle-detached, GitHub Actions untouched) exists and **was actually run**.
It **failed**: this environment's egress policy blocks `services.nvd.nist.gov`,
`jeremylong.github.io` and `www.cisa.gov`, so Dependency-Check cannot build or refresh its
vulnerability database and aborts with `NoDataException: No documents exist`.

**No vulnerability analysis was performed. No CVE was classified. No claim of "zero
vulnerabilities" is made here, and none may be inferred from this document.**

A reviewer on a network where those hosts are reachable must run:

```bash
./mvnw --batch-mode --no-transfer-progress -P dependency-check verify
```

The unedited failure output is recorded in `Security-And-Dependency-Review.md`. This is the one
acceptance criterion of IJPC-174 that is not met, and it remains open.

## 6. Open items carried into delivery

1. **OWASP Dependency-Check has never been executed** against this codebase. Until it is, the
   release cannot claim a clean dependency posture.
2. **The independent Sprint 4 AI review completed on the third attempt** and returned a verdict of
   *fit to certify*. Its one MAJOR finding — ADR-006 and the custody event guide claimed the
   append-only trigger "rejects every mutation", which is not true of `TRUNCATE` — is resolved in
   commit `b69325e` by correcting the claim. Six MINOR/NOTE findings were accepted without code
   change. Details in `AI-Validation-Record.md`. The corresponding Jira issues can now be
   transitioned; that step still requires a connector-enabled session.
3. **Jira is unreachable from the delivery sessions.** Every task comment and transition is queued
   in `/tmp/proofchain-jira-pending.md` and awaits a connector-enabled session.
4. The known functional limitations are listed in `Known-Limitations.md` and are not repeated here.

## Delegation and validation status

This release was produced through the delegated automated gate. The Project Owner authorized
autonomous AI delivery for this session.

**No human validation was performed.** Independent AI review is complete for the areas recorded
above; teacher approval has not yet been performed. `Human-Validation-Checklist.md` lists what a
human reviewer should confirm before the release is presented.
