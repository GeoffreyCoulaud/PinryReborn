# Consolidate the agent instructions into one owned file

Date: 2026-08-12
Status: Draft
ADR: `docs/adr/0011-own-the-agent-instructions.md`

## Goal

Make this repository the owner of the instructions that govern work in it. Today `AGENTS.md`,
`agents/modules/kotlin.md`, `agents/modules/backend.md` and the three review mandates are frozen
copies of `agents-baseline`, replaced by copy and never edited here. Keeping two repositories in
step costs more than the shared text is worth, so the copies stop being copies and the four
instruction files become one.

**No engineering rule changes.** What this lot removes is the freeze, the cross-file plumbing that
the split required, and the sentences whose only subject was the upstream regime. Every norm in
force on the day before this lot is in force on the day after, in the same words.

## Context

The freeze is held by three separate mechanisms, all of which have to go together:

1. A marker on line 1 of seven files (`AGENTS.md`, the two modules, the three review mandates and
   `.claude/hooks/evidence-guard.py`), spelling `agents-baseline` and `do not edit in place`.
2. `evidence-guard.py:102-126`: `is_generic()` reads line 1 and blocks `Edit`, `Write` and
   `MultiEdit` when both marks are present. It is the third of the hook's three rules; the other
   two (content written by a command, a check that cannot fail) are independent of it.
3. Four sentences in prose: the `AGENTS.md` header, the `agents-baseline` clause in phase Improve,
   the `sourced` row of the documentation regime table, and the `Improve commits separately`
   convention in `agents/project.md`.

Nothing outside those files depends on the layout. Only `.claude/CLAUDE.md` (`@../AGENTS.md`) and
the hook's refusal message name these paths; the build, the CI workflow and the git hooks do not.

## What changes

### 1. The freeze is removed

The marker goes from all seven files. The hook loses `GENERIC_MARKS`, `GENERIC_ADVICE`,
`is_generic()`, its call site and the three sentences of the module docstring that describe the
rule. Its other two rules are untouched.

### 2. Four files become one

`AGENTS.md`, `agents/project.md`, `agents/modules/kotlin.md` and `agents/modules/backend.md` merge
into a single `AGENTS.md` at the repository root. `agents/modules/` disappears. `.claude/CLAUDE.md`
keeps its single `@../AGENTS.md` line and needs no change.

The context an agent loads does not grow: the `@` imports were already resolved at load time, so
the merge removes cross-references, not content.

**The three review mandates stay separate files.** `agents/reviews/{plan,task,holistic}.md` are
forbidden reading for the main agent, so that an implementer never writes towards the criteria that
will judge the work. Folding them into `AGENTS.md` would load them into every session and destroy
that. They are unfrozen like the rest and keep their paths.

### 3. The three restrictions are resolved

Nine of the twelve references to `AGENTS.md` in `agents/project.md` are plumbing and disappear with
the merge. Three state a restriction in the form "the generic file says X, here it is Y", which
becomes incoherent once both halves live in one file. Each keeps the situated wording, which is the
more precise one, and drops the generic portion it annulled. The effective norm is unchanged.

| Site | Today | After |
|---|---|---|
| `project.md:250` | "`AGENTS.md` offers squash or rebase; here only rebase exists" | "Merging is rebase only: `gh repo view` reports squash and merge commits disabled." |
| `project.md:257` | "`AGENTS.md` lets documentation-only changes merge locally; this project declares no such paths" | "Everything integrates through a PR, including documentation-only changes, because a local merge to `main` bypasses the `validate / gate` check." |
| `project.md:95,103` | the perimeter, plus "the only other narrowed `AGENTS.md` rule is the merge convention" | the perimeter unchanged, and the count of departures kept as a statement of its own: two exist, the models-package exclusion and the merge convention, both named. |

The vocabulary of narrowing goes, since there is no third-party document left to narrow. What it
carried, that departures are counted and named rather than accumulated quietly, stays.

### 4. What is deleted (closed list)

Nothing leaves the file except these. Anything else surviving in the diff is a defect.

1. The seven line-1 markers.
2. The hook's third rule (see 1 above).
3. `AGENTS.md` header: "Engineering baseline, byte-identical across projects: never edit it here."
4. `AGENTS.md` phase Improve: "A lesson true of every project is proposed to the shared baseline
   (`agents-baseline`), never edited here."
5. `project.md:122`: the `sourced` row of the documentation regime table. `AGENTS.md` joins the
   table as `living`; `agents/reviews/*` joins it as `living` too.
6. `project.md:291`: "since `AGENTS.md` is generic and the hook refuses to edit it", and the clause
   sending a generic lesson upstream.
7. The cross-file plumbing: nine renvois in `project.md`, four in the two modules, and the two `@`
   import lines.
8. The generic halves of the three restrictions in section 3.
9. Two historical vestiges about the pre-baseline `AGENTS.md`, which is not an instruction but a
   record: `project.md:233-247` ("Claims the old `AGENTS.md` made that the code disproved") and the
   trailing clause of `project.md:151` ("HTTP Basic is gone; the old `AGENTS.md` still announced
   it"). Both are quoted in ADR 0011 with their origin, and git keeps the rest.

### 5. One guard replaces the freeze

The freeze did two things and only one of them is being refused. Synchronising two repositories is
the cost. Preventing an agent from amending, mid-lot, the rules that govern it is worth keeping and
costs nothing. It becomes a convention in the merged file: a rule changes only in a lot whose
subject it is, never in passing, and the observable is a separate `docs(agents):` commit. The
Improve phase already commits that way, so this states an existing practice rather than adding one.

## Target structure

One file, sections in this order. Every bullet moves intact; only section titles and the sentences
listed in section 4 change.

| Section | Source |
|---|---|
| Header and the amendment guard | new, replaces the freeze header |
| The project, orientation, where the code lives | `project.md` |
| Commands and the gate | `project.md` |
| Scope, Evidence, Design | `AGENTS.md` |
| Workflow: phases, tiers, review mandates | `AGENTS.md` |
| Engineering norms and the gate perimeter | `AGENTS.md` + `project.md` |
| Kotlin | `kotlin.md` |
| Backend and the API contract | `backend.md` + `project.md` |
| Design invariants | `project.md` |
| Documentation regimes | `AGENTS.md` + `project.md` |
| Conventions | `AGENTS.md` + `project.md` |
| Gotchas | `project.md` |

Conventions currently arrive as one list of seven generic bullets and about twenty situated ones,
of mixed subject. They are grouped into named subsections (git and integration, writing, tests,
code, harness, backlog) without a bullet being rewritten. Placement is a judgement call per bullet;
the anti-loss check below is what makes a misplacement visible as a survival, not a rewrite.

## Acceptance criteria

Each one is a command whose failure is defined.

1. **Nothing is lost.** `grep -o '\*\*[^*]*\*\*'` over the four source files, sorted, measured at
   194 fragments on 2026-08-12 before the first edit, diffed against the same command over the
   merged file plus the three mandates. The diff shows only the removals of section 4. A surviving
   fragment that section 4 does not list is a rule silently dropped.
2. **The layout is what it claims.** `git ls-files agents` returns the three review mandates and
   nothing else.
3. **The cut is clean.** `grep -rn "agents-baseline" --exclude-dir=.git --exclude-dir=docs .`
   returns nothing. `docs/` is excluded because ADR 0001, ADR 0005 and three handoffs are frozen
   dated documents that record the adoption and are never rewritten.
4. **The hook still guards what it guarded.** Two invocations, output pasted in the commit message:
   a redirection writing into the working tree still exits 2, and an `Edit` on `AGENTS.md`, which
   exited 2 before this lot, now exits 0. The hook is Python under `.claude/`, outside the gate
   perimeter, so no automated test covers it and this manual pair is the whole evidence.
5. **The gate is green.** `./gradlew gate`, which runs `checkNoLongDashes` over every tracked file.

## Out of scope

- **Changing any engineering rule.** Not one norm is added, weakened or reworded beyond the closed
  list of section 4.
- **The content of the three review mandates.** They are unfrozen and left byte-identical.
- **`.claude/settings.json`,** the permissions and the deny list.
- **The `agents-baseline` repository itself.** Out of this working tree; this lot never reads it.
- **Rewriting ADR 0001 or ADR 0005.** They stay `Accepted`: their content arbitrations still hold,
  only the adoption mechanism ends. ADR 0011 records that.
- **The backlog entries** open at the start of this lot.
