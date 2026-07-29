# ADR-004: Sprint 2 custody cases and membership

- Status: Accepted
- Date: 2026-07-29
- Scope: Sprint 2 custody case lifecycle, access, persistence, and membership

## Context

ProofChain needs a stable boundary for grouping future evidence and custody events. That boundary must support case metadata and closure while preventing unauthorized discovery, partial creation, duplicate assignments, and concurrent changes that leave a case without a responsible manager.

Global operator roles alone cannot express which non-ADMIN operators may see a particular case. At the same time, authorization must continue to use current database role and status rather than stale JWT claims. The persistence design therefore needs both a case aggregate and an explicit contextual membership relation.

## Decisions

### Aggregate and lifecycle

- `CustodyCase` is the aggregate root for case metadata and lifecycle state.
- A case starts OPEN. Closing is irreversible; targeting CLOSED repeatedly is idempotent, while targeting OPEN is an invalid transition.
- Closed cases reject metadata and membership changes.
- Persisted timestamps use the microsecond precision established by ADR-002. JPA `@Version` protects competing changes to one case.
- Request and response models are separate from JPA entities. Persistence versions and sensitive operator data are not public API fields.

### Contextual access

- ADMIN has global case visibility and mutation authority.
- Other authenticated operators see only cases for which they have a membership.
- An inaccessible existing case is indistinguishable from a missing case and returns `404`.
- An assigned CASE_MANAGER may update metadata, close the case, and manage membership. Assigned EVIDENCE_OFFICER and AUDITOR operators have read-only case access.
- Case creation is restricted to ACTIVE ADMIN and CASE_MANAGER operators and creates the creator membership atomically with the case.

### Membership invariant

- `CaseMembership` is an explicit persisted relation with immutable case, operator, assigner, and assignment timestamp fields.
- The database enforces one membership per case and operator.
- Manual assignment accepts ACTIVE non-ADMIN targets. Repeated assignment and removal are idempotent.
- Every case must retain at least one membership whose operator is currently ACTIVE and has ADMIN or CASE_MANAGER role.
- Operator role and status mutations enforce the same responsible-manager invariant across all affected cases.

### Transaction and lock strategy

- Case creation and creator membership commit in one transaction.
- Metadata and closure conflicts are detected with optimistic locking.
- Membership mutations take a pessimistic write lock on the case before evaluating and changing its membership set.
- Operations that reduce an operator's responsibility lock affected cases in UUID order, verify that the affected set remained stable, and use a bounded fresh-transaction retry if concurrent membership changes invalidate that set.
- The named membership uniqueness constraint is the final authority for duplicate insertion races. Recovery reads the committed existing membership in a fresh transaction; unrecoverable lock or integrity races use a stable conflict response.

### HTTP contract

- Case routes are rooted at `/api/v1/cases` and require bearer authentication.
- Collection ordering is fixed: cases use `createdAt DESC, id ASC`; memberships use `assignedAt ASC, id ASC`.
- Client-controlled case sorting is rejected. Case pagination is zero-based with a size from 1 to 100.
- Errors use the existing Problem Details envelope plus case-specific stable conflict types.
- OpenAPI documents the strict request shapes, response schemas, examples, success and error statuses, and bearer requirement. The runtime remains authoritative if documentation and implementation diverge.

## Consequences

Case discovery is predictable and does not reveal inaccessible identifiers. Authorization remains contextual without duplicating operator identity, and current database role/status changes immediately affect responsibility. Named constraints, transactional creation, optimistic versions, and ordered pessimistic locks provide complementary protection for distinct concurrency risks.

The design introduces coordination between operator mutations and case memberships. This is intentional because responsibility is derived from current operator state. Evidence storage and custody events remain outside this ADR and must use this established case boundary without weakening its access or lifecycle invariants.
