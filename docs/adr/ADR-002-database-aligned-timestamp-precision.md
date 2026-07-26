# ADR-002: Database-aligned timestamp precision

## Status

Accepted for Sprint 1.

## Context

ProofChain represents domain timestamps with Java `Instant` values and persists them to PostgreSQL `TIMESTAMPTZ` columns. Java can represent nanosecond precision, while PostgreSQL stores these timestamps at microsecond precision.

A domain update previously guaranteed monotonic `updatedAt` values by adding one nanosecond when `Instant.now()` did not advance. That increment can be lost when the value is persisted because it is below the database precision. The in-memory aggregate could therefore observe a strictly newer timestamp while a database round trip returned the same stored value.

ProofChain depends on timestamps for auditability and deterministic evidence. The domain model must not promise temporal precision that the persistence boundary cannot preserve.

## Decision

- Domain timestamps persisted to PostgreSQL are normalized to microsecond precision with `Instant.truncatedTo(ChronoUnit.MICROS)`.
- Aggregate creation timestamps and later update timestamps use the same precision contract.
- When the current clock value does not advance beyond the previous domain timestamp, the next value advances by exactly one microsecond.
- Tests must verify both strict monotonicity and microsecond normalization.
- JPA `@Version` remains the authoritative mechanism for optimistic concurrency control. Timestamps are audit data and must not replace version checks.
- Future persisted domain timestamps must follow this precision contract unless the database technology or schema precision changes through a later ADR.

## Consequences

The Java domain and PostgreSQL persistence model now share one explicit timestamp precision. Rapid successive updates remain distinguishable after persistence, tests no longer depend on nanosecond values that PostgreSQL cannot retain, and timestamp behavior is easier to explain and review.

The system intentionally gives up unused nanosecond precision for persisted domain state. Any component that introduces persisted timestamps must normalize them consistently. A future database change with different timestamp semantics requires review of this decision and its tests.

## Evidence

- `Operator` truncates creation and update timestamps to `ChronoUnit.MICROS`.
- `Operator.touch()` advances by one microsecond when the clock has not moved beyond the current value.
- `OperatorTest` verifies microsecond normalization and strictly increasing update timestamps.
- GitHub Copilot review discussion `discussion_r3652265826` identified the mismatch between Java nanosecond precision and PostgreSQL microsecond persistence.
