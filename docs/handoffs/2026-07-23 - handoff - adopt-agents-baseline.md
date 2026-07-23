# Handoff: adopting agents-baseline v2.1.0

Date: 2026-07-23
Branch: `chore/adopt-agents-baseline` (backup at `chore/adopt-agents-baseline-backup`)

## Current state

The project's own `AGENTS.md` is gone, replaced by the generic file of `agents-baseline` v2.1.0.
Everything situated moved to `docs/project.md`, which is now the only place project truth lives and
the only file of the pair meant to be edited. The five contradictions the sort surfaced were put to
the operator and answered; `docs/adr/0001-adopt-agents-baseline.md` is the record and the reason
`docs/adr/` exists at all.

What landed:

| Path | State |
|---|---|
| `AGENTS.md` | Generic, v2.1.0. Never edited here: the hook refuses it, deciding on the marker in line 1. |
| `CLAUDE.md` | `@AGENTS.md`, nothing else. |
| `docs/project.md` | Written from the code, not from the old file. Modules, commands, gate perimeter, invariants, conventions, gotchas. |
| `docs/agents/kotlin.md`, `docs/agents/backend.md` | The two generic modules, imported by `docs/project.md`. |
| `docs/reviews/plan.md`, `task.md`, `holistic.md` | The three review mandates. **Do not read them**: pass the path to the reviewing subagent. An implementer who knows the criteria writes to the criteria. |
| `docs/adr/0001-adopt-agents-baseline.md` | The arbitrations, the tooling decisions, and the inventory of pre-existing violations. |
| `.claude/settings.json`, `.claude/hooks/evidence-guard.py` | Now tracked, so the whole team gets them. |
| `.gitignore` | `.claude` became `.claude/*` plus two negations. `settings.local.json` and `.claude/worktrees/` stay local. |

## What changes for whoever works here next

- **A plan is reviewed before any task is dispatched**, by a fresh subagent, against
  `docs/reviews/plan.md`. The old rule said the opposite ("plans are not reviewed, they follow from
  the approved spec") and it is repealed.
- **Seven phases, and not all of them run.** The tier (Direct, Spec, Plan) is recommended by the
  agent and **chosen by the operator before the first edit**. The old six-phase sequence is the Spec
  tier.
- **Every spec produces an ADR**, or a stated reason why it settles no architectural question.
- **The failing test is committed alone**, as `test(scope): <behaviour>`, before the commit that
  makes it pass. This is the most visible daily change: of the 50 commits before the adoption, 24
  bundled `src/main` and `src/test` together. A red run shown only in a conversation leaves no trace
  in the diff a reviewer receives.
- **The hook blocks writing file content through a command.** Redirections outside `/dev/null`,
  `/tmp` and `$TMPDIR`, plus `sed -i`, `tee`, `truncate`, `git apply`, `git am`, `patch`, and
  `python - <<EOF`. The repository's own scripts are unaffected: `.githooks/pre-push`,
  `.githooks/pre-commit` and `scripts/generate-openapi.sh` all pass. What will hit it is a workflow
  writing a review diff into `.superpowers/sdd/` by redirection.
- **A remedy from the Improve phase goes into `docs/project.md`**, not into `AGENTS.md` any more.

## Pitfalls learned during the adoption

- **`.gitignore` ignored `.claude` in full.** `apply` wrote the hook and the settings into an ignored
  path, so `git status` showed nothing and the PR would have carried neither: enforced on one
  machine, absent from every clone. `git check-ignore -v` is the only way to see this, since the
  files exist on disk and look fine. The two permissions parked in `settings.local.json`
  (`Bash(./gradlew:*)`, `Bash(git stash:*)`) had the same defect and moved to the shared file.
- **The old file's facts had drifted from the code**, and copying them forward would have carried
  the drift into the new one. Three claims were disproved and are recorded in `docs/project.md`
  under Design invariants rather than deleted: six modules where `settings.gradle.kts` declares
  eleven, `api-usecases` depending on `api-domain` alone where it also declares `api-utilities`, and
  JUnit 5 where the catalog pins `junit = 6.1.1`.
- **The gate perimeter had to be read from `build.gradle.kts`, not from the document's shape.** The
  real perimeter is per module (Kover applied everywhere except `api-application`), measured from
  each module's own tests with no aggregation, minus three Ebean filters. One of those filters is by
  annotation, which the generic rule forbids; the operator kept it, and that override is the only
  one in the file.

## Not validated

- **The hook has never fired.** It takes effect at the next session start, so nothing in this
  session was blocked by it. Its first real refusal is still ahead.
- **`/context`, `/hooks` and `/permissions` were not run.** They need an interactive session and
  each fails silently: a mistyped import loads nothing and says nothing. The import chain resolves
  on disk (`CLAUDE.md` to `AGENTS.md` to `docs/project.md` to `agents/kotlin.md` and
  `agents/backend.md`), which is necessary and not sufficient. Ask for all three, not the first
  only.
- **CI has not run on this branch.** The local gate (`./gradlew check koverVerify`) is green, but
  `validate.yml` also builds the multi-arch container image behind the same `validate / gate` check,
  and no local command covers that.
- **No `docs/reviews/` mandate has been exercised**, so the review flow the baseline describes is
  documented here but never yet run in this repository.

## To recommend upstream in agents-baseline

Kept out of this repository on purpose: `AGENTS.md` and the modules are generic and cannot be edited
here. These are candidates for the shared baseline, and writing them is **its own task in the
`agents-baseline` repository**, not a side effect of an adoption.

Decided by the operator during this adoption, repealed here pending a generic form:

1. **Current documentation comes from a documentation MCP server.** The old rule named context7 and
   required consulting it before concluding that a tool cannot do something. The baseline's Evidence
   section already says to consult current documentation rather than recall, but names no mechanism,
   so each project re-invents the pointer. The generic form should name the mechanism without naming
   one server.
2. **A clarification skill for the Discuss phase.** The old rule named `brainstorm` and
   `pick-my-brain`. Discuss is generic; the affordance for asking better questions in it is generic
   too, and only the skill names are project-local.

Surfaced by this adoption and **not yet decided by anyone**:

3. **"Fix the design, do not work around it", with its three smells**: the same explanatory comment
   repeated in several places for one workaround; a domain type widened to nullable or optional so
   existing call sites need not change (a migration-cost argument, not a design one); a workaround
   for a tool limitation nobody verified. This is true of every project, not of this one. It sits in
   `docs/project.md` today only because there was nowhere else to put it without losing it. The
   baseline's Design section has two adjacent rules and no room for these three, which are more
   operational than either.
4. **"The evidence goes in the commit message, never an unchecked claim in a comment."** The
   baseline requires that claims carry their proof, but says nothing about where the proof lives once
   the conversation is over. The commit message is the only durable home, and a comment asserting an
   unverified fact is the failure mode this closes.
5. **The gate perimeter rule has no room for generated code.** "Decided by location, no per-category
   exemption" collides with kapt, protobuf, generated clients and bytecode enhancement, none of
   which exist as source and none of which any test can reach. This project needed an override on
   day one (Kover's `annotatedBy("io.ebean.typequery.Generated")` filter), and the next JVM project
   will need the same one. Either the rule admits generated artefacts explicitly, or it should say
   how to declare them so every adoption does not re-litigate it.
6. **`modules/kotlin.md` states a stale version.** It says "JUnit 5" where this project runs
   `junit = 6.1.1`. A generic module naming a major version will keep aging; either drop the number
   or move it to the project file.
7. **The `report` heuristic flags legitimate prose as unfilled placeholders.** `holes_in` matches any
   `<...>` sequence, so `<clinit>` (a real JVM method name) and naming templates such as
   `baseline-<module>.xml` were reported as holes. Four were reported here and all four were false.
   They were reworded rather than left, because a warning that is usually wrong stops being read, but
   the heuristic is what should change.

## Suggested next step

1. Get `/context`, `/hooks` and `/permissions` confirmed by the operator. All three, since each is
   silent on failure.
2. Let the PR's `validate / gate` go green and merge with squash or rebase. Do not merge locally:
   `enforce_admins` is false, so a local admin merge bypasses CI without a word.
3. First real work under the new rules is the natural place to test the tier selection and the plan
   review. `docs/backlog.md` is unchanged and still holds the open items, the largest being **user
   data import**, the other half of the export that shipped as `v0.9.0-user-data-export`.
4. The upstream list above is worth taking to `agents-baseline` as one task rather than seven
   drive-by edits.
