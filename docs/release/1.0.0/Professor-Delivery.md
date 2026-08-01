# ProofChain 1.0.0 — delivery package

## What this project is

ProofChain is a chain-of-custody backend for digital evidence. It records who held a piece of
evidence, what was done to it and when, in an append-only history that can be verified afterwards.

It is a **modular monolith** built on Spring Boot, with an **unkeyed SHA-256 hash chain per evidence
item**. It is not a blockchain, it uses no digital signatures and it performs no distributed
consensus. No SLA, capacity or production certification is claimed.

## Where the code is

| Item | Value |
| --- | --- |
| Repository | `pianic2/its-java-proofchain` |
| Delivery branch | `ijpc-8-sprint-6-final-delivery` |
| Certified commit | `a0a1c45ec3998eacf1a0c3505ada5fb5d0a60b74` |
| Certification anchor commit | `739d980c6256b4b7b321424741aa87808b7d3277` |
| Tag `uf14-final-2026` | **not created** — see "Delivery status" |
| GitHub Release | **not published** — see "Delivery status" |

> **Read the delivery branch, not `main`.** At the time of writing, `main` still points at an
> earlier commit and does not contain Sprint 3 through Sprint 6.

## Requirements

| Component | Version |
| --- | --- |
| Java | 25 |
| Maven | none required — the repository ships the Maven Wrapper (3.9.9) |
| PostgreSQL | 18.4 (provided by Docker Compose) |
| Docker | 29.x |
| Docker Compose | v5.x |

PostgreSQL is the only supported database. This is a deliberate deviation from the supplied
assessment rubric — see "Deviation requiring acknowledgement" below.

## Quick start with Docker Compose

```bash
git clone <repository-url> && cd its-java-proofchain
cp .env.example .env
# edit .env: set POSTGRES_PASSWORD, DB_PASSWORD and a Base64 PROOFCHAIN_JWT_SECRET of >= 32 bytes
#   openssl rand -base64 48
docker compose build
docker compose up -d
docker compose ps          # both services must report healthy
```

The application refuses to start with a missing, malformed or weak secret; that is intentional.
Full instructions, including the environment table and the operational commands, are in
[Operations](../../Operations.md) and [Configuration](../../Configuration.md).

## Running the tests

```bash
./mvnw --batch-mode --no-transfer-progress clean verify
```

Requires a running Docker daemon: the integration tests start a real PostgreSQL 18.4 through
Testcontainers. No global Maven installation is needed.

## API documentation

With the stack running:

- Swagger UI — `http://localhost:8080/swagger-ui.html`
- OpenAPI document — `http://localhost:8080/v3/api-docs`

The document is generated from the runtime, not hand-maintained. An automated test pins the surface
at exactly 27 approved operations, so an undocumented or unexpected endpoint fails the build.

## Postman

```
postman/ProofChain.postman_collection.json
postman/ProofChain.local.postman_environment.json
postman/README.md
```

97 requests and 200 assertions across 14 modules, covering the full positive lifecycle and the
negative and security scenarios. Run it from the GUI, or:

```bash
npx --yes newman@6.2.2 run postman/ProofChain.postman_collection.json \
  -e postman/ProofChain.local.postman_environment.json
```

The environment file contains placeholders only — no token, no password, no real data. Preconditions
are documented in `postman/README.md`.

## Demo

[Demo Guide](../../Demo-Guide.md) is the authoritative procedure: a numbered run of roughly 12–15
minutes with preconditions, exact requests, expected statuses and what to observe at each step. The
presentation source is `presentation/ProofChain.md`.

Helper scripts live in `scripts/demo/`:

| Script | Purpose |
| --- | --- |
| `demo-preflight.sh` | checks the environment before the demo |
| `demo-smoke.sh` | runs the approved verification flow |
| `demo-reset.sh` | **destructive** — prints its exact scope and asks for confirmation before removing only this project's Compose volumes and containers |

The two tampering scenarios — invalid file integrity and invalid chain verification — run only in a
disposable environment and require an explicit human step. No script alters evidence silently.

## Technical report

[Technical Report](../../Technical-Report.md) is the main document for assessment. It covers the
problem and objective, scope and exclusions, the modular monolith architecture, the domain model and
its invariants, authentication and authorization, persistence and Flyway, the filesystem storage
model, the custody event protocol and hash chain, the operational workflows, the transaction and
lock order, concurrency and rollback, the API and Problem Details, testing and coverage, the
container runtime, security hardening, known limitations and the delivery model.

Supporting documents: [Architecture](../../Architecture.md) with eight Mermaid diagrams,
[Custody Events](../../Custody-Events.md) with the reproducible hash vector, and the architecture
decision records `docs/adr/ADR-001` through `ADR-008`.

## Assessment rubric mapping

[ITS Compliance](../../ITS-Compliance.md) maps the delivered implementation to the supplied rubric,
point by point, including what was delivered, where it lives and what deviates.

## Verification evidence

Certified from a **separate clean clone**, so the result does not depend on the development machine:

| Check | Result |
| --- | --- |
| `./mvnw spotless:check` | PASS |
| `clean verify` run 1 | BUILD SUCCESS |
| `clean verify` run 2, no modification between runs | BUILD SUCCESS |
| Working tree before run 1 vs after run 2 | identical |
| Surefire | 443 tests, 0 failures, 0 errors, 4 skipped |
| Failsafe | 377 tests, 0 failures, 0 errors |
| JaCoCo LINE | 91.66% (gate 0.51) |
| JaCoCo BRANCH | 77.81% |
| Postman, twice from a destructive reset | 98 requests, 200 assertions, 0 failures — identical both runs |

The four skipped tests are POSIX permission assumptions that cannot be falsified while the build runs
as `root`. They are skipped with a stated reason, not disabled.

Environment: Ubuntu 24.04.4 LTS, kernel 6.18.5 x86_64, OpenJDK 25.0.3, Maven Wrapper → 3.9.9,
Docker 29.3.1, Compose v5.1.1, PostgreSQL 18.4, timezone UTC.

## Artifact checksums

| Artifact | Value |
| --- | --- |
| `target/proofchain-1.0.0.jar` | 69 284 949 bytes |
| SHA-256 | `d2ba94a420d9f59f63d087ed4cbfd26e6386b0536381ae5f63b58141be3664ce` |

The JAR is not committed. Reproduce it with
`./mvnw --batch-mode --no-transfer-progress clean package` from the certified commit.

## Deviation requiring acknowledgement

**PostgreSQL is the only supported database.** The supplied rubric anticipates a different or
portable database. The application was **not** redesigned to satisfy that expectation: Flyway is the
sole schema authority, the migrations are PostgreSQL-specific, and features such as the append-only
trigger, the lifecycle transition trigger and the pessimistic locking strategy rely on PostgreSQL
semantics. This is a deliberate engineering decision, recorded in the architecture decision records
and in [ITS Compliance](../../ITS-Compliance.md), and it requires explicit acknowledgement rather
than being presented as complete rubric coverage.

## Known limitations

The full list with reasoning is in [Known-Limitations.md](./Known-Limitations.md). The ones that
matter most for assessment:

1. **OWASP Dependency-Check has never been executed.** The profile exists and was run, but the NVD,
   the hosted suppressions and the CISA feed are unreachable from the build network, so it aborts
   with no data. **No vulnerability analysis was performed and no "zero vulnerabilities" claim may be
   inferred.** A reviewer with network access should run
   `./mvnw --batch-mode --no-transfer-progress -P dependency-check verify`.
2. A readable **zero-byte** evidence file is reported as a technical failure rather than as
   `valid=false`, because the frozen event payload requires a positive file size.
3. Descriptive metadata is not UTF-16 validated, so a malformed title fails at canonicalization and
   surfaces as an undeclared generic 500 — after a full rollback, so no partial state is committed.
4. Integrity verification updates `updatedAt` although it is not a mutating command.
5. An unsupported HTTP method returns a sanitized generic 500 rather than 405, on every path.
6. The Postman collection cannot produce the **invalid** integrity verdict, because no approved API
   can alter stored bytes. It is proven by integration tests; a manual step is documented.
7. No TLS, clustering, reverse proxy or rate limiting is delivered.

## Delivery status — read this before assessing

This release was produced through a delegated automated gate. The Project Owner authorized
autonomous AI delivery for the sessions that produced it.

**No human validation was performed.** Independent AI review is complete for the areas recorded in
[Certification-Report.md](./Certification-Report.md); teacher approval has not yet been performed.

Three items remain open and are stated here rather than left to be discovered:

1. **OWASP Dependency-Check has never run** (see above).
2. **The independent Sprint 4 AI review is complete** and returned *fit to certify*. Its one MAJOR
   finding was a documentation claim that overstated the database append-only guarantee; it is
   corrected. Six lesser findings were accepted without code change. See
   [AI-Validation-Record.md](./AI-Validation-Record.md).
3. **The tag `uf14-final-2026`, the GitHub Release and the merge into `main` have not been
   performed.** The delivery sessions had no GitHub write connector. The code, the tests and the
   documentation are complete and pushed on `ijpc-8-sprint-6-final-delivery`; the publication steps
   remain.

## Suggested assessment path

1. [Technical Report](../../Technical-Report.md) — the architecture and the engineering decisions.
2. [Architecture](../../Architecture.md) — the eight diagrams.
3. [Custody Events](../../Custody-Events.md) — the hash chain and the reproducible fixed vector; the
   published digest can be recomputed independently from the documented bytes.
4. `docker compose up -d`, then Swagger UI at `/swagger-ui.html`.
5. [Demo Guide](../../Demo-Guide.md) — the 12–15 minute run, including the tampering scenarios that
   show the chain detecting corruption.
6. `./mvnw --batch-mode --no-transfer-progress clean verify` — the full suite against a real
   PostgreSQL.
7. [ITS Compliance](../../ITS-Compliance.md) — the rubric mapping and the PostgreSQL deviation.
8. [Certification-Report.md](./Certification-Report.md) and
   [Known-Limitations.md](./Known-Limitations.md) — what was verified, and what was not.
