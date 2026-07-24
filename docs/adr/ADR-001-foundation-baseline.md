# ADR-001: Foundation baseline

- Status: Accepted
- Date: 2026-07-24

## Context

ProofChain needs a reproducible Sprint 0 engineering baseline before domain development begins. The project must validate the same way on a developer machine and in GitHub Actions.

## Decision

ProofChain uses Java 25, Spring Boot 4.0.7, Maven Wrapper 3.9.9, PostgreSQL, Flyway, and Testcontainers with `postgres:18.4-trixie`.

The canonical quality command is:

```bash
./mvnw --batch-mode --no-transfer-progress clean verify
```

Maven owns the quality lifecycle:

- Spotless 3.6.0 runs `spotless:check` in `validate`; Java formatting uses `palantir-java-format 2.78.0`.
- Surefire 3.5.4 runs fast tests using standard names and excludes `*IT.java`.
- Failsafe 3.5.4 runs only `*IT.java` integration tests.
- JaCoCo 0.8.15 writes a report without a blocking coverage threshold in Sprint 0.

GitHub Actions provisions Temurin Java 25 and Docker, then invokes the canonical Maven command for pull requests and pushes to `main`. It has only `contents: read` permission and retains quality reports for seven days.

Checkstyle and ArchUnit are deferred. No GitHub Actions PostgreSQL service container is used; integration tests provision their own database through Testcontainers.

## Consequences

Formatting, test execution, packaging, and coverage reporting have one source of truth. Fast tests and Docker-backed integration tests are reported separately and cannot be executed twice. The project gains a measured coverage baseline without imposing a meaningless target before domain logic exists.
