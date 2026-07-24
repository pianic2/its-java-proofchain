# Contributing to ProofChain

## Quality gate

The single quality command is:

```bash
./mvnw --batch-mode --no-transfer-progress clean verify
```

Run it before opening a pull request. Maven owns the quality lifecycle locally and in GitHub Actions: it checks formatting, compiles, runs tests, packages the application, and writes reports. GitHub Actions only provides Java 25 and Docker, then invokes the same command.

## Test naming

Use the standard Maven test names, such as `*Test.java`, for fast unit and MVC tests. Maven Surefire runs these tests.

Use `*IT.java` exclusively for integration tests. Maven Failsafe runs these tests during the integration-test and verify phases. Do not use `IntegrationTest` in a class name. This separation keeps Docker-backed Testcontainers checks out of the fast test phase and prevents a test from running twice.

`DatabaseBootstrapIT` is the baseline integration test. It starts its own `postgres:18.4-trixie` container through Testcontainers and never uses the local Docker Compose database.

## Formatting

Spotless checks the Java sources, root Markdown files, the Maven POM, and GitHub Actions YAML during Maven's validate phase. Java formatting is frozen to `palantir-java-format 2.78.0`, verified by the Java 25 CI runner.

Check formatting only:

```bash
./mvnw spotless:check
```

Apply safe automatic corrections locally:

```bash
./mvnw spotless:apply
```

Never commit a CI-generated correction: CI runs only `spotless:check` and fails if formatting is not compliant.

## Reports

After a successful `clean verify`, inspect:

- `target/surefire-reports/` for fast-test reports.
- `target/failsafe-reports/` for integration-test reports.
- `target/site/jacoco/index.html` for coverage.

JaCoCo is report-only in Sprint 0. There is no blocking coverage percentage because domain behaviour has not been implemented yet. A threshold above 50% will be introduced when there is sufficient domain logic to make it meaningful.

## CI scope

The GitHub Actions Quality workflow runs for pull requests and pushes to `main`. It has read-only repository permissions, a 15-minute timeout, cancels obsolete runs for the same ref, and uploads the JaCoCo, Surefire, and Failsafe reports for seven days.

Checkstyle and ArchUnit are intentionally absent from Sprint 0. Spotless already owns deterministic formatting; architecture rules will be introduced only when concrete modular boundaries can be enforced.
