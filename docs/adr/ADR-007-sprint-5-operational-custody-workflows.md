# ADR-007: Sprint 5 operational custody workflows

- Status: Accepted
- Date: 2026-07-31
- Scope: Sprint 5 operational custody slice — custody transfer, descriptive metadata update, file-integrity verification, sealing and release — together with the shared command foundation, the authorization matrix, the enforced lifecycle graph, the locking and transaction contract, and the reconciled Problem Details catalogue

## Context

Sprint 4 delivered an append-only, hash-linked custody chain but only one producer: the registration genesis event. Five payload records existed as frozen contracts with no runtime that could write them, and [ADR-006](./ADR-006-sprint-4-custody-events-and-hash-chain.md) left an explicit obligation: the first operational write endpoint must map `CustodyEventConcurrencyConflictException` to `409 custody-event-concurrency-conflict`.

Sprint 5 must turn that protocol into an operational surface without redesigning it. A chain of custody is only useful if the actions it records are the actions the system actually performs, so every workflow has to append its event inside the same transaction as the business change, against the same aggregate it just mutated, at the same instant it stamped. The slice must stay reviewable and time-bounded: it adds no new `EventType`, no new payload version, no new evidence status and no new hash.

It must also decide honestly which capabilities are deliberately absent. A generic command endpoint, a manual event append, an arbitrary evidence `PATCH`, an unseal, a bulk transfer or an asynchronous verification job would each be easy to add and would each dissolve a guarantee the previous four sprints paid for.

## Decisions

### Exactly five operational use cases

- The Sprint 5 surface is exactly five routes on one evidence item: `POST /transfer`, `PATCH /metadata`, `POST /verify-integrity`, `POST /seal`, `POST /release`, all under `/api/v1/evidences/{evidenceId}`. Each is a named business operation, not a resource mutation.
- Every command answers `200 OK` with a `Location` header pointing at the appended custody event, `/api/v1/evidences/{evidenceId}/events/{eventId}`. `200` rather than `201` is deliberate: the request acts on an existing evidence item, and the created event is navigational, not the response resource.
- Four commands return the shared `EvidenceOperationResponse` — the complete evidence representation plus the appended event summary. Integrity verification returns the specialized `IntegrityVerificationResponse` because expected and actual hash and size must be comparable side by side; it carries the same event summary and the same `Location`.
- Request documents are strict and closed. Each has a hand-written deserializer that rejects any unknown property, so `additionalProperties` is `false` in fact and not only in the schema. Transfer accepts exactly `newHolderId` and `reason`; seal and release accept exactly `reason`; metadata accepts the fourteen descriptive fields plus `reason`; verification accepts no body at all and ignores one if sent.
- **Rejected: a generic command dispatch endpoint** (`POST /evidences/{id}/commands` with a discriminated body). It would move authorization, lifecycle and payload rules from the type system into a runtime switch, and would make the API surface unbounded from the client's point of view. Five explicit routes are five explicitly certifiable contracts.
- **Rejected: a manual event append endpoint.** Restating ADR-006: a custody chain a client can write by hand proves nothing. Sprint 5 adds producers, not a writing API.
- **Rejected: arbitrary evidence `PATCH`, `PUT` replacement, JSON Patch, JSON Merge Patch and a status `PATCH`.** A status `PATCH` would express the illegal transitions the lifecycle graph forbids; an arbitrary `PATCH` would let one document mix descriptive metadata, holder and lifecycle, three operations with three different authorization rules and three different events.
- **Rejected: unseal, reopen and custody restoration.** `RELEASED` is terminal by decision, not by omission. A route that restores custody would make the terminal state advisory.
- **Rejected: bulk commands, batch or asynchronous verification, idempotency-key processing, client-supplied event payloads, and repair, quarantine or deletion of altered files.** Each either breaks the one-command-one-event correspondence, or gives the system a way to change evidence in response to a finding about that evidence.

### Authorization matrix

`ADMIN` is always allowed globally and is therefore never listed as a member role. Every other role must be an assigned member of the owning custody case. The matrix is data on `EvidenceOperationalCommand`, evaluated once by the shared foundation, and the differences between rows are deliberate:

| Command | ADMIN | Member `CASE_MANAGER` | Member `EVIDENCE_OFFICER` | Member `AUDITOR` |
| --- | --- | --- | --- | --- |
| Transfer | yes | yes | only while current holder | no |
| Metadata update | yes | yes | yes | no |
| Integrity verification | yes | yes | yes | yes |
| Seal | yes | yes | only while current holder | no |
| Release | yes | yes | no | no |

- An `EVIDENCE_OFFICER` may hand over or freeze only what it actually holds, because both acts are custody statements about the holder. It may correct descriptive metadata of any evidence in its case, because that is a documentation act and never a custody statement.
- Release is management-only: ending custody is the one irreversible operational act, so no officer can perform it even on evidence it holds.
- Integrity verification is open to every case member including `AUDITOR`, because re-reading the stored file and recording the result is exactly what an auditor is for. It is the only command an `AUDITOR` can issue.
- Visibility and permission are separated. An evidence item the caller cannot see is reported as `404 resource-not-found`, identically to one that does not exist. Only after visibility is established can a caller receive `403 access-denied`, so `403` never reveals anything a `404` would have hidden.
- Authorization is re-evaluated from committed database state inside the command transaction, after the custody case lock, with the operator entity refreshed. The JWT identifies the caller; PostgreSQL supplies the role, status and membership that actually decide.
- The current-holder gate is evaluated only after the evidence write lock is held, because the holder is not trustworthy until the aggregate is locked.

### Holder eligibility, recovery transfer and no-ops

- A holder candidate must be resolvable by one anti-enumeration query: an assigned member of the owning case, `ACTIVE`, with role `ADMIN`, `CASE_MANAGER` or `EVIDENCE_OFFICER`. Every failing cause — nonexistent operator, non-member, inactive, suspended, disabled, `AUDITOR` — returns the same `409 holder-not-eligible` with the same constant message. A caller cannot use the transfer endpoint to discover that an operator exists.
- A globally privileged `ADMIN` without a membership in that case is **not** a holder candidate. Global read and command authority is not the same as being answerable for physical custody.
- Transfer eligibility-checks only the **target**. The current holder is never re-validated, so an `ADMIN` or a member `CASE_MANAGER` can perform a **recovery transfer** away from a holder that has been suspended, disabled or demoted. Without this, deactivating one operator could strand evidence permanently.
- Self-transfer is a legitimate operation: an eligible caller that is not already the holder may take custody. It is not a special case in the code, only the general rule applied to the caller.
- Transferring to the operator that already holds the evidence is `409 custody-transfer-no-op`. A normalized metadata patch whose complete before and after snapshots are equal is `409 metadata-update-no-op`. Both are decided against the locked aggregate, so a stale client view can never turn into a silent success, a bumped `updatedAt` or an event that records nothing.
- Transfer is **not** a status transition. `IN_CUSTODY` stays `IN_CUSTODY` and `SEALED` stays `SEALED`: sealed evidence remains transferable because a seal freezes content and description, not location.

### Descriptive metadata contract

- The mutable set is exactly fourteen fields: `title`, `description`, `sourceType`, `sourceDescription`, `sourceManufacturer`, `sourceModel`, `sourceSerialNumber`, `sourceLogicalIdentifier`, `acquisitionMethod`, `acquiredAt`, `acquisitionLocation`, `acquisitionToolName`, `acquisitionToolVersion`, `acquisitionNotes`. Identity, lifecycle, holder, uploader, file metadata, both content hashes, the storage key, chain internals and the optimistic version are not modifiable and are rejected as unknown properties.
- **Rejected: an arbitrary metadata map or an extensible attribute bag.** An open map cannot be validated, cannot be normalized, cannot produce a comparable before/after snapshot and would make the `METADATA_UPDATED` payload unverifiable across schema drift.
- Semantics are presence-aware, and presence is tracked separately from value with an `EnumSet` filled from the parsed JSON object. A record with nullable components cannot express the difference between absent and explicitly null, and that difference is the whole contract: **absent** preserves the current aggregate value, **explicit `null`** clears an optional field, **blank optional text** normalizes to `null`, and a required field sent as `null` or blank is a `400`.
- The event payload carries the **complete** before and after snapshot with explicit nulls, never a diff. A diff is only interpretable next to the state it was applied to; a full snapshot pair is self-contained evidence.
- Snapshots are built from the locked aggregate, never from the request, and the after-snapshot is re-read from the aggregate after the mutation, so the recorded state is the state that commits.
- Metadata update requires `IN_CUSTODY`. Sealed evidence cannot be re-described — that is what sealing means — and released evidence cannot be touched at all.

### Operational reason

- Transfer, metadata update, seal and release each require a `reason`: trimmed, non-blank, 1 to 1,000 characters after trimming, Unicode preserved. It is carried into the event payload and is never logged.
- Integrity verification is the deliberate exception: it has no reason and no request body. The system, not the caller, states what was observed.
- The reason is also validated for well-formed UTF-16 and **fails closed** with `400` on an unpaired surrogate. Such a string has no UTF-8 encoding and could not reach the canonical event preimage; rejecting it at the edge keeps malformed client input a `400` instead of an aggregate that was already mutated and a sanitized `500`.

### File-integrity verification

- Verification opens the exact stored file through the hardened storage port and performs **one** streaming pass with a fixed 8 KiB buffer, producing both the SHA-256 digest and the actually observed byte count. Content is never materialized in memory, never reached through a client-supplied path and never resolved through a symbolic link.
- `valid` requires **both** the persisted `contentSha256` and the persisted `fileSize` to equal the observed values. A digest that matches while the byte count does not is `valid = false`, not a partial success.
- The event `fileSize` is the **observed** byte count, not the persisted size, so the event records what was seen rather than what was expected.
- The `IntegrityVerifiedPayload` invariant is `valid implies hashes equal`, relaxed from strict equivalence so that "hashes equal, size differs, therefore invalid" is expressible without changing the frozen payload shape. The specialized HTTP response exposes expected and actual hash and size side by side, which is where a size-only mismatch becomes diagnosable.
- **A completed verification is always a success.** Both conforming and non-conforming results return `200 OK` and append exactly one `INTEGRITY_VERIFIED` event. A non-conforming result is never a `409`, a `422` or a `500`, and it is never a Problem Detail.
- Only a **technical inability** to read the exact bytes is an error. Missing, unreadable, non-regular and symlinked content are indistinguishable and all become `500 evidence-file-unavailable`, with no event appended and nothing changed.
- Nothing is repaired. The persisted hash, the persisted size and the stored bytes are never rewritten, quarantined or deleted in response to a finding. Verification performs no filesystem write and therefore needs no storage compensation, unlike registration.
- There is no persisted verification table, no verification history, no `reason` and no batch or asynchronous mode. The custody chain already is the history.
- Verification is the only command allowed on `RELEASED` evidence, because it is the only one that asserts nothing about custody.

### Lifecycle graph

The graph is exactly:

```text
IN_CUSTODY -> SEALED
IN_CUSTODY -> RELEASED
SEALED     -> RELEASED
```

- Sealing requires `IN_CUSTODY` and requires the **current** holder to still be eligible. A seal freezes the custody chain around whoever holds the item, so freezing around a suspended or disabled holder would make the record permanently wrong; an explicit recovery transfer must come first. Sealing never changes, clears or re-derives the holder.
- Release accepts `IN_CUSTODY` or `SEALED`, and deliberately **does not** require the previous holder to still be eligible. Management must be able to end custody even when a recovery transfer was never performed. The captured previous holder is still recorded in the payload, so the record stays complete.
- Release clears `currentHolder` before the payload is constructed, while the previous holder identifier is already captured in a local variable. The committed aggregate and the appended event therefore cannot disagree, and `newHolderId` is `null` by construction.
- `RELEASED` is terminal for transfer, metadata update, seal and release: the shared foundation refuses every mutating command on released evidence with `409 invalid-evidence-state`, before any workflow body runs. Released evidence nevertheless remains fully readable, downloadable, listable, timeline-visible, chain-verifiable and **file-integrity-verifiable**.
- Case closure is irreversible and blocks all five commands with `409 case-closed`, including verification, because verification appends an event. Reads and chain verification stay available on closed cases.
- [`V7__enforce_evidence_lifecycle_transitions.sql`](../../src/main/resources/db/migration/V7__enforce_evidence_lifecycle_transitions.sql) installs a `BEFORE UPDATE OF status` trigger that raises a `check_violation` for any transition outside the graph. Together with the existing holder/status check from `V3` it makes unsealing, reopening or re-holding released evidence impossible for application code, a repair script or a manual session. The application never reaches it: seal and release validate the source state under the evidence write lock first. It is a last-resort invariant, not a control-flow mechanism, and it is written to be replay-safe.

### Locking, timing and transaction contract

- The shared order is frozen:

  ```text
  PESSIMISTIC_READ CustodyCase -> PESSIMISTIC_WRITE DigitalEvidence -> append event
  ```

- The order is enforced **by construction**, not by convention. `EvidenceCommandLockService.lockEvidence` requires a `CaseReadLock` token, and that token can only be produced by `lockCase`. Inverting the order does not compile.
- ADR-006 originally summarised this order as "custody case, then operators, then evidence". That summary was corrected there, because operator rows and memberships are read and refreshed inside the transaction but are never pessimistically locked: locking an operator row would serialize unrelated cases that merely share a member. Two locks, in one order, enforced by the type system, is the contract.
- The case lock is a **read** lock, so commands on different evidence items in the same open case are not globally serialized; they only prove the case is not closing underneath them. Case closure takes `PESSIMISTIC_WRITE` on the case and therefore genuinely excludes in-flight commands.
- Commands on the **same** evidence item serialize on the evidence write lock, which is also the single serialization point of the custody-event appender, so the chain stays gapless and correctly linked.
- The case lock is never upgraded, at most one evidence row is locked per command, and target operators and memberships are never pessimistically locked. There is no global JVM mutex.
- `DigitalEvidence.version` remains a defensive internal invariant. It is never exposed, never used for API concurrency control and never required from clients; the pessimistic locks are the operational mechanism and optimistic locking is the backstop.
- One command produces **one** server UTC instant, truncated to microseconds, generated after both locks are held. It is the event `occurredAt`, the aggregate `updatedAt`, and — for verification — the response `verifiedAt`. Workflow bodies receive it and cannot invent another.
- Verification stamps that same instant even though it changes no business field, so a verification is visible in `updatedAt` and cannot be confused with a silent read.
- Everything is one transaction: aggregate mutation, event insert, and `custody_event_count` / `custody_chain_head_hash` advancement commit or roll back together. The appender's `MANDATORY` propagation makes it impossible for an event to commit independently of the change it records.
- **No silent retry.** Lock timeouts, deadlocks, query timeouts, optimistic failures and appender collisions are all translated to `CustodyEventConcurrencyConflictException` and surface as `409 custody-event-concurrency-conflict`. Any other data-access failure becomes `500 custody-event-persistence-failure`. A retry would risk appending a second event for one client intent, and the client is the only party that can decide whether repeating a custody statement is correct.
- No workflow is asynchronous, eventually consistent or queued. When the response is written, the event is committed.
- **Rejected: a transactional outbox, a message broker, an async worker or a microservice split.** Each reintroduces the window in which an evidence change exists with no event, which is precisely the gap the chain must exclude.
- **Rejected: cloud object storage, antivirus or malware scanning during verification, and any protocol change** (new `EventType`, new payload version, new hash envelope, signatures). Storage remains the hardened local filesystem adapter of ADR-005, verification remains a pure re-read, and the ADR-006 protocol is untouched.

### Stable API, error and disclosure contract

- The published Problem Details catalogue for this slice contains only types the runtime can actually emit: `validation-error` `400`, `access-denied` `403`, `resource-not-found` `404`, `case-closed` `409`, `invalid-evidence-state` `409`, `holder-not-eligible` `409`, `custody-transfer-no-op` `409`, `metadata-update-no-op` `409`, `custody-event-concurrency-conflict` `409`, `custody-event-persistence-failure` `500`, `evidence-file-unavailable` `500`, `storage-failure` `500`, plus the shared `401` authentication types of ADR-003.
- Sprint 5 discharges the ADR-006 obligation: `custody-event-concurrency-conflict` and `custody-event-persistence-failure` are now wired, reachable and published, because operational append endpoints finally exist.
- An invalid integrity result is **not** in this catalogue and never will be. It is a completed `200`.
- Responses expose no storage key, no absolute path, no `custody_event_count`, no `custody_chain_head_hash`, no optimistic version and no JPA entity. The evidence representation is the same 28-field contract Sprint 3 certified.
- Logging is operational and sanitized: command name, case, evidence, actor, event and sequence identifiers, the verification verdict, and a failure **category** derived from the exception class name. Reason text, request bodies, payload JSON, metadata values, storage keys, absolute paths and full content hashes are never logged.

## Consequences

The custody chain now has five real producers. Every operational act on an evidence item — who took it, what was corrected, whether the bytes still match, when it was frozen and when custody ended — is recorded atomically with the act itself, at the same instant, under the same locks, and is verifiable by the unchanged ADR-006 protocol. A reviewer can read the five routes and the matrix and know the complete set of things the system can do to evidence.

The design accepts real limits, and two are worth stating plainly.

First, a stored file that is readable but **zero bytes** is reported as `500 evidence-file-unavailable` rather than as a completed `valid = false` result. Registration rejects empty content, so a zero-byte stored file is an impossible state rather than an observation — but the deeper reason is that the frozen `IntegrityVerifiedPayload` requires a positive `fileSize`, so a zero-byte observation is not expressible as an event without changing a Sprint 4 protocol contract. Changing that contract was out of scope; the behaviour is documented instead of hidden.

Second, the descriptive metadata fields are **not** UTF-16 surrogate-validated, unlike the operational `reason`. A `title` containing an unpaired surrogate passes DTO and aggregate validation, mutates the locked aggregate, and then fails at canonicalization, surfacing as a sanitized generic `500` for what is really malformed client input. The transaction rolls back completely, so nothing is committed and no event is appended, but the status code is wrong for the cause. The `reason` path already fails closed with `400`; extending the same check to the descriptive fields is a small, contained follow-up and is deliberately not bundled into this contract-reconciliation task.

Beyond those, the slice inherits and keeps the honest limits of its foundations: the chain proves internal consistency and ordering, not authorship; verification is synchronous, per evidence item, and linear in file size; there is no bulk operation, no scheduled re-verification, no notification, no export and no repair path. Concurrency conflicts are reported, never retried, so clients must be prepared to re-issue a command with fresh data.

Sprint 6 owns what remains: a certified release collection and final CI verification, plus any decision to sign events, to add surrogate validation to descriptive metadata, or to widen the payload contract. None of them requires reopening the decisions above.

## Evidence

- [`Operational-Custody-Workflows.md`](../Operational-Custody-Workflows.md) documents the five workflows from zero: purpose, matrix, lifecycle, holder semantics, the metadata field table, exact requests and responses, before/after snapshots, valid and invalid verification examples, lock and transaction sequences, concurrency outcomes, the Problem Details catalogue, anti-enumeration, sanitized logging and focused test commands.
- [`Custody-Events.md`](../Custody-Events.md) remains the authority for the event model, canonical protocol and chain verification, which Sprint 5 consumes unchanged.
- `EvidenceOperationalCommand`, `EvidenceOperationalCommandTransaction`, `EvidenceCommandLockService`, `CaseReadLock`, `EvidenceOperationalAccessService`, `EvidenceCommandReason`, `EvidenceCommandConflictTranslator` and `EvidenceCommandResponseMapper` are the shared foundation; `CustodyTransferService`, `EvidenceMetadataUpdateService`, `EvidenceIntegrityVerificationService`, `EvidenceSealService` and `EvidenceReleaseService` are the five workflow bodies.
- `V7__enforce_evidence_lifecycle_transitions.sql` enforces the lifecycle graph in PostgreSQL.
- `EvidenceOperationalAuthorizationMatrixTest`, `EvidenceOperationalCommandSecurityTest`, `EvidenceOperationalCommandTransactionTest`, `EvidenceCommandReasonTest`, `EvidenceCommandConflictTranslatorTest` and `EvidenceCommandResponseMapperTest` pin the matrix, the lock order, the reason contract, conflict translation and response sanitization.
- `CustodyTransferWebMvcIT`, `EvidenceMetadataUpdateWebMvcIT`, `EvidenceIntegrityVerificationWebMvcIT`, `EvidenceLifecycleWebMvcIT` and `Sprint5ContractWebMvcIT` cover the HTTP contracts, anti-enumeration, the exact route set, the `Location` header, response sanitization and the emitted Problem Details.
- `CustodyTransferConcurrencyIT`, `EvidenceMetadataUpdateConcurrencyIT`, `EvidenceIntegrityVerificationConcurrencyIT`, `EvidenceLifecycleConcurrencyIT` and `EvidenceCommandConcurrencyIT` cover same-evidence serialization, competing case closure and the no-retry conflict contract.
