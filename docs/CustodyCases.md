# Custody Cases

## Purpose

A custody case is the access and lifecycle boundary that groups future evidence and custody activity. Sprint 2 implements case metadata, deterministic discovery, irreversible closure, and explicit operator membership. It does not yet implement evidence ingestion or custody events.

The API uses dedicated request and response models. Persistence entities, JPA versions, operator email addresses, password data, and other internal fields are not exposed.

## Domain model

[`CustodyCase`](../src/main/java/it/itsprodigi/proofchain/custodycase/domain/CustodyCase.java) is the aggregate root stored in `custody_cases`.

| Field | Rule |
| --- | --- |
| `id` | Random UUID, immutable after creation. |
| `title` | Required, trimmed, 3–200 characters. |
| `description` | Optional, trimmed, at most 2,000 characters; blank becomes `null`. |
| `authorityName`, `externalReference` | Optional, trimmed, at most 200 characters; blank becomes `null`. |
| `location` | Optional, trimmed, at most 300 characters; blank becomes `null`. |
| `priority` | `LOW`, `MEDIUM`, `HIGH`, or `CRITICAL`. |
| `status` | `OPEN` at creation, then irreversibly `CLOSED`. |
| `createdBy` | Immutable operator identity that created the case. |
| `createdAt`, `updatedAt`, `closedAt` | UTC instants normalized to PostgreSQL microsecond precision. `closedAt` is null only while OPEN. |
| `version` | JPA optimistic-lock value; never part of the HTTP response. |

Metadata is mutable only while the case is OPEN. A partial update preserves omitted fields. Explicit `null` or blank clears nullable text, while `title` and `priority` cannot be null. Empty documents, unknown properties, invalid enum values, and values of the wrong JSON type are rejected.

Closing a case is irreversible. `{"status":"CLOSED"}` performs the transition; repeating the same command is idempotent and returns the unchanged closure timestamps. `{"status":"OPEN"}` is a `409` invalid transition rather than a reopen operation.

## Membership model

[`CaseMembership`](../src/main/java/it/itsprodigi/proofchain/custodycase/domain/CaseMembership.java) records the case, assigned operator, assigning operator, and microsecond-precision assignment time. A named database constraint permits at most one membership for each `(case_id, operator_id)` pair.

Creating a case atomically creates the creator membership, including when the creator is an ADMIN. Manual assignment accepts an ACTIVE non-ADMIN operator. A repeated assignment returns the original membership with `200`; a new assignment returns `201`. Removal is idempotent and returns `204` whether or not the target membership existed.

Membership changes are allowed only while the case is OPEN. The system prevents removing the final responsible manager. A responsible manager is a membership whose current operator state is ACTIVE and whose current role is ADMIN or CASE_MANAGER. Operator role and status changes enforce the same invariant across every affected case.

## Access rules

All case routes require bearer authentication. The database-backed principal described in [Authentication](./Auth.md) supplies the current operator role and status.

| Operation | ADMIN | CASE_MANAGER | EVIDENCE_OFFICER or AUDITOR |
| --- | --- | --- | --- |
| Create a case | Allowed; creator membership is created | Allowed; creator membership is created | `403` |
| List or read cases | All cases | Membership-scoped | Membership-scoped |
| List members | All cases | Membership-scoped | Membership-scoped |
| Update metadata or close | All visible cases | Own memberships only | `403` for a visible case |
| Assign or remove members | All visible OPEN cases | Own OPEN memberships only | `403` for a visible case |

A non-ADMIN caller receives `404` for an existing case without membership, exactly as for a missing case. This anti-enumeration rule is evaluated before role-based mutation denial, so a non-member CASE_MANAGER also receives `404`. An assigned EVIDENCE_OFFICER or AUDITOR can read the case and member list but receives `403` when attempting a mutation.

## HTTP API

The generated OpenAPI document is the field-level contract and includes strict schemas, examples, response status codes, and the bearer security requirement. The implemented routes are:

| Method and path | Success | Notes |
| --- | --- | --- |
| `POST /api/v1/cases` | `201` | Creates the case and creator membership in one transaction; returns `Location`. |
| `GET /api/v1/cases?page=0&size=20` | `200` | Fixed order: `createdAt DESC, id ASC`; page is zero-based, size is 1–100, and every `sort` parameter is rejected. |
| `GET /api/v1/cases/{caseId}` | `200` | Returns an accessible case. |
| `PATCH /api/v1/cases/{caseId}` | `200` | Strict partial metadata update on an OPEN case. |
| `PATCH /api/v1/cases/{caseId}/status` | `200` | Irreversible, idempotent CLOSED target. |
| `GET /api/v1/cases/{caseId}/members` | `200` | Fixed order: `assignedAt ASC, membership id ASC`. |
| `PUT /api/v1/cases/{caseId}/members/{operatorId}` | `201` or `200` | New or unchanged existing membership. |
| `DELETE /api/v1/cases/{caseId}/members/{operatorId}` | `204` | Idempotent removal subject to the responsible-manager invariant. |

Example creation request:

```json
{
  "title": "Mobile device seizure",
  "description": "Device collected under warrant 2026-0142.",
  "authorityName": "Court of Rome",
  "externalReference": "WARRANT-2026-0142",
  "location": "Evidence room A",
  "priority": "HIGH"
}
```

## Transactions and concurrency

Creation persists the case and creator membership in one transaction: either both commit or neither does. Metadata and closure use JPA optimistic locking; competing stale changes return the stable `concurrent-modification` problem.

Membership mutations pessimistically lock the case before changing memberships. Duplicate insertion races rely on the named unique constraint and are recovered in a fresh transaction as the idempotent existing result when possible. Case locks serialize competing removals and assignments. Operations that reduce an operator's responsible role or ACTIVE state lock all affected cases in UUID order, verify that the affected membership set stayed stable, and require another responsible manager for each case. The bounded retry occurs at the operator mutation boundary when that set changes concurrently.

## Persistence

[`V2__create_custody_cases_and_memberships.sql`](../src/main/resources/db/migration/V2__create_custody_cases_and_memberships.sql) creates `custody_cases` and `case_memberships`. PostgreSQL enforces field lengths, normalized text, enum values, status/closure consistency, non-negative versions, foreign keys, and membership uniqueness. Indexes support the fixed case ordering, fixed membership ordering, and operator-to-case visibility lookup. Flyway owns this schema and Hibernate validates it.

## Error contracts

Errors use `application/problem+json` with a stable type, request instance, and UTC timestamp.

| Situation | HTTP | Problem type |
| --- | --- | --- |
| Invalid UUID, JSON, field, enum, page, size, or sort | `400` | `https://proofchain.dev/problems/validation-error` |
| Missing, invalid, or expired authentication | `401` | Authentication types described in [Authentication](./Auth.md#error-contracts) |
| Visible case but insufficient mutation authority | `403` | `https://proofchain.dev/problems/access-denied` |
| Missing case, hidden case, or missing assignment target | `404` | `https://proofchain.dev/problems/resource-not-found` |
| Mutation attempted after closure | `409` | `https://proofchain.dev/problems/case-closed` |
| OPEN supplied to the closure endpoint | `409` | `https://proofchain.dev/problems/invalid-case-status-transition` |
| Removal would leave no responsible manager | `409` | `https://proofchain.dev/problems/last-case-manager-removal` |
| Assignment target is inactive or ADMIN | `409` | `https://proofchain.dev/problems/operator-not-active` or `https://proofchain.dev/problems/admin-membership-not-assignable` |
| Optimistic or membership concurrency conflict | `409` | `https://proofchain.dev/problems/concurrent-modification` or `https://proofchain.dev/problems/concurrent-membership-conflict` |

## Testing coverage

- [`CustodyCaseTest`](../src/test/java/it/itsprodigi/proofchain/custodycase/domain/CustodyCaseTest.java) covers normalization, states, closure, timestamps, equality, and safe output.
- [`CustodyCaseServiceTest`](../src/test/java/it/itsprodigi/proofchain/custodycase/application/CustodyCaseServiceTest.java), [`CaseAccessServiceTest`](../src/test/java/it/itsprodigi/proofchain/custodycase/application/CaseAccessServiceTest.java), and the membership service tests cover business rules and authorization decisions.
- [`CaseControllerWebMvcTest`](../src/test/java/it/itsprodigi/proofchain/custodycase/api/CaseControllerWebMvcTest.java) and [`CaseMembershipControllerWebMvcTest`](../src/test/java/it/itsprodigi/proofchain/custodycase/api/CaseMembershipControllerWebMvcTest.java) cover the HTTP, security, Problem Details, and OpenAPI contracts.
- [`CustodyCaseRepositoryIT`](../src/test/java/it/itsprodigi/proofchain/custodycase/persistence/CustodyCaseRepositoryIT.java), [`CustodyCaseLockIT`](../src/test/java/it/itsprodigi/proofchain/custodycase/persistence/CustodyCaseLockIT.java), and [`CustodyCaseApplicationIT`](../src/test/java/it/itsprodigi/proofchain/custodycase/CustodyCaseApplicationIT.java) verify Flyway, PostgreSQL constraints, transactions, visibility, locks, and optimistic conflicts.
- [`CaseMembershipConcurrencyIT`](../src/test/java/it/itsprodigi/proofchain/custodycase/CaseMembershipConcurrencyIT.java) verifies duplicate assignment, competing removals, operator-state changes, ordered multi-case locks, stable-set retry, and rollback.

The architectural baseline is recorded in [ADR-004](./adr/ADR-004-sprint-2-custody-cases-and-membership.md).
