# Database migrations

Flyway is the only source of truth for ProofChain database schema changes.

Sprint 0 intentionally contained no SQL migration. Sprint 1 starts the domain schema with `V1__create_operators.sql`; PostgreSQL still creates only the database and user, while Flyway owns all tables, constraints and indexes.

Add new schema changes as immutable, versioned SQL files in this directory, for example `V1__create_operators.sql`. Never edit an applied migration; introduce a new migration version for corrections.
