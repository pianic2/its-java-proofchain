# ProofChain demo guide

The authoritative procedure for demonstrating ProofChain `1.0.0`. It is deterministic, it uses only synthetic data, and
every state it shows is produced through the approved HTTP API. Nothing in this guide adds a seed script, a demo-only
endpoint or a fixture committed to the repository.

| Part | Contents | Duration |
| --- | --- | --- |
| [Preparation](#preparation) | tools, `.env`, reset, preflight | 5 min, before the audience arrives |
| [Part A: the canonical demonstration](#part-a-the-canonical-demonstration) | steps 1–28, positive and negative interleaved in lifecycle order | 12–15 min |
| [Part B: tampering, disposable environments only](#part-b-tampering-disposable-environments-only) | invalid file integrity and invalid chain verification | 8–10 min, optional |
| [Part C: mandatory reset](#part-c-mandatory-reset) | destroy the demo data | 1 min |
| [Semi-automated alternative](#semi-automated-alternative) | the Postman/Newman smoke run | 3 min |
| [Failure recovery](#failure-recovery) | what to do when something breaks live | as needed |

A reviewer who wants the shorter, self-service path should use the [reviewer checklist](./Reviewer-Checklist.md)
instead. The slide source for the accompanying talk is [`presentation/ProofChain.md`](../presentation/ProofChain.md).

## Safety contract

Read this before running anything.

1. **Everything here is destructive to demo data.** Never point this guide at a stack that holds real material.
2. **Part B alters stored bytes and one database column on purpose.** It runs only in a disposable environment, only
   after Part A, and only through a command a human types. No script in this repository performs it.
3. **The reset removes exactly two Docker volumes and this project's containers and network.** It touches no host
   directory, runs no `docker system prune`, and never removes a volume it has not verified as belonging to this
   Compose project.
4. **No credential is stored in the repository.** The bootstrap administrator password and the demo operator password
   come from your untracked `.env` and from shell variables you export. `.env` is git-ignored; keep it that way.
5. **ProofChain never repairs anything.** A tampering scenario ends with a detection, never with a correction. The only
   way back is the reset in [Part C](#part-c-mandatory-reset).

## Preparation

### Tools

| Tool | Needed for | Note |
| --- | --- | --- |
| Docker Engine + Compose v2 | the stack | must be able to run `postgres:18.4-trixie`, `eclipse-temurin:25-jdk`, `eclipse-temurin:25-jre` |
| `curl` | every request | |
| `jq` | readable output | demo convenience only; the Maven build never needs it |
| `sha256sum`, `cmp` | byte-parity proof | `shasum -a 256` on macOS |
| Java 25 | `./mvnw ... verify`, if you also show the build | not needed for the demo itself |
| Node.js | the optional Newman smoke run | not needed for the demo itself |

### `.env`

Docker Compose reads `.env` from the repository root automatically, and it is git-ignored.

```bash
cp .env.example .env
```

Then set, in `.env`:

```bash
POSTGRES_PASSWORD=<your local database password>
DB_PASSWORD=<the same local database password>
PROOFCHAIN_JWT_SECRET=<output of: openssl rand -base64 32>
SPRING_PROFILES_ACTIVE=container

# The only identity the API cannot create for itself. Opt-in, idempotent, disabled by default.
PROOFCHAIN_BOOTSTRAP_ADMIN_ENABLED=true
PROOFCHAIN_BOOTSTRAP_ADMIN_USERNAME=proofchain-admin
PROOFCHAIN_BOOTSTRAP_ADMIN_EMAIL=proofchain-admin@example.org
PROOFCHAIN_BOOTSTRAP_ADMIN_PASSWORD=<your local demo password>

# Keeps step 13 fast: proving the 413 contract otherwise means transferring more than 50MB live.
PROOFCHAIN_MAX_FILE_SIZE=1MB
PROOFCHAIN_MAX_REQUEST_SIZE=2MB
```

Every other variable can keep the value shipped in [`.env.example`](../.env.example); the full reference is the
[configuration baseline](./Configuration.md).

### Reset, then preflight

Always start from an empty database and empty evidence storage.

```bash
echo 'DESTROY PROOFCHAIN DEMO DATA' | ./scripts/demo/demo-reset.sh
./scripts/demo/demo-preflight.sh
```

[`demo-reset.sh`](../scripts/demo/demo-reset.sh) prints the exact scope it will delete and refuses to proceed without
the confirmation phrase. [`demo-preflight.sh`](../scripts/demo/demo-preflight.sh) validates the tools and `.env`,
checks the published ports, builds the image if it is missing, starts the stack, waits for readiness, and writes the
two synthetic fixtures to `target/demo/`, verifying their pinned SHA-256 values.

`target/` is git-ignored, so no fixture and no demo output can ever be committed. `./mvnw clean` deletes them; re-run
the preflight if you rebuild between rehearsal and demo.

### Shell session

Open one terminal at the repository root and export the presenter-supplied values. Nothing below writes a credential
to disk.

```bash
export BASE=http://localhost:8080
export DEMO_OPERATOR_PASSWORD='<a local demo password of at least 12 characters>'
read -rs -p 'Bootstrap admin password: ' ADMIN_PASSWORD; export ADMIN_PASSWORD; echo
export ADMIN_USERNAME=proofchain-admin
export WORK=target/demo
```

`DEMO_OPERATOR_PASSWORD` is the password the demo assigns to the four operators it creates. It must satisfy the
password policy: at least 12 characters, at most 128.

### The synthetic fixtures

| File | Bytes | SHA-256 | Purpose |
| --- | --- | --- | --- |
| `target/demo/demo-evidence.bin` | 348 | `e3e5108483d028cc9409f5237ddf7db67055a851cfa990cf030711a307aea562` | the registered evidence content |
| `target/demo/demo-oversized-evidence.bin` | 1,258,291 | `4225c7625f0ec257408588ee31be9229ce767ec2c77222568e025053f919a99c` | the `413` payload |

`demo-evidence.bin` is a fixed ASCII banner, 256 `NUL` bytes and a run of control bytes, so it is genuinely binary and
still fully reproducible. Both files are synthetic; neither contains personal data or real evidence material. The
preflight recomputes and checks both digests, so a demo that does not start from the exact same bytes stops before the
audience sees it.

## Part A: the canonical demonstration

28 steps. Steps marked **negative** prove a refusal and are placed at the lifecycle moment where that refusal is the
interesting one — a rejection after release means nothing once the case is closed, because closure answers first.

Every response body below is real output from a rehearsal of this guide. Identifiers, timestamps and the contextual
hash differ on your run; the content digest, sizes, statuses and reasons do not.

---

### 1. Health and readiness — positive

```bash
docker compose ps
curl -s "$BASE/actuator/health" | jq .
curl -s "$BASE/actuator/health/liveness" | jq .
curl -s "$BASE/actuator/health/readiness" | jq .
```

Expected: both services `Up (healthy)`; three `200` responses, all `{"status":"UP"}`.

Highlight: the probes are unauthenticated and render a bare status. No version, no JDBC URL, no filesystem path, no
free-space figure. Readiness is green only once Flyway has migrated, Hibernate has validated the schema, PostgreSQL
answers and the evidence root has passed a real write-and-delete probe.

### 2. OpenAPI document — positive

```bash
curl -s "$BASE/v3/api-docs" | jq '{version: .info.version, operations: [.paths | to_entries[] | .value | keys[]] | length, security: .components.securitySchemes}'
```

Expected: `200`, `"version": "1.0.0"`, a global bearer scheme.

Highlight: this document is generated from the live request mappings — there is no static specification file that could
drift from the code. Open `http://localhost:8080/swagger-ui/index.html` for the browsable form. No actuator path
appears in either.

### 3. A protected endpoint without a token — negative

```bash
curl -s -w '\nHTTP %{http_code}\n' "$BASE/api/v1/cases"
```

Expected: `401`.

```json
{
  "type": "https://proofchain.dev/problems/authentication-required",
  "title": "Authentication required",
  "status": 401,
  "detail": "Authentication is required to access this resource.",
  "instance": "/api/v1/cases"
}
```

Highlight: `application/problem+json` with a stable `type` URI, and a message that names the mechanism rather than
anything about the resource. A malformed or tampered bearer token produces the identical answer:

```bash
curl -s -o /dev/null -w 'malformed token: %{http_code}\n' "$BASE/api/v1/cases" -H 'Authorization: Bearer not-a-jwt'
```

### 4. Administrator login — positive

```bash
export ADMIN_TOKEN=$(curl -s -X POST "$BASE/api/v1/auth/login" \
  -H 'Content-Type: application/json' \
  -d "{\"username\":\"$ADMIN_USERNAME\",\"password\":\"$ADMIN_PASSWORD\"}" | jq -r .accessToken)
echo "token length: ${#ADMIN_TOKEN}"
```

Expected: `200` and a JWT roughly 320 characters long.

Highlight: this administrator exists because of the opt-in, idempotent bootstrap mechanism in `.env` — the one identity
the API cannot create for itself. It does nothing when an active `ADMIN` already exists. Every other operator in this
demo is created through the API.

### 5. Administrator identity — positive

```bash
curl -s "$BASE/api/v1/auth/me" -H "Authorization: Bearer $ADMIN_TOKEN" | jq .
```

Expected: `200`, `"role": "ADMIN"`, `"status": "ACTIVE"`.

Highlight: the token carries a signed operator identifier and nothing else. Role and status are re-read from PostgreSQL
on every authenticated request, so a suspension committed one second ago is honoured by the next call.

### 6. Create the four demo operators — positive

```bash
create_operator() {
  curl -s -X POST "$BASE/api/v1/operators" \
    -H "Authorization: Bearer $ADMIN_TOKEN" -H 'Content-Type: application/json' \
    -d "{\"username\":\"$1\",\"email\":\"$1@example.org\",\"password\":\"$DEMO_OPERATOR_PASSWORD\",\"firstName\":\"$2\",\"lastName\":\"$3\",\"role\":\"$4\"}" \
  | jq -r .id
}
export MANAGER_ID=$(create_operator demo.manager  Dana  Keys      CASE_MANAGER)
export OFFICER_ID=$(create_operator demo.officer  Robin Vault     EVIDENCE_OFFICER)
export AUDITOR_ID=$(create_operator demo.auditor  Ash   Ledger    AUDITOR)
export OUTSIDER_ID=$(create_operator demo.outsider Sam  Elsewhere AUDITOR)
printf 'manager=%s\nofficer=%s\nauditor=%s\noutsider=%s\n' "$MANAGER_ID" "$OFFICER_ID" "$AUDITOR_ID" "$OUTSIDER_ID"
```

Expected: four `201` responses, four UUIDs.

Highlight: passwords are BCrypt-hashed at cost 12 and never appear in a response, a log line or the OpenAPI examples.
`demo.outsider` is deliberately **not** given a case membership — it is the actor that proves invisibility in step 14.

### 7. Log the four operators in — positive

```bash
login() {
  curl -s -X POST "$BASE/api/v1/auth/login" -H 'Content-Type: application/json' \
    -d "{\"username\":\"$1\",\"password\":\"$DEMO_OPERATOR_PASSWORD\"}" | jq -r .accessToken
}
export MANAGER_TOKEN=$(login demo.manager)
export OFFICER_TOKEN=$(login demo.officer)
export AUDITOR_TOKEN=$(login demo.auditor)
export OUTSIDER_TOKEN=$(login demo.outsider)
```

Expected: four `200` responses.

Highlight: four independent stateless sessions. There is no HTTP session and no persisted security context anywhere.

### 8. Operator administration as a non-administrator — negative

```bash
curl -s -o /dev/null -w 'HTTP %{http_code}\n' "$BASE/api/v1/operators" -H "Authorization: Bearer $OFFICER_TOKEN"
```

Expected: `403 access-denied`.

Highlight: operator administration is `ADMIN`-only, and the whole module answers `403` — not `404`. The endpoint itself
is not a secret; the caller simply may not use it.

### 9. Create the custody case — positive

```bash
export CASE_ID=$(curl -s -X POST "$BASE/api/v1/cases" \
  -H "Authorization: Bearer $MANAGER_TOKEN" -H 'Content-Type: application/json' \
  -d '{
    "title": "Synthetic seizure ProofChain demo",
    "description": "Device collected under warrant 2026-0142.",
    "authorityName": "Court of Rome",
    "externalReference": "WARRANT-2026-0142",
    "location": "Evidence room A",
    "priority": "HIGH"
  }' | jq -r .id)
curl -s "$BASE/api/v1/cases/$CASE_ID" -H "Authorization: Bearer $MANAGER_TOKEN" | jq '{id, title, status, createdBy: .createdBy.username, closedAt}'
```

Expected: `201` with a `Location` header, `"status": "OPEN"`, `"closedAt": null`.

Highlight: the creating `CASE_MANAGER` becomes the responsible member of its own case in the same transaction — visible
in step 11.

### 10. Creating a case as an `EVIDENCE_OFFICER` — negative

```bash
curl -s -X POST "$BASE/api/v1/cases" \
  -H "Authorization: Bearer $OFFICER_TOKEN" -H 'Content-Type: application/json' \
  -d '{"title":"Officer attempt","description":null,"authorityName":"Court of Rome","externalReference":null,"location":null,"priority":"LOW"}' \
  -w '\nHTTP %{http_code}\n'
```

Expected: `403 access-denied`, `"The authenticated operator is not authorized to perform this operation."`

Highlight: a role boundary, not a visibility boundary. Compare it with step 14, where the answer is `404` because the
caller may not even learn that the resource exists.

### 11. Assign case membership — positive

```bash
curl -s -o /dev/null -w 'officer: HTTP %{http_code}\n' -X PUT "$BASE/api/v1/cases/$CASE_ID/members/$OFFICER_ID" -H "Authorization: Bearer $MANAGER_TOKEN"
curl -s -o /dev/null -w 'auditor: HTTP %{http_code}\n' -X PUT "$BASE/api/v1/cases/$CASE_ID/members/$AUDITOR_ID" -H "Authorization: Bearer $MANAGER_TOKEN"
curl -s -o /dev/null -w 'officer again: HTTP %{http_code}\n' -X PUT "$BASE/api/v1/cases/$CASE_ID/members/$OFFICER_ID" -H "Authorization: Bearer $MANAGER_TOKEN"
curl -s "$BASE/api/v1/cases/$CASE_ID/members" -H "Authorization: Bearer $MANAGER_TOKEN" | jq '[.[] | {member: .operator.username, role: .operator.role, assignedBy: .assignedBy.username}]'
```

Expected: `201`, `201`, then `200` for the repeat, and three members — the manager, the officer and the auditor.

Highlight: membership assignment is idempotent — `201` the first time, `200` afterwards, never a duplicate row.
`CaseMembership` is an explicit join entity with its own identity and audit fields, not a hidden many-to-many table.

### 12. Register digital evidence — positive

```bash
cat > "$WORK/demo-metadata.json" <<EOF
{
  "referenceTag": "PC-DEMO-001",
  "title": "Forensic mobile image",
  "description": "Full logical acquisition of the seized handset.",
  "sourceType": "DEVICE",
  "sourceDescription": "Seized Android handset",
  "sourceManufacturer": "Example Mobile",
  "sourceModel": "Model X",
  "sourceSerialNumber": "SN-000042",
  "sourceLogicalIdentifier": "device:userdata",
  "acquisitionMethod": "LOGICAL",
  "acquiredAt": "2026-07-29T09:30:00Z",
  "acquisitionLocation": "Forensics laboratory A",
  "acquisitionToolName": "Example Extractor",
  "acquisitionToolVersion": "1.2.3",
  "acquisitionNotes": "Airplane mode enabled before acquisition.",
  "initialHolderId": "$OFFICER_ID"
}
EOF

export EVIDENCE_ID=$(curl -s -X POST "$BASE/api/v1/cases/$CASE_ID/evidences" \
  -H "Authorization: Bearer $OFFICER_TOKEN" \
  -F "metadata=@$WORK/demo-metadata.json;type=application/json" \
  -F "file=@$WORK/demo-evidence.bin;type=application/octet-stream" | jq -r .id)

curl -s "$BASE/api/v1/evidences/$EVIDENCE_ID" -H "Authorization: Bearer $OFFICER_TOKEN" \
  | jq '{status, holder: .currentHolder.username, uploadedBy: .uploadedBy.username, originalFilename, fileSize, contentSha256, contextualSha256}'
```

Expected: `201` with a `Location` header, then:

```json
{
  "status": "IN_CUSTODY",
  "holder": "demo.officer",
  "uploadedBy": "demo.officer",
  "originalFilename": "demo-evidence.bin",
  "fileSize": 348,
  "contentSha256": "e3e5108483d028cc9409f5237ddf7db67055a851cfa990cf030711a307aea562",
  "contextualSha256": "846a20c505396e672d838608a1b3d43890304d7de8a1de821473aaffbaaf283d"
}
```

Highlight, in this order:

- `contentSha256` is exactly the digest the preflight printed for the local file. The server hashed the bytes it
  actually received; it did not trust a client-supplied value, and no such field exists in the request.
- `contextualSha256` is a **different** digest that also binds the case and evidence identifiers, so identical bytes
  registered in two cases are two distinct records. It changes on every run because the identifiers do.
- `initialHolderId` names the first custodian. The chain therefore starts with a named answerable person, not with an
  anonymous upload.
- The response exposes no `storageKey`, no `storagePath`, no JPA `version` and no absolute path. The storage layout is
  derived from identifiers, never from the uploaded filename.

### 13. An oversized upload — negative

```bash
curl -s -X POST "$BASE/api/v1/cases/$CASE_ID/evidences" \
  -H "Authorization: Bearer $OFFICER_TOKEN" \
  -F "metadata=@$WORK/demo-metadata.json;type=application/json" \
  -F "file=@$WORK/demo-oversized-evidence.bin;type=application/octet-stream" \
  -w '\nHTTP %{http_code}\n'
```

Expected: `413 payload-too-large`, `"The multipart request exceeds the configured upload limit."`

Highlight: the limit is enforced by the container's multipart layer before any business code runs, so an oversized body
is never staged, never hashed and never written. It is a configured value (`PROOFCHAIN_MAX_FILE_SIZE`), not a hardcoded
one.

### 14. Hidden versus nonexistent — negative

```bash
curl -s "$BASE/api/v1/evidences/$EVIDENCE_ID" -H "Authorization: Bearer $OUTSIDER_TOKEN" | jq 'del(.timestamp, .instance)'
curl -s "$BASE/api/v1/evidences/00000000-0000-4000-8000-000000000000" -H "Authorization: Bearer $OUTSIDER_TOKEN" | jq 'del(.timestamp, .instance)'
```

Expected: two `404` responses whose bodies are identical apart from the echoed `instance` path and the timestamp:

```json
{
  "type": "https://proofchain.dev/problems/resource-not-found",
  "title": "Resource not found",
  "status": 404,
  "detail": "The requested resource was not found."
}
```

Highlight: `demo.outsider` is a valid, active operator holding a valid token, and the evidence in the first call really
exists. Because the operator is not a member of the owning case, the answer is byte-identical to the answer for an
identifier that was never issued. These endpoints cannot be used as an existence oracle. Contrast with step 10: `403`
is reachable only *after* visibility has already been established.

### 15. Byte-for-byte download — positive

```bash
curl -s -D "$WORK/download-headers.txt" -o "$WORK/downloaded.bin" \
  "$BASE/api/v1/evidences/$EVIDENCE_ID/download" -H "Authorization: Bearer $OFFICER_TOKEN"
grep -iE '^(HTTP|content-type|content-length|content-disposition|x-content-type-options)' "$WORK/download-headers.txt"
cmp "$WORK/demo-evidence.bin" "$WORK/downloaded.bin" && echo 'BYTE-FOR-BYTE IDENTICAL'
sha256sum "$WORK/demo-evidence.bin" "$WORK/downloaded.bin"
```

Expected: `200`, `Content-Length: 348`, `X-Content-Type-Options: nosniff`, `cmp` silent, two identical digests equal to
`e3e5108483d028cc9409f5237ddf7db67055a851cfa990cf030711a307aea562`.

Highlight: this is the round-trip proof — the bytes that come out are the bytes that went in, including the 256 `NUL`
bytes and the control characters. `Content-Disposition` echoes the original filename for the user, while the file on
disk is stored under an identifier-derived path.

### 16. The custody timeline — positive

```bash
curl -s "$BASE/api/v1/evidences/$EVIDENCE_ID/events?page=0&size=20" -H "Authorization: Bearer $OFFICER_TOKEN" \
  | jq '{totalElements, events: [.content[] | {sequenceNumber, eventType, actorRole, previousHash, eventHash}]}'
```

Expected: `200`, `totalElements: 1`, one `EVIDENCE_REGISTERED` event at sequence `1` whose `previousHash` is 64 zeros.

Highlight: the genesis event. Its `previousHash` is the zero hash because nothing precedes it; every later event links
to the one before. Events are written by a single server-side appender inside the business transaction they describe —
there is no endpoint that appends an event directly.

### 17. Chain verification — positive

```bash
curl -s -X POST "$BASE/api/v1/evidences/$EVIDENCE_ID/verify-chain" -H "Authorization: Bearer $OFFICER_TOKEN" | jq .
```

Expected: `200`.

```json
{
  "valid": true,
  "checkedEvents": 1,
  "storedEventCount": 1,
  "loadedEventCount": 1,
  "storedHeadHash": "9d940e...",
  "calculatedHeadHash": "9d940e...",
  "reason": null
}
```

Highlight: verification recomputes every event hash from the canonical payload and re-links the chain, then compares
the result with the head hash stored on the evidence row. `reason` is `null` exactly when `valid` is `true`. Say plainly
what this is: an **unkeyed SHA-256 chain inside one PostgreSQL database**. It is tamper-*evidence*, not a signature and
not a distributed ledger.

### 18. Custody transfer — positive

```bash
curl -s -X POST "$BASE/api/v1/evidences/$EVIDENCE_ID/transfer" \
  -H "Authorization: Bearer $MANAGER_TOKEN" -H 'Content-Type: application/json' \
  -d "{\"newHolderId\":\"$MANAGER_ID\",\"reason\":\"Handover to the laboratory analyst.\"}" \
  | jq '{status: .evidence.status, holder: .evidence.currentHolder.username, event: {sequenceNumber: .eventSummary.sequenceNumber, eventType: .eventSummary.eventType, previousHash: .eventSummary.previousHash, eventHash: .eventSummary.eventHash}}'
```

Expected: `200`, holder `demo.manager`, status still `IN_CUSTODY`, event `2` of type `CUSTODY_TRANSFERRED`.

Highlight: the `previousHash` of event 2 is the `eventHash` of event 1 — read them side by side. Transfer is not a
lifecycle transition: it changes who is answerable, not what the evidence is. The response also carries a `Location`
header pointing at the new event.

### 19. Transferring to the current holder — negative

```bash
curl -s -X POST "$BASE/api/v1/evidences/$EVIDENCE_ID/transfer" \
  -H "Authorization: Bearer $MANAGER_TOKEN" -H 'Content-Type: application/json' \
  -d "{\"newHolderId\":\"$MANAGER_ID\",\"reason\":\"Repeat of the same transfer.\"}" \
  -w '\nHTTP %{http_code}\n'
```

Expected: `409 custody-transfer-no-op`.

Highlight: the comparison is made against the aggregate after the write lock is taken, so a stale client view can never
become a silent success or an event that records no change. A no-op is refused, not absorbed.

### 20. Descriptive metadata update — positive

```bash
curl -s -X PATCH "$BASE/api/v1/evidences/$EVIDENCE_ID/metadata" \
  -H "Authorization: Bearer $MANAGER_TOKEN" -H 'Content-Type: application/json' \
  -d '{"acquisitionToolVersion":"3.1.4","acquisitionNotes":null,"reason":"Corrected the acquisition tool version after the laboratory review."}' \
  | jq '{toolVersion: .evidence.acquisitionToolVersion, notes: .evidence.acquisitionNotes, fileSize: .evidence.fileSize, contentSha256: .evidence.contentSha256, event: .eventSummary.eventType}'
```

Expected: `200`, `"3.1.4"`, `null` notes, `METADATA_UPDATED` at sequence 3.

Highlight: the file, its size and both hashes are untouched — only description changed. The appended event carries a
complete before/after snapshot rather than a diff, so the timeline is readable without replaying it. An explicit `null`
clears a field; an absent field is left alone.

### 21. File integrity verification — positive

```bash
curl -s -X POST "$BASE/api/v1/evidences/$EVIDENCE_ID/verify-integrity" -H "Authorization: Bearer $AUDITOR_TOKEN" \
  | jq '{valid, expectedContentSha256, actualContentSha256, expectedFileSize, actualFileSize, event: .eventSummary.eventType}'
```

Expected: `200`.

```json
{
  "valid": true,
  "expectedContentSha256": "e3e5108483d028cc9409f5237ddf7db67055a851cfa990cf030711a307aea562",
  "actualContentSha256": "e3e5108483d028cc9409f5237ddf7db67055a851cfa990cf030711a307aea562",
  "expectedFileSize": 348,
  "actualFileSize": 348,
  "event": "INTEGRITY_VERIFIED"
}
```

Highlight: the `AUDITOR` issued this. It is the one command every case member may run, because re-reading the file and
recording the result asserts nothing about custody. The command streams the stored file once with a fixed 8 KiB buffer,
recomputing the digest and counting the bytes; it writes nothing to the filesystem and repairs nothing. The result is
appended to the chain as event 4, so "someone checked" is itself part of the history.

### 22. Seal the evidence — positive

```bash
curl -s -X POST "$BASE/api/v1/evidences/$EVIDENCE_ID/seal" \
  -H "Authorization: Bearer $MANAGER_TOKEN" -H 'Content-Type: application/json' \
  -d '{"reason":"Analysis completed; the working copy is sealed for preservation."}' \
  | jq '{status: .evidence.status, holder: .evidence.currentHolder.username, event: .eventSummary.eventType, sequence: .eventSummary.sequenceNumber}'
```

Expected: `200`, `"SEALED"`, holder still `demo.manager`, `EVIDENCE_SEALED` at sequence 5.

Highlight: sealing freezes what the evidence *is*, not where it is — the holder is unchanged and sealed evidence can
still be transferred. Seal does require the current holder to still be eligible, because a seal freezes the custody
record around whoever holds the item.

### 23. Sealing twice — negative

```bash
curl -s -X POST "$BASE/api/v1/evidences/$EVIDENCE_ID/seal" \
  -H "Authorization: Bearer $MANAGER_TOKEN" -H 'Content-Type: application/json' \
  -d '{"reason":"Repeat seal."}' -w '\nHTTP %{http_code}\n'
```

Expected: `409 invalid-evidence-state`.

Highlight: the lifecycle graph is `IN_CUSTODY → SEALED → RELEASED` and nothing else. There is no unseal route, and the
same graph is enforced a second time by a PostgreSQL trigger, so even a manual repair session cannot walk it backwards.

### 24. Release the evidence — positive

```bash
curl -s -X POST "$BASE/api/v1/evidences/$EVIDENCE_ID/release" \
  -H "Authorization: Bearer $MANAGER_TOKEN" -H 'Content-Type: application/json' \
  -d '{"reason":"Proceedings closed; custody of the evidence is terminated."}' \
  | jq '{status: .evidence.status, holder: .evidence.currentHolder, event: .eventSummary.eventType, sequence: .eventSummary.sequenceNumber}'
```

Expected: `200`, `"RELEASED"`, `"holder": null`, `EVIDENCE_RELEASED` at sequence 6.

Highlight: **the holder is cleared atomically with the transition** — one transaction, one event. `RELEASED` is
terminal and release is the only irreversible operational act, which is why a member `EVIDENCE_OFFICER` may never issue
it, not even for evidence it holds.

### 25. Mutating released evidence — negative

Run this **now**, while the case is still open. Once the case is closed the same call answers `case-closed` instead and
the point is lost.

```bash
curl -s -X PATCH "$BASE/api/v1/evidences/$EVIDENCE_ID/metadata" \
  -H "Authorization: Bearer $MANAGER_TOKEN" -H 'Content-Type: application/json' \
  -d '{"reason":"Attempt to amend released evidence."}' -w '\nHTTP %{http_code}\n'
```

Expected: `409 invalid-evidence-state`, `"Released evidence is terminal and cannot be modified."`

Highlight: released evidence is frozen against every command. It stays fully readable and downloadable, and
`verify-integrity` still works while the case is open — verification is the one act that says nothing about custody.

### 26. Close the custody case — positive

```bash
curl -s -X PATCH "$BASE/api/v1/cases/$CASE_ID/status" \
  -H "Authorization: Bearer $MANAGER_TOKEN" -H 'Content-Type: application/json' \
  -d '{"status":"CLOSED"}' | jq '{status, closedAt}'
curl -s -X PATCH "$BASE/api/v1/cases/$CASE_ID/status" \
  -H "Authorization: Bearer $MANAGER_TOKEN" -H 'Content-Type: application/json' \
  -d '{"status":"OPEN"}' -w '\nHTTP %{http_code}\n'
```

Expected: `200` with `"status": "CLOSED"` and a `closedAt` timestamp; then `409 invalid-case-status-transition`,
`"CLOSED is the only permitted target status."`

Highlight: closure is irreversible by contract, not by convention. There is no reopen route.

### 27. Mutating a closed case — negative

```bash
curl -s -o /dev/null -w 'patch case metadata:  HTTP %{http_code}\n' -X PATCH "$BASE/api/v1/cases/$CASE_ID" \
  -H "Authorization: Bearer $MANAGER_TOKEN" -H 'Content-Type: application/json' -d '{"priority":"CRITICAL"}'
curl -s -o /dev/null -w 'assign a member:      HTTP %{http_code}\n' -X PUT "$BASE/api/v1/cases/$CASE_ID/members/$OUTSIDER_ID" \
  -H "Authorization: Bearer $MANAGER_TOKEN"
curl -s -X POST "$BASE/api/v1/cases/$CASE_ID/evidences" -H "Authorization: Bearer $OFFICER_TOKEN" \
  -F "metadata=@$WORK/demo-metadata.json;type=application/json" \
  -F "file=@$WORK/demo-evidence.bin;type=application/octet-stream" -w '\nHTTP %{http_code}\n'
curl -s -o /dev/null -w 'verify integrity:     HTTP %{http_code}\n' -X POST "$BASE/api/v1/evidences/$EVIDENCE_ID/verify-integrity" \
  -H "Authorization: Bearer $MANAGER_TOKEN"
```

Expected: four `409 case-closed` responses, `"The custody case is closed and cannot be modified."`

Highlight: closure blocks every write in the case, including integrity verification — because verification appends an
event, and a closed case appends nothing. Closure answers before the evidence lifecycle does, which is why step 25 had
to run earlier.

### 28. Read access after closure — positive

```bash
curl -s -o /dev/null -w 'case detail:      HTTP %{http_code}\n' "$BASE/api/v1/cases/$CASE_ID" -H "Authorization: Bearer $MANAGER_TOKEN"
curl -s -o /dev/null -w 'evidence detail:  HTTP %{http_code}\n' "$BASE/api/v1/evidences/$EVIDENCE_ID" -H "Authorization: Bearer $MANAGER_TOKEN"
curl -s -o "$WORK/downloaded-archived.bin" -w 'download:         HTTP %{http_code}\n' "$BASE/api/v1/evidences/$EVIDENCE_ID/download" -H "Authorization: Bearer $MANAGER_TOKEN"
cmp "$WORK/demo-evidence.bin" "$WORK/downloaded-archived.bin" && echo 'archived content still byte-identical'
curl -s "$BASE/api/v1/evidences/$EVIDENCE_ID/events?page=0&size=20" -H "Authorization: Bearer $MANAGER_TOKEN" \
  | jq '{totalElements, types: [.content[].eventType]}'
curl -s -X POST "$BASE/api/v1/evidences/$EVIDENCE_ID/verify-chain" -H "Authorization: Bearer $MANAGER_TOKEN" \
  | jq '{valid, checkedEvents, storedEventCount, reason}'
```

Expected:

```text
case detail:      HTTP 200
evidence detail:  HTTP 200
download:         HTTP 200
archived content still byte-identical
```

```json
{
  "totalElements": 6,
  "types": ["EVIDENCE_REGISTERED", "CUSTODY_TRANSFERRED", "METADATA_UPDATED", "INTEGRITY_VERIFIED", "EVIDENCE_SEALED", "EVIDENCE_RELEASED"]
}
{ "valid": true, "checkedEvents": 6, "storedEventCount": 6, "reason": null }
```

Highlight — the closing statement of the demo: a closed case is an archive, not a tombstone. The complete six-event
history is readable in order, the content still downloads byte-for-byte, and the chain still verifies end to end.
Closure removed the ability to change anything, and nothing else.

**Part A ends here.** If you are not going to run Part B, go straight to [Part C](#part-c-mandatory-reset).

## Part B: tampering, disposable environments only

> ## STOP — HUMAN CHECKPOINT
>
> Part B deliberately corrupts stored evidence bytes and a database column. It exists to show that ProofChain
> **detects** tampering and refuses to hide or repair it.
>
> - Run it **only** against a throwaway local Compose stack that contains nothing but the synthetic demo data.
> - Run it **only** after Part A, or in an environment created by a reset for this purpose.
> - **No script in this repository performs these steps.** Every tampering command below must be typed, read aloud and
>   consciously executed by a human. `demo-preflight.sh`, `demo-smoke.sh` and `demo-reset.sh` will never do it for you,
>   and none of them accepts a flag that would.
> - When Part B is over, [Part C](#part-c-mandatory-reset) is **mandatory**. The environment is not reusable.
>
> If any of the above is not true for the stack in front of you, stop now.

Part B needs an **open** case, because integrity verification appends an event. Part A ends with the case closed, so
begin like this:

```bash
echo 'DESTROY PROOFCHAIN DEMO DATA' | ./scripts/demo/demo-reset.sh
./scripts/demo/demo-preflight.sh
```

then replay **Part A steps 4 to 21 only** — administrator login through the valid integrity verification — and stop
there. Do not run steps 22 to 28. You now have `IN_CUSTODY` evidence in an `OPEN` case with a four-event chain, and the
shell variables `$CASE_ID`, `$EVIDENCE_ID` and the four tokens are set.

### B1. The append-only guard, before any tampering

```bash
set -a; . ./.env; set +a
docker compose exec -T postgres psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" \
  -c "UPDATE custody_events SET event_hash = repeat('b',64);"
```

Expected: the statement **fails** and changes nothing.

```text
ERROR:  custody_events are append-only
CONTEXT:  PL/pgSQL function reject_custody_event_mutation() line 3 at RAISE
```

Highlight: this is a `BEFORE UPDATE OR DELETE` trigger installed by migration `V4`. Rewriting history is refused at the
database level, with full superuser access, from a direct `psql` session. That is why the tampering below has to go
after the *content* and after a *derived column* instead — the events themselves are not editable.

### B2. Invalid file-integrity verification

> **HUMAN CHECKPOINT — this command destroys the stored evidence content.**
> Read it out loud, confirm the environment is disposable, then type it.

```bash
docker compose exec -T proofchain sh -c \
  "printf X | dd of=/var/lib/proofchain/storage/cases/$CASE_ID/evidences/$EVIDENCE_ID/content.bin bs=1 seek=0 conv=notrunc status=none"
```

This overwrites byte 0 (`P`) with `X`. The file stays 348 bytes long, so only the digest changes — which is the more
interesting case, because a size check alone would not catch it.

Now verify:

```bash
curl -s -X POST "$BASE/api/v1/evidences/$EVIDENCE_ID/verify-integrity" -H "Authorization: Bearer $AUDITOR_TOKEN" \
  | jq '{valid, expectedContentSha256, actualContentSha256, expectedFileSize, actualFileSize, event: .eventSummary.eventType, sequence: .eventSummary.sequenceNumber}'
```

Expected: **`200 OK`**, not an error:

```json
{
  "valid": false,
  "expectedContentSha256": "e3e5108483d028cc9409f5237ddf7db67055a851cfa990cf030711a307aea562",
  "actualContentSha256": "e6c7b9982bec408a119de74c77b0c5c944d50d59d56177072596fdfb5be25412",
  "expectedFileSize": 348,
  "actualFileSize": 348,
  "event": "INTEGRITY_VERIFIED",
  "sequence": 5
}
```

Highlight:

- A non-conforming result is a **completed verification**, not a failure. It is `200`, not `409`, `422` or `500`, and it
  is never a Problem Detail. Only a technical inability to read the bytes is an error.
- The finding is **frozen into the chain**: a fifth `INTEGRITY_VERIFIED` event is appended recording the observed
  digest and the observed byte count. The bad news is now part of the history and cannot be removed.
- Nothing was repaired, quarantined or overwritten. Re-run the same request and it reports `valid: false` again, with
  another appended event. There is no repair route anywhere in the system.
- The download still returns the tampered bytes:
  `curl -s "$BASE/api/v1/evidences/$EVIDENCE_ID/download" -H "Authorization: Bearer $AUDITOR_TOKEN" | sha256sum`
  prints the *actual* digest. ProofChain reports; it does not censor.

Then show what integrity verification is **not**:

```bash
curl -s -X POST "$BASE/api/v1/evidences/$EVIDENCE_ID/verify-chain" -H "Authorization: Bearer $AUDITOR_TOKEN" | jq '{valid, checkedEvents, reason}'
```

Expected: `"valid": true`.

Highlight: the custody chain is intact, because the chain protects the *event history*, not the file content. The two
mechanisms are independent and both are needed. Say this explicitly — it is the single most common misunderstanding
about the system.

### B3. Invalid custody-chain verification

> **HUMAN CHECKPOINT — this command corrupts the database.**
> Same rules: disposable environment, typed by a human, reset afterwards.

```bash
set -a; . ./.env; set +a
docker compose exec -T postgres psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" \
  -c "UPDATE digital_evidence SET custody_chain_head_hash = repeat('a',64) WHERE id = '$EVIDENCE_ID';"
```

Expected: `UPDATE 1`.

```bash
curl -s -X POST "$BASE/api/v1/evidences/$EVIDENCE_ID/verify-chain" -H "Authorization: Bearer $AUDITOR_TOKEN" | jq .
```

Expected: `200` with `valid: false` and a precise, frozen reason:

```json
{
  "valid": false,
  "checkedEvents": 5,
  "storedEventCount": 5,
  "loadedEventCount": 5,
  "storedHeadHash": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
  "calculatedHeadHash": "<the real recomputed head>",
  "brokenAtEventId": null,
  "brokenAtSequenceNumber": null,
  "reason": "CHAIN_HEAD_MISMATCH",
  "expectedValue": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
  "actualValue": "<the real recomputed head>"
}
```

The three counts are `5` when B2's verification was run once. Each extra `verify-integrity` call appends another event,
so if you re-ran it they are `6`, `7`, and so on — all three stay equal to each other.

Highlight:

- `reason` is one of twelve closed-vocabulary values evaluated in a fixed precedence order —
  `EMPTY_CHAIN`, `CHAIN_LENGTH_MISMATCH`, `CASE_MISMATCH`, `EVIDENCE_MISMATCH`, `SEQUENCE_GAP`, `GENESIS_MISMATCH`,
  `PREVIOUS_HASH_MISMATCH`, `UNSUPPORTED_HASH_VERSION`, `UNSUPPORTED_PAYLOAD_VERSION`, `INVALID_PAYLOAD`,
  `EVENT_HASH_MISMATCH`, `CHAIN_HEAD_MISMATCH`. The verifier stops at the first violation and reports exactly which
  invariant broke, with the expected and actual values.
- All five events still verified individually (`checkedEvents: 5`); the mismatch is between the recomputed head and the
  head recorded on the evidence row. That is the class of tampering the head column exists to catch.
- Nothing is rebuilt. The append-only trigger from B1 already makes rewriting the events impossible, and no route
  recomputes the stored head to make the complaint go away.

Optional variant, if there is time:

```bash
docker compose exec -T postgres psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" \
  -c "UPDATE digital_evidence SET custody_event_count = custody_event_count + 1 WHERE id = '$EVIDENCE_ID';"
curl -s -X POST "$BASE/api/v1/evidences/$EVIDENCE_ID/verify-chain" -H "Authorization: Bearer $AUDITOR_TOKEN" \
  | jq '{valid, reason, storedEventCount, loadedEventCount, expectedValue, actualValue}'
```

The answer changes to `"reason": "CHAIN_LENGTH_MISMATCH"` with `expectedValue` and `actualValue` holding the two counts
— and it does so **even though the head hash is still corrupt from the previous step**, because the length check sits
earlier in the precedence order. That is the point: the reason is computed, not a single canned answer, and it always
names the first invariant that broke.

### B4. End of Part B

The environment is now knowingly corrupt. Do not demonstrate anything else from it and do not reuse it. Go to Part C.

## Part C: mandatory reset

```bash
echo 'DESTROY PROOFCHAIN DEMO DATA' | ./scripts/demo/demo-reset.sh
```

The script prints the exact scope first and refuses to continue without the confirmation phrase. It removes only:

- the containers and the network of this Compose project;
- the named volume `<project>_proofchain-postgres-data`;
- the named volume `<project>_proofchain-evidence-data`.

It verifies, before deleting, that each volume carries this project's Compose labels. It never removes a host
directory, never runs `docker system prune`, and never touches a volume it did not verify.

Interactively, run it without piping and type the phrase when prompted:

```bash
./scripts/demo/demo-reset.sh
```

Confirm the result:

```bash
docker volume ls | grep proofchain || echo 'no ProofChain volume remains'
```

The next `./scripts/demo/demo-preflight.sh` starts from empty volumes: Flyway applies `V1`–`V7` from scratch and the
bootstrap administrator is recreated.

## Semi-automated alternative

When time is short, or as a pre-demo confidence check, run the delivered Postman collection instead of Part A. It
covers the same surface with 200 assertions and no manual typing.

```bash
echo 'DESTROY PROOFCHAIN DEMO DATA' | ./scripts/demo/demo-reset.sh
./scripts/demo/demo-preflight.sh
./scripts/demo/demo-smoke.sh
```

[`demo-smoke.sh`](../scripts/demo/demo-smoke.sh) is a thin wrapper around the pinned `newman@6.2.2` run documented in
the [Postman package guide](../postman/README.md). It performs no tampering, needs Node.js, and is the only part of
this guide that reaches the network.

The collection deliberately **cannot** produce an invalid integrity or an invalid chain result: no approved endpoint
can alter stored content, and the collection performs no filesystem or database edit. That is why Part B exists and why
it is manual.

## Failure recovery

Recover by restarting, resetting or falling back to documented evidence. Never edit code live, never weaken a check to
make a demo pass, and never present a modified system as the delivered one.

| Symptom | Likely cause | Recovery |
| --- | --- | --- |
| `bind: address already in use` on `up` | another process owns 8080 or 5432 | stop it, or set `APP_PORT` / `POSTGRES_PORT` in `.env` and re-run the preflight; the preflight reports the collision before starting anything |
| `proofchain` stays `starting`, then unhealthy | slow first start, or the database is not ready | `docker compose logs --since 5m proofchain`; wait out the 30s start period; if it exited, read the fail-fast message — it names the invalid setting, never the value |
| Container exits immediately, code 1 | invalid `.env` value: JWT secret under 32 decoded bytes, wildcard CORS origin, unusable storage root, missing datasource password | fix `.env`, `docker compose up -d`. The application never degrades and never retries; the preflight catches the common cases first |
| Readiness never turns `UP` | Flyway or Hibernate validation stopped the context | `docker compose logs proofchain`; a schema that fails validation is a reset, not a repair — see [database schema lifecycle](./Database-Schema-Lifecycle.md). Never enable `baseline-on-migrate` or Flyway `clean` to make an error disappear |
| A step returns `409 case-closed` unexpectedly | a previous run's state survived, or step 26 was run early | reset and start Part A from step 1. Do not work around it |
| `duplicate` / `409` on operator or reference tag | the previous demo data is still in the volumes | reset. The demo uses fixed names on purpose, so it requires an empty database |
| Postman/Newman assertions fail from the middle onwards | the collection was run out of order, or against a dirty stack | reset, preflight, re-run the whole collection; individual requests only make sense after the modules before them |
| `500 storage-failure` on upload | the evidence volume is full or not writable | free space and restart; then run the read-only orphan report before continuing — see [Operations](./Operations.md) |
| `newman`, `jq` or Node.js unavailable | offline or missing tool | fall back to Part A, which needs only `curl`; for coverage evidence use the committed reports referenced in [Testing](./Testing.md) rather than installing tools live |
| Docker itself is unavailable | daemon down | this is an environment fault. State it plainly, fall back to the documentation and the recorded test evidence, and do not improvise a substitute runtime |

Symptom-by-symptom diagnosis for anything not listed here is in [Troubleshooting](./Troubleshooting.md).

## What this demo deliberately does not show

State these when asked, rather than letting the demo imply them:

- No production certification, no availability or throughput commitment, no SLA.
- No cloud deployment, no clustering, no reverse proxy, no TLS termination.
- No digital signature, no timestamping authority, no external anchoring, no distributed ledger. The chain is an
  unkeyed SHA-256 chain inside one PostgreSQL database.
- No malware scanning of uploaded content.
- No frontend. The demo surface is the API, Swagger UI and Postman.
- No repair, no undo, no unseal, no case reopen, no evidence deletion and no background reconciliation.
- No automated backup or restore. Backups are an operator procedure, taken with the stack stopped.

The full list of known defects and gaps is [Technical report §16](./Technical-Report.md#16-known-limitations-and-future-work).
