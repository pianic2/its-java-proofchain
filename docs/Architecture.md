# ProofChain architecture

Version-controlled architecture diagrams for ProofChain `1.0.0`. Every diagram is rendered from Mermaid source in this
file and describes the code, migrations and configuration actually present in this repository.

ProofChain is a **single-process modular monolith backed by one PostgreSQL database**. The custody chain is a
per-evidence, hash-linked, append-only history stored in that one database. It is **not** a distributed ledger: there is
no peer network, no replication protocol, no consensus, no mining and no digital signature. None of the diagrams below
depicts one.

| # | Diagram | Kind |
| --- | --- | --- |
| 1 | [Module dependencies](#1-module-dependencies) | Flowchart |
| 2 | [Domain model and aggregate ownership](#2-domain-model-and-aggregate-ownership) | Flowchart |
| 3 | [Database schema from the Flyway migrations](#3-database-schema-from-the-flyway-migrations) | ER diagram |
| 4 | [Evidence registration](#4-evidence-registration) | Sequence |
| 5 | [Generic operational command](#5-generic-operational-command) | Sequence |
| 6 | [File integrity verification](#6-file-integrity-verification) | Sequence |
| 7 | [Custody-chain verification](#7-custody-chain-verification) | Flowchart |
| 8 | [Custody-chain structure](#8-custody-chain-structure) | Flowchart |

---

## 1. Module dependencies

Six feature packages under `it.itsprodigi.proofchain`. Arrows point from the dependent module to the module it uses.

```mermaid
flowchart TD
    auth["auth<br/>login, JWT, request authentication, audit log"]
    operator["operator<br/>operator aggregate, admin use cases, password policy"]
    custodycase["custodycase<br/>case aggregate, CaseMembership, contextual access"]
    evidence["evidence<br/>evidence aggregate, registration, reads,<br/>5 operational commands, storage port, maintenance"]
    custodyevent["custodyevent<br/>event domain, canonical protocol,<br/>single appender, reads, chain verification"]
    common["common<br/>security wiring, OpenAPI, CORS,<br/>Problem Details catalogue"]

    auth --> operator
    auth --> common
    operator --> common
    custodycase --> operator
    custodycase --> auth
    custodycase --> common
    evidence --> custodycase
    evidence --> operator
    evidence --> auth
    evidence --> common
    evidence --> custodyevent
    custodyevent --> evidence
    custodyevent --> custodycase
    custodyevent --> operator
    custodyevent --> auth
    custodyevent --> common
```

Notes on the two edges that matter:

- `evidence -> custodyevent` exists because every operational command and the registration path append exactly one
  custody event through `CustodyEventAppender`.
- `custodyevent -> evidence` exists because the appender and the verifier both need the `DigitalEvidence` aggregate:
  it carries the chain anchor columns (`custody_event_count`, `custody_chain_head_hash`) and is the row that gets the
  `PESSIMISTIC_WRITE` lock.

That cycle is real. It is why the two remain packages inside one deployable rather than separately deployable modules:
the aggregate mutation and its event must commit or roll back together in one local transaction.

## 2. Domain model and aggregate ownership

Boxes grouped by the aggregate that owns them. Edge labels give the cardinality on the referenced side.

```mermaid
flowchart LR
    subgraph AGG_OP["Aggregate: Operator (module operator)"]
        OPERATOR["Operator<br/>username, email, passwordHash<br/>role, status, version"]
    end

    subgraph AGG_CASE["Aggregate: CustodyCase (module custodycase)"]
        CASE["CustodyCase<br/>title, priority, status<br/>closedAt, version"]
        MEMBERSHIP["CaseMembership<br/>explicit join entity<br/>assignedBy, assignedAt"]
    end

    subgraph AGG_EV["Aggregate: DigitalEvidence (module evidence)"]
        EVIDENCE["DigitalEvidence<br/>file facts, source and acquisition metadata<br/>status, custodyEventCount, custodyChainHeadHash<br/>version"]
    end

    subgraph AGG_EVT["Aggregate: CustodyEvent (module custodyevent)"]
        EVENT["CustodyEvent<br/>sequenceNumber, eventType, occurredAt<br/>payloadJson, previousHash, eventHash<br/>immutable, no version column"]
    end

    CASE -- "createdBy 1" --> OPERATOR
    MEMBERSHIP -- "case 1" --> CASE
    MEMBERSHIP -- "operator 1" --> OPERATOR
    MEMBERSHIP -- "assignedBy 1" --> OPERATOR
    EVIDENCE -- "case 1" --> CASE
    EVIDENCE -- "uploadedBy 1" --> OPERATOR
    EVIDENCE -- "currentHolder 0..1" --> OPERATOR
    EVENT -- "evidence 1" --> EVIDENCE
    EVENT -- "case 1" --> CASE
    EVENT -- "actor operator 1" --> OPERATOR
```

Read the cardinalities in the other direction as: one `CustodyCase` has `0..N` `CaseMembership` rows and `0..N`
`DigitalEvidence` rows; one `Operator` has `0..N` memberships, `0..N` created cases, `0..N` uploaded evidence items and
`0..N` currently held evidence items; one `DigitalEvidence` has `1..N` `CustodyEvent` rows — at least the genesis
event, since registration always appends it.

Ownership rules that the code enforces:

- `CaseMembership` is owned by the `custodycase` aggregate and is an **explicit entity**, not a `@ManyToMany`
  collection, because the assignment itself carries attributes (`assignedBy`, `assignedAt`).
- `DigitalEvidence` is its own aggregate root. It references its case but is not loaded as a collection on it — there is
  no `@OneToMany` mapping anywhere in the model; every association is a lazy `@ManyToOne` on the owning side.
- `CustodyEvent` is written only by `CustodyEventAppender`, only inside a caller's transaction, and never mutated.
- No entity in the model is mapped with `@OneToOne` or `@ManyToMany`. That is a modelling outcome, not an oversight;
  see [ITS compliance](./ITS-Compliance.md).

## 3. Database schema from the Flyway migrations

Derived directly from `V1`–`V7` under `src/main/resources/db/migration` plus the Java migration `V6`. Only the
constraints that carry a rule are listed; the full text is in the migrations themselves.

```mermaid
erDiagram
    OPERATORS ||--o{ CASE_MEMBERSHIPS : "operator_id (FK)"
    OPERATORS ||--o{ CASE_MEMBERSHIPS_ASSIGNER : "assigned_by_operator_id (FK)"
    OPERATORS ||--o{ CUSTODY_CASES : "created_by_operator_id (FK)"
    OPERATORS ||--o{ DIGITAL_EVIDENCE : "uploaded_by / current_holder (FK)"
    OPERATORS ||--o{ CUSTODY_EVENTS : "operator_id (FK)"
    CUSTODY_CASES ||--o{ CASE_MEMBERSHIPS : "case_id (FK)"
    CUSTODY_CASES ||--o{ DIGITAL_EVIDENCE : "case_id (FK)"
    CUSTODY_CASES ||--o{ CUSTODY_EVENTS : "case_id (FK)"
    DIGITAL_EVIDENCE ||--|{ CUSTODY_EVENTS : "(evidence_id, case_id) composite FK"

    OPERATORS {
        uuid id PK "V1"
        varchar username UK "3..64, lowercase, regex a-z0-9._-"
        varchar email UK "lowercase, trimmed"
        varchar password_hash "CHECK length = 60 (BCrypt)"
        varchar role "CHECK ADMIN|CASE_MANAGER|EVIDENCE_OFFICER|AUDITOR"
        varchar status "CHECK ACTIVE|SUSPENDED|DISABLED"
        bigint version "CHECK >= 0, optimistic lock"
    }

    CUSTODY_CASES {
        uuid id PK "V2"
        varchar title "CHECK 3..200, trimmed"
        varchar priority "CHECK LOW|MEDIUM|HIGH|CRITICAL"
        varchar status "CHECK OPEN|CLOSED"
        uuid created_by_operator_id FK "-> operators.id"
        timestamptz closed_at "CHECK non-null iff status = CLOSED"
        bigint version "CHECK >= 0"
    }

    CASE_MEMBERSHIPS {
        uuid id PK "V2"
        uuid case_id FK "-> custody_cases.id"
        uuid operator_id FK "-> operators.id"
        uuid assigned_by_operator_id FK "-> operators.id"
        timestamptz assigned_at "immutable"
        constraint uk_case_operator UK "UNIQUE (case_id, operator_id)"
    }

    DIGITAL_EVIDENCE {
        uuid id PK "V3"
        uuid case_id FK "-> custody_cases.id"
        varchar reference_tag "partial UNIQUE (case_id, reference_tag) WHERE NOT NULL"
        varchar status "CHECK IN_CUSTODY|SEALED|RELEASED"
        uuid current_holder_operator_id FK "CHECK non-null iff status <> RELEASED"
        uuid uploaded_by_operator_id FK "-> operators.id"
        bigint file_size "CHECK > 0"
        varchar content_sha256 "CHECK regex ^[0-9a-f]{64}$"
        varchar contextual_sha256 "CHECK regex ^[0-9a-f]{64}$"
        varchar storage_key "CHECK relative, no .. no backslash no // no ctrl"
        timestamptz updated_at "CHECK updated_at >= created_at"
        bigint custody_event_count "V5, CHECK >= 0"
        char custody_chain_head_hash "V5, CHECK hex64; zero hash while count = 0"
        constraint uk_id_case UK "V4 UNIQUE (id, case_id) for the composite FK"
        trigger tr_lifecycle "V7 BEFORE UPDATE OF status: only IN_CUSTODY->SEALED|RELEASED, SEALED->RELEASED"
    }

    CUSTODY_EVENTS {
        uuid id PK "V4"
        uuid case_id FK "-> custody_cases.id"
        uuid evidence_id FK "composite FK (evidence_id, case_id)"
        uuid operator_id FK "-> operators.id"
        bigint sequence_number "CHECK > 0, UNIQUE (evidence_id, sequence_number)"
        varchar event_type "CHECK 6 approved types"
        timestamptz occurred_at "microsecond precision"
        integer payload_version "CHECK = 1"
        jsonb payload_json "CHECK jsonb_typeof = object"
        varchar previous_hash "CHECK hex64; zero hash at sequence 1"
        varchar event_hash "CHECK hex64, UNIQUE (evidence_id, event_hash)"
        integer hash_version "CHECK = 1"
        trigger append_only "V4 BEFORE UPDATE OR DELETE: RAISE SQLSTATE 55000"
    }

    CASE_MEMBERSHIPS_ASSIGNER {
        uuid placeholder "same table as CASE_MEMBERSHIPS, second FK to operators"
    }
```

`CASE_MEMBERSHIPS_ASSIGNER` is not a table. Mermaid cannot draw two distinct relationships between the same pair of
entities, so the second foreign key from `case_memberships.assigned_by_operator_id` to `operators.id` is shown as a
separate node. There are exactly five tables: `operators`, `custody_cases`, `case_memberships`, `digital_evidence`,
`custody_events`, plus Flyway's own `flyway_schema_history`.

The two triggers are the enforcement of last resort:

- `custody_events_append_only` (`V4`) makes the event table physically append-only: any `UPDATE` or `DELETE` raises
  SQLSTATE `55000`, whatever the client.
- `tr_digital_evidence_lifecycle_transition` (`V7`) rejects any status change outside the lifecycle graph with a
  `check_violation` named `ck_digital_evidence_lifecycle_transition`. The application never reaches it, because seal
  and release validate under the evidence write lock first; it exists so that a repair script or a manual `psql`
  session cannot un-seal or reopen released evidence either.

## 4. Evidence registration

`POST /api/v1/cases/{caseId}/evidences`, implemented by `EvidenceRegistrationService`. One transaction, plus a
transaction synchronization for the filesystem outcome.

```mermaid
sequenceDiagram
    autonumber
    participant C as Client
    participant API as EvidenceController
    participant SVC as EvidenceRegistrationService
    participant FS as FileSystemEvidenceStorage
    participant DB as PostgreSQL
    participant APP as CustodyEventAppender

    C->>API: multipart metadata + file
    API->>SVC: register(caseId, request, file, actor)
    SVC->>SVC: contextual access check, metadata + filename + media type validation
    SVC->>DB: SELECT case FOR UPDATE, refresh
    SVC->>DB: SELECT actor / holder FOR UPDATE, refresh
    SVC->>SVC: reject closed case, ineligible holder, duplicate reference tag

    Note over SVC,FS: staging - bytes are streamed, never buffered whole
    SVC->>FS: stage(storageKey, stream)
    FS-->>SVC: StagedEvidence(contentSha256, byteCount)
    SVC->>SVC: contextualSha256 = f(caseId, evidenceId, contentSha256)

    SVC->>DB: saveAndFlush(DigitalEvidence)
    Note over SVC,APP: genesis - sequence 1, previousHash = zero hash
    SVC->>APP: initializeGenesis(evidence, actor, EVIDENCE_REGISTERED payload, registeredAt)
    APP->>APP: canonical JSON, domain separator, SHA-256
    APP->>DB: saveAndFlush(CustodyEvent)
    APP->>SVC: advanceCustodyChain(1, eventHash)
    Note over SVC,DB: chain head anchor written on the evidence row

    SVC->>FS: finalizeStaged(staged)
    SVC->>SVC: register transaction synchronization

    alt transaction commits
        DB-->>SVC: COMMIT
        SVC-->>API: 201 Created + Location
        API-->>C: EvidenceResponse
    else any failure before finalize
        SVC->>FS: discardStaged(staged)
        SVC-->>C: Problem Details, nothing persisted
    else rollback after finalize
        DB-->>SVC: ROLLBACK
        SVC->>FS: discardFinalized(storageKey) via afterCompletion
        SVC-->>C: Problem Details, no orphan content
    end
```

The genesis event's `occurredAt` is asserted to equal the evidence `createdAt`; `initializeGenesis` refuses to run
unless the chain is provably empty (count `0`, head is the zero hash, no stored event).

## 5. Generic operational command

The shared template `EvidenceOperationalCommandTransaction`, used unchanged by transfer, metadata update, integrity
verification, seal and release. Only the highlighted "workflow body" differs per command.

```mermaid
sequenceDiagram
    autonumber
    participant C as Client
    participant API as Command controller
    participant SVC as Command service
    participant TX as EvidenceOperationalCommandTransaction
    participant ACC as EvidenceOperationalAccessService
    participant LCK as EvidenceCommandLockService
    participant APP as CustodyEventAppender
    participant DB as PostgreSQL

    C->>API: POST/PATCH /api/v1/evidences/{id}/...
    API->>SVC: command(evidenceId, actor, body)
    SVC->>SVC: @PreAuthorize isAuthenticated, validate reason (fails closed, 400)
    SVC->>TX: execute(command, evidenceId, actor, workflowBody)

    TX->>ACC: requireVisibleCaseId(evidenceId, actor)
    Note over TX,ACC: invisible evidence is 404, never 403

    TX->>LCK: lockCase(caseId)
    LCK->>DB: SELECT custody_cases ... FOR SHARE (PESSIMISTIC_READ)
    DB-->>LCK: CaseReadLock token

    TX->>ACC: requireAuthorizedActor(command, caseId, actor)
    TX->>TX: reject if case status is not OPEN (409 case-closed)

    TX->>LCK: lockEvidence(caseReadLock, evidenceId)
    LCK->>DB: SELECT digital_evidence ... FOR UPDATE (PESSIMISTIC_WRITE)
    Note over LCK: lockEvidence requires the token only lockCase produces,<br/>so the order cannot be inverted - it would not compile

    TX->>ACC: requireAuthorizedHolder(command, actor, evidence)
    TX->>TX: reject mutating command on RELEASED evidence (409 invalid-evidence-state)
    TX->>TX: occurredAt = now(clock) truncated to MICROS (one instant)

    rect rgb(238, 242, 248)
        TX->>SVC: workflow body applies the mutation and builds the typed payload
    end

    TX->>TX: stampCommandInstant(occurredAt) on the aggregate
    TX->>APP: append(evidenceId, actor, payload, occurredAt)
    APP->>DB: INSERT custody_events (sequence n+1, previousHash = current head)
    APP->>TX: advanceCustodyChain(n+1, eventHash)
    TX->>DB: saveAndFlush(evidence)

    alt success
        DB-->>TX: COMMIT
        TX-->>C: 200 + Location of the new event
    else lock or version conflict
        DB-->>APP: PessimisticLockingFailure / OptimisticLockingFailure
        APP-->>TX: CustodyEventConcurrencyConflictException
        TX-->>C: 409 custody-event-concurrency-conflict (no silent retry)
    else workflow or append failure
        DB-->>TX: ROLLBACK
        TX-->>C: Problem Details, aggregate mutation discarded with the event
    end
```

## 6. File integrity verification

`POST /api/v1/evidences/{evidenceId}/verify-integrity`. It reuses the whole template above; this diagram shows only the
workflow body and the verdict rules.

```mermaid
sequenceDiagram
    autonumber
    participant TX as Command template (locks held)
    participant SVC as EvidenceIntegrityVerificationService
    participant FS as FileSystemEvidenceStorage
    participant APP as CustodyEventAppender

    TX->>SVC: workflow body (case read lock + evidence write lock held)
    SVC->>SVC: read expected contentSha256 and fileSize from the locked row
    SVC->>FS: open(storageKey)

    alt file missing, non-regular, unreadable or unsafe path
        FS-->>SVC: IOException / UnsafeEvidenceStoragePathException
        SVC-->>TX: EvidenceFileUnavailableException
        Note over SVC,TX: 500 evidence-file-unavailable, transaction aborts,<br/>NO event is appended
    else file opens
        loop 8 KiB buffer, single streaming pass
            FS-->>SVC: bytes
            SVC->>SVC: digest.update(...) and byteCount += read
        end

        alt byteCount == 0
            SVC-->>TX: EvidenceFileUnavailableException
            Note over SVC,TX: known limitation: a readable zero-byte file is reported as a<br/>technical inability, not as valid=false, because the frozen<br/>IntegrityVerifiedPayload requires a positive fileSize
        else byteCount > 0
            SVC->>SVC: valid = (expectedSha == actualSha) AND (expectedSize == actualSize)
            SVC->>SVC: stampCommandInstant(occurredAt)
            SVC->>APP: INTEGRITY_VERIFIED payload (algorithm, expected, actual, valid, fileSize)
            APP-->>TX: event appended, chain advanced
            TX-->>SVC: 200 with valid true or false
            Note over TX,SVC: a mismatch is a SUCCESSFUL verification with valid=false,<br/>never an error; no stored hash, size or byte is ever rewritten
        end
    end
```

## 7. Custody-chain verification

`POST /api/v1/evidences/{evidenceId}/verify-chain`, implemented by `CustodyChainVerifier`. Side-effect-free: it never
mutates, and it never throws for corrupt stored data — every diagnosable failure comes back as a verdict. Reasons are
evaluated in strict precedence order and evaluation stops at the first violation.

```mermaid
flowchart TD
    START([Load stored count, stored head hash and all events ordered by sequence]) --> EMPTY{"events empty?"}
    EMPTY -- yes --> R_EMPTY["INVALID - EMPTY_CHAIN"]
    EMPTY -- no --> LEN{"loaded count == stored count?"}
    LEN -- no --> R_LEN["INVALID - CHAIN_LENGTH_MISMATCH"]
    LEN -- yes --> LOOP[["for each event, in sequence order"]]

    LOOP --> CASEID{"event.caseId == evidence case?"}
    CASEID -- no --> R_CASE["INVALID - CASE_MISMATCH"]
    CASEID -- yes --> EVID{"event.evidenceId == evidence?"}
    EVID -- no --> R_EVID["INVALID - EVIDENCE_MISMATCH"]
    EVID -- yes --> SEQ{"sequenceNumber == expected?"}
    SEQ -- no --> R_SEQ["INVALID - SEQUENCE_GAP"]
    SEQ -- yes --> GEN{"first event?"}

    GEN -- yes --> ZERO{"previousHash == zero hash?"}
    ZERO -- no --> R_GEN["INVALID - GENESIS_MISMATCH"]
    ZERO -- yes --> HV
    GEN -- no --> PREV{"previousHash == previous event hash?"}
    PREV -- no --> R_PREV["INVALID - PREVIOUS_HASH_MISMATCH"]
    PREV -- yes --> HV

    HV{"hashVersion == 1?"} -- no --> R_HV["INVALID - UNSUPPORTED_HASH_VERSION"]
    HV -- yes --> PV{"payloadVersion == 1?"}
    PV -- no --> R_PV["INVALID - UNSUPPORTED_PAYLOAD_VERSION"]
    PV -- yes --> DEC{"payload decodes and canonicalizes?"}
    DEC -- no --> R_DEC["INVALID - INVALID_PAYLOAD"]
    DEC -- yes --> RECOMP["recompute SHA-256 over<br/>domain separator + canonical JSON"]
    RECOMP --> EQ{"recomputed == stored eventHash?"}
    EQ -- no --> R_EQ["INVALID - EVENT_HASH_MISMATCH"]
    EQ -- yes --> NEXT{"more events?"}
    NEXT -- yes --> LOOP
    NEXT -- no --> HEAD{"calculated head == stored chain head anchor?"}
    HEAD -- no --> R_HEAD["INVALID - CHAIN_HEAD_MISMATCH"]
    HEAD -- yes --> OK(["VALID - checkedEvents = storedCount"])
```

A technical inability to read the chain at all is different from a verdict: it surfaces as
`custody-chain-read-failure` (500) rather than as `valid: false`.

## 8. Custody-chain structure

The stored shape of one evidence item's chain. Every evidence item has its own independent chain; there is no shared
or global ledger, and nothing is replicated anywhere.

```mermaid
flowchart LR
    subgraph ANCHOR["digital_evidence row - external anchor"]
        CNT["custody_event_count = 3"]
        HEAD["custody_chain_head_hash = hash(E3)"]
    end

    ZERO["previousHash of the genesis event<br/>0000...0000 (64 zeros)"]

    subgraph CHAIN["custody_events for this evidence - append-only"]
        E1["seq 1 - EVIDENCE_REGISTERED<br/>previousHash = zero hash<br/>eventHash = H1"]
        E2["seq 2 - CUSTODY_TRANSFERRED<br/>previousHash = H1<br/>eventHash = H2"]
        E3["seq 3 - EVIDENCE_SEALED<br/>previousHash = H2<br/>eventHash = H3"]
    end

    ZERO --> E1
    E1 -- "H1" --> E2
    E2 -- "H2" --> E3
    E3 -. "count and head mirrored on the aggregate<br/>in the same transaction" .-> ANCHOR

    HASH["eventHash = SHA-256 over<br/>proofchain:custody-event:v1 + LF + canonical JSON of the event<br/>fixed field order, UTC microseconds, explicit escaping, no whitespace<br/>UNKEYED - tamper evidence, not authorship"]
    E3 -.-> HASH
```

Why the external anchor exists: a chain alone cannot detect events removed from its tail — the remaining events would
still link correctly. `custody_event_count` and `custody_chain_head_hash` live on the `digital_evidence` row and are
updated inside the same transaction as the append, so a deleted tail shows up immediately as `CHAIN_LENGTH_MISMATCH` or
`CHAIN_HEAD_MISMATCH`.

What the chain does and does not prove:

- **Does prove** that the stored events are internally consistent with one another and with the anchor, so any edit,
  reordering, insertion or deletion is detectable by recomputation.
- **Does not prove** authorship or origin. The hash is unkeyed and there is no signature, no key material and no
  external timestamp authority. An actor able to rewrite the whole table and the anchor could produce a consistent
  chain. The append-only trigger, the `updatable = false` mappings and the absence of any write route are what make
  that require direct, privileged database access rather than an API call.

---

## Related documents

- [Technical report](./Technical-Report.md) — the full narrative these diagrams illustrate.
- [Custody Events](./Custody-Events.md) — protocol details, typed payloads and the reproducible fixed vector.
- [Operational Custody Workflows](./Operational-Custody-Workflows.md) — the authorization matrix and per-command contracts.
- [Database schema lifecycle](./Database-Schema-Lifecycle.md) — certified baselines, upgrade paths and recovery.
- [ADR index](./adr/README.md) — the decisions behind these structures.
