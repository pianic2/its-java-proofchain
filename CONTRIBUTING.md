# Contributing to ProofChain

These are the working rules for the ProofChain repository at `1.0.0`. They are enforced partly by tests and partly by
review discipline; where a rule is machine-checked, the check is named.

## Project language and scope control

All repository files, branch names, commits, pull requests, Jira technical comments and other canonical project
artifacts are written in English. Conversation with the Project Owner may remain in Italian.

Implement only the approved Jira scope. Do not add future functionality, unrelated refactors, new governance documents,
dependencies, plugins, frameworks or configuration. Keep technical decisions and implementation evidence in GitHub;
Jira owns work management; Confluence is limited to concise monitoring and professor-facing review material.

## Work-unit rule

One subtask produces one branch and one pull request. Only immediate corrections within that pull request are
exceptions. **Do not merge a pull request as part of an implementation task.**

## Branch naming

```text
ijpc-<issue-number>-<short-kebab-description>
```

Example: `ijpc-176-final-technical-documentation`.

## Commit naming

Conventional Commits with the Jira key in the scope:

```text
<type>(IJPC-<number>): <imperative description>
```

Allowed types: `feat`, `fix`, `docs`, `test`, `refactor`, `build`, `ci`, `chore`.

Example: `docs(IJPC-176): add the final technical report`.

## Pull request naming and content

```text
IJPC-<number> — <issue summary>
```

Every pull request uses the repository template in `.github/pull_request_template.md` and links its technical
evidence.

## Independent review

The author cannot approve their own work. Review is a separate activity from authoring, and the reviewer compares the
change against the actual repository, the Jira scope and the runtime — not against the author's prose.

For documentation changes specifically, the reviewer must verify claims against code, tests, migrations or a command
actually executed. A document that reads well but overstates the implementation is a blocking finding, not a nitpick.

AI agents may propose, implement and review changes. They must never claim final human approval.

## Commands

The canonical quality gate, and the only one:

```bash
./mvnw --batch-mode --no-transfer-progress clean verify
```

Run it before opening a pull request; it must report **BUILD SUCCESS**. Maven owns formatting checks, compilation,
tests, packaging and report generation. GitHub Actions provisions Temurin Java 25 and invokes this command; it must not
duplicate Maven lifecycle logic and must never be modified to work around a failure.

Supporting commands:

```bash
./mvnw spotless:check                 # verify formatting without changing files
./mvnw spotless:apply                 # apply deterministic formatting, local workspace only
git diff --check                      # no whitespace errors before committing
./mvnw --batch-mode -Dtest=<Class> test
./mvnw --batch-mode -Dit.test=<Class> verify
```

CI must never execute `spotless:apply` or commit formatter-generated changes.

Spotless `3.6.0` is the formatting authority; Java formatting is frozen to `palantir-java-format 2.78.0`, verified
under Java 25. Markdown, `pom.xml` and workflow files are checked for trailing whitespace and a final newline.

## Testing expectations

- `*Test.java` for fast unit, domain, protocol, MVC-slice and configuration tests — run by Surefire.
- `*IT.java` for integration tests — run by Failsafe.
- Integration tests provision their own PostgreSQL through Testcontainers and never use the local Compose database.
- Concurrency proofs use `CountDownLatch`, `CyclicBarrier` or `pg_stat_activity` lock-waiter polling. **Never**
  `Thread.sleep` as a synchronization or proof mechanism.
- The JaCoCo gate is `BUNDLE` / `LINE` / `COVEREDRATIO` ≥ `0.51`. Do not lower it, and do not add `<excludes>` to make
  a change pass. Add tests instead.
- Do not `@Disabled` a test to make the build green. A skip must be a JUnit `Assumption` about the environment, and it
  must be documented in [Testing](./docs/Testing.md).

The complete testing model is in [Testing](./docs/Testing.md).

## Migrations

- Flyway is the only schema authority. Hibernate runs `ddl-auto: validate` and must never be switched to `create`,
  `update` or `create-drop`.
- **A released migration is immutable.** Never edit `V1`–`V7` or any future released version — not to fix a typo, not
  to reorder a constraint. Add a new versioned migration instead. `MigrationGovernanceTest` enforces this.
- New migrations take the next dense version number, are replay-safe where they touch existing objects, and are
  covered by an integration test on both an empty database and the certified upgrade path.
- `baseline-on-migrate` stays `false` and `clean-disabled` stays `true`.
- Rules and the certified lifecycle: [the migration guide](./src/main/resources/db/migration/README.md) and
  [Database schema lifecycle](./docs/Database-Schema-Lifecycle.md).

## Secret handling

- Never commit `.env` files, passwords, credentials, access keys, tokens or unredacted sensitive logs.
- Use placeholders for local configuration; keep real values in an untracked `.env` or an approved secret store.
- The application never generates or defaults a secret. Do not add a fallback to make local setup easier.
- Review documentation, test fixtures and pull request evidence for accidental secret disclosure before publishing.
- Never commit real evidence content, a runtime storage directory, a database dump or a generated report. `.gitignore`
  covers `target/`, `.env`, `*.log`, `/storage/` and IDE files; keep it that way.

## Architecture Decision Records

- A material decision affecting architectural boundaries, persistence, security, the release model or the runtime
  requires an ADR under `docs/adr/`. An implementation detail does not.
- Historical ADRs are immutable except for factual link or index corrections.
- ADR numbering is dense and gapless, and every ADR is listed exactly once in `docs/adr/README.md`.
  `DocumentationLinkAuditTest` enforces both.

## Documentation expectations

- Documentation must never describe a planned endpoint, permission, workflow or capability as implemented before the
  corresponding code and tests exist.
- Every technical claim must be true of this repository and verifiable against code, a test or an executed command.
- Known limitations are documented plainly. Do not soften or omit a defect to make a document read better.
- Every internal link must resolve and every heading anchor must exist; `DocumentationLinkAuditTest` fails the build
  otherwise.
- Do not create a second document that duplicates or contradicts an existing one — update the existing home instead.

## Evidence requirements

Each pull request must identify:

1. the changed files;
2. the tests added or updated, and the commands actually run with their results;
3. rendered documentation links;
4. relevant risks and known limitations introduced or left open;
5. any validation that remains manual.

Do not claim Copilot review or final human validation until those activities have actually occurred.

## Definition of Ready

A subtask is Ready only when it has an objective, scope, out-of-scope boundaries, dependencies, frozen technical
decisions, acceptance criteria, required tests, required evidence, completion commands and no unresolved blocker.

## Definition of Done

A subtask is Done only when its approved scope is implemented, boundaries are respected, tests and
`./mvnw clean verify` pass, `git diff --check` is clean, no secret or generated local file is tracked, documentation is
current, pull request review is complete, implementation evidence is linked in Jira, **final human validation is
complete**, and the changes are merged into `main`.

## Final human validation gate

The Project Owner performs final validation and approval. No agent, workflow or automated check substitutes for it.
Specifically, the following are human actions and are never performed by an implementation agent:

- merging a pull request;
- declaring a release accepted;
- creating the delivery tag `uf14-final-2026`;
- accepting the approved deviations recorded in [ITS compliance](./docs/ITS-Compliance.md) — PostgreSQL instead of
  MySQL, and the `CaseMembership` join entity instead of `@ManyToMany`;
- accepting or scheduling the known limitations listed in
  [Technical report §16](./docs/Technical-Report.md#16-known-limitations-and-future-work).

## AI-assisted workflow

Planning may use a high-reasoning model. Implementation may use a comparable implementation model. Review is performed
by manual GitHub Copilot review agents and by a human reviewer. The Project Owner performs final validation and
approval.

## Source-of-truth responsibilities

| System | Owns |
| --- | --- |
| GitHub | Source code, tests, Maven and CI configuration, README, contribution rules, ADRs, pull requests, technical evidence |
| Jira | Work scope, workflow state, dependencies, acceptance criteria, blockers, evidence links |
| Confluence | Concise monitoring, navigation and professor-facing review material — no duplicated technical content |

## Reports

After a successful quality gate, inspect `target/surefire-reports/`, `target/failsafe-reports/` and
`target/site/jacoco/index.html`. None of these are tracked; `target/` is git-ignored and generated reports must never
be committed.
