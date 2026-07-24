# ADR-001: Foundation baseline

## Status

Accepted for Sprint 0. This cumulative ADR records the minimum foundation and is updated only when an approved implementation changes it.

## Context

ProofChain is a time-bounded ITS project, not a long-running platform. It needs a reproducible baseline that is easy to explain, review, and update without creating a disproportionate documentation hierarchy. The same quality command must validate a developer checkout and GitHub Actions.

## Decision

- Use Java 25 LTS, Spring Boot 4.0.7, and Maven Wrapper 3.9.9.
- Use `spring-boot-starter-webmvc` for the HTTP layer and `springdoc-openapi-starter-webmvc-ui:3.0.2` for OpenAPI documentation.
- Organize the application as a modular monolith with feature-first packages.
- Use PostgreSQL `18.4-trixie` for local development and PostgreSQL Testcontainers for integration tests.
- Use Flyway as the schema source of truth with `baseline-on-migrate=false`; do not use Hibernate DDL generation for schema changes.
- Keep configuration externalized through Spring placeholders and local environment values; application code must not contain secrets.
- Use `./storage` as the default storage root and a `50MB` MVP upload limit configurable through `PROOFCHAIN_STORAGE_ROOT` and `PROOFCHAIN_MAX_FILE_SIZE`.
- Represent timestamps as ISO-8601 UTC values.
- Use Spring Problem Details for application and controller errors.
- Keep GitHub as the technical source of truth, Jira as the work-management source of truth, and Confluence as the concise monitoring and professor-review hub.
- Use Spotless `3.6.0` with `palantir-java-format 2.78.0`, verified under Java 25. Bind formatting checks to the Maven lifecycle and keep `spotless:apply` local-only.
- Use `./mvnw --batch-mode --no-transfer-progress clean verify` as the canonical quality command. Maven owns formatting checks, compilation, tests, packaging, and report generation. GitHub Actions provisions the environment and invokes the same command without modifying source files.

## Consequences

The project has one documented, reproducible engineering baseline and one technical evidence location. Feature boundaries remain explicit without introducing a framework or governance layer for its own sake. Fast tests and Docker-backed integration tests stay separate, and coverage is measured without a premature blocking threshold. The upload-size baseline is explicit rather than inferred from configuration. Later domain work must update this ADR only when an approved implementation changes a recorded foundation decision.

## Evidence

- `pom.xml` declares Java 25, Spring Boot 4.0.7, the approved web and OpenAPI dependencies, Spotless `3.6.0`, `palantir-java-format 2.78.0`, Maven test plugins, JaCoCo, and Testcontainers.
- `src/main/resources/application.yml` declares UTC handling, Flyway locations, `baseline-on-migrate=false`, the `50MB` configurable upload limit, and the configurable `./storage` root.
- `.env.example` exposes `PROOFCHAIN_MAX_FILE_SIZE=50MB` and `PROOFCHAIN_STORAGE_ROOT=./storage` without real credentials.
- `src/main/resources/application-local.yml` externalizes PostgreSQL connection values.
- `compose.yml` provisions PostgreSQL `18.4-trixie`; integration tests provision an independent database through Testcontainers.
- `.github/workflows/quality.yml` provisions Temurin Java 25 and invokes the canonical Maven command with read-only repository permissions.
