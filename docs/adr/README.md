# Architecture Decision Records

This index lists the architecture decisions that are implemented in the repository. Numbering is dense and gapless, and
every record appears here exactly once — `DocumentationLinkAuditTest` fails the build otherwise.

| ADR | Title | Status | Subject |
| --- | --- | --- | --- |
| 001 | [Foundation baseline](./ADR-001-foundation-baseline.md) | Accepted | Project, build, quality and governance foundation |
| 002 | [Sprint 1 baseline](./ADR-002-sprint-1-baseline.md) | Accepted | Modular monolith structure, persistence and Problem Details baseline |
| 003 | [Authentication and operator security](./ADR-003-authentication-and-operator-security.md) | Accepted | Login, JWT, database-backed authorization, password controls, audit logging |
| 004 | [Sprint 2 custody cases and membership](./ADR-004-sprint-2-custody-cases-and-membership.md) | Accepted | Case lifecycle, contextual access, the explicit `CaseMembership` join entity, concurrency |
| 005 | [Sprint 3 digital evidence and filesystem storage](./ADR-005-sprint-3-digital-evidence-and-filesystem-storage.md) | Accepted | Evidence aggregate, registration, integrity hashes, hardened filesystem adapter |
| 006 | [Sprint 4 custody events and hash chain](./ADR-006-sprint-4-custody-events-and-hash-chain.md) | Accepted | Custody-event model, canonical hashing protocol, append-only persistence, genesis, backfill, reads, chain verification |
| 007 | [Sprint 5 operational custody workflows](./ADR-007-sprint-5-operational-custody-workflows.md) | Accepted | The five named commands, authorization matrix, lifecycle graph, locking and transaction contract, Problem Details catalogue |
| 008 | [Sprint 6 release, runtime and delivery baseline](./ADR-008-sprint-6-release-runtime-and-delivery-baseline.md) | Accepted | Java 25 and `1.0.0` freeze, release and tag model, container image and hardened runtime, restricted health model, fail-fast configuration, PostgreSQL retention and the ITS deviation, non-destructive orphan reporting, certification and human gate |

## How to read them

Each record states the context that forced a decision, the decisions themselves — including the alternatives that were
rejected and why — the consequences the project accepted, and where the evidence lives. Rejected options are recorded
deliberately: a decision without its discarded alternatives is not reviewable.

Historical ADRs are immutable except for factual link or index corrections. A material decision affecting
architectural boundaries, persistence, security, the release model or the runtime requires a new ADR; an implementation
detail does not.

## Where each subject is settled

- Authentication and operator security: **ADR-003**.
- Custody case lifecycle, contextual access, membership and concurrency: **ADR-004**.
- Digital-evidence registration, integrity hashes, persistence and filesystem storage: **ADR-005**.
- The custody-event model, canonical hashing protocol, append-only persistence, registration genesis, the `V6`
  backfill, the read APIs and chain verification: **ADR-006**.
- Evidence transfer, descriptive metadata update, file-integrity verification, sealing and release — with their
  authorization matrix, lifecycle graph, locking and transaction contract and Problem Details catalogue: **ADR-007**.
- The Java 25 and `1.0.0` baseline, the release and tag model, the container image and its non-root read-only runtime,
  the restricted health model, externalized fail-fast configuration, the retention of PostgreSQL with the ITS
  deviation it creates, non-destructive orphan reporting, and the final certification and human gate: **ADR-008**.

## Related documents

- [Technical report](../Technical-Report.md) — the implemented system these decisions produced.
- [Architecture](../Architecture.md) — the diagrams the decisions are visible in.
- [ITS compliance](../ITS-Compliance.md) — the rubric mapping and the deviations ADR-008 records.
