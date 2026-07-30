# Custody Events

## Purpose and boundary

Sprint 4 implements the custody-event vertical slice: an append-only, hash-linked chain of events for every registered digital evidence item, a deterministic canonical protocol, an immutable read API, and an on-demand chain verification command.

A **custody event** is an immutable factual record that something happened to one evidence item: who acted, in which role, at which server instant, what changed, and how that record links to the event before it. Events are never created, edited or deleted through the public API. The only event the Sprint 4 runtime can produce is the genesis `EVIDENCE_REGISTERED` event written inside the existing evidence-registration transaction.

Exactly three custody-event operations are implemented:

| Method and path | Success | Purpose |
| --- | --- | --- |
| `GET /api/v1/evidences/{evidenceId}/events?page=0&size=50` | `200` | Read a deterministic, sequence-ordered page of custody-event summaries. |
| `GET /api/v1/evidences/{evidenceId}/events/{eventId}` | `200` | Read one custody event including its exact typed payload. |
| `POST /api/v1/evidences/{evidenceId}/verify-chain` | `200` | Recompute and re-link the complete chain of one evidence item. |

`POST /api/v1/cases/{caseId}/evidences` keeps the certified Sprint 3 request and response contract described in [Digital Evidence](./DigitalEvidence.md); Sprint 4 only changes what happens inside its transaction.

Sprint 4 exposes no endpoint for manual event append, event update or deletion, case-level or batch verification, persisted verification history, transfer, metadata update, file-integrity verification, seal, release, search, filtering, export or aggregation.

## Three different hashes

ProofChain now stores three unrelated SHA-256 values. They answer different questions and must not be confused.

| Value | Covers | Answers |
| --- | --- | --- |
| `contentSha256` | The exact uploaded file bytes. | "Is this file still byte-identical to what was registered?" |
| `contextualSha256` | `proofchain:evidence:v1\n{caseId}\n{evidenceId}\n{contentSha256}` | "Does this content belong to this case and this evidence identity?" |
| `eventHash` | The complete canonical custody event, including its `previousHash` link. | "Is the custody history of this evidence complete, ordered and untampered?" |

The first two are documented in [Digital Evidence](./DigitalEvidence.md) and are unchanged. Only the third is a chain: each event hash covers the previous event hash, so altering, reordering, inserting or removing any event invalidates every later link. None of the three is a digital signature, and none proves that the stored file still exists on disk.

## Event model

[`CustodyEvent`](../src/main/java/it/itsprodigi/proofchain/custodyevent/domain/CustodyEvent.java) is a `@Immutable` JPA entity stored in `custody_events`. Every event belongs to exactly one `DigitalEvidence`; there is no case-only event and no nullable evidence association.

| Field | Rules |
| --- | --- |
| `id` | Server-generated UUID v4. Rejected at construction if it is not version 4. |
| `caseId` | The case of the evidence. The composite foreign key `(evidence_id, case_id)` makes an inconsistent pair unstorable. |
| `evidenceId` | The owning evidence item. Immutable. |
| `operatorId` | The acting operator. Immutable; the operator row itself is never rewritten by the event. |
| `actorRole` | The operator's role **at the moment the event was created**, snapshotted as a string. Later role changes never rewrite history. |
| `sequenceNumber` | Positive `BIGINT`, starting at `1` per evidence and advancing by exactly one. Unique per `(evidence_id, sequence_number)`. |
| `eventType` | One of exactly six values, constrained in the database as well as in Java. |
| `occurredAt` | One server-generated UTC instant at microsecond precision. Clients never supply it. |
| `payloadVersion` | Always `1` in this protocol version. |
| `payloadJson` | The canonical typed payload, stored as PostgreSQL `jsonb`. |
| `previousHash` | The preceding event hash, or 64 zeros for the genesis event. |
| `eventHash` | The lowercase SHA-256 of the canonical event. Unique per `(evidence_id, event_hash)`. |
| `hashVersion` | Always `1` in this protocol version. |

The frozen event-type set is:

```text
EVIDENCE_REGISTERED
CUSTODY_TRANSFERRED
METADATA_UPDATED
INTEGRITY_VERIFIED
EVIDENCE_SEALED
EVIDENCE_RELEASED
```

`EVIDENCE_REGISTERED` is the only genesis event: it is always sequence `1` and always carries the zero previous hash. The five remaining types are frozen protocol contracts consumed by the Sprint 5 workflows; the Sprint 4 runtime never produces them, and the read APIs decode them only if such rows exist.

### Actor identity and historical role

`operatorId` is the durable identity link; `actorRole` is a historical snapshot. If an EVIDENCE_OFFICER registers evidence and is later promoted to CASE_MANAGER or deactivated, the stored event keeps `EVIDENCE_OFFICER`. The domain refuses to build an event whose `actorRole` differs from the acting operator's role at creation time, so the snapshot can never be back-dated. Timeline and detail responses expose the stored snapshot, never the operator's current role.

## Typed payloads

Every event type has one frozen payload record under [`custodyevent/protocol`](../src/main/java/it/itsprodigi/proofchain/custodyevent/protocol). The payload hierarchy is a `sealed interface` with exactly six permitted implementations, so an unknown payload type cannot be introduced by accident. Text is trimmed, blank optional text becomes `null`, `reason` is required and 1–1,000 characters, and every hash field must already be 64 lowercase hexadecimal characters.

API responses render payloads with the record component order shown below, and render instants as ISO-8601 UTC text without trailing fractional zeros. The stored and hashed canonical form uses a different, strictly lexicographic order and always writes six fractional digits, as described in the next section.

`EVIDENCE_REGISTERED` — the complete registration snapshot, the only payload Sprint 4 writes:

```json
{
  "backfilled": false,
  "referenceTag": "DEMO-01",
  "title": "Forensic demo evidence",
  "description": null,
  "status": "IN_CUSTODY",
  "sourceType": "DEVICE",
  "sourceDescription": null,
  "sourceManufacturer": null,
  "sourceModel": null,
  "sourceSerialNumber": null,
  "sourceLogicalIdentifier": null,
  "acquisitionMethod": "PHYSICAL",
  "acquiredAt": null,
  "acquisitionLocation": null,
  "acquisitionToolName": null,
  "acquisitionToolVersion": null,
  "acquisitionNotes": null,
  "originalFilename": "demo-evidence.bin",
  "fileExtension": "bin",
  "mediaType": "application/octet-stream",
  "fileSize": 25,
  "contentSha256": "9ac0e4751fa6d0fca5082060cd44e943660f510cc4af096424b6396d52327262",
  "contextualSha256": "665dea9d0df23b5ea6e6a3b18f424270a3d9c5dd8e92e2272bfed580047a1b57",
  "uploadedById": "44444444-4444-4444-8444-444444444444",
  "initialHolderId": "44444444-4444-4444-8444-444444444444"
}
```

`backfilled` is `false` for events written by the application and `true` for events created by the Sprint 3 migration backfill.

`CUSTODY_TRANSFERRED` — holder handover; the two holders must differ:

```json
{
  "previousHolderId": "44444444-4444-4444-8444-444444444444",
  "newHolderId": "55555555-5555-4555-8555-555555555555",
  "reason": "Handover to the mobile forensics lab"
}
```

`METADATA_UPDATED` — a complete before/after descriptive snapshot, never a partial diff:

```json
{
  "before": {
    "title": "Disk image",
    "description": null,
    "sourceType": "DEVICE",
    "sourceDescription": null,
    "sourceManufacturer": null,
    "sourceModel": null,
    "sourceSerialNumber": null,
    "sourceLogicalIdentifier": null,
    "acquisitionMethod": "PHYSICAL",
    "acquiredAt": null,
    "acquisitionLocation": null,
    "acquisitionToolName": null,
    "acquisitionToolVersion": null,
    "acquisitionNotes": null
  },
  "after": {
    "title": "Disk image of workstation 12",
    "description": "Corrected asset number",
    "sourceType": "DEVICE",
    "sourceDescription": null,
    "sourceManufacturer": null,
    "sourceModel": null,
    "sourceSerialNumber": null,
    "sourceLogicalIdentifier": null,
    "acquisitionMethod": "PHYSICAL",
    "acquiredAt": "2026-07-29T09:30:00Z",
    "acquisitionLocation": "Lab 2",
    "acquisitionToolName": null,
    "acquisitionToolVersion": null,
    "acquisitionNotes": null
  },
  "reason": "Corrected the asset identifier"
}
```

`INTEGRITY_VERIFIED` — a file re-hash result; `algorithm` is fixed to `SHA-256` and `valid` must agree with the compared hashes:

```json
{
  "algorithm": "SHA-256",
  "expectedContentSha256": "9ac0e4751fa6d0fca5082060cd44e943660f510cc4af096424b6396d52327262",
  "actualContentSha256": "9ac0e4751fa6d0fca5082060cd44e943660f510cc4af096424b6396d52327262",
  "valid": true,
  "fileSize": 25
}
```

`EVIDENCE_SEALED` — `previousStatus` must be `IN_CUSTODY` and `newStatus` must be `SEALED`:

```json
{
  "previousStatus": "IN_CUSTODY",
  "newStatus": "SEALED",
  "holderId": "44444444-4444-4444-8444-444444444444",
  "reason": "Analysis complete, evidence sealed"
}
```

`EVIDENCE_RELEASED` — `previousStatus` must be `IN_CUSTODY` or `SEALED`, `newStatus` must be `RELEASED`, and `newHolderId` must be `null` because release clears the holder:

```json
{
  "previousStatus": "SEALED",
  "newStatus": "RELEASED",
  "previousHolderId": "44444444-4444-4444-8444-444444444444",
  "newHolderId": null,
  "reason": "Released to the court registry"
}
```

## Canonical JSON and the event hash

Hashing must be reproducible by a third party, so the hashed bytes are produced by a dedicated canonicalizer ([`CustodyEventCanonicalizer`](../src/main/java/it/itsprodigi/proofchain/custodyevent/protocol/CustodyEventCanonicalizer.java)) instead of by a general-purpose JSON library. The rules are:

- every object writes its fields in ascending lexicographic order of the field name, at every nesting level;
- no whitespace, no line breaks and no trailing separators;
- every field is always present; an absent optional value is written as an explicit `null`, never omitted;
- strings are UTF-8, with `"`, `\`, backspace, form feed, LF, CR and tab escaped, other control characters escaped as `\u00xx`, and every other character — including non-ASCII letters and emoji — written literally;
- an unpaired UTF-16 surrogate is rejected rather than silently replaced;
- UUIDs are lowercase canonical 36-character text;
- enums use their exact Java constant name;
- integers are plain decimal digits without separators or exponent, and booleans are `true`/`false`;
- timestamps use exactly `uuuu-MM-dd'T'HH:mm:ss.SSSSSS'Z'` in UTC with six fractional digits, independent of the JVM default locale and time zone.

The canonical event envelope contains exactly these fields, in this order: `actorRole`, `caseId`, `eventId`, `eventType`, `evidenceId`, `occurredAt`, `operatorId`, `payload`, `payloadVersion`, `previousHash`, `sequenceNumber`.

The event hash is:

```text
eventHash = lowercase_hex(SHA-256("proofchain:custody-event:v1" + LF + canonicalJson))
```

The domain separator `proofchain:custody-event:v1` followed by one line feed is version 1 of the protocol and is what makes this digest incompatible with any other ProofChain hash. There is no HMAC, no configurable algorithm and no key: verification must remain possible for any auditor holding only the data.

The genesis event uses a literal zero hash, not the text `GENESIS` and not `null`:

```text
0000000000000000000000000000000000000000000000000000000000000000
```

`payload_json` is stored as `jsonb`, which does not preserve physical key order. This never affects verification: the verifier decodes the stored payload back into its typed record and re-canonicalizes it before hashing, so the canonical byte sequence is rebuilt deterministically from values, not from stored formatting.

### Reproducible fixed vector

The following genesis event is pinned by [`CustodyEventDocumentationVectorTest`](../src/test/java/it/itsprodigi/proofchain/custodyevent/protocol/CustodyEventDocumentationVectorTest.java), so this guide cannot drift away from the implementation. It reuses the content and contextual hashes of the Sprint 3 demo file `ProofChain demo evidence` followed by one line feed.

Canonical JSON, exactly as hashed (one single line, shown wrapped here only for readability, with `\` marking a wrap that is **not** part of the bytes):

```text
{"actorRole":"EVIDENCE_OFFICER","caseId":"11111111-1111-4111-8111-111111111111",\
"eventId":"33333333-3333-4333-8333-333333333333","eventType":"EVIDENCE_REGISTERED",\
"evidenceId":"22222222-2222-4222-8222-222222222222","occurredAt":"2026-07-30T10:15:30.123456Z",\
"operatorId":"44444444-4444-4444-8444-444444444444","payload":{"acquiredAt":null,\
"acquisitionLocation":null,"acquisitionMethod":"PHYSICAL","acquisitionNotes":null,\
"acquisitionToolName":null,"acquisitionToolVersion":null,"backfilled":false,\
"contentSha256":"9ac0e4751fa6d0fca5082060cd44e943660f510cc4af096424b6396d52327262",\
"contextualSha256":"665dea9d0df23b5ea6e6a3b18f424270a3d9c5dd8e92e2272bfed580047a1b57",\
"description":null,"fileExtension":"bin","fileSize":25,\
"initialHolderId":"44444444-4444-4444-8444-444444444444",\
"mediaType":"application/octet-stream","originalFilename":"demo-evidence.bin",\
"referenceTag":"DEMO-01","sourceDescription":null,"sourceLogicalIdentifier":null,\
"sourceManufacturer":null,"sourceModel":null,"sourceSerialNumber":null,\
"sourceType":"DEVICE","status":"IN_CUSTODY","title":"Forensic demo evidence",\
"uploadedById":"44444444-4444-4444-8444-444444444444"},"payloadVersion":1,\
"previousHash":"0000000000000000000000000000000000000000000000000000000000000000",\
"sequenceNumber":1}
```

The hashed byte string is the domain separator, one LF, then those canonical bytes with no trailing LF. Its expected digest is:

```text
71bd5e38f56d4a22228532372d058304246ed58e8634b8e58da37fd30e82fd2d
```

On a Unix-like system, with `$CANONICAL` holding the single-line canonical JSON above:

```bash
printf 'proofchain:custody-event:v1\n%s' "$CANONICAL" | sha256sum
```

Changing any hashed value — one character of the title, one microsecond of `occurredAt`, the sequence number or the previous hash — produces a completely different digest.

## Chain head anchor

Two columns on `digital_evidence` are the external anchor of each chain:

- `custody_event_count`: the number of events, which is also the sequence number of the last event;
- `custody_chain_head_hash`: the hash of the last event, or the zero hash while the chain is empty.

Both are advanced in the same transaction as the event insert, and a database check keeps an empty chain pinned to the zero head. The anchor is what makes **tail deletion** detectable: removing the last events would leave a consistent-looking prefix, but the stored count and head would no longer match the loaded events, and verification reports `CHAIN_LENGTH_MISMATCH` or `CHAIN_HEAD_MISMATCH`. The anchor is internal: no evidence API response exposes `custodyEventCount` or `custodyChainHeadHash`.

## Append transaction and locking

[`CustodyEventAppender`](../src/main/java/it/itsprodigi/proofchain/custodyevent/application/CustodyEventAppender.java) is the single writer of custody events. It is annotated `@Transactional(propagation = MANDATORY)`: it can never open its own transaction, so an event can only be written as part of a caller's business transaction and always commits or rolls back together with it.

The append sequence is:

1. take a `PESSIMISTIC_WRITE` lock on the evidence row (the genesis path reuses the evidence instance already managed and locked by registration);
2. compute `sequenceNumber = custody_event_count + 1` and set `previousHash` to the current head, or the zero hash for sequence `1`;
3. build the canonical event with a server-generated UUID v4 and a microsecond server instant, then compute the event hash;
4. insert and flush the event;
5. advance `custody_event_count` and `custody_chain_head_hash` on the locked evidence row.

Because the write lock is taken per evidence, concurrent appends to the **same** evidence are serialized and produce a gapless, correctly linked chain, while appends to **different** evidence items proceed in parallel without global serialization. A lock or optimistic failure is translated into `CustodyEventConcurrencyConflictException`; rollback removes the event and restores the previous count and head together.

The lock order shared with Sprint 5 is: custody case, then operators, then evidence. Verification takes only a `PESSIMISTIC_READ` on the evidence row, so it never deadlocks against that order.

## Registration integration and backfill

Successful evidence registration produces exactly one genesis event inside the registration transaction:

1. registration locks the case, validates the caller and holder, stages the file and computes hashes as in Sprint 3;
2. the evidence row is saved and flushed;
3. `initializeGenesis` refuses to run unless the evidence is managed, has zero events, has the zero head hash and has no stored event, and unless the genesis `occurredAt` equals the evidence `createdAt` — evidence and its genesis event therefore share one single server timestamp;
4. the payload is built from the **persisted, normalized** evidence values, not from the raw request;
5. the staged file is atomically finalized;
6. the evidence row, the event, the chain head and the final file commit together, or every controlled failure discards the staged or finalized file and rolls the transaction back.

The public Sprint 3 registration request and `201` response are unchanged: no event identifier, sequence number or hash was added to them.

[`V6__backfill_evidence_registration_events.java`](../src/main/java/db/migration/V6__backfill_evidence_registration_events.java) gives pre-existing Sprint 3 evidence the same guarantee. For every evidence row, in identifier order and under `FOR UPDATE`, it:

- rebuilds a deterministic `EVIDENCE_REGISTERED` payload with `backfilled = true` from the stored columns, using the evidence `createdAt` as `occurredAt` and the uploader as actor;
- inserts the genesis event and moves the anchor from `0`/zero-hash to `1`/event hash;
- revalidates instead of duplicating when a valid genesis event already exists, recomputing its expected hash and comparing the stored payload with `jsonb` equality;
- **fails the whole migration** for any ambiguous legacy state: a missing case, uploader or holder reference, a status other than `IN_CUSTODY`, a malformed snapshot, a non-zero head with no events, or a count that disagrees with the stored events.

On a fresh installation there is no evidence yet, so the migration performs no data backfill.

The backfill has one honest limitation: PostgreSQL stores no history of operator roles, so the reconstructed `actorRole` is the uploader's role **at migration time**, not necessarily the role held when the file was originally uploaded. Events created by the application never have this ambiguity.

## Timeline and detail APIs

Both read routes require an authenticated operator and use the same visibility rule as evidence reads: ADMIN sees every case, and any other operator must be an assigned member of the evidence case. AUDITOR members have full read access. Evidence that exists but is not visible is reported exactly like evidence that does not exist, so identifiers cannot be enumerated. Closed cases and every evidence lifecycle state, including `RELEASED`, remain readable.

```bash
curl --fail-with-body \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  "http://localhost:8080/api/v1/evidences/$EVIDENCE_ID/events?page=0&size=50"

curl --fail-with-body \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  "http://localhost:8080/api/v1/evidences/$EVIDENCE_ID/events/$EVENT_ID"
```

The timeline is zero-based, defaults to `page=0&size=50`, accepts sizes from 1 through 200, and is always ordered by ascending sequence number. Any occurrence of `sort`, including an empty one, is rejected with `400`; there is no filtering. The envelope contains exactly `content`, `page`, `size`, `totalElements` and `totalPages`.

A summary contains exactly twelve fields and deliberately omits the payload:

```json
{
  "id": "f24f1f96-2527-4b7d-bb1a-9781fc50cc07",
  "caseId": "1ca01c67-75b9-48e3-a2ed-72259373c67c",
  "evidenceId": "6f674949-c508-49bf-a160-ef720f9b51ee",
  "sequenceNumber": 1,
  "eventType": "EVIDENCE_REGISTERED",
  "operatorId": "eb8c2d1d-3f4a-4a8e-88c8-2e70b08f9714",
  "actorRole": "EVIDENCE_OFFICER",
  "occurredAt": "2026-07-29T12:34:56.123456Z",
  "hashVersion": 1,
  "payloadVersion": 1,
  "previousHash": "0000000000000000000000000000000000000000000000000000000000000000",
  "eventHash": "7f3eaf87d89253f7cd8d7bde43310f61efb87abb62ca9617ec2c0d46cd4f494c"
}
```

The detail response adds exactly one field, `payload`, holding the decoded typed payload for that event type. Neither response exposes JPA entities, operator credentials, evidence storage keys or the internal chain-head columns.

An event identifier that does not exist, or that exists but belongs to a different evidence item, returns `404 event-not-found` — but only after evidence visibility has been established, so the event-specific answer never reveals a hidden evidence item.

## Chain verification

```bash
curl --fail-with-body -X POST \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  "http://localhost:8080/api/v1/evidences/$EVIDENCE_ID/verify-chain"
```

The request has no body. It is a `POST` because it is a command, not because it writes: verification appends no event, persists no verification record, and mutates no evidence, event or case state. It uses the same visibility rules as the read routes and works on closed cases and released evidence.

The service resolves visibility, then re-reads the evidence under a `PESSIMISTIC_READ` lock and loads every event ordered by sequence number, so the comparison between the stored anchor and the loaded events is a coherent snapshot: a concurrent append is observed either entirely before or entirely after, never half-applied.

[`CustodyChainVerifier`](../src/main/java/it/itsprodigi/proofchain/custodyevent/application/CustodyChainVerifier.java) then evaluates, stopping at the **first** violation:

1. the chain is not empty, and the loaded event count equals the stored anchor count;
2. per event, in ascending sequence: the case and evidence identifiers match, the sequence number is exactly the expected ordinal, the genesis previous hash is the zero hash and every other previous hash equals the preceding recomputed hash, the hash and payload versions are supported, the stored payload decodes into the exact typed record, and the recomputed canonical hash equals the stored `eventHash`;
3. finally, the last recomputed hash equals the stored chain head.

A completed verification always answers `200 OK`, whether the chain is valid or corrupt. Corruption is a finding, not an HTTP error, and is never reported as `409` or `422`. Only a technical inability to read the data safely produces a sanitized `500`.

The response has exactly thirteen fields:

| Field | Meaning |
| --- | --- |
| `evidenceId` | The verified evidence item. |
| `valid` | `true` only if every check passed. |
| `checkedEvents` | Complete events verified before the first violation. |
| `storedEventCount` | The evidence anchor count. |
| `loadedEventCount` | The number of events actually loaded. |
| `storedHeadHash` | The evidence anchor head hash. |
| `calculatedHeadHash` | The last successfully recomputed hash, or the zero hash if none passed. |
| `brokenAtEventId` | The offending event, when the failure is event-specific. |
| `brokenAtSequenceNumber` | The offending sequence number, when the failure is event-specific. |
| `reason` | The exact failure reason, `null` when valid. |
| `expectedValue` | The compact expected value for the failure. |
| `actualValue` | The compact observed value for the failure. |
| `verifiedAt` | The server instant of the verification, at microsecond precision. |

`reason`, `brokenAtEventId`, `brokenAtSequenceNumber`, `expectedValue` and `actualValue` are `null` exactly when `valid` is `true`. The complete reason enum, in evaluation precedence order, is:

| Reason | Meaning |
| --- | --- |
| `EMPTY_CHAIN` | The evidence has no custody event at all. |
| `CHAIN_LENGTH_MISMATCH` | The anchor count disagrees with the number of loaded events, which is how tail loss is detected. |
| `CASE_MISMATCH` | An event references a different case than the evidence. |
| `EVIDENCE_MISMATCH` | An event references a different evidence item. |
| `SEQUENCE_GAP` | A sequence number is missing, duplicated or out of order. |
| `GENESIS_MISMATCH` | The first event's previous hash is not the zero hash. |
| `PREVIOUS_HASH_MISMATCH` | An event does not link to the recomputed hash of its predecessor. |
| `UNSUPPORTED_HASH_VERSION` | The stored hash version is not `1`. |
| `UNSUPPORTED_PAYLOAD_VERSION` | The stored payload version is not `1`. |
| `INVALID_PAYLOAD` | The stored payload does not decode into the exact typed, canonical record for its event type. |
| `EVENT_HASH_MISMATCH` | The recomputed canonical hash differs from the stored event hash. |
| `CHAIN_HEAD_MISMATCH` | Every event verified, but the anchor head differs from the last recomputed hash. |

Diagnostic fields stay compact on purpose: they carry hashes, identifiers, counts, versions or the literal marker `invalid payload`, never a full payload body or descriptive evidence content.

`EMPTY_CHAIN` is not reachable through normal operation, because registration and the migration backfill both guarantee a genesis event; it exists to diagnose direct database tampering.

## Append-only enforcement

Immutability is enforced in three independent layers:

- **Database.** [`V4__create_custody_events.sql`](../src/main/resources/db/migration/V4__create_custody_events.sql) installs a `BEFORE UPDATE OR DELETE` trigger that raises SQL state `55000` with `custody_events are append-only`. Named checks pin the event-type and actor-role sets, `payload_version = 1`, `hash_version = 1`, positive sequence numbers, `jsonb` object payloads and the lowercase `^[0-9a-f]{64}$` hash shape. Unique constraints pin one sequence number and one event hash per evidence, and foreign keys — including the composite `(evidence_id, case_id)` — prevent orphan or mismatched events and block deletion of a referenced case, evidence item or operator. There is no cascade and no soft delete.
- **Persistence layer.** The entity is `@Immutable` with non-updatable columns, and [`CustodyEventRepository`](../src/main/java/it/itsprodigi/proofchain/custodyevent/persistence/CustodyEventRepository.java) extends the minimal `Repository` interface, exposing only `save`, `saveAndFlush`, an existence check and deterministic reads. No delete or update method is published.
- **API layer.** No endpoint creates, edits or removes an event. The only writer is the appender, reachable exclusively from a server-side business transaction.

Hibernate runs with `ddl-auto: validate`; Flyway is the only schema authority, both from an empty database and when upgrading a certified Sprint 3 database.

## Logging

Custody-event logging is deliberately identifier-based. Verification logs the case identifier, evidence identifier, validity, checked-event count, failure reason and broken sequence number — at `INFO` when valid and `WARN` when invalid. Registration logs the case, evidence, event and sequence identifiers with the caller identifier, and only after the transaction commits. No log statement contains payload bodies, evidence titles, filenames, storage keys, file content, hashes of user secrets or credentials. The read APIs log nothing beyond the shared Problem Details boundary.

## Problem Details

Custody-event errors use `application/problem+json` and the shared request instance/timestamp envelope.

| Situation | HTTP | Problem type |
| --- | --- | --- |
| Invalid UUID, invalid page or size, or any `sort` parameter | `400` | `https://proofchain.dev/problems/validation-error` |
| Missing, invalid, or expired authentication | `401` | Authentication types described in [Authentication](./Auth.md#error-contracts) |
| Missing or hidden evidence, or an evidence item in an inaccessible case | `404` | `https://proofchain.dev/problems/resource-not-found` |
| Missing event, or an event that belongs to a different evidence item | `404` | `https://proofchain.dev/problems/event-not-found` |
| Persisted event payload or protocol version cannot be read safely | `500` | `https://proofchain.dev/problems/custody-chain-read-failure` |

There is no post-visibility `403` on these routes: a visible evidence item is fully readable and verifiable by every case member.

Two further problem types belong to the frozen protocol catalogue but are intentionally **not** emitted by the Sprint 4 runtime, and therefore are not published in the OpenAPI document: `custody-event-concurrency-conflict` (`409`) and `custody-event-persistence-failure` (`500`). Sprint 4 has no operational append endpoint, so no request can reach an append conflict; the reasoning and the Sprint 5 obligation are recorded in [ADR-006](./adr/ADR-006-sprint-4-custody-events-and-hash-chain.md).

## Sprint 5 integration contract

Sprint 5 workflows must reuse this slice instead of extending it:

- call `CustodyEventAppender.append` from inside the workflow's own transaction; `MANDATORY` propagation makes the event atomic with the business change;
- build the frozen typed payload for the operation, without adding fields, event types or payload versions;
- respect the lock order case → operators → evidence, and keep the evidence `PESSIMISTIC_WRITE` lock as the single writer serialization point;
- map `CustodyEventConcurrencyConflictException` to `409 custody-event-concurrency-conflict` when the first operational append endpoint is introduced;
- leave the read and verification contracts unchanged.

Transfer, metadata update, file-integrity verification, seal and release remain unimplemented in Sprint 4: the payload records exist, the endpoints and the state transitions do not.

## Testing

Focused fast tests:

```bash
./mvnw --batch-mode --no-transfer-progress -Djacoco.skip=true \
  -Dtest=CustodyEventProtocolTest,CustodyEventDocumentationVectorTest,CustodyEventTest,CustodyChainVerifierTest,CustodyEventMapperTest,CustodyEventQueryServiceTest,CustodyChainVerificationServiceTest \
  test
```

Focused PostgreSQL, API and migration integration tests:

```bash
./mvnw --batch-mode --no-transfer-progress -Djacoco.skip=true \
  -Dit.test=CustodyEventRepositoryIT,CustodyEventAppenderIT,CustodyEventReadWebMvcIT,CustodyChainVerificationWebMvcIT,CustodyChainVerificationConcurrencyIT,CustodyEventBackfillMigrationIT \
  test-compile failsafe:integration-test failsafe:verify
```

The canonical complete gate remains:

```bash
./mvnw --batch-mode --no-transfer-progress clean verify
```

The primary executable references are [`CustodyEventProtocolTest`](../src/test/java/it/itsprodigi/proofchain/custodyevent/protocol/CustodyEventProtocolTest.java), [`CustodyEventDocumentationVectorTest`](../src/test/java/it/itsprodigi/proofchain/custodyevent/protocol/CustodyEventDocumentationVectorTest.java), [`CustodyEventTest`](../src/test/java/it/itsprodigi/proofchain/custodyevent/domain/CustodyEventTest.java), [`CustodyChainVerifierTest`](../src/test/java/it/itsprodigi/proofchain/custodyevent/application/CustodyChainVerifierTest.java), [`CustodyEventRepositoryIT`](../src/test/java/it/itsprodigi/proofchain/custodyevent/persistence/CustodyEventRepositoryIT.java), [`CustodyEventAppenderIT`](../src/test/java/it/itsprodigi/proofchain/custodyevent/application/CustodyEventAppenderIT.java), [`CustodyEventReadWebMvcIT`](../src/test/java/it/itsprodigi/proofchain/custodyevent/api/CustodyEventReadWebMvcIT.java), [`CustodyChainVerificationWebMvcIT`](../src/test/java/it/itsprodigi/proofchain/custodyevent/api/CustodyChainVerificationWebMvcIT.java), [`CustodyChainVerificationConcurrencyIT`](../src/test/java/it/itsprodigi/proofchain/custodyevent/application/CustodyChainVerificationConcurrencyIT.java), and [`CustodyEventBackfillMigrationIT`](../src/test/java/it/itsprodigi/proofchain/migration/CustodyEventBackfillMigrationIT.java).

## Residual limits and future scope

- The chain proves internal consistency and ordering, not authorship: there is no digital signature, no HMAC key, no timestamping authority and no external anchoring. Anyone able to rewrite the whole database and both anchor columns could rebuild a self-consistent chain. Cryptographic hardening is deliberate future scope.
- Verification is synchronous and reads the complete chain, so its cost grows linearly with the number of events for one evidence item. There is no incremental, cached or scheduled verification, and no persisted verification history.
- Verification checks the custody chain only. It does not re-read the stored file and does not recompute `contentSha256`; file integrity is the separate Sprint 5 `INTEGRITY_VERIFIED` workflow.
- There is no case-level or batch verification, no export of a signed custody report, and no event search, filtering or aggregation.
- Backfilled events carry the uploader's migration-time role and are marked `backfilled = true` to keep that provenance explicit.
- Five of the six payload types are frozen contracts without a Sprint 4 producer; only `EVIDENCE_REGISTERED` is written by the current runtime.

The architectural decisions behind this slice are recorded in [ADR-006](./adr/ADR-006-sprint-4-custody-events-and-hash-chain.md).
