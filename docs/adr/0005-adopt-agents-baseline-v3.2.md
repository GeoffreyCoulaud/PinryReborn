# 0005. Adopt agents-baseline v3.2.0

Status: Accepted
Date: 2026-07-28

## Context

The repository ran `agents-baseline` v3.0.0, installed on 2026-07-23 and recorded in
`docs/adr/0001-adopt-agents-baseline.md`. The adoption script surveyed the tree, found situation
`update`, and recopied the seven generic files at v3.2.0.

Most of the bump is inert here. `.claude/CLAUDE.md` is byte-identical. `evidence-guard.py`,
`agents/modules/backend.md` and `agents/modules/kotlin.md` differ only by their version marker, so
the hook refuses exactly what it refused before and no new class of command breaks.
`.claude/settings.json` was left untouched: a key-by-key comparison showed the project's file
already covers the baseline's entirely, adding `Bash(./gradlew:*)` and `Bash(git stash:*)` on top,
and denying `AskUserQuestion` and `EnterPlanMode` as the baseline does. No permission was lost.

What changed in substance sits in `AGENTS.md`:

1. Spec names the PR review in Wrap as the gate at exit, the human's word never assumed.
2. Wrap (c) requires a PR to be merged only after the human has reviewed it, each round of
   feedback addressed and the next awaited.
3. Improve begins only once Wrap has fully completed, work integrated and branch cleaned.
4. The review brief is bounded: criteria pointed at by path and line range, at most three zones of
   risk phrased as open questions, no instruction beginning with "confirm" or "verify".
5. The test-only commit must carry the red in its message body: the command run and the failure it
   produced, pasted from that run and never retyped.

`agents/project.md` was reexamined statement by statement against that delta. Six statements
survived unchanged. Two collided with the arriving rules, both because the generic file imposes an
obligation this repository does not practice:

- **The merge moment.** The file described the merge as the agent's own gesture
  (`gh pr merge --rebase`), with nothing awaited. Measured on 2026-07-28: of 31 merged PRs, none
  carries a recorded review.
- **The red in the test commit body.** The file governs test names, test bodies and testing order,
  but is silent on commit message bodies. Measured on 2026-07-28: of 41 `test(...)` commits, 1
  cites a command in its body. The others describe the red in prose, which the new rule replaces
  with pasted output.

## Decision

Both contradictions were reported to the operator and settled by the operator on 2026-07-28. Both
came back **adopt-baseline**.

1. **A PR is merged only after the human has reviewed it.** The agent opens the PR, stops, and
   waits. It addresses each round of feedback and waits for the next. `gh pr merge --rebase` no
   longer follows the push in the same breath. Rebase-only is unaffected: what changed is the
   moment of the merge, not its mode.
2. **The test-only commit carries the red in its body**, pasted from the run that produced it.

Neither resolution narrows a generic rule, so the gate perimeter and the count of narrowed rules
are unchanged: the local-merge exemption refused under Conventions remains the only narrowing,
alongside the Kover models-package exclusion of decision B1.

Three situated facts land in `agents/project.md` as a consequence, none of them an arbitration:

- The observable of the review gate cannot be `reviewDecision`. GitHub does not let an author
  approve their own pull request, and every PR here is authored by the sole operator, so the trace
  of the review is the conversation and the addressed feedback, not GitHub's approval state.
- The red in this codebase is usually a compilation failure rather than a failing assertion, since
  a test naming a type that does not exist yet breaks `compileTestKotlin`. That output is what the
  commit body must carry.
- Improve starts from `main` on its own branch and integrates through its own PR, which the
  repository already practised (PR #30).

## Consequences

- Wrap gains a wait. The elapsed time between opening a PR and merging it is now bounded by the
  operator's availability rather than by CI, which is the point of a gate at exit.
- Pre-existing work is not touched. The arriving rules govern work done from the moment they enter
  the repository, so the 31 reviewless PRs and the 40 test commits without pasted red stay as they
  are; a sweep would rewrite frozen dated documents to satisfy a newer rule.
- The bounded review brief (change 4) applies to every subagent dispatch from now on and needed no
  project decision, since nothing in `agents/project.md` contradicted it.
- This ADR is the record of the arbitration. `0001-adopt-agents-baseline.md` is delivered and was
  not rewritten to carry it: only its `Status` field could change.
