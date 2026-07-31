# Sprint 5 certification — operational custody workflows

Status: AUTOMATED VERIFICATION COMPLETE
Independent AI review: PENDING (Sprint 5 cumulative review runs before the final delivery gate)
Project Owner delegation: RECORDED
Human validation: NOT PERFORMED

This record covers IJPC-163 through IJPC-169 on branch `ijpc-7-sprint-5-operational-workflows`.
Only results actually observed on this machine are recorded. Nothing below is projected.

## Verification environment

| Item | Value |
| --- | --- |
| OS | Ubuntu 24.04.4 LTS, kernel 6.18.5, x86_64 |
| JDK | OpenJDK 25.0.3+9 |
| Maven | Maven Wrapper resolving Apache Maven 3.9.9 |
| Docker | 29.3.1 |
| Docker Compose | v5.1.1 |
| PostgreSQL | 18.4 (`postgres:18.4-trixie`) via Testcontainers |
| Timezone | UTC |
| Locale | not set in the environment; the canonical protocol is locale-independent by construction |

## Certified API surface

Exactly five operational routes exist. Verified by `Sprint5ContractWebMvcIT` against the rendered
OpenAPI document, and by grep over the controller sources:

```http
POST  /api/v1/evidences/{evidenceId}/transfer
PATCH /api/v1/evidences/{evidenceId}/metadata
POST  /api/v1/evidences/{evidenceId}/verify-integrity
POST  /api/v1/evidences/{evidenceId}/seal
POST  /api/v1/evidences/{evidenceId}/release
```

No alias, generic command endpoint, generic event append, bulk command, asynchronous job,
reopen, unseal, arbitrary evidence PATCH, status PATCH, idempotency-key path or repair endpoint
exists. `EvidenceReadWebMvcIT` pins the full evidence-scoped path set and the exact HTTP method
of each route, so adding one silently fails the build.

## Commands executed and results

| Command | Result |
| --- | --- |
| `./mvnw spotless:check` | PASS |
| `./mvnw --batch-mode --no-transfer-progress clean verify` (run 1) | **BUILD SUCCESS** |
| `./mvnw --batch-mode --no-transfer-progress clean verify` (run 2, no changes between runs) | **BUILD SUCCESS** |
| `git diff --check` | clean |
| `git status --porcelain` before run 1 vs after run 2 | byte-identical |

No test was weakened, skipped, reordered or disabled to obtain either pass. The JaCoCo threshold
was never lowered.

### Test totals

| Suite | Tests | Failures | Errors | Skipped |
| --- | --- | --- | --- | --- |
| Surefire | 291 | 0 | 0 | 2 |
| Failsafe | 336 | 0 | 0 | 0 |

The two skipped Surefire tests are pre-existing and predate Sprint 5.

### Coverage

| Counter | Covered / total | Ratio |
| --- | --- | --- |
| LINE | 3763 / 4038 | 93.19% |
| BRANCH | 1008 / 1279 | 78.81% |
| INSTRUCTION | 16907 / 18156 | 93.12% |
| COMPLEXITY | 1195 / 1474 | 81.07% |
| METHOD | 795 / 827 | 96.13% |
| CLASS | 207 / 207 | 100.00% |

Configured gate: LINE >= 0.51. Met with a wide margin and left unchanged.

### Focused Sprint 5 integration totals

| Suite | Tests |
| --- | --- |
| `EvidenceOperationalCommandFoundationIT` | 7 |
| `EvidenceCommandConcurrencyIT` | 2 |
| `CustodyTransferServiceIT` | 15 |
| `CustodyTransferWebMvcIT` | 10 |
| `CustodyTransferConcurrencyIT` | 5 |
| `EvidenceMetadataUpdateWebMvcIT` | 51 |
| `EvidenceMetadataUpdateServiceIT` | 14 |
| `EvidenceMetadataUpdateConcurrencyIT` | 7 |
| `EvidenceIntegrityVerificationServiceIT` | 33 |
| `EvidenceIntegrityVerificationWebMvcIT` | 15 |
| `EvidenceIntegrityVerificationConcurrencyIT` | 5 |
| `EvidenceLifecycleWebMvcIT` | 15 |
| `EvidenceLifecycleConcurrencyIT` | 6 |
| `Sprint5ContractWebMvcIT` | 6 |

## Lock order

The frozen order is `PESSIMISTIC_READ CustodyCase` then `PESSIMISTIC_WRITE DigitalEvidence` then
the append. It is enforced by construction rather than by convention: `lockEvidence` accepts only
the `CaseReadLock` token that `lockCase` produces, so acquiring the evidence lock first does not
compile.

`EvidenceOperationalCommandFoundationIT` proves the order rather than the mere presence of locks.
While a command waits on an externally held case lock, a `FOR UPDATE NOWAIT` probe on the evidence
row succeeds, showing no evidence lock was taken first. While it waits on an externally held
evidence lock, the same probe on the case row fails with SQLSTATE `55P03`, showing the case read
lock is already held. Waiters are observed through `pg_stat_activity`. No test uses `Thread.sleep`
as a synchronization or proof mechanism.

Operators and memberships are read and re-checked inside the transaction but are never
pessimistically locked, because locking an operator row would serialize unrelated cases that
merely share a member.

## Migrations

| Version | Kind | Purpose |
| --- | --- | --- |
| V1–V3 | SQL | operators, custody cases and memberships, digital evidence |
| V4–V5 | SQL | custody events, chain head anchor |
| V6 | Java | deterministic genesis-event backfill |
| V7 | SQL | database-level lifecycle transition guard |

Flyway from an empty database and the upgrade path are exercised by the Testcontainers suite on
every `clean verify`; `CustodyEventBackfillMigrationIT` additionally clears the trailing history
and replays V6 and V7. V7 is replay-safe (`CREATE OR REPLACE FUNCTION`, `DROP TRIGGER IF EXISTS`).
No historical migration was edited.

## Known limitations

These are real and are stated rather than smoothed over.

1. **Zero-byte file reported as technical inability.** A readable but zero-byte evidence file is
   reported as `evidence-file-unavailable` (500) instead of a completed `valid=false` result,
   because the frozen `IntegrityVerifiedPayload` requires a positive `fileSize`. Truncation to
   zero bytes is therefore classified as technical inability rather than observed corruption.
   Fixing it would change a frozen Sprint 4 payload and was deliberately not attempted here.
2. **Surrogate validation asymmetry.** The operational `reason` is validated for well-formed
   UTF-16 and fails closed with `400`. Descriptive metadata fields are not. A `title` containing
   an unpaired surrogate therefore mutates the aggregate, fails inside canonicalization and
   surfaces as a generic `500` that the controllers do not declare. The transaction rolls back
   fully, so no partial state is committed. Extending validation is new behaviour rather than
   reconciliation and is deferred to Sprint 6 hardening.
3. **Integrity verification stamps `updatedAt`** although it is not a mutating command. Recorded
   explicitly so it is not mistaken for a pure read.
4. **Case-closure concurrency rows.** The "command versus case closure" concurrency rows have no
   dedicated deterministic test, because closure runs through `CustodyCaseService` on a different
   lock path. Closed-case rejection is covered non-concurrently for every Sprint 5 command.
5. **Docker Compose smoke** is deferred to IJPC-171, where the application image and the Compose
   runtime first exist. The repository currently ships a PostgreSQL-only `compose.yml`.

## Corrections made during certification

- `ADR-006` claimed the Sprint 4/5 lock order was "custody case, then operators, then evidence".
  No operator or membership row is ever pessimistically locked and the IJPC-163 contract forbids
  it. The statement was corrected in place so an auditor reading ADR-006 is not misled.
- `EvidenceCommandReason` accepted unpaired surrogates, turning malformed client input into a
  sanitized 500 after the aggregate had been mutated. It now fails closed with 400 before any
  lock is taken.
- The Sprint 3 OpenAPI endpoint allowlist had not been extended when Sprint 4 added its
  endpoints, leaving the published branch head red. Fixed and extended to pin each route's exact
  HTTP method.

## Sprint 6 boundary

Sprint 5 ends here. Not in scope and deliberately absent: release packaging and version freeze,
Dockerfile and Compose runtime certification, Flyway empty/upgrade certification as a standalone
gate, filesystem hardening and orphan reporting, dependency and secret scanning, Postman
delivery artifacts, the final documentation suite, demo materials and the 1.0.0 release
candidate. Those are IJPC-170 through IJPC-178.
