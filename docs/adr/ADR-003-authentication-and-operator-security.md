# ADR-003: Authentication and operator security

- Status: Accepted
- Date: 2026-07-28
- Scope: Sprint 1 authentication and operator management

## Context

ProofChain exposes username/password login, stateless bearer authentication and ADMIN-protected operator management. This record describes the security behavior implemented in the repository, including its operational logging boundary.

## Decisions

### JWT access tokens

- JJWT is version `0.13.0`, using `jjwt-api`, `jjwt-impl` and `jjwt-jackson`.
- Tokens are signed and verified with HS256.
- `PROOFCHAIN_JWT_SECRET` is standard Base64 decoded before key creation. The decoded key must contain at least 256 bits (32 bytes).
- The default access-token TTL is 30 minutes (`PT30M`) and remains externally configurable through `PROOFCHAIN_JWT_ACCESS_TOKEN_TTL`.
- The issuer is the fixed value `proofchain-api`.
- The allowlisted claims are `sub`, `username`, `role`, `iat`, `exp`, `jti` and `iss`.
- `sub` contains the operator UUID. `jti` is a UUID v4. The parser rejects unknown claims, future `iat`, non-positive lifetime, wrong issuer and non-HS256 algorithms.
- The signing key is never logged or returned by an API.

### Authentication and authorization

- Login uses the normalized username and password. It does not use email as a login identifier.
- BCrypt is the password encoder. Production strength defaults to 12; the test profile uses strength 4. Passwords are never returned or logged.
- The bootstrap configuration can create one normalized, ACTIVE `ADMIN` from externalized properties. It is idempotent while an ACTIVE ADMIN exists. Completion logging is registered after the surrounding transaction commits.
- Roles are `ADMIN`, `CASE_MANAGER`, `EVIDENCE_OFFICER` and `AUDITOR`.
- States are `ACTIVE`, `SUSPENDED` and `DISABLED`.
- Every authenticated request looks up the operator by UUID in the database. The database is authoritative for the current role and status; issued JWT claims do not grant authority by themselves.
- Operator administration uses dedicated request and response DTOs rather than serializing entities.
- The `@Version` field provides optimistic locking for individual operator updates.
- Changes that may reduce the ACTIVE ADMIN set use pessimistic locking of the ACTIVE ADMIN rows to protect the last-ACTIVE-ADMIN invariant.

### HTTP and public API contracts

- Authentication and application failures use Spring Problem Details with the existing problem types, including `invalid-credentials`, `authentication-required`, `invalid-token`, `expired-token`, `access-denied`, `duplicate-resource`, `operator-invariant-conflict` and `concurrent-modification`.
- This ADR adds no HTTP error, endpoint or response-field change.
- Swagger UI and the OpenAPI document are public at `/swagger-ui/index.html` and `/v3/api-docs`. Login and documentation routes are public; protected routes use the documented bearer scheme.

### Authentication event logging

- Authentication events are emitted through the centralized `AuthEventLogger` and the closed `AuthEvent.Event` set.
- The dedicated `AUTH_AUDIT` logger writes to the ignored, local `auth.log` file without propagating to the normal application logger.
- Each event uses this field order:

  ```text
  event=<EVENT> operatorId=<UUID|-> username=<VALUE|-> role=<ROLE|-> outcome=<OUTCOME> reason=<CODE|-> path=<PATH|->
  ```

- User-influenced username and path values are centrally sanitized. CR, LF and ISO control characters are removed; usernames are limited to 64 characters and paths to 512 characters. Missing optional values are `-`, and email-like username inputs are not written as complete email addresses.
- The logger accepts only the approved event fields. Passwords, password hashes, complete JWTs, signatures, secrets, Authorization headers and request bodies are never supplied to it.
- The implemented events are `LOGIN_SUCCESS`, `LOGIN_FAILURE`, `INVALID_TOKEN`, `EXPIRED_TOKEN`, `ACCESS_DENIED`, `INACTIVE_OPERATOR_ACCESS`, `BOOTSTRAP_ADMIN_COMPLETED` and `BOOTSTRAP_ADMIN_SKIPPED`.
- Login success is emitted only after credential validation and token issuance. Invalid and expired tokens, inactive operator access and access denial are emitted at their existing security boundaries. Bootstrap completion is emitted only after commit; logging failures are swallowed so they cannot change an authentication outcome.

## Deferred capabilities

The Sprint 1 implementation does not include refresh tokens, token revocation, server-side logout, MFA, an authentication audit database table, production retention or log shipping, SIEM integration, metrics, dashboards, distributed tracing, IP-address trust policy, custody-event logging or legal-grade audit evidence.

## Consequences

The application has reviewable local authentication visibility while keeping security data out of the database and public HTTP contracts. `auth.log` is an operational diagnostic file, not a production-grade audit or retention subsystem.
