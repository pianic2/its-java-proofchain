# ADR-005: Sprint 3 digital evidence and filesystem storage

- Status: Accepted
- Date: 2026-07-29
- Scope: Sprint 3 digital-evidence domain, integrity, persistence, registration, filesystem storage, and read APIs

## Context

ProofChain must register digital evidence without loading bounded files into memory, expose sufficient typed context for later custody workflows, and make the relationship between persisted metadata and exact content reproducible. Access must reuse the case boundary from ADR-004 and must not reveal whether an inaccessible evidence identifier exists.

The Sprint 3 deployment is intentionally local and time-bounded. PostgreSQL is authoritative for metadata, while binary content lives on a configurable filesystem root. These two resources cannot participate in one atomic transaction, so the design must define ordering, compensation, concurrency, and an honest residual crash condition. Paths and storage keys are sensitive implementation details and must remain behind an adapter.

## Decisions

### Evidence aggregate and typed context

- `DigitalEvidence` belongs to one custody case and starts `IN_CUSTODY` with an eligible current holder and immutable uploader.
- The domain represents `IN_CUSTODY`, `SEALED`, and terminal `RELEASED`. Release clears the holder. Transfer, seal, release, and metadata-update rules exist in the model but have no Sprint 3 HTTP endpoint.
- Source and acquisition context use explicit enums plus bounded optional descriptive fields. Client text is normalized, timestamps use UTC microsecond precision, and JPA `@Version` remains internal.
- File identity, byte count, hashes, storage key, creator association, and creation time are immutable. The API uses dedicated response records and never exposes storage keys, paths, versions, operator email addresses, or password data.
- Flyway migration V3 is the schema authority. Named checks mirror domain normalization and lifecycle invariants; foreign keys preserve references; a partial unique index enforces non-null reference-tag uniqueness per case.

### Reproducible integrity values

- `contentSha256` is computed while streaming the exact uploaded bytes and stored as 64 lowercase hexadecimal characters.
- `contextualSha256` is SHA-256 over the exact UTF-8 sequence `proofchain:evidence:v1\n{caseId}\n{evidenceId}\n{contentSha256}`, without a trailing line feed.
- IDs and hashes are server-generated. The contextual value binds content identity to its case and evidence identity; it is not a signature or a custody-event chain.
- Read and download responses expose both hashes. Download does not recompute them, avoiding a second full-file pass in the request path.

### Filesystem adapter boundary

- Binary content is owned by `EvidenceStoragePort`; the Sprint 3 adapter stores it beneath `PROOFCHAIN_STORAGE_ROOT`, defaulting to `./storage`.
- Final keys have the single canonical form `cases/{caseId}/evidences/{evidenceId}/content.bin`. Temporary content is isolated under `.staging`.
- Registration streams with a bounded buffer and enforces `PROOFCHAIN_MAX_FILE_SIZE`. Spring independently enforces `PROOFCHAIN_MAX_REQUEST_SIZE` for complete multipart requests.
- The adapter rejects non-canonical keys, path escapes, traversal, symbolic links, control characters, and unsafe directory/file types. A sibling lock file reserves the target across processes. Finalization requires an atomic move and never overwrites an existing target.
- Downloads accept only readable regular files opened without following links. The application confirms the opened key and byte count against the immutable database descriptor before streaming.
- The default `storage/` root is excluded from Git. Storage cleanup is a deliberate manual operation against an inspected, explicit root.

### Registration transaction and concurrency

- Registration accepts exactly one strict JSON metadata part and one binary file part. Unknown JSON properties and extra or duplicate multipart parts are rejected.
- Current database role, status, case membership, case state, and holder eligibility are authoritative.
- The transaction pessimistically locks the case before rechecking OPEN state and locks current operator rows needed for eligibility. This serializes registration with case closure.
- Work is ordered as lock, stage and content-hash, contextual-hash, persist and flush, then reserve and atomically finalize.
- The named database constraint resolves duplicate reference-tag races; the filesystem reservation prevents concurrent target replacement.
- Failures before finalization attempt to discard staging. Transaction rollback after finalization attempts to discard the final file. Cleanup failures are logged without masking the original outcome or exposing storage data.
- Success is logged only after database commit.

### Authorization and HTTP surface

- ADMIN has global case/evidence visibility. Non-ADMIN callers require case membership; missing and inaccessible resources both return `404`.
- ADMIN, assigned CASE_MANAGER, and assigned EVIDENCE_OFFICER can register in an OPEN case. AUDITOR is read-only. An EVIDENCE_OFFICER may take initial custody only personally; every initial holder must be ACTIVE, assigned, and role-eligible.
- Any assigned member, including AUDITOR, can list, inspect, and download evidence. Closed cases and released evidence remain readable.
- The evidence API has exactly four operations: one multipart registration plus list, detail, and full download.
- Listing is zero-based, defaults to 20, caps size at 100, rejects every client `sort` parameter, and fixes order to `createdAt DESC, id ASC`.
- Download opens storage outside the read-only metadata transaction, returns complete bytes with confirmed length and a safe attachment filename, and intentionally ignores `Range`. Partial responses and ETags are not implemented.
- Stable Problem Details distinguish validation, authentication, authorization, hidden/missing resources, closed cases, duplicate tags, holder eligibility, payload limits, storage failure, and unavailable persisted content. Responses and logs do not expose filenames, storage keys, or filesystem paths on storage failures.

## Consequences

The implementation provides a bounded, reviewable evidence slice with deterministic access, typed provenance, reproducible integrity values, database constraints, and hardened local storage. Registration does not buffer the whole file, duplicate reference races have a database authority, and closing a case cannot interleave past the registration case lock.

The design deliberately accepts a consistency limit: the atomic filesystem move occurs before the PostgreSQL commit. A process crash in that interval can leave an orphaned final file, and Sprint 3 has no startup cleanup, outbox, or reconciliation process. Operators must inspect and clean an explicit storage root manually. The local adapter also does not provide replication, backup, object storage, malware scanning, encryption policy, range downloads, or on-download hash verification.

Evidence mutation and custody-event workflows remain later-sprint scope. They must preserve the immutable file identity and hashes, contextual anti-enumeration, case lifecycle coordination, and storage boundary established here.

## Evidence

- [`DigitalEvidence.md`](../DigitalEvidence.md) records the exact HTTP, domain, hash, storage, failure, configuration, testing, and operational contracts.
- `V3__create_digital_evidence.sql` defines the PostgreSQL constraints and indexes.
- `EvidenceRegistrationWebMvcIT` and `EvidenceReadWebMvcIT` exercise registration, authorization, compensation, paging, downloads, Problem Details, and OpenAPI with PostgreSQL Testcontainers and real temporary storage.
- `DigitalEvidenceRepositoryIT`, `DigitalEvidenceTest`, `EvidenceHashingAndStorageKeyTest`, and `FileSystemEvidenceStorageTest` cover the domain, schema, hashing, canonical keys, filesystem safety, locking, and cleanup boundaries.
