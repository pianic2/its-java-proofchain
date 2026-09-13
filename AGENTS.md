# AGENTS.md — ProofChain

Repository-wide instructions for coding agents. The objective is **correct changes with minimum context, tool calls and token usage**. Work autonomously inside the approved task; do not make the Project Owner perform implementation steps.

## 1. Token-first operating mode

1. Read this file once at the start of the run.
2. Inspect only the current branch/status/diff and the task actually assigned.
3. Search for symbols/paths before opening files. Prefer `rg -n`, targeted file reads and focused tests.
4. Read the **smallest code surface that can prove the change**. Expand context only when blocked by a concrete unknown.
5. Do not recursively read the repository, `docs/`, `target/`, generated reports or Git history by default.
6. Do not reread unchanged files or repeat searches whose answer is already known in the current run.
7. Do not load `README.md`, `CONTRIBUTING.md` or architecture documents merely for orientation: the stable rules needed for implementation are summarized here.
8. If a Jira key is supplied, read that issue first. Do not scan the whole backlog, epic, Confluence space or GitHub history unless the issue has a missing dependency that cannot be resolved locally.
9. Use external/MCP tools only for information or writes required by the task. Batch independent reads when possible.
10. One task means one bounded implementation. Stop when its acceptance criteria and required evidence are satisfied; do not start adjacent work.

**Escalation rule:** when uncertain, first inspect the nearest implementation/test/migration. Open a specific canonical document only if the answer is still missing. Broad research is a last resort.

## 2. Source-of-truth order

For an assigned change, resolve conflicts in this order:

1. explicit task/acceptance criteria;
2. this `AGENTS.md` for agent execution policy;
3. current code, tests, migrations and runtime behavior for implemented facts;
4. `CONTRIBUTING.md` for repository governance not summarized here;
5. the single relevant document under `docs/`;
6. Jira for workflow/scope and Confluence for concise monitoring material.

Never treat old prose as proof that a feature exists. Never silently widen scope.

## 3. Project invariants — keep in working memory

- Backend: Java 25, Spring Boot 4.0.7, Maven wrapper, PostgreSQL, Flyway, Spring Security/JWT, OpenAPI, JUnit 5 and Testcontainers.
- Base package: `it.itsprodigi.proofchain`.
- Architecture: modular monolith organized by feature. Keep controllers thin; application/services own use-case and transaction logic; repositories own persistence; DTOs are separate from entities.
- Authorization is global role plus case membership; `ADMIN` has global access. Keep case authorization centralized rather than duplicating checks.
- Custody events are append-only. Do not add update/delete event APIs.
- Evidence history is a deterministic SHA-256 hash chain with ordered sequence numbers and previous-hash linkage. Do not hash entity `toString()` output.
- Concurrent writes affecting one evidence must preserve serialized event ordering/consistency; retain the existing locking strategy unless the task explicitly changes it.
- Evidence binary content is stored outside PostgreSQL; metadata/hashes are persisted. Preserve DB/filesystem compensation semantics.
- Flyway is the only schema authority; Hibernate validates the schema.
- Released migrations are immutable. **Never edit V1–V7 or any already released migration.** Add the next version instead.
- No microservice migration, blockchain, broker, framework replacement or new dependency unless explicitly approved in task scope.
- Repository artifacts are English. Conversation with the Project Owner may be Italian.

## 4. Minimal task loop

### Discover

- Read the issue/acceptance criteria.
- `git status --short` and inspect existing diff before editing.
- Locate the owning feature with targeted search.
- Read the implementation plus its nearest relevant tests. Read migration/config only when touched.

Do **not** produce a long reconnaissance report. Once the change is understood, implement it.

### Implement

- Make the smallest coherent diff that satisfies the task.
- Preserve public contracts unless changing them is explicit scope.
- No opportunistic refactors, dependency upgrades, renames, style rewrites or duplicate documentation.
- Add/update tests for changed behavior and important edge cases.
- Update documentation only when a user-visible contract, configuration, operation, limitation or architectural decision actually changes.
- Never commit secrets, `.env`, runtime evidence files, database dumps, `storage/` or generated `target/` content.

### Validate efficiently

Use the narrowest useful check while iterating:

```bash
./mvnw --batch-mode -Dtest=<Class> test
./mvnw --batch-mode -Dit.test=<Class> verify
./mvnw spotless:check
git diff --check
```

Run the canonical gate **once when the implementation is ready**:

```bash
./mvnw --batch-mode --no-transfer-progress clean verify
```

Do not repeatedly run the full gate after every edit. If it fails, isolate the failure with the smallest targeted command, fix it, then rerun the canonical gate after the fix.

The canonical gate owns formatting, compilation, unit tests, integration tests, packaging and JaCoCo. JaCoCo line coverage must remain `>= 0.51`; never lower the threshold, add exclusions, disable tests or weaken assertions to get green.

Test naming/routing:

- `*Test.java` → Surefire;
- `*IT.java` → Failsafe/Testcontainers;
- integration tests own their PostgreSQL container and do not depend on local Compose;
- concurrency tests must use deterministic synchronization, never `Thread.sleep` as proof.

### Finish

Before declaring implementation complete:

- acceptance criteria are demonstrably satisfied;
- targeted tests pass;
- `clean verify` reports `BUILD SUCCESS`;
- `git diff --check` is clean;
- only intended files changed;
- no secret/generated/local artifact is tracked;
- relevant docs are truthful and current.

## 5. Git and review contract

When the task is a Jira subtask:

- branch: `ijpc-<number>-<short-kebab-description>`;
- commit: `<type>(IJPC-<number>): <imperative description>`;
- PR title: `IJPC-<number> — <issue summary>`;
- one subtask → one branch → one PR;
- use `.github/pull_request_template.md`;
- do not merge the PR as part of implementation;
- do not claim human approval, release acceptance or final validation.

The Project Owner owns final validation, merge/release acceptance and the final delivery tag.

## 6. Evidence without token waste

Evidence must be factual, not narrative. Record only:

- changed files/behavior;
- tests/commands actually run and result;
- relevant risk or known limitation;
- manual validation still required.

Do not paste full Maven logs, generated reports, whole diffs or source files into Jira/PR/chat. Quote only the failing/relevant lines when needed. Link or name canonical artifacts instead of duplicating their content.

## 7. Agent/subagent routing

Use a single agent for small/local tasks. Delegation is justified only when work is independent and saves context.

If subagents are available, give them a **narrow question, exact paths and required output**. Good delegation: locate a symbol, review one bounded diff, run one test family, verify one documentation claim. Bad delegation: “understand the project”, “review the repository”, or multiple agents rereading the same context.

Keep architecture/security/domain decisions in the primary reasoning context. Use cheaper/faster agents for mechanical searches, bounded test execution or documentation consistency checks when that does not reduce quality.

## 8. Stop conditions

Stop and ask/escalate only for a real blocker: contradictory acceptance criteria, unavailable required secret/service, destructive migration ambiguity, security decision not covered by existing policy, or a requested change that violates an immutable project invariant.

Otherwise choose the smallest reversible implementation consistent with current code and tests and continue autonomously.

## 9. Final response format

Keep the completion report compact. State:

1. what changed;
2. validation performed and whether the canonical gate passed;
3. remaining blocker/risk, only if one exists;
4. PR/commit/evidence reference when created.

Do not repeat the task, architecture or file contents. Do not provide a step-by-step diary.
