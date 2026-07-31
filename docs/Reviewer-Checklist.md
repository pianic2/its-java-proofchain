# Reviewer checklist

A self-service assessment path for ProofChain `1.0.0`, executable in **20–30 minutes** without a presenter. Every item
is a command you run and an observation you record. Nothing here requires reading the source.

If you also want the guided walkthrough, use the [demo guide](./Demo-Guide.md); the slide source for the talk is
[`presentation/ProofChain.md`](../presentation/ProofChain.md).

| # | Section | Budget |
| --- | --- | --- |
| 1 | [Clone and commit expectation](#1-clone-and-commit-expectation) | 2 min |
| 2 | [Environment setup](#2-environment-setup) | 3 min |
| 3 | [Maven verification](#3-maven-verification) | 8–12 min (first run downloads dependencies) |
| 4 | [Coverage and test evidence](#4-coverage-and-test-evidence) | 2 min |
| 5 | [Docker Compose startup and health](#5-docker-compose-startup-and-health) | 3 min |
| 6 | [OpenAPI access](#6-openapi-access) | 1 min |
| 7 | [Core positive workflow](#7-core-positive-workflow) | 4 min |
| 8 | [Security and error examples](#8-security-and-error-examples) | 3 min |
| 9 | [Documentation and ITS mapping](#9-documentation-and-its-mapping) | 3 min |
| 10 | [Release artifacts and the final tag](#10-release-artifacts-and-the-final-tag) | 1 min |
| 11 | [Clean up](#11-clean-up) | 1 min |

Section 3 dominates the budget. If you are short of time, run sections 1–2, start section 3 in a second terminal and
continue with sections 5–10 while it runs.

Prerequisites: Java 25, Docker Engine with Compose v2, `curl`, `jq`. Docker must be able to run
`postgres:18.4-trixie`, `eclipse-temurin:25-jdk` and `eclipse-temurin:25-jre`. Sections 3 and 4 need network access to
Maven Central on the first run; section 6 of the demo guide's optional Newman run additionally needs Node.js.

---

## 1. Clone and commit expectation

```bash
git clone <repository-url> proofchain
cd proofchain
git log --oneline -1
git status --short
```

| Expected | Note |
| --- | --- |
| A clean working tree | `git status --short` prints nothing |
| The delivered commit on the final delivery branch | The Project Owner supplies the exact commit or tag under assessment |
| `git tag -l` prints **nothing** | The delivery tag `uf14-final-2026` is the Project Owner's final acceptance action and deliberately does not exist yet. Its absence is expected, not a defect |

Also confirm nothing sensitive is tracked:

```bash
git ls-files | grep -E '(^|/)\.env$' || echo 'no tracked .env — correct'
git ls-files | grep -E '\.(dump|sql\.gz|tar\.gz|log)$' || echo 'no tracked dump, archive or log — correct'
```

## 2. Environment setup

```bash
cp .env.example .env
openssl rand -base64 32          # paste as PROOFCHAIN_JWT_SECRET
```

Then edit `.env`: set `POSTGRES_PASSWORD` and `DB_PASSWORD` to the same local value, paste the JWT secret, and enable
the opt-in bootstrap administrator:

```bash
SPRING_PROFILES_ACTIVE=container
PROOFCHAIN_BOOTSTRAP_ADMIN_ENABLED=true
PROOFCHAIN_BOOTSTRAP_ADMIN_USERNAME=proofchain-admin
PROOFCHAIN_BOOTSTRAP_ADMIN_EMAIL=proofchain-admin@example.org
PROOFCHAIN_BOOTSTRAP_ADMIN_PASSWORD=<a local password of at least 12 characters>
PROOFCHAIN_MAX_FILE_SIZE=1MB
PROOFCHAIN_MAX_REQUEST_SIZE=2MB
```

| Expected | Note |
| --- | --- |
| `.env.example` contains only placeholders | No value in it authenticates anything anywhere |
| `.env` is git-ignored | `git check-ignore -v .env` names the `.gitignore` rule |
| No secret has a default | The application generates nothing and defaults nothing; a missing JWT secret stops startup |

The full variable reference is the [configuration baseline](./Configuration.md).

## 3. Maven verification

The single quality gate. It runs the format check, compilation, Surefire, packaging, Failsafe (Testcontainers-backed,
so Docker must be running), the JaCoCo report and the coverage gate.

```bash
export JAVA_HOME=<your Java 25 home>
./mvnw --batch-mode --no-transfer-progress clean verify
```

| Expected | Note |
| --- | --- |
| `BUILD SUCCESS` | This is the same command GitHub Actions runs; CI never runs `spotless:apply` and never modifies sources |
| Surefire: 443 tests, 0 failures, 0 errors, **4 skipped** | The four skips are documented: two storage classes assert POSIX permission behaviour and abort through JUnit `Assumptions` when the build runs as `root` |
| Failsafe: 377 tests, 0 failures, 0 errors, 0 skipped | Each `*IT.java` provisions its own PostgreSQL 18.4 through Testcontainers |

Counts and the reasoning behind them are in [Testing](./Testing.md); a run on a different machine may differ slightly
in timing but not in outcome.

## 4. Coverage and test evidence

```bash
grep -o 'LINE[^/]*' target/site/jacoco/jacoco.xml | head -1
open target/site/jacoco/index.html      # or: xdg-open
ls target/surefire-reports target/failsafe-reports | head
```

| Expected | Note |
| --- | --- |
| Line coverage ≈ **91.66 %** (4 167 / 4 546) | Far above the enforced gate |
| The JaCoCo gate is `BUNDLE / LINE / COVEREDRATIO` at **0.51** | Verify in `pom.xml` |
| **No `<excludes>` element** in the JaCoCo configuration | `grep -c '<excludes>' pom.xml` — nothing is hidden from the measurement |

## 5. Docker Compose startup and health

```bash
./scripts/demo/demo-preflight.sh
```

The preflight validates the tooling and `.env`, checks the published ports, builds the image if it is absent, starts
the stack and waits for readiness. To do it by hand instead: `docker compose up -d --build`.

```bash
docker compose ps
curl -s http://localhost:8080/actuator/health          | jq .
curl -s http://localhost:8080/actuator/health/liveness | jq .
curl -s http://localhost:8080/actuator/health/readiness| jq .
docker compose exec proofchain id
```

| Expected | Note |
| --- | --- |
| Both services `Up (healthy)` | The application container starts only after the PostgreSQL healthcheck — no sleep in the startup path |
| Three `200` responses, all `{"status":"UP"}` | Bare status only: no version, JDBC URL, filesystem path or free-space figure |
| `uid=10001(proofchain) gid=10001(proofchain)` | Non-root runtime, read-only root filesystem, `cap_drop: ALL` |
| Any other actuator path returns `401`/`404` | e.g. `curl -s -o /dev/null -w '%{http_code}\n' http://localhost:8080/actuator/env` |

## 6. OpenAPI access

```bash
curl -s http://localhost:8080/v3/api-docs | jq '{version: .info.version, security: .components.securitySchemes}'
```

Then open `http://localhost:8080/swagger-ui/index.html`.

| Expected | Note |
| --- | --- |
| `"version": "1.0.0"` | Frozen in `pom.xml`, the document, the image label and the Compose tag; `ReleaseBaselineTest` asserts all of them |
| A global `bearerAuth` scheme | Exactly one operation is unauthenticated: login |
| **No** static specification file in the repository | The document is generated from the live request mappings, so it cannot drift from the code |
| No actuator path appears in the document | |

## 7. Core positive workflow

Either run the delivered Postman collection, or follow the guide by hand.

**Option A — Postman/Newman** (needs Node.js; 97 requests, 200 assertions):

```bash
./scripts/demo/demo-smoke.sh
```

Expected: every assertion passes, `0 failed`. The wrapper injects the bootstrap identity from your untracked `.env`
into a temporary environment file that is deleted on exit. Details: [Postman package](../postman/README.md).

**Option B — by hand:** follow [demo guide](./Demo-Guide.md) Part A, steps 1–28. It needs only `curl` and `jq`.

Either way, confirm you have observed all of:

| Expected | Where |
| --- | --- |
| Administrator login, then operators created **through the API** | steps 4–7 |
| Case creation and membership assignment (`201`, then `200` on repeat — idempotent) | steps 9, 11 |
| Multipart evidence registration with an initial holder, `201` + `Location` | step 12 |
| Server-computed `contentSha256` equal to your local digest, and a **different** `contextualSha256` | step 12 |
| **Byte-for-byte download parity** (`cmp` silent) | step 15 |
| The genesis event with a zero `previousHash`, then each event linking to the previous one | steps 16, 18 |
| `verify-chain` → `valid: true`, `reason: null` | step 17 |
| Transfer, metadata update, valid `verify-integrity`, seal | steps 18–22 |
| Release with `currentHolder` becoming `null` | step 24 |
| Case closure, then reads, download, timeline and chain verification still working | steps 26, 28 |

## 8. Security and error examples

All errors are `application/problem+json` with a stable `type` URI.

| Check | Command | Expected |
| --- | --- | --- |
| Unauthenticated | `curl -s -o /dev/null -w '%{http_code}\n' $BASE/api/v1/cases` | `401` |
| Malformed / tampered token | same, with `-H 'Authorization: Bearer not-a-jwt'` | `401` |
| Visible but unauthorized | operator administration as a non-`ADMIN` | `403` |
| Hidden vs nonexistent | read real evidence as a non-member, then read a fictional UUID | two `404` bodies identical apart from the echoed `instance` |
| Lifecycle / no-op | seal twice; transfer to the current holder | `409` |
| Oversized multipart | upload a file above `PROOFCHAIN_MAX_FILE_SIZE` | `413` |
| Mutation after release | `PATCH .../metadata` on `RELEASED` evidence in an `OPEN` case | `409 invalid-evidence-state` |
| Mutation after closure | any write in a `CLOSED` case | `409 case-closed` |

The exact commands are steps 3, 8, 10, 13, 14, 19, 23, 25 and 27 of the [demo guide](./Demo-Guide.md).

Also confirm no leak:

```bash
docker compose logs proofchain | grep -Ei 'password|secret|eyJ|/var/lib/proofchain' || echo 'no credential, token or storage path in the logs'
```

## 9. Documentation and ITS mapping

| Document | What to check |
| --- | --- |
| [Technical report](./Technical-Report.md) | The whole system in one document — read §16 first: the known defects are published, not hidden |
| [Architecture](./Architecture.md) | Eight Mermaid diagrams matching the code |
| [ITS compliance](./ITS-Compliance.md) | The rubric mapping, and the four items that need explicit acknowledgement |
| [Testing](./Testing.md) | How to reproduce the numbers you saw in sections 3 and 4 |
| [Operations](./Operations.md) · [Troubleshooting](./Troubleshooting.md) | Runtime, health, backup, recovery boundaries |
| [ADR index](./adr/README.md) | ADR-001 … ADR-008, the decisions that govern the implementation |

**The four acknowledgement items**, all argued in [ITS compliance](./ITS-Compliance.md):

1. **PostgreSQL instead of the MySQL the rubric asks for** — a genuine architectural dependency, presented as a
   deviation and not as a compliant equivalent.
2. **No `@OneToOne`** — no pair of entities in this domain is one-to-one.
3. **No `@ManyToMany`** — the association is modelled as the attributed join entity `CaseMembership`.
4. **No vulnerability scan** — the pinned Dependency-Check profile exists but the NVD feeds were egress-blocked. No
   zero-vulnerability claim may be inferred.

`DocumentationLinkAuditTest` fails the build on a broken internal link or a missing/duplicated ADR, so the navigation
you are reading is verified by the build you ran in section 3.

## 10. Release artifacts and the final tag

| Expected | Command |
| --- | --- |
| `target/proofchain-1.0.0.jar` exists | `ls -l target/proofchain-1.0.0.jar` |
| Image tagged `proofchain:1.0.0` with the version label | `docker image inspect proofchain:1.0.0 --format '{{index .Config.Labels "org.opencontainers.image.version"}}'` |
| `1.0.0` in `pom.xml`, the OpenAPI document, the image label and `compose.yml` | asserted by `ReleaseBaselineTest` |
| `CHANGELOG.md` describes the `1.0.0` content | Keep a Changelog structure |
| Security and dependency review present | [`docs/release/1.0.0/`](./release/1.0.0/Security-And-Dependency-Review.md) |
| **`uf14-final-2026` does not exist yet** | `git tag -l` prints nothing — tagging is the Project Owner's acceptance action |
| No generated deck, video, database dump or storage snapshot is tracked | `git ls-files \| wc -l`, and the `.gitignore` rules |

## 11. Clean up

```bash
echo 'DESTROY PROOFCHAIN DEMO DATA' | ./scripts/demo/demo-reset.sh
docker volume ls | grep proofchain || echo 'no ProofChain volume remains'
```

The reset prints the exact scope first and refuses to run without the confirmation phrase. It removes only this Compose
project's containers, network and the two named volumes it has verified by label — no host directory, no
`docker system prune`, nothing else.
