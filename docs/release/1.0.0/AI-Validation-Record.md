# AI validation record — ProofChain 1.0.0

This document records how this release was actually produced and verified. It exists so that the
provenance of the work is auditable rather than assumed.

## Delegation

The Project Owner authorized autonomous AI delivery for the sessions that produced Sprint 4
reconciliation, Sprint 5 and Sprint 6. The delegation replaced the human-only gate written into the
original task contracts with an independent AI validation gate.

**No human validation was performed.** Teacher approval has not yet been performed. Nothing in this
repository may be read as a human review, a manual approval or a stakeholder sign-off.

## Method

Work was executed by one orchestrating agent and a series of separate implementer agents, one per
Jira task. The orchestrator never implemented and then approved the same task without re-checking
it independently.

For every task the orchestrator:

1. read the Jira contract and derived the task perimeter;
2. delegated the implementation to a fresh agent with the contract and the explicit exclusions;
3. **re-ran `clean verify` itself** rather than trusting the reported result;
4. spot-checked the specific claim that mattered most for that task;
5. committed only after the build was observed green, with the real numbers in the message.

This caught several things a report-only workflow would have missed, listed below.

## What independent re-checking actually found

| Task | Claim checked | Outcome |
| --- | --- | --- |
| IJPC-162 | published hash vector | recomputed out-of-band in Python from the bytes parsed back out of the Markdown; matched |
| IJPC-163 | lock order | verified the order is enforced by the type system, not by convention |
| IJPC-165 | agent-reported defect | confirmed, then fixed; the fix broke a test, which proved the fix worked |
| IJPC-167 | migration test change | diff read line by line to confirm no assertion was weakened |
| IJPC-171 | `.gitignore` defect | confirmed two new files would have been silently excluded from every commit |
| IJPC-172 | migrations untouched | `git diff --name-only` over the migration paths returned empty, as required |
| IJPC-173 | agent cut off mid-task | found the core service had 328 lines and zero tests; the orchestrator wrote the missing tests, the documentation and ran the command against a real database |
| IJPC-174 | `src/main` untouched | confirmed; the JaCoCo gate and GitHub Actions were also confirmed unchanged |
| IJPC-175 | Postman repeatability | raw Newman output read directly; both runs identical after a destructive reset |
| IJPC-178 | all certification numbers | re-observed from a separate clean clone rather than carried over |

## Defects found and fixed during the delegated work

- The Sprint 3 OpenAPI endpoint allowlist was never extended when Sprint 4 added endpoints, so the
  published branch head did not build.
- `CaseAccessService` loaded the case aggregate before the lock query, so the post-lock reload saw a
  stale snapshot.
- The operational `reason` accepted unpaired UTF-16 surrogates, turning malformed client input into
  a sanitized 500 after the aggregate had already been mutated.
- `ADR-006` stated a lock order the implementation does not use. Corrected in place rather than
  merely superseded, so an auditor reading it is not misled.
- Four test call sites used locale-dependent case conversion and failed under a Turkish locale.
  Production code was already correct.
- An unanchored `.gitignore` rule silently excluded new files under the evidence storage package.

## Incomplete AI validation

**The independent Sprint 4 review never returned findings.** A reviewer agent was launched twice
over the Sprint 4 slice (IJPC-157 through IJPC-162) and both runs ended without producing a report.

Because those findings were never read, IJPC-160, IJPC-161, IJPC-162 and the IJPC-6 container were
deliberately left un-transitioned. Marking them complete would have asserted a review that did not
happen. This is an open item, not an oversight.

Sprint 5 and Sprint 6 were verified task by task by the orchestrator as described above, but no
separate cumulative independent reviewer completed a pass over either sprint as a whole.

## Environment constraints that limited validation

- **OWASP Dependency-Check could not run.** The NVD, the hosted suppressions and the CISA feed are
  unreachable from the build network. No vulnerability analysis was performed.
- `shellcheck` is not installed in the build environment.
- Four POSIX permission tests cannot be falsified while the build runs as `root`; they are skipped
  through JUnit `Assumptions` with a stated reason.
- Jira was unreachable from the delivery sessions. Every task comment and transition is queued for a
  connector-enabled session rather than being silently dropped.

## Honesty constraints applied throughout

Documentation was written against the code and rejected where it could not be verified. Specific
claims that were deliberately **not** made: cloud readiness, any SLA, guaranteed throughput or
capacity, distributed consensus, blockchain, digital signatures, antivirus scanning, production
certification, and any wording that would imply human review or approval of this work.

Where a check could not be executed, it is recorded as NOT EXECUTED with its blocking reason and the
exact command a reviewer must run. No unexecuted check is reported as passed.
