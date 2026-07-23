# Database migrations

Flyway is the only source of truth for ProofChain database schema changes.

Sprint 0 intentionally contains no SQL migration: PostgreSQL creates only the database and user, while Flyway creates and maintains `flyway_schema_history` on an empty database. The first domain migration is deferred to Sprint 1.

Add new schema changes as immutable, versioned SQL files in this directory, for example `V1__create_operators.sql`. Never edit an applied migration; introduce a new migration version for corrections.
