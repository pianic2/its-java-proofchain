# ProofChain 1.0.0-rc.1 — human validation checklist

Release candidate: `ProofChain 1.0.0-rc.1`.
Branch: `ijpc-8-sprint-6-final-delivery`.
Certification commit: `739d980c6256b4b7b321424741aa87808b7d3277`.

**No human validation has been performed on this release candidate.** Everything in
[the certification report](./Certification-Report.md) was produced through the delegated automated gate: the Project
Owner authorized autonomous AI delivery, automated verification is complete, and an independent AI review was
performed. This document is the list of things that a delegated automated gate **cannot** decide, written so a human
can work through them and either accept or reject the candidate.

Nothing below may be ticked by an agent. No agent may record an outcome in this file.

## How to use this document

Each item states what must be decided, what evidence already exists, and what the reviewer must do. The outcome
column is left empty on purpose. The candidate is not releasable until every **gate** item has a recorded human
decision.

---

## A. Acknowledgement gates — required before release

These are deviations from the supplied ITS rubric. They are not defects and they are not presented as compliance.
They need an explicit human decision.

| # | Gate | What is asked | Evidence | Decision |
| --- | --- | --- | --- | --- |
| A1 | **PostgreSQL instead of MySQL** | Acknowledge that PostgreSQL 18.4 is the only supported database and that no MySQL support exists or is planned. The dependency is architectural (JSONB, partial/expression indexes, `FOR SHARE`/`FOR UPDATE`, `pg_stat_activity`, PL/pgSQL triggers), not a configuration switch. | [ITS compliance §2](../../ITS-Compliance.md), [known limitations §1.1](./Known-Limitations.md) | |
| A2 | **No `@OneToOne` in the model** | Acknowledge that no one-to-one relationship exists in this domain and that none was invented to satisfy the rubric. | [ITS compliance §3](../../ITS-Compliance.md) | |
| A3 | **`CaseMembership` join entity instead of `@ManyToMany`** | Acknowledge that the attributed join entity (`assignedBy`, `assignedAt`, unique `(case_id, operator_id)`) is accepted in place of `@ManyToMany`. | [ITS compliance §3](../../ITS-Compliance.md), `custodycase/domain/CaseMembership.java`, `V2` migration | |
| A4 | **No vulnerability analysis exists** | Acknowledge that OWASP Dependency-Check has never run, that the release carries no vulnerability analysis, and decide whether to require a successful run before acceptance. | [certification report, area 6](./Certification-Report.md), [security review §3](./Security-And-Dependency-Review.md) | |
| A5 | **Sprint 4 independent review never returned** | Acknowledge that the independent AI review of Sprint 4 was launched twice and returned no findings, and that IJPC-160, IJPC-161, IJPC-162 and IJPC-6 were consequently never moved to Done. Decide whether that gate must be satisfied before release. | [known limitations §1.4](./Known-Limitations.md) | |
| A6 | **No human validation was performed** | Acknowledge that this candidate reached certification through the delegated automated gate only, and that this document is the first human touchpoint. | this document | |

---

## B. Reproduce the automated evidence independently

Every command below was executed during certification and its observed result is recorded in
[the certification report](./Certification-Report.md). A reviewer who wants independent evidence should re-run them
on their own machine rather than trust the recorded output.

| # | Check | Command | Expected | Outcome |
| --- | --- | --- | --- | --- |
| B1 | Clean clone | `git clone <repo> /tmp/proofchain-check && cd /tmp/proofchain-check && git checkout 739d980` | working tree clean at the certification SHA | |
| B2 | Format gate | `./mvnw spotless:check` | `BUILD SUCCESS` | |
| B3 | Canonical build, twice, no modification between | `./mvnw --batch-mode --no-transfer-progress clean verify` (run it twice) | `BUILD SUCCESS` both times | |
| B4 | Whitespace | `git diff --check` | no output | |
| B5 | Working tree still clean | `git status --short` | no output | |
| B6 | Packaged JAR startup | start `target/proofchain-1.0.0.jar` against a running PostgreSQL with the documented environment, then `curl -s localhost:8080/actuator/health/readiness` | `{"status":"UP"...}` | |
| B7 | Compose stack | `docker compose build && docker compose up -d && docker compose ps` | both services healthy | |
| B8 | Postman collection | `npx --yes newman@6.2.2 run postman/ProofChain.postman_collection.json -e postman/ProofChain.local.postman_environment.json` | 0 failed assertions | |

---

## C. Judgement calls a machine may not make

| # | Question | Context | Decision |
| --- | --- | --- | --- |
| C1 | Is the JaCoCo gate of `LINE ≥ 0.51` acceptable as the *contractual* floor, given that the observed coverage is far above it? | The gate was frozen early and deliberately never raised or lowered. Raising it now would be a policy change, not a code change. | |
| C2 | Are the four known functional defects acceptable in a `1.0.0`? | Zero-byte file → 500; unpaired surrogate in descriptive metadata → undeclared 500 after full rollback; integrity verification stamps `updatedAt`; wrong HTTP method → 500 instead of 405. See [known limitations §2](./Known-Limitations.md). | |
| C3 | Is it acceptable that the `INVALID` integrity verdict is only reachable through a documented manual out-of-band step? | The collection cannot manufacture corrupted storage without a filesystem edit. See [known limitations §3.1](./Known-Limitations.md). | |
| C4 | Should the seven Testcontainers-backed `*Test` classes be renamed to `*IT` before release? | A rename touches 7 files and changes which plugin runs them. It was deliberately not done unilaterally. | |
| C5 | Should `/v3/api-docs` and `/swagger-ui.html` remain publicly reachable? | Intentional for an ITS delivery; Springdoc warns against it for production. | |
| C6 | Is the absence of a dedicated case-closure concurrency test acceptable? | Closed-case rejection is covered non-concurrently for every command. | |
| C7 | Is the Hibernate `org.hibernate.orm.jdbc.error` WARN line — which prints PostgreSQL constraint text and the conflicting business key server-side — acceptable? | Server-side only; the HTTP response is a generic Problem Detail. Suppressing it would hide genuine diagnostics. | |

---

## D. Checks this environment could not perform

A reviewer with the required tooling or network should perform these. Each is recorded as **NOT EXECUTED** in the
certification report with its blocking reason.

| # | Check | Command | Why it did not run here | Outcome |
| --- | --- | --- | --- | --- |
| D1 | OWASP Dependency-Check | `export NVD_API_KEY=<key>` then `./mvnw -Pdependency-check -DnvdApiKey=$NVD_API_KEY org.owasp:dependency-check-maven:12.2.2:check` | `services.nvd.nist.gov`, `jeremylong.github.io` and `www.cisa.gov` are egress-blocked; Dependency-Check aborts with `NoDataException: No documents exist` | |
| D2 | Shell static analysis | `shellcheck scripts/demo/*.sh docker/healthcheck.sh docker/unzip-for-maven-wrapper.sh` | `shellcheck` is not installed in this environment | |
| D3 | The four POSIX-permission Surefire tests | `./mvnw test -Dtest='FileSystemEvidenceStorageTest+FileSystemEvidenceStorageHardeningTest'` **as an unprivileged user** | the build runs as `root`, for whom POSIX permission bits are not enforced, so the JUnit `Assumptions` abort | |
| D4 | Behaviour of a deployed Tomcat's `Server` header | inspect response headers against the Compose stack from outside the container | asserted only under MockMvc, which does not run the servlet container | |

---

## E. Release actions reserved for the Project Owner

**None of these was performed. An agent must never perform them.**

| # | Action | State |
| --- | --- | --- |
| E1 | Merge the certification pull request | not performed |
| E2 | Create the definitive tag `uf14-final-2026` at the approved commit | not performed — `git tag -l` returns nothing |
| E3 | Publish the GitHub Release using the prepared title and body | not performed — the draft content is in [Release-Notes.md](./Release-Notes.md) |
| E4 | Transition IJPC-178, IJPC-179 and IJPC-8 to Done | not performed |
| E5 | Post the queued Jira comments | queued in `/tmp/proofchain-jira-pending.md`; Jira is unreachable from the certification session |
| E6 | Decide whether IJPC-160, IJPC-161, IJPC-162 and IJPC-6 may close without an independent review | open |

Between final certification approval and definitive tagging, **no code or documentation modification is allowed**.
The approved commit must be the tagged commit.

---

## F. Reviewer sign-off

| Field | Value |
| --- | --- |
| Reviewer name | |
| Role | |
| Date | |
| Commit reviewed | |
| Every gate in section A decided | |
| Verdict (accept / reject / accept with conditions) | |
| Conditions, if any | |
