# Digital Evidence

## Purpose and boundary

Sprint 3 implements the first digital-evidence vertical slice: typed metadata, PostgreSQL persistence, streamed filesystem registration, reproducible integrity hashes, contextual authorization, deterministic listing, detail retrieval, and full-file download. Persistence entities, JPA versions, storage keys, and filesystem paths are never exposed through the API.

Exactly four evidence operations are implemented:

| Method and path | Success | Purpose |
| --- | --- | --- |
| `POST /api/v1/cases/{caseId}/evidences` | `201` | Register one strict JSON metadata part and one non-empty binary file in an OPEN case. |
| `GET /api/v1/cases/{caseId}/evidences?page=0&size=20` | `200` | List a deterministic page of evidence summaries for one visible case. |
| `GET /api/v1/evidences/{evidenceId}` | `200` | Read the complete public metadata of one visible evidence item. |
| `GET /api/v1/evidences/{evidenceId}/download` | `200` | Stream the complete stored file with attachment headers. |

Sprint 3 does not expose evidence update, transfer, seal, release, delete, or bulk-ingestion endpoints. Sprint 4 later added the read-only custody-event timeline, custody-event detail, and chain-verification routes documented in [Custody Events](./Custody-Events.md); the registration contract above is unchanged.

## Domain and persistence model

[`DigitalEvidence`](../src/main/java/it/itsprodigi/proofchain/evidence/domain/DigitalEvidence.java) is stored in `digital_evidence` and belongs to exactly one custody case.

| Area | Fields and rules |
| --- | --- |
| Identity | `id` is a server-generated UUID v4. Optional `referenceTag` is trimmed, uppercased, matches `[A-Z0-9][A-Z0-9._-]{0,63}`, and is unique within a case when present. |
| Description | `title` is required and 3–200 characters. `description` is optional and at most 2,000 characters. Optional strings are trimmed and blank values become `null`. |
| Lifecycle | New evidence starts `IN_CUSTODY`. The model also defines `SEALED` and terminal `RELEASED`; a released item has no current holder. |
| Source context | `sourceType` is one of `DEVICE`, `FILESYSTEM`, `REMOVABLE_MEDIA`, `CLOUD_SERVICE`, `NETWORK_CAPTURE`, `EMAIL`, `DATABASE`, `OTHER`, or `UNKNOWN`. Optional limits are 500 characters for description, 100 for manufacturer/model, 200 for serial number, and 300 for logical identifier. |
| Acquisition context | `acquisitionMethod` is one of `PHYSICAL`, `LOGICAL`, `EXPORT`, `CAPTURE`, `MANUAL_UPLOAD`, `OTHER`, or `UNKNOWN`. Optional limits are 300 characters for location, 200 for tool name, 100 for tool version, and 2,000 for notes. `acquiredAt` is optional and cannot be later than creation. |
| Operators | `uploadedBy` is immutable. `currentHolder` is required while `IN_CUSTODY` or `SEALED` and is `null` when `RELEASED`. API operator summaries contain only `id`, `username`, `firstName`, `lastName`, `role`, and `status`. |
| File metadata | Safe `originalFilename`, derived lowercase `fileExtension`, `mediaType`, positive `fileSize`, `contentSha256`, `contextualSha256`, and the internal `storageKey` are immutable after registration. |
| Audit and locking | `createdAt` and `updatedAt` are UTC instants at PostgreSQL microsecond precision. `version` is an internal JPA optimistic-lock value. |

The exact lifecycle graph is:

```text
IN_CUSTODY -> SEALED
IN_CUSTODY -> RELEASED
SEALED -> RELEASED
```

Transfer is allowed only before release, `RELEASED` is terminal and clears the holder, and descriptive metadata is immutable after sealing or release. These rules are not HTTP capabilities in Sprint 3.

[`V3__create_digital_evidence.sql`](../src/main/resources/db/migration/V3__create_digital_evidence.sql) is the schema authority. Named checks enforce normalized text, enum values, holder/status consistency, safe file metadata, lowercase SHA-256 values, timestamps, and non-negative versions. Foreign keys preserve case and operator references. The partial unique index on `(case_id, reference_tag)` is the final duplicate authority, while indexes support case-scoped ordering and common integrity, holder, status, source, and acquisition lookups.

## Registration contract

Registration consumes `multipart/form-data` with exactly two parts:

- `metadata`: `application/json`, with an optional `charset` parameter and no unknown JSON properties;
- `file`: the binary evidence content.

The metadata document requires `title`, `sourceType`, `acquisitionMethod`, and `initialHolderId`. Every other context field is optional and follows the bounds in the domain table. The file must be non-empty and no larger than `PROOFCHAIN_MAX_FILE_SIZE`. Directory components in an uploaded filename are removed; the persisted basename cannot be blank, `.` or `..`, longer than 255 characters, or contain control characters. A missing, blank, oversized, or invalid media type falls back to `application/octet-stream` where applicable.

A successful response contains the complete 28-field public evidence representation, returns `201 Created`, and sets `Location: /api/v1/evidences/{evidenceId}`. Clients do not submit identifiers, hashes, file size, extension, status, uploader, storage keys, or audit timestamps.

With a bearer token, visible case identifier, eligible holder identifier, and local sample file:

```bash
curl --fail-with-body -X POST "http://localhost:8080/api/v1/cases/$CASE_ID/evidences" \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  --form 'metadata={"referenceTag":"phone-2026-0042","title":"Forensic mobile image","sourceType":"DEVICE","sourceDescription":"Seized Android handset","acquisitionMethod":"LOGICAL","acquiredAt":"2026-07-29T09:30:00Z","acquisitionToolName":"Example Extractor","acquisitionToolVersion":"1.2.3","initialHolderId":"'"$HOLDER_ID"'"};type=application/json' \
  --form 'file=@./sample.bin;type=application/octet-stream'
```

The stored tag is `PHONE-2026-0042`; the original client filename is metadata only and never becomes a storage path.

### Registration authorization

- Every operation requires a currently ACTIVE database-backed authenticated operator.
- ADMIN has global case visibility. Other operators must be assigned case members; a hidden and a missing case both return `404`.
- Registration is allowed to ADMIN, assigned CASE_MANAGER, and assigned EVIDENCE_OFFICER callers. An assigned AUDITOR receives `403`.
- The case must still be OPEN after its row is locked. A closed case returns `409 case-closed`.
- The initial holder must be an ACTIVE member of the case whose current role is ADMIN, CASE_MANAGER, or EVIDENCE_OFFICER.
- An EVIDENCE_OFFICER can select only themselves as initial holder. Selecting another operator returns `403`; other missing or ineligible holder choices return `409 holder-not-eligible`.

Authorization uses current PostgreSQL role, status, and membership state rather than trusting authorization claims captured in the JWT.

## Registration transaction and concurrency

Registration deliberately coordinates the relational transaction and filesystem adapter in this order:

1. Validate contextual visibility, caller role, the strict metadata document, and filename/media inputs.
2. Take a pessimistic write lock on the custody case, refresh it, and lock the caller and selected holder as required.
3. Re-evaluate the OPEN case state, current operator eligibility, membership, and optional per-case reference-tag uniqueness.
4. Allocate the evidence UUID and canonical storage key.
5. Stream the upload once into the private staging directory while enforcing the byte limit and computing the content hash.
6. Compute the contextual hash from the case UUID, evidence UUID, and content hash.
7. Persist and flush the evidence row. The named PostgreSQL uniqueness constraint resolves duplicate-reference races.
8. Append the genesis `EVIDENCE_REGISTERED` custody event and advance the chain anchor in the same transaction, sharing the single registration timestamp. See [Custody Events](./Custody-Events.md).
9. Reserve the final target and atomically move the staged file without overwriting an existing target.
10. Register the transaction outcome: log success only after commit; on rollback, attempt to delete the finalized file without masking the original failure.

The case lock serializes registration against case closure. Staging or persistence failures attempt to remove the temporary file; a rollback after finalization attempts to remove the final file. Cleanup failures are logged with identifiers and stable reason codes, without logging content, filenames, storage keys, or paths.

The filesystem move and PostgreSQL commit cannot form one distributed atomic transaction. There is an unavoidable crash window after the atomic move and before the database commit: abrupt process termination can leave an orphaned final file. Sprint 3 has no startup cleanup, outbox, or reconciliation job, so operators must inspect and remove such files manually.

## Reproducible integrity hashes

`contentSha256` is the lowercase hexadecimal SHA-256 digest of the exact uploaded bytes. The storage adapter computes it while streaming; the client cannot supply it.

`contextualSha256` is the lowercase hexadecimal SHA-256 digest of this exact UTF-8 byte sequence, with line-feed separators and no trailing line feed:

```text
proofchain:evidence:v1\n{caseId}\n{evidenceId}\n{contentSha256}
```

It can be reproduced on a Unix-like system with values copied from an evidence response:

```bash
printf 'proofchain:evidence:v1\n%s\n%s\n%s' "$CASE_ID" "$EVIDENCE_ID" "$CONTENT_SHA256" | sha256sum
```

For a concrete vector, the exact bytes `ProofChain demo evidence` followed by one LF have content hash:

```text
9ac0e4751fa6d0fca5082060cd44e943660f510cc4af096424b6396d52327262
```

Using case `11111111-1111-4111-8111-111111111111` and evidence `22222222-2222-4222-8222-222222222222` produces contextual hash:

```text
665dea9d0df23b5ea6e6a3b18f424270a3d9c5dd8e92e2272bfed580047a1b57
```

The contextual hash binds identical content to a particular case and evidence identity. It is not a custody-event hash chain, a digital signature, or proof that the file still exists. Download does not recompute either hash; callers that need verification must hash the returned bytes and compare them with `contentSha256`.

## Filesystem storage and configuration

The default storage root is `./storage`, configured by `PROOFCHAIN_STORAGE_ROOT`. Each immutable final object uses the canonical relative key:

```text
cases/{caseId}/evidences/{evidenceId}/content.bin
```

Temporary uploads are created under `<storage-root>/.staging`. The adapter:

- normalizes and pins the configured root at startup;
- accepts only the canonical key shape with lowercase canonical UUID text;
- rejects absolute keys, traversal segments, backslashes, control characters, path escapes, and symbolic links in existing path components;
- opens and creates files with no-follow semantics where supported;
- uses a sibling lock file as a cross-process reservation;
- requires an atomic move and never replaces an existing final target;
- accepts downloads only from readable regular files and confirms the opened key and byte count against persisted metadata.

The default file limit is `PROOFCHAIN_MAX_FILE_SIZE=50MB`. Spring's complete multipart limit is separately configured as `PROOFCHAIN_MAX_REQUEST_SIZE=51MB` to allow bounded metadata and framing overhead. The configured request limit must remain greater than the file limit.

The default `storage/` directory is ignored by Git and is not database-backed. Stop the application before manual cleanup, confirm that the current directory is the repository root and that the configured root is the default, then target only that directory:

```bash
rm -rf -- ./storage
```

Never derive a destructive cleanup target from an unchecked environment variable. A non-default root must be inspected and cleaned by an operator using its explicit resolved path.

## Listing, detail, and download

ADMIN can read evidence globally. Any assigned member, including AUDITOR, can list, inspect, and download evidence in their case. Existing but inaccessible evidence and cases are hidden as `404`; there is no post-visibility `403` on read operations. Closed cases and released evidence remain readable.

The case list is zero-based, defaults to `page=0&size=20`, and accepts sizes from 1 through 100. Any occurrence of `sort`, including an empty one, is rejected with `400`. Ordering is fixed as `createdAt DESC, id ASC`. The page envelope contains exactly `content`, `page`, `size`, `totalElements`, and `totalPages`; each summary contains the compact identity, status, source/acquisition types, file metadata and hashes, holder/uploader summaries, acquisition time, and audit timestamps. Queries fetch the required associations in bounded form rather than relying on open-session lazy loading.

The detail route returns the complete public metadata representation. It never returns `storageKey`, `version`, an operator email address, password data, or a filesystem path.

Example read requests:

```bash
curl --fail-with-body \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  "http://localhost:8080/api/v1/cases/$CASE_ID/evidences?page=0&size=20"

curl --fail-with-body \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  "http://localhost:8080/api/v1/evidences/$EVIDENCE_ID"

curl --fail-with-body \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -D ./download.headers \
  -o ./downloaded-evidence.bin \
  "http://localhost:8080/api/v1/evidences/$EVIDENCE_ID/download"
```

Download resolves the immutable database descriptor in a read-only transaction, then opens and streams storage after that transaction ends. A successful response:

- is always `200 OK` with the complete bytes;
- includes confirmed `Content-Length`;
- uses the stored valid media type or falls back to `application/octet-stream`;
- uses an attachment `Content-Disposition` with a safe ASCII/UTF-8 filename;
- ignores `Range` and does not return partial content, `Content-Range`, or `ETag`.

For a visible row, a missing file, directory, symbolic link, unreadable file, or descriptor mismatch returns the stable `500 evidence-file-unavailable` response. Other adapter failures use `500 storage-failure`. Neither response nor WARN logging exposes the original filename, storage key, filesystem path, or file content.

## Problem Details

Evidence errors use `application/problem+json` and the shared request instance/timestamp envelope.

| Situation | HTTP | Problem type |
| --- | --- | --- |
| Invalid UUID, multipart shape, metadata, filename, empty file, page, size, or any `sort` parameter | `400` | `https://proofchain.dev/problems/validation-error` |
| Missing, invalid, or expired authentication | `401` | Authentication types described in [Authentication](./Auth.md#error-contracts) |
| Visible case but caller role or EVIDENCE_OFFICER holder choice is forbidden | `403` | `https://proofchain.dev/problems/access-denied` |
| Missing or hidden case/evidence | `404` | `https://proofchain.dev/problems/resource-not-found` |
| Registration attempted after case closure | `409` | `https://proofchain.dev/problems/case-closed` |
| Duplicate non-null reference tag in one case | `409` | `https://proofchain.dev/problems/duplicate-evidence-reference-tag` |
| Missing, inactive, non-member, or role-ineligible initial holder | `409` | `https://proofchain.dev/problems/holder-not-eligible` |
| File or multipart request exceeds its configured limit | `413` | `https://proofchain.dev/problems/payload-too-large` |
| Filesystem staging/finalization or other adapter failure | `500` | `https://proofchain.dev/problems/storage-failure` |
| Visible persisted evidence has unavailable or inconsistent file content | `500` | `https://proofchain.dev/problems/evidence-file-unavailable` |

## Testing

Focused fast tests:

```bash
./mvnw --batch-mode --no-transfer-progress -Djacoco.skip=true \
  -Dtest=DigitalEvidenceTest,EvidenceHashingAndStorageKeyTest,EvidenceUploadNormalizerTest,FileSystemEvidenceStorageTest \
  test
```

Focused PostgreSQL, registration, and read/download integration tests:

```bash
./mvnw --batch-mode --no-transfer-progress -Djacoco.skip=true \
  -Dit.test=DigitalEvidenceRepositoryIT,EvidenceRegistrationWebMvcIT,EvidenceReadWebMvcIT \
  test-compile failsafe:integration-test failsafe:verify
```

The canonical complete gate remains:

```bash
./mvnw --batch-mode --no-transfer-progress clean verify
```

The primary executable references are [`DigitalEvidenceTest`](../src/test/java/it/itsprodigi/proofchain/evidence/domain/DigitalEvidenceTest.java), [`EvidenceHashingAndStorageKeyTest`](../src/test/java/it/itsprodigi/proofchain/evidence/application/EvidenceHashingAndStorageKeyTest.java), [`FileSystemEvidenceStorageTest`](../src/test/java/it/itsprodigi/proofchain/evidence/storage/FileSystemEvidenceStorageTest.java), [`DigitalEvidenceRepositoryIT`](../src/test/java/it/itsprodigi/proofchain/evidence/persistence/DigitalEvidenceRepositoryIT.java), [`EvidenceRegistrationWebMvcIT`](../src/test/java/it/itsprodigi/proofchain/evidence/api/EvidenceRegistrationWebMvcIT.java), and [`EvidenceReadWebMvcIT`](../src/test/java/it/itsprodigi/proofchain/evidence/api/EvidenceReadWebMvcIT.java).

## Residual limits and future scope

- Local filesystem storage is a single-node adapter; replication, object storage, backup policy, encryption at rest, retention, and disaster recovery are operational responsibilities outside Sprint 3.
- There is no malware scan, file-format inspection, content indexing, thumbnailing, or metadata extraction.
- There is no automatic orphan-file reconciliation for the finalization/commit crash window.
- Download has no range/resume support, ETag, conditional request, or server-side hash re-verification.
- Evidence mutation, transfer, seal, release, and deletion are deferred. The four routes listed above are the complete Sprint 3 evidence API; the custody-event chain added in Sprint 4 is documented separately in [Custody Events](./Custody-Events.md).

The architectural decision is recorded in [ADR-005](./adr/ADR-005-sprint-3-digital-evidence-and-filesystem-storage.md).
