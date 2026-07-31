# ADR-006: Sprint 4 custody events and hash chain

- Status: Accepted
- Date: 2026-07-30
- Scope: Sprint 4 custody-event domain, canonical hashing protocol, append-only persistence, registration genesis and backfill, timeline/detail read APIs, and chain verification

## Context

Sprint 3 made evidence metadata and content reproducible but recorded no history: nothing proved that a registered item had not been quietly re-described, re-attributed or removed from its own narrative. A chain of custody requires an ordered, tamper-evident record of every action on one evidence item, reproducible by a third party from the data alone.

The slice must stay reviewable and time-bounded. It must reuse the ADR-004 case boundary and the ADR-005 evidence aggregate, must not weaken the certified Sprint 3 registration contract, and must not implement the Sprint 5 custody workflows. It must also be honest about what a hash chain without keys or signatures does and does not prove.

## Decisions

### Chain shape and event identity

- Each `DigitalEvidence` owns exactly one independent chain. There is no case-level chain and no cross-evidence linkage: independent chains keep verification bounded and make one corrupt item local.
- Every custody event references exactly one evidence item. There are no case-only events and no nullable evidence association; the composite foreign key `(evidence_id, case_id)` makes an inconsistent pair unstorable.
- `EventType` is frozen at exactly six values: `EVIDENCE_REGISTERED`, `CUSTODY_TRANSFERRED`, `METADATA_UPDATED`, `INTEGRITY_VERIFIED`, `EVIDENCE_SEALED`, `EVIDENCE_RELEASED`.
- `EVIDENCE_REGISTERED` is the sole genesis event: always sequence `1`, always the zero previous hash, always present for every evidence item.
- The acting operator is stored as an immutable UUID reference plus `actorRole`, a historical snapshot of the role held when the event was created. Later promotions, demotions or deactivations never rewrite history, and the domain refuses a snapshot that disagrees with the operator's role at creation time.
- One operation produces one server-generated UTC timestamp at microsecond precision, shared by the aggregate change and its event. Clients never supply event time.
- Payloads form a `sealed` typed hierarchy with one record per event type and frozen Sprint 5 contracts: transfer carries both holders and a reason with distinct holders; metadata update carries a complete before/after snapshot rather than a diff; integrity verification fixes `SHA-256` and requires `valid` to agree with the compared hashes; sealing requires `IN_CUSTODY` to `SEALED`; release requires `IN_CUSTODY` or `SEALED` to `RELEASED` with a null new holder.

### Canonical protocol and hashing

- Payloads are persisted as PostgreSQL `jsonb` for queryability, but hashing never trusts stored formatting. A dedicated canonicalizer rebuilds the exact bytes from typed values, so `jsonb` key reordering cannot change a recomputed hash.
- Canonical JSON is deterministic: ascending lexicographic field order at every level, no whitespace, explicit `null` for every absent optional value, UTF-8 strings with minimal JSON escaping and rejected unpaired surrogates, lowercase canonical UUID text, exact enum constant names, plain decimal integers, and `uuuu-MM-dd'T'HH:mm:ss.SSSSSS'Z'` UTC timestamps independent of the JVM locale and time zone.
- The hash envelope is version 1 and domain-separated: `SHA-256("proofchain:custody-event:v1" + LF + canonicalJson)`, rendered as 64 lowercase hexadecimal characters. This separator makes the digest incompatible with the Sprint 3 contextual hash.
- Genesis uses a literal 64-zero previous hash rather than the text `GENESIS` or `null`, so the previous-hash column keeps one shape and one database check.
- SHA-256 without HMAC, key material or a configurable algorithm is deliberate: verification must remain possible for any auditor holding only the data.
- Sequence numbers start at `1` and are the only ordering authority. Timestamps are informational; ordering never depends on clock behaviour.
- `digital_evidence.custody_event_count` and `custody_chain_head_hash` are the external chain anchor. They advance atomically with the event insert and make tail deletion detectable, which a self-consistent prefix alone would hide.

### Persistence and append discipline

- Flyway owns the schema. `V4` creates `custody_events` with frozen enum, version, positive-sequence, `jsonb`-object and lowercase-hash checks, per-evidence uniqueness of sequence number and event hash, non-cascading foreign keys and the composite case/evidence key. `V5` adds the anchor columns with their checks.
- A PostgreSQL `BEFORE UPDATE OR DELETE` trigger rejects every mutation with SQL state `55000`. Application-level restrictions reinforce it: an `@Immutable` entity with non-updatable columns, a minimal repository interface that publishes only saves, an existence check and deterministic reads, and no public endpoint that writes events.
- `CustodyEventAppender` is the single writer and uses `MANDATORY` transaction propagation, so an event can only be written inside a caller's business transaction and can never commit independently of the change it records.
- Chain writes serialize on a `PESSIMISTIC_WRITE` lock of the evidence row. Same-evidence appends are deterministic and gapless; different-evidence appends proceed in parallel without global serialization.
- The Sprint 4/5 lock order is `PESSIMISTIC_READ` on the custody case, then `PESSIMISTIC_WRITE` on the evidence row. Operators and memberships are read and re-checked under that order but are never pessimistically locked, because locking an operator row would serialize unrelated cases that happen to share a member.

### Registration genesis and migration backfill

- Registration keeps the certified Sprint 3 order — lock, stage, hash, persist, finalize — and inserts the genesis event after the evidence row is flushed and before the file is finalized. Evidence row, event, anchor and final file therefore commit or roll back together, and every controlled storage, database or event failure is compensated by discarding the staged or finalized file without masking the original error.
- The genesis payload is built from persisted, normalized evidence values, not from the raw request, and genesis time must equal the evidence creation time.
- The public registration request and `201` response are unchanged: no event identifier, sequence number or hash was added.
- `V6` is a deterministic Java Flyway migration that gives every legitimate Sprint 3 evidence item a `backfilled = true` genesis event, revalidates an already correct event instead of duplicating it, and fails the whole migration for any ambiguous legacy state. A fresh installation performs no data backfill.
- The backfill has one accepted limitation: no role history exists in the database, so a reconstructed `actorRole` is the uploader's role at migration time.

### Read APIs and verification

- The Sprint 4 surface is exactly `GET /events`, `GET /events/{eventId}` and `POST /verify-chain` on one evidence item. There is no generic append, update, delete, case-level or batch verification, persisted verification history, search, filtering, export or aggregation endpoint.
- Visibility reuses the case boundary: ADMIN globally, otherwise assigned membership, with AUDITOR included. A hidden evidence item is indistinguishable from a missing one, and the event-specific `404` is only reachable after visibility is established, so identifiers cannot be enumerated.
- The timeline is zero-based, defaults to size `50`, caps size at `200`, rejects every `sort` parameter and is ordered strictly by ascending sequence number. Summaries omit payloads; the detail response adds exactly the decoded typed payload.
- `POST /verify-chain` is a read-only command: it appends no event, persists no verification record and mutates nothing. `POST` expresses the command, not a write.
- Verification recomputes every event and reports the **first** violation with a precise reason, the offending event and sequence number, and compact expected/actual values that never contain payload bodies. A completed verification always returns `200`, valid or invalid; only a technical inability to read the data returns a sanitized `500`.
- Verification holds a `PESSIMISTIC_READ` lock on the evidence row so the anchor and the loaded events form one coherent snapshot against a concurrent append.
- Closed cases and every evidence lifecycle state, including `RELEASED`, remain readable and verifiable.

### Accepted deviation: verification transaction is not `readOnly`

`CustodyChainVerificationService.verifyChain` is annotated `@Transactional` **without** `readOnly = true`, while acquiring a `PESSIMISTIC_READ` lock.

Spring's `JpaTransactionManager` translates `readOnly = true` into a read-only JDBC connection, and pgjdbc's default `readOnlyMode=transaction` turns that into a genuinely read-only PostgreSQL transaction. PostgreSQL then rejects `SELECT ... FOR SHARE` with *cannot execute SELECT FOR SHARE in a read-only transaction*, so a `PESSIMISTIC_READ` lock is not executable inside a literally read-only transaction on this stack. The lock is required for the coherent snapshot guarantee above, so the read-only JDBC hint is dropped rather than the lock.

The service still performs no writes: it calls no write repository method, mutates no entity, and integration tests assert that the event count, chain head hash, `updatedAt` and the complete event set are unchanged after verification. The Project Owner has explicitly accepted this deviation.

### Rejected alternatives

- **Generic event CRUD or a manual append endpoint.** Rejected: a chain of custody that a client can write by hand proves nothing, and it would make the append-only guarantee an API convention rather than an invariant.
- **A `CHAIN_VERIFIED` event.** Rejected: verification is a read. Appending an event for every verification would let observation change the chain, grow it without custody meaning, and turn a diagnostic into a write path requiring locks and authorization.
- **A case-level chain.** Rejected: it would couple unrelated evidence items, make verification cost grow with case size and turn one corrupt item into a case-wide failure.
- **A transactional outbox or asynchronous append.** Rejected for this scope: eventual consistency would allow a committed evidence change with no event yet written, exactly the gap the chain must exclude. `MANDATORY` propagation gives atomicity without new infrastructure.
- **Digital signatures, HMAC keys or blockchain anchoring.** Rejected for this scope: they require key management, rotation, custody of the key material and an external service or ledger. Sprint 4 delivers a verifiable, unkeyed chain and states its limits honestly.
- **A configurable hash algorithm or payload schema versioning by convention.** Rejected: explicit `hashVersion` and `payloadVersion` columns pinned to `1`, with an unsupported-version failure reason, are simpler to certify than negotiated algorithms.

## Consequences

Every registered evidence item now has a complete, ordered, tamper-evident custody history that any auditor can recompute from the published canonical rules and fixed vector. Tail deletion, reordering, insertion, re-attribution and payload edits are all detectable, and the database, persistence and API layers each independently refuse mutation. Sprint 5 workflows can append events atomically with their business change without redesigning the protocol.

The design accepts real limits. The chain proves internal consistency, not authorship: an actor able to rewrite the whole database, including both anchor columns, could rebuild a self-consistent chain, and nothing binds an event to a person cryptographically. Verification is synchronous and linear in chain length, with no caching, incremental verification or persisted history. It verifies custody records only and never re-reads the stored file. Backfilled events carry a migration-time role, marked by `backfilled = true`. Five of the six payload types are frozen contracts with no Sprint 4 producer.

Two problem types of the frozen catalogue, `custody-event-concurrency-conflict` (`409`) and `custody-event-persistence-failure` (`500`), are deliberately not wired and not published: Sprint 4 exposes no operational append endpoint, so no request can reach an append conflict, and documenting an error the runtime cannot emit would break documentation/runtime isomorphism. `CustodyEventConcurrencyConflictException` exists and is thrown by the generic append path; Sprint 5 must map it to `409 custody-event-concurrency-conflict` together with the first write endpoint that can reach it.

## Evidence

- [`Custody-Events.md`](../Custody-Events.md) records the event model, typed payloads, canonical rules, the reproducible fixed vector, API contracts, verification semantics, failure reasons and operational limits.
- `V4__create_custody_events.sql`, `V5__add_custody_chain_head.sql` and `V6__backfill_evidence_registration_events.java` define the schema, the anchor, the append-only trigger and the deterministic backfill.
- `CustodyEventProtocolTest` and `CustodyEventDocumentationVectorTest` pin the canonical form and the published fixed vectors, including locale/time-zone independence and per-field hash sensitivity.
- `CustodyEventRepositoryIT`, `CustodyEventAppenderIT` and `CustodyEventBackfillMigrationIT` cover PostgreSQL constraints, the append-only trigger, atomic head advance, rollback, `MANDATORY` propagation, per-evidence locking and the migration paths.
- `CustodyEventReadWebMvcIT`, `CustodyChainVerificationWebMvcIT` and `CustodyChainVerificationConcurrencyIT` cover authorization, anti-enumeration, paging, typed payloads, every corruption reason, OpenAPI parity and the verification snapshot under a concurrent append.
