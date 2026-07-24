# Contributing to ProofChain

## Branch naming

Use one branch for each Jira subtask:

```text
ijpc-<issue-number>-<short-kebab-description>
```

Example: `ijpc-130-foundation-governance`.

## Commit naming

Use Conventional Commits with the Jira key in the scope:

```text
<type>(IJPC-<number>): <imperative description>
```

Allowed common types are `feat`, `fix`, `docs`, `test`, `refactor`, `build`, `ci`, and `chore`.

Example: `docs(IJPC-130): record foundation decisions`.

## Pull request naming

Use:

```text
IJPC-<number> — <issue summary>
```

Example: `IJPC-130 — Record foundation decisions and contribution rules`.

Every pull request uses the repository template in `.github/pull_request_template.md` and links its technical evidence.

## Work-unit rule

One subtask produces one branch and one pull request. Only immediate corrections within that pull request are exceptions. Do not merge a pull request as part of an implementation task.

## Project language and scope control

All repository files, branch names, commits, pull requests, Jira technical comments, and other canonical project artifacts are written in English. Conversation with the project owner may remain in Italian.

Implement only the approved Jira scope. Do not add future functionality, unrelated refactors, new governance documents, dependencies, plugins, frameworks, or configuration. Keep technical decisions and implementation evidence in GitHub; Jira owns work management; Confluence is limited to concise monitoring and professor-facing review material.

## Testing expectations

Use `*Test.java` for fast unit and MVC tests and `*IT.java` for integration tests. Maven Surefire runs the former and Failsafe runs the latter. Integration tests provision their own PostgreSQL through Testcontainers and do not use the local Compose database.

The canonical quality gate is:

```bash
./mvnw --batch-mode --no-transfer-progress clean verify
```

Run it before opening a pull request. It owns formatting, compilation, tests, packaging, and report generation. Spotless is the formatting authority; Checkstyle and ArchUnit are intentionally absent from Sprint 0.

## Evidence requirements

Each pull request must identify the changed files, tests and commands run, rendered documentation links, relevant risks, and any validation that remains manual. Do not claim Copilot review or final human validation until those activities have actually occurred.

## Definition of Ready

A subtask is Ready only when it has an objective, scope, out-of-scope boundaries, dependencies, frozen technical decisions, acceptance criteria, required tests, required evidence, completion commands, and no unresolved blocker.

## Definition of Done

A subtask is Done only when its approved scope is implemented, boundaries are respected, tests and `./mvnw clean verify` pass, no secret or generated local file is tracked, documentation is current, pull request review is complete, implementation evidence is linked in Jira, final human validation is complete, and the changes are merged into `main`.

## AI-assisted workflow

Planning may use GPT-5.6 Sol or an equivalent high-reasoning model. Implementation may use Claude Sonnet 5 or a superior implementation model. Review is performed by manual GitHub Copilot review agents. The project owner performs final validation and approval. AI agents may propose, implement, and review changes, but must never claim final human approval.

## Source-of-truth responsibilities

### GitHub

GitHub owns source code, tests, Maven and CI configuration, README, contribution rules, ADRs, pull requests, and technical evidence.

### Jira

Jira owns work scope, workflow state, dependencies, acceptance criteria, blockers, and evidence links.

### Confluence

Confluence owns concise monitoring, navigation, and professor-facing review material. Extended technical content must not be duplicated there.

## Secret handling

Never commit `.env` files, passwords, credentials, access keys, tokens, or unredacted sensitive logs. Use placeholders for local configuration and keep secrets in the approved local or CI secret store. Review documentation and pull request evidence for accidental secret disclosure before publishing.

## Reports

After a successful quality gate, inspect `target/surefire-reports/`, `target/failsafe-reports/`, and `target/site/jacoco/index.html`. JaCoCo is report-only in Sprint 0; no coverage threshold is enforced before meaningful domain logic exists.
