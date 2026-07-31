# Operational Custody Workflows

## Purpose and boundary

Sprint 5 implements the operational custody vertical slice: the five named commands an operator can issue against one registered digital evidence item. Each command validates its own business rules, mutates the evidence aggregate, and appends exactly one custody event **in the same database transaction**, so the recorded history and the committed state can never disagree.

Exactly five operational commands are implemented:

| Method and path | Success | Purpose |
| --- | --- | --- |
| `POST /api/v1/evidences/{evidenceId}/transfer` | `200` | Hand custody of the evidence to another eligible member of its case. |
| `PATCH /api/v1/evidences/{evidenceId}/metadata` | `200` | Correct the descriptive metadata of evidence that is still in custody. |
| `POST /api/v1/evidences/{evidenceId}/verify-integrity` | `200` | Re-read the stored file, recompute its digest and byte count, and record the result. |
| `POST /api/v1/evidences/{evidenceId}/seal` | `200` | Freeze evidence in custody so its description can no longer change. |
| `POST /api/v1/evidences/{evidenceId}/release` | `200` | End custody permanently and clear the holder. |

Every one of them appends exactly one custody event and answers with `Location` pointing at that event.

There is **no** endpoint for a generic command dispatch, a manual custody-event append, an arbitrary evidence `PATCH` or `PUT`, a status `PATCH`, an unseal, a reopen, a custody restoration, a bulk or batch command, an asynchronous verification job, idempotency-key processing, client-supplied event payloads, or the repair, quarantine or deletion of an altered file. There is no case-nested alias and no administrative bypass route.

The registration, read, list and download contracts of [Digital Evidence](./DigitalEvidence.md) and the timeline, detail and chain-verification contracts of [Custody Events](./Custody-Events.md) are unchanged by this slice. Authentication is described in [Authentication](./Auth.md), roles in [Operator Management](./Operators.md), and case visibility and closure in [Custody Cases](./CustodyCases.md).

## The five workflows in one paragraph each

**Transfer** changes only the current holder. It is not a lifecycle transition: `IN_CUSTODY` stays `IN_CUSTODY` and `SEALED` stays `SEALED`, because a seal freezes what the evidence *is*, not where it is. The target must be an eligible member of the owning case; the current holder is deliberately not re-checked, which is what makes recovery from a suspended holder possible.

**Metadata update** changes only the fourteen descriptive fields. It never touches identity, lifecycle, holder, file metadata, hashes or storage. It requires `IN_CUSTODY`: sealed evidence cannot be re-described, and released evidence cannot be touched at all. The appended event carries a complete before/after snapshot, not a diff.

**Integrity verification** re-reads the exact stored bytes in one pass, recomputes the SHA-256 and counts the bytes, and compares both against the persisted metadata. It writes nothing to the filesystem and never repairs anything. A conforming *and* a non-conforming result are both completed verifications and both answer `200`.

**Seal** moves `IN_CUSTODY` to `SEALED`. It never changes or clears the holder, but it does require the current holder to still be eligible, because a seal freezes the custody record around whoever holds the item.

**Release** moves `IN_CUSTODY` or `SEALED` to the terminal `RELEASED`, and atomically clears the holder. It is the only irreversible operational act and the only command a member `EVIDENCE_OFFICER` may never issue.

## Authorization matrix

Every command requires a valid bearer token. `ADMIN` is allowed globally and never needs a membership. Every other role must be an **assigned member of the owning custody case**.

| Command | `ADMIN` (global) | Member `CASE_MANAGER` | Member `EVIDENCE_OFFICER` | Member `AUDITOR` |
| --- | --- | --- | --- | --- |
| `POST .../transfer` | allowed | allowed | allowed **only while current holder** | forbidden |
| `PATCH .../metadata` | allowed | allowed | allowed | forbidden |
| `POST .../verify-integrity` | allowed | allowed | allowed | **allowed** |
| `POST .../seal` | allowed | allowed | allowed **only while current holder** | forbidden |
| `POST .../release` | allowed | allowed | forbidden | forbidden |

The differences are intentional:

- An `EVIDENCE_OFFICER` may hand over or freeze only evidence it actually holds, because both acts are statements about custody. It may correct descriptive metadata of any evidence in its case, because that is a documentation act.
- Release is management-only. Ending custody is irreversible, so no officer performs it, not even on evidence it holds.
- Integrity verification is the one command every case member can issue, `AUDITOR` included. Re-reading the file and recording the result is exactly an auditor's job, and it asserts nothing about custody.

Authorization is re-evaluated **from committed database state inside the command transaction**, after the custody case has been locked and with the operator row refreshed. A JWT identifies the caller; PostgreSQL decides. A demotion, suspension or membership removal committed a moment earlier is honoured.

The current-holder condition is checked only **after** the evidence write lock is held, because the holder is not trustworthy until the aggregate is locked.

### Visible `403` versus hidden `404`

Access is layered, and the layers never leak into each other:

1. **Invisible evidence is invisible.** If the caller is not `ADMIN` and is not a member of the owning case, or the evidence does not exist at all, the answer is `404 resource-not-found` with an identical body in both cases. A caller cannot use these endpoints to discover that an evidence identifier exists.
2. **Only then can permission be denied.** A caller that *can* see the evidence but lacks the command permission gets `403 access-denied`. Because `403` is only reachable after visibility, it never reveals anything the `404` would have hidden.
3. **Holder ineligibility never explains itself.** A nonexistent operator, a non-member, an inactive, suspended or disabled operator and an `AUDITOR` all produce the same `409 holder-not-eligible` with the same constant message. The transfer endpoint cannot be used as an operator-existence oracle.

## Evidence lifecycle

```text
                 seal                    release
   IN_CUSTODY ──────────► SEALED ──────────────────┐
        │                                          │
        │                release                   ▼
        └────────────────────────────────────► RELEASED  (terminal)
```

Exactly three transitions exist:

```text
IN_CUSTODY -> SEALED
IN_CUSTODY -> RELEASED
SEALED     -> RELEASED
```

| Rule | Behaviour |
| --- | --- |
| Transfer is not a transition | The status is unchanged by a transfer. |
| Sealed evidence stays transferable | `SEALED` evidence can change holder and remains `SEALED`. |
| Sealed evidence cannot be re-described | `PATCH .../metadata` on `SEALED` is `409 invalid-evidence-state`. |
| `RELEASED` is terminal | Transfer, metadata update, seal and release on `RELEASED` are all `409 invalid-evidence-state`. |
| Release clears the holder | `currentHolder` becomes `null` atomically with the transition. |
| Released evidence stays readable | Detail, list, download, timeline, event detail and `POST .../verify-chain` all keep working. |
| Released evidence stays file-verifiable | `POST .../verify-integrity` still works — but only while the case is `OPEN`, because it appends an event. |
| Case closure blocks everything | All five commands answer `409 case-closed`. Closure is irreversible. |

There is no unseal, no reopen and no route that can restore `IN_CUSTODY` or `SEALED` once released.

The graph is also enforced in PostgreSQL. [`V7__enforce_evidence_lifecycle_transitions.sql`](../src/main/resources/db/migration/V7__enforce_evidence_lifecycle_transitions.sql) installs a `BEFORE UPDATE OF status` trigger that raises a `check_violation` for any other transition, and the `V3` holder/status check already ties a non-null holder to `IN_CUSTODY`/`SEALED` and a null holder to `RELEASED`. The application never reaches the trigger — seal and release validate the source state under the write lock first — so it is a last-resort invariant against a repair script or a manual session, not a control-flow mechanism.

## Holder semantics

`currentHolder` is the operator answerable for the evidence right now.

**Eligibility.** A holder candidate must satisfy all of the following, resolved by **one** query so that no cause is distinguishable:

- assigned member of the owning custody case;
- operator status `ACTIVE`;
- role `ADMIN`, `CASE_MANAGER` or `EVIDENCE_OFFICER`.

`AUDITOR` can never hold evidence. A globally privileged `ADMIN` **without a membership in that case** is *not* a holder candidate: global authority is not the same as being answerable for custody.

**Recovery transfer.** Transfer eligibility-checks only the *target*. The outgoing holder is never re-validated, so an `ADMIN` or a member `CASE_MANAGER` can move evidence away from a holder that has since been suspended, disabled or demoted. Without this, deactivating one operator would strand evidence permanently.

**Self-transfer** is legitimate. An eligible caller that is not already the holder may take custody of the evidence. It is not a special case in the code, only the general rule applied to the caller.

**No-op transfer.** Targeting the operator that already holds the evidence is `409 custody-transfer-no-op`. The comparison is made against the locked aggregate, so a stale client view never becomes a silent success or an event that records nothing.

**Seal versus release.** Seal requires the *current* holder to still be eligible, and returns `409 holder-not-eligible` otherwise — a recovery transfer must come first. Release deliberately does **not** require it, so custody can always be terminated; the captured previous holder is still recorded in the event payload.

## Metadata PATCH contract

Exactly fourteen fields are modifiable:

| Field | Required after the patch | Normalization and limits |
| --- | --- | --- |
| `title` | yes | trimmed, 3–200 characters. Explicit `null` or blank is `400`. |
| `description` | no | trimmed; blank becomes `null`; at most 2,000 characters. |
| `sourceType` | yes | one of `DEVICE`, `FILESYSTEM`, `REMOVABLE_MEDIA`, `CLOUD_SERVICE`, `NETWORK_CAPTURE`, `EMAIL`, `DATABASE`, `OTHER`, `UNKNOWN`. Explicit `null` is `400`. |
| `sourceDescription` | no | trimmed; blank becomes `null`; at most 500 characters. |
| `sourceManufacturer` | no | trimmed; blank becomes `null`; at most 100 characters. |
| `sourceModel` | no | trimmed; blank becomes `null`; at most 100 characters. |
| `sourceSerialNumber` | no | trimmed; blank becomes `null`; at most 200 characters. |
| `sourceLogicalIdentifier` | no | trimmed; blank becomes `null`; at most 300 characters. |
| `acquisitionMethod` | yes | one of `PHYSICAL`, `LOGICAL`, `EXPORT`, `CAPTURE`, `MANUAL_UPLOAD`, `OTHER`, `UNKNOWN`. Explicit `null` is `400`. |
| `acquiredAt` | no | ISO-8601 instant, truncated to microseconds; must not be later than the immutable evidence `createdAt`. |
| `acquisitionLocation` | no | trimmed; blank becomes `null`; at most 300 characters. |
| `acquisitionToolName` | no | trimmed; blank becomes `null`; at most 200 characters. |
| `acquisitionToolVersion` | no | trimmed; blank becomes `null`; at most 100 characters. |
| `acquisitionNotes` | no | trimmed; blank becomes `null`; at most 2,000 characters. |

Plus the required operational `reason`, which is **not** part of the snapshots.

Every other property — `referenceTag`, `status`, `currentHolder`, `uploadedBy`, `createdAt`, `updatedAt`, `originalFilename`, `fileExtension`, `mediaType`, `fileSize`, `contentSha256`, `contextualSha256`, `storageKey`, `version`, `custodyEventCount`, `custodyChainHeadHash` — is rejected as an unknown property with `400 validation-error`. There is no arbitrary metadata map, no JSON Patch and no JSON Merge Patch.

### Presence semantics

Presence is tracked separately from value, so three request shapes mean three different things:

| Request shape | Meaning |
| --- | --- |
| property **absent** | keep the current aggregate value |
| property present with **explicit `null`** | clear the optional field |
| property present with **blank text** (`""`, `"   "`) | normalize to `null`, i.e. clear the optional field |
| **required** field present with `null` or blank | `400 validation-error` |

```json
{
  "description": null,
  "acquisitionToolVersion": "  3.1.4  ",
  "acquisitionNotes": "   ",
  "reason": "Corrected the acquisition tool version after the laboratory review."
}
```

That document clears `description`, sets `acquisitionToolVersion` to `3.1.4`, clears `acquisitionNotes`, and leaves the other eleven fields exactly as they are.

### Normalized no-op

If the complete **normalized** before and after snapshots are equal, the request is `409 metadata-update-no-op`. Nothing is written: no field changes, `updatedAt` is not bumped, and no custody event is appended. Sending `"title": "  Disk image  "` when the stored title is already `Disk image` is a no-op, because normalization happens before the comparison.

## Exact before/after event snapshots

The `METADATA_UPDATED` payload always carries the **complete** field set on both sides, with explicit nulls, never a diff. Both snapshots are built from the locked aggregate — the `after` snapshot is re-read from the aggregate *after* the mutation — so the event describes exactly the state that commits.

```json
{
  "before": {
    "title": "Disk image",
    "description": "Pending review",
    "sourceType": "DEVICE",
    "sourceDescription": null,
    "sourceManufacturer": null,
    "sourceModel": null,
    "sourceSerialNumber": null,
    "sourceLogicalIdentifier": null,
    "acquisitionMethod": "PHYSICAL",
    "acquiredAt": null,
    "acquisitionLocation": null,
    "acquisitionToolName": "AcquireTool",
    "acquisitionToolVersion": "3.1.3",
    "acquisitionNotes": null
  },
  "after": {
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
    "acquisitionToolName": "AcquireTool",
    "acquisitionToolVersion": "3.1.4",
    "acquisitionNotes": null
  },
  "reason": "Corrected the acquisition tool version after the laboratory review."
}
```

The payload is not part of the command response — the response carries the event *summary* only. Fetch it with `GET` on the returned `Location`, as shown in [Event Location navigation](#event-location-navigation).

The other payload shapes (`CUSTODY_TRANSFERRED`, `INTEGRITY_VERIFIED`, `EVIDENCE_SEALED`, `EVIDENCE_RELEASED`) are documented in [Custody Events](./Custody-Events.md#typed-payloads) and are unchanged by this slice.

## Integrity verification semantics

The command opens the exact stored file through the hardened storage port and performs **one** streaming pass with a fixed 8 KiB buffer, producing both the SHA-256 digest and the number of bytes actually read. The content is never materialized in memory, never reached through a client-supplied path and never resolved through a symbolic link.

- `valid` is `true` only when **both** the persisted `contentSha256` and the persisted `fileSize` equal the observed values. A digest that matches while the byte count does not is `valid = false`.
- The event `fileSize` is the **observed** byte count, not the persisted size: the event records what was seen.
- Both outcomes are completed verifications. Both return `200 OK` and both append exactly one `INTEGRITY_VERIFIED` event. A non-conforming result is never a `409`, a `422` or a `500`, and it is never a Problem Detail.
- Only a **technical inability** to read the exact bytes is an error. Missing, unreadable, non-regular and symlinked content are indistinguishable and all become `500 evidence-file-unavailable`, with **no event appended and nothing changed**.
- Nothing is ever repaired. The persisted hash, the persisted size and the stored bytes are never rewritten, quarantined or deleted in response to a finding.
- There is no `reason`, no request body, no persisted verification table, no verification history and no batch or asynchronous mode. The custody chain is the history.
- The command still stamps the shared instant on the aggregate, so a verification is visible in `updatedAt` and cannot be mistaken for a silent read.

Verification is the only command allowed on `RELEASED` evidence, because it is the only one that asserts nothing about custody. It still requires an `OPEN` case, because it appends an event.

## Exact REST requests and responses

All examples use a bearer token and produce `application/json`; errors produce `application/problem+json`.

### Transfer

```bash
curl --fail-with-body -X POST \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"newHolderId":"b32ecaa9-8c4c-43d7-bdc0-28f9e38f3c37","reason":"Handover to the laboratory analyst."}' \
  "http://localhost:8080/api/v1/evidences/$EVIDENCE_ID/transfer"
```

`200 OK`, `Location: /api/v1/evidences/6f674949-c508-49bf-a160-ef720f9b51ee/events/ac2f7e10-5f6f-4f0e-9d1b-0f5a3a1c9d21`:

```json
{
  "evidence": {
    "id": "6f674949-c508-49bf-a160-ef720f9b51ee",
    "caseId": "1ca01c67-75b9-48e3-a2ed-72259373c67c",
    "referenceTag": "DEMO-01",
    "title": "Disk image",
    "description": null,
    "status": "IN_CUSTODY",
    "currentHolder": {
      "id": "b32ecaa9-8c4c-43d7-bdc0-28f9e38f3c37",
      "username": "lab.analyst",
      "firstName": "Lab",
      "lastName": "Analyst",
      "role": "EVIDENCE_OFFICER",
      "status": "ACTIVE"
    },
    "uploadedBy": {
      "id": "eb8c2d1d-3f4a-4a8e-88c8-2e70b08f9714",
      "username": "field.officer",
      "firstName": "Field",
      "lastName": "Officer",
      "role": "EVIDENCE_OFFICER",
      "status": "ACTIVE"
    },
    "createdAt": "2026-07-30T09:00:00.000000Z",
    "updatedAt": "2026-07-30T09:15:00.123456Z",
    "sourceType": "DEVICE",
    "sourceDescription": null,
    "sourceManufacturer": null,
    "sourceModel": null,
    "sourceSerialNumber": null,
    "sourceLogicalIdentifier": null,
    "acquisitionMethod": "PHYSICAL",
    "acquiredAt": null,
    "acquisitionLocation": null,
    "acquisitionToolName": "AcquireTool",
    "acquisitionToolVersion": "3.1.4",
    "acquisitionNotes": null,
    "originalFilename": "demo-evidence.bin",
    "fileExtension": "bin",
    "mediaType": "application/octet-stream",
    "fileSize": 25,
    "contentSha256": "9ac0e4751fa6d0fca5082060cd44e943660f510cc4af096424b6396d52327262",
    "contextualSha256": "665dea9d0df23b5ea6e6a3b18f424270a3d9c5dd8e92e2272bfed580047a1b57"
  },
  "eventSummary": {
    "id": "ac2f7e10-5f6f-4f0e-9d1b-0f5a3a1c9d21",
    "caseId": "1ca01c67-75b9-48e3-a2ed-72259373c67c",
    "evidenceId": "6f674949-c508-49bf-a160-ef720f9b51ee",
    "sequenceNumber": 2,
    "eventType": "CUSTODY_TRANSFERRED",
    "operatorId": "eb8c2d1d-3f4a-4a8e-88c8-2e70b08f9714",
    "actorRole": "EVIDENCE_OFFICER",
    "occurredAt": "2026-07-30T09:15:00.123456Z",
    "hashVersion": 1,
    "payloadVersion": 1,
    "previousHash": "7f3eaf87d89253f7cd8d7bde43310f61efb87abb62ca9617ec2c0d46cd4f494c",
    "eventHash": "1d5c6b0f4a8e2c93b7d40f1e6a2c8b5f3e97d0a4c1b8e6f2d3a5c7091e4b8d62"
  }
}
```

`updatedAt` and `occurredAt` are the **same** instant. That is a guarantee, not a coincidence.

Transfer to the operator that already holds the evidence is `409`:

```json
{
  "type": "https://proofchain.dev/problems/custody-transfer-no-op",
  "title": "Custody transfer no-op",
  "status": 409,
  "detail": "The requested holder already holds this evidence.",
  "instance": "/api/v1/evidences/6f674949-c508-49bf-a160-ef720f9b51ee/transfer"
}
```

### Metadata update

```bash
curl --fail-with-body -X PATCH \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"description":null,"acquisitionToolVersion":"3.1.4","reason":"Corrected the acquisition tool version after the laboratory review."}' \
  "http://localhost:8080/api/v1/evidences/$EVIDENCE_ID/metadata"
```

`200 OK` with the same `EvidenceOperationResponse` envelope and `Location`, with `eventSummary.eventType` = `METADATA_UPDATED`.

A normalized no-op is `409`:

```json
{
  "type": "https://proofchain.dev/problems/metadata-update-no-op",
  "title": "Metadata update no-op",
  "status": 409,
  "detail": "The requested metadata already matches the current evidence metadata.",
  "instance": "/api/v1/evidences/6f674949-c508-49bf-a160-ef720f9b51ee/metadata"
}
```

### Integrity verification

```bash
curl --fail-with-body -X POST \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  "http://localhost:8080/api/v1/evidences/$EVIDENCE_ID/verify-integrity"
```

Conforming content, `200 OK` with `Location`:

```json
{
  "evidenceId": "6f674949-c508-49bf-a160-ef720f9b51ee",
  "valid": true,
  "expectedContentSha256": "9ac0e4751fa6d0fca5082060cd44e943660f510cc4af096424b6396d52327262",
  "actualContentSha256": "9ac0e4751fa6d0fca5082060cd44e943660f510cc4af096424b6396d52327262",
  "expectedFileSize": 25,
  "actualFileSize": 25,
  "verifiedAt": "2026-07-30T09:15:00.123456Z",
  "eventSummary": { "...": "as above, with eventType INTEGRITY_VERIFIED" }
}
```

Non-conforming content — **still `200 OK`**, still with `Location`, still with an appended event:

```json
{
  "evidenceId": "6f674949-c508-49bf-a160-ef720f9b51ee",
  "valid": false,
  "expectedContentSha256": "9ac0e4751fa6d0fca5082060cd44e943660f510cc4af096424b6396d52327262",
  "actualContentSha256": "3f79bb7b435b05321651daefd374cdc681dc06faa65e374e38337b88ca046dea",
  "expectedFileSize": 25,
  "actualFileSize": 12,
  "verifiedAt": "2026-07-30T09:15:00.123456Z",
  "eventSummary": { "...": "appended INTEGRITY_VERIFIED event summary" }
}
```

A size-only mismatch is expressible and is also `valid: false`: identical hashes with `expectedFileSize: 25` and `actualFileSize: 24`.

Technical inability to read the file is `500`, and appends nothing:

```json
{
  "type": "https://proofchain.dev/problems/evidence-file-unavailable",
  "title": "Evidence file unavailable",
  "status": 500,
  "detail": "Evidence content is unavailable.",
  "instance": "/api/v1/evidences/6f674949-c508-49bf-a160-ef720f9b51ee/verify-integrity"
}
```

### Seal

```bash
curl --fail-with-body -X POST \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"reason":"Analysis completed; the working copy is sealed for preservation."}' \
  "http://localhost:8080/api/v1/evidences/$EVIDENCE_ID/seal"
```

`200 OK`, `evidence.status` becomes `SEALED`, `evidence.currentHolder` is **unchanged**, `eventSummary.eventType` is `EVIDENCE_SEALED`.

If the current holder is no longer eligible:

```json
{
  "type": "https://proofchain.dev/problems/holder-not-eligible",
  "title": "Evidence holder not eligible",
  "status": 409,
  "detail": "The requested holder is not eligible for this custody case.",
  "instance": "/api/v1/evidences/6f674949-c508-49bf-a160-ef720f9b51ee/seal"
}
```

### Release

```bash
curl --fail-with-body -X POST \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"reason":"Proceedings closed; custody of the evidence is terminated."}' \
  "http://localhost:8080/api/v1/evidences/$EVIDENCE_ID/release"
```

Both source statuses are accepted. From `IN_CUSTODY` and from `SEALED` the response is the same shape:

```json
{
  "evidence": {
    "status": "RELEASED",
    "currentHolder": null,
    "...": "the remaining 26 fields are unchanged"
  },
  "eventSummary": {
    "eventType": "EVIDENCE_RELEASED",
    "...": "identifiers, sequence number, hashes and the shared occurredAt"
  }
}
```

`currentHolder` is `null` in the response, while the event payload still records `previousHolderId`. The holder is cleared *before* the payload is built, from a previously captured identifier, so the committed aggregate and the appended event cannot disagree, and `newHolderId` is always `null`.

Any further command on released evidence is terminal:

```json
{
  "type": "https://proofchain.dev/problems/invalid-evidence-state",
  "title": "Invalid evidence state",
  "status": 409,
  "detail": "Released evidence is terminal and cannot be modified.",
  "instance": "/api/v1/evidences/6f674949-c508-49bf-a160-ef720f9b51ee/release"
}
```

### Hidden resource versus visible forbidden operation

An `AUDITOR` that is **not** a member of the case, and any caller using an identifier that does not exist, both receive:

```json
{
  "type": "https://proofchain.dev/problems/resource-not-found",
  "title": "Resource not found",
  "status": 404,
  "detail": "The requested resource was not found.",
  "instance": "/api/v1/evidences/6f674949-c508-49bf-a160-ef720f9b51ee/seal"
}
```

An `AUDITOR` that **is** a member — the evidence is visible, the operation is not permitted:

```json
{
  "type": "https://proofchain.dev/problems/access-denied",
  "title": "Access denied",
  "status": 403,
  "detail": "The authenticated operator is not authorized to perform this operation.",
  "instance": "/api/v1/evidences/6f674949-c508-49bf-a160-ef720f9b51ee/seal"
}
```

## Event Location navigation

Every successful command sets:

```text
Location: /api/v1/evidences/{evidenceId}/events/{eventId}
```

It is a relative path and it is the canonical custody-event detail route of [Custody Events](./Custody-Events.md#timeline-and-detail-apis). Following it returns the full event **including the typed payload** that the command response deliberately omits:

```bash
LOCATION=$(curl -sS -D - -o /dev/null -X POST \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"reason":"Analysis completed."}' \
  "http://localhost:8080/api/v1/evidences/$EVIDENCE_ID/seal" \
  | awk '/^[Ll]ocation:/ {print $2}' | tr -d '\r')

curl --fail-with-body \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  "http://localhost:8080$LOCATION"
```

## Locking and transaction sequence

The lock order is frozen and identical for all five commands:

```text
PESSIMISTIC_READ CustodyCase  ->  PESSIMISTIC_WRITE DigitalEvidence  ->  append custody event
```

It is enforced **by construction**: `EvidenceCommandLockService.lockEvidence` requires a `CaseReadLock` token that only `lockCase` can produce, so the order cannot be inverted without failing to compile.

The complete sequence of one command:

```text
client                foundation                 PostgreSQL
  │  POST /transfer        │                          │
  ├───────────────────────►│                          │
  │                        │ resolve case id of evidence, check visibility
  │                        ├─────────────────────────►│   404 if invisible or missing
  │                        │ PESSIMISTIC_READ custody_cases (FOR SHARE)
  │                        ├─────────────────────────►│
  │                        │ reload + refresh operator; re-check membership, status, role
  │                        ├─────────────────────────►│   404 hidden / 403 denied
  │                        │ case must be OPEN        │   409 case-closed
  │                        │ PESSIMISTIC_WRITE digital_evidence (FOR UPDATE)
  │                        ├─────────────────────────►│
  │                        │ current-holder gate      │   403 denied
  │                        │ RELEASED gate (mutating) │   409 invalid-evidence-state
  │                        │ occurredAt = now(µs)     │
  │                        │ workflow body: validate, mutate, build typed payload
  │                        │ stamp updatedAt = occurredAt
  │                        │ append event + advance count/head
  │                        ├─────────────────────────►│
  │                        │ flush                    │
  │◄───────────────────────┤ COMMIT                   │
  │  200 + Location        │                          │
```

Key properties:

- **The case lock is a read lock.** Commands on *different* evidence items in the same open case are not globally serialized; they only prove the case is not closing underneath them. Case closure takes `PESSIMISTIC_WRITE` on the case row and therefore genuinely excludes in-flight commands.
- **Commands on the same evidence item serialize** on the evidence write lock, which is also the custody-event appender's single serialization point, so the chain stays gapless and correctly linked.
- The case lock is never upgraded, at most one evidence row is locked per command, and target operators and memberships are never pessimistically locked. There is no global JVM mutex.
- `DigitalEvidence.version` is an internal JPA optimistic-lock value and stays a defensive backstop. It is never exposed, never required from clients and never used for API concurrency control.
- **One command produces one instant.** A single server UTC instant, truncated to microseconds, is generated after both locks are held and is used as the event `occurredAt`, the aggregate `updatedAt`, and the verification `verifiedAt`. Workflow bodies receive it and cannot invent another.
- **One transaction.** Aggregate mutation, event insert and `custody_event_count` / `custody_chain_head_hash` advancement commit or roll back together. The appender uses `MANDATORY` propagation, so an event can never commit independently of the change it records.
- Integrity verification performs **no filesystem write**, so unlike registration it needs no storage compensation on rollback. The file is streamed while both locks are held, so no other command can interleave between the observed metadata and the appended event.

## Concurrency outcomes

| Scenario | Outcome |
| --- | --- |
| Two commands on the **same** evidence item | Serialized by the evidence write lock. Both complete, in some order, with consecutive sequence numbers. |
| Two commands on **different** evidence items of the same case | Run in parallel. The shared case read lock does not serialize them. |
| A command racing case **closure** | The closure's `PESSIMISTIC_WRITE` on the case and the command's `PESSIMISTIC_READ` exclude each other. Either the command completes and closure follows, or the command observes the closed case and answers `409 case-closed`. |
| A second transfer to a holder that just changed | Re-read under the write lock, so it either transfers from the *new* holder or is rejected as `409 custody-transfer-no-op`. Never a lost update. |
| A metadata patch racing another patch | The second sees the first's committed values; if they now match, it is `409 metadata-update-no-op`. |
| A second release of the same evidence | `409 invalid-evidence-state`. It is never reported as a success. |
| Lock timeout, deadlock, query timeout, optimistic failure, appender collision | All translated to `409 custody-event-concurrency-conflict`. |
| Any other data-access failure | `500 custody-event-persistence-failure`. |

**Nothing is retried.** A retry could append a second custody event for one client intent, and only the client can decide whether repeating a custody statement is correct. There is no asynchronous, queued or eventually consistent path: when the response is written, the event is committed.

## Problem Details

All errors use `application/problem+json` with the shared `instance` and `timestamp` envelope. Only the types below are emitted by these five routes.

| Situation | HTTP | Problem type |
| --- | --- | --- |
| Malformed JSON, non-object body, unknown or immutable property, wrong JSON type, invalid UUID, unsupported enum value, invalid length after normalization, `acquiredAt` later than `createdAt`, required field null or blank, invalid or unpaired-surrogate `reason`, wrong content type | `400` | `https://proofchain.dev/problems/validation-error` |
| Missing, malformed, invalid or expired token, or a non-`ACTIVE` operator | `401` | Authentication types described in [Authentication](./Auth.md#error-contracts) |
| Visible evidence, but the caller lacks the command permission | `403` | `https://proofchain.dev/problems/access-denied` |
| Evidence missing, or hidden from a non-`ADMIN` non-member | `404` | `https://proofchain.dev/problems/resource-not-found` |
| The owning custody case is `CLOSED` | `409` | `https://proofchain.dev/problems/case-closed` |
| Wrong evidence status for the command, including every command on `RELEASED` evidence | `409` | `https://proofchain.dev/problems/invalid-evidence-state` |
| Transfer target, or current holder at seal time, is not an eligible case holder | `409` | `https://proofchain.dev/problems/holder-not-eligible` |
| Transfer targets the operator that already holds the evidence | `409` | `https://proofchain.dev/problems/custody-transfer-no-op` |
| The normalized before and after metadata snapshots are equal | `409` | `https://proofchain.dev/problems/metadata-update-no-op` |
| Lock timeout, deadlock, optimistic conflict or custody-chain append collision | `409` | `https://proofchain.dev/problems/custody-event-concurrency-conflict` |
| The custody event could not be persisted | `500` | `https://proofchain.dev/problems/custody-event-persistence-failure` |
| The stored file is missing, unreadable, non-regular, symlinked or empty | `500` | `https://proofchain.dev/problems/evidence-file-unavailable` |
| Stored content exceeds the addressable byte count | `500` | `https://proofchain.dev/problems/storage-failure` |

Which types each route can emit:

| Route | `400` | `403` | `404` | `409` types | `500` types |
| --- | --- | --- | --- | --- | --- |
| `POST .../transfer` | yes | yes | yes | `case-closed`, `invalid-evidence-state`, `holder-not-eligible`, `custody-transfer-no-op`, `custody-event-concurrency-conflict` | `custody-event-persistence-failure` |
| `PATCH .../metadata` | yes | yes | yes | `case-closed`, `invalid-evidence-state`, `metadata-update-no-op`, `custody-event-concurrency-conflict` | `custody-event-persistence-failure` |
| `POST .../verify-integrity` | yes | — | yes | `case-closed`, `custody-event-concurrency-conflict` | `evidence-file-unavailable`, `storage-failure`, `custody-event-persistence-failure` |
| `POST .../seal` | yes | yes | yes | `case-closed`, `invalid-evidence-state`, `holder-not-eligible`, `custody-event-concurrency-conflict` | `custody-event-persistence-failure` |
| `POST .../release` | yes | yes | yes | `case-closed`, `invalid-evidence-state`, `custody-event-concurrency-conflict` | `custody-event-persistence-failure` |

Integrity verification declares no `403` because every role is permitted and every non-`ACTIVE` operator is already rejected with `401` by the authentication filter; the in-transaction status re-check is defence in depth against a deactivation committed mid-request.

**An invalid integrity result is not a Problem Detail.** It is a completed `200 OK` with `valid: false`. Nothing in this catalogue reports a content mismatch.

## Anti-enumeration

Three separate non-disclosure rules apply, and they compose:

1. **Evidence.** Nonexistent evidence and evidence hidden from a non-`ADMIN` non-member produce byte-identical `404` bodies. Visibility is resolved before anything else, and the lookup that resolves it returns only the case identifier, never any evidence content.
2. **Permission.** `403` is only reachable *after* visibility, so it never confirms the existence of something a `404` had hidden.
3. **Operators.** Holder ineligibility is resolved by one query over membership, status and role, so a nonexistent operator, a non-member, an inactive, suspended or disabled operator and an `AUDITOR` are indistinguishable. The message is a constant and never names the cause.

`404` bodies carry no evidence identifier-specific detail beyond the request path, `409 holder-not-eligible` never names the target, and no error response reveals a case identifier, a storage key, a filename or a hash.

## Sanitized logging

Operational logs are identifier-based and carry a stable command name plus a **failure category** derived from the exception class name — never the exception message, SQL state, lock owner or stack trace.

```text
Operational custody command result=success failureCategory=none command=custody-transfer caseId=… evidenceId=… actorId=… eventId=… sequenceNumber=2
Custody transfer result=failure command=TRANSFER evidenceId=… actorId=… targetHolderId=… failureCategory=CustodyTransferNoOpException
Evidence integrity verification result=completed failureCategory=none command=VERIFY_INTEGRITY caseId=… evidenceId=… actorId=… valid=false eventId=… sequenceNumber=3
```

Successful verifications log at `INFO`; a non-conforming verification logs at `WARN`; a storage-level failure logs at `ERROR`. No log statement contains the operational `reason`, a request body, payload JSON, descriptive metadata values, titles, filenames, storage keys, absolute paths, full content hashes or credentials.

## Testing

Focused fast tests:

```bash
./mvnw --batch-mode --no-transfer-progress -Djacoco.skip=true \
  -Dtest=EvidenceOperationalAuthorizationMatrixTest,EvidenceOperationalCommandSecurityTest,EvidenceOperationalCommandTransactionTest,EvidenceCommandReasonTest,EvidenceCommandConflictTranslatorTest,EvidenceCommandResponseMapperTest,EvidenceIntegrityVerificationServiceTest,DigitalEvidenceTest \
  test
```

Focused PostgreSQL and HTTP integration tests:

```bash
./mvnw --batch-mode --no-transfer-progress -Djacoco.skip=true \
  -Dit.test=CustodyTransferWebMvcIT,EvidenceMetadataUpdateWebMvcIT,EvidenceIntegrityVerificationWebMvcIT,EvidenceLifecycleWebMvcIT,Sprint5ContractWebMvcIT,EvidenceOperationalCommandFoundationIT,CustodyTransferServiceIT,EvidenceMetadataUpdateServiceIT,EvidenceIntegrityVerificationServiceIT,EvidenceSealServiceIT,EvidenceReleaseServiceIT \
  test-compile failsafe:integration-test failsafe:verify
```

Focused concurrency tests:

```bash
./mvnw --batch-mode --no-transfer-progress -Djacoco.skip=true \
  -Dit.test=EvidenceCommandConcurrencyIT,CustodyTransferConcurrencyIT,EvidenceMetadataUpdateConcurrencyIT,EvidenceIntegrityVerificationConcurrencyIT,EvidenceLifecycleConcurrencyIT \
  test-compile failsafe:integration-test failsafe:verify
```

The canonical complete gate remains:

```bash
./mvnw --batch-mode --no-transfer-progress clean verify
```

## Limitations and Sprint 6 boundary

- **A readable but zero-byte stored file is reported as `500 evidence-file-unavailable`, not as `valid: false`.** Registration rejects empty content, so this state should be unreachable, but the deeper reason is that the frozen `IntegrityVerifiedPayload` requires a positive `fileSize`: a zero-byte observation cannot be expressed as a custody event without changing a Sprint 4 protocol contract. The behaviour is documented rather than hidden.
- **Descriptive metadata fields are not UTF-16 surrogate-validated.** The operational `reason` is, and fails closed with `400`. A `title` or other descriptive field containing an unpaired surrogate passes DTO and aggregate validation, mutates the locked aggregate, and only fails at canonicalization, surfacing as a sanitized generic `500`. The transaction rolls back completely — nothing is committed and no event is appended — but the status code does not describe the cause. Extending the surrogate check to the descriptive fields is a contained follow-up.
- Verification is synchronous, per evidence item, and linear in file size. There is no scheduled re-verification, no incremental verification, no cached result and no persisted verification report.
- There is no bulk or batch command, no notification or webhook, no export of a signed custody report, and no repair, quarantine or deletion path for a file whose content no longer matches.
- Concurrency conflicts are reported and never retried. Clients must be prepared to re-issue a command with fresh data.
- The custody chain proves internal consistency and ordering, not authorship. Sprint 5 adds producers to the ADR-006 protocol; it does not add signatures, keys or external anchoring. The residual limits in [Custody Events](./Custody-Events.md#residual-limits-and-future-scope) still apply in full.
- Sprint 6 owns the certified release collection and the final CI verification, plus any decision to sign events, to widen the payload contract or to add the descriptive-field surrogate check.

The architectural decisions behind this slice are recorded in [ADR-007](./adr/ADR-007-sprint-5-operational-custody-workflows.md).
