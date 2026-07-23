# 0001. Adopt agents-baseline v2.1.0

Status: Accepted
Date: 2026-07-23

## Context

The project carried its own `AGENTS.md`: 254 lines of house rules covering the workflow, the hard
rules, the architecture and the testing conventions. `agents-baseline` v2.1.0 replaces that file
with a generic one, byte-identical across projects, plus two optional modules (`kotlin.md`,
`backend.md`) and three review mandates under `docs/reviews/`. Everything situated moves to
`docs/project.md`.

The old file was sorted statement by statement rather than file by file. Most statements were
either already covered by the generic file or specific to this project. Five contradicted the
arriving rules, and those are decisions about how this repository works, so they were put to the
operator rather than settled by the agent performing the adoption. This ADR is the record: without
it, whoever later asks why this repository reviews its plans, or why its coverage gate excludes
classes by annotation, has nowhere to find the answer.

## Options considered

For each contradiction, two options only: `keep-project`, meaning the project rule moves to
`docs/project.md` and overrides the generic file here, or `adopt-baseline`, meaning the project
rule is repealed.

## Decision

| Contradiction | Resolution |
|---|---|
| "Plans are not reviewed, they follow from the approved spec" against a plan reviewed by a fresh subagent before any dispatch | `adopt-baseline` |
| "Six phases, always in order" against seven phases plus a tier the user selects before the first edit | `adopt-baseline` |
| No ADR requirement against one ADR per spec, with `docs/adr/` absent and thirteen specs written | `adopt-baseline` |
| Strict TDD silent on commit granularity against the failing test committed alone before its implementation commit | `adopt-baseline` |
| The coverage gate excluding Ebean's kapt output by annotation against a perimeter decided by location with no per-category exemption | `keep-project` |

Six statements naming tooling were settled separately, since absent a pile of their own they would
have been dropped in silence:

| Tooling statement | Resolution |
|---|---|
| Current documentation comes from the context7 MCP server | Repealed here, proposed upstream as a generic rule |
| The `brainstorm` and `pick-my-brain` skills for clarification during Discuss | Repealed here, proposed upstream as a generic rule |
| A worktree as the third branching option, suggested default when dispatching agents | Kept as an option, no longer a suggested default |
| The `subagent-driven-development` and `dispatching-parallel-agents` skills | Repealed |
| Worktree mechanics: `.claude/worktrees/<name>`, `worktree.baseRef` | Kept, in `docs/project.md` |
| The `finishing-a-development-branch` skill for Wrap | Repealed |

`.gitignore` ignored `.claude` in full, which would have left the arriving `settings.json` and
`evidence-guard.py` hook untracked: enforced on one machine, absent from every clone. It now
ignores `.claude/*` and re-includes those two paths, keeping `settings.local.json` and
`.claude/worktrees/` local. The two permissions parked in `settings.local.json`
(`Bash(./gradlew:*)`, `Bash(git stash:*)`) moved to the shared `settings.json` for the same reason.

## Consequences

- **A plan is now reviewed before any task is dispatched**, against `docs/reviews/plan.md`. The
  reviewer is a fresh subagent and it never edits.
- **Not every request runs every phase.** The tier (Direct, Spec, Plan) is recommended by the agent
  and chosen by the operator before the first edit. The old six-phase sequence maps onto the Spec
  tier, so nothing that used to happen stops happening; smaller work stops paying for phases it did
  not need.
- **Every spec now produces an ADR** in `docs/adr/`, or a stated reason why it settles no
  architectural question. Decisions already taken keep living where they were recorded (handoffs,
  backlog, git history): this rule governs work done from 2026-07-23 onward and nothing is
  retrofitted.
- **Commit granularity changes for every task**: the failing test lands in its own
  `test(scope):` commit before the implementation that makes it pass, because a red run shown only
  in a conversation leaves no trace in the diff a reviewer receives. This is the most visible daily
  change of the adoption.
- **The coverage perimeter keeps its annotation-based exclusion.** Removing it would measure kapt
  output nobody writes and no test can reach. The exception is declared in `docs/project.md` under
  Gate perimeter, which is where the generic rule says the perimeter lives, and it is the only
  place the generic file is overridden here.
- **The `evidence-guard.py` hook is enforced for the whole team** and takes effect at the next
  session start. It blocks writing file content through a command (redirections outside disposable
  locations, `sed -i`, `tee`, `git apply`, `patch`) and refuses any edit of a generic file, deciding
  on the marker in line 1. A remedy from the Improve phase that used to be written into `AGENTS.md`
  now goes into `docs/project.md`, or upstream into `agents-baseline` when it is true of every
  project.

## Pre-existing violations, inventoried and not fixed

The generic file governs work done from the moment it entered the repository. What predates it is
counted once, here, and left alone. A frozen dated document is never rewritten to satisfy a rule
adopted after it.

- Em dashes and en dashes, which the generic file forbids: 41 tracked files. 33 are frozen dated
  documents under `docs/specs/`, `docs/plans/` and `docs/handoffs/`; one is `docs/backlog.md`
  (living, so it cleans up as it is edited); the others are `SECURITY.md`, two files under
  `.github/`, three `.kt` files and `api-presentation-quarkus/build.gradle.kts`. No commit message
  among the last 50 carries one.
- `.gitignore` holds two comments in French without accents, predating the 2026-07-07 decision
  that all code is English.
- Commit granularity: of the last 50 commits, 24 change `src/main` and `src/test` together and 5
  are test-only.
- Thirteen specs exist with no ADR. This one is the first.
