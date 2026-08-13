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

**The operator removes the marker, because no agent can.** The guard is a PreToolUse deny over
every write tool, keyed on line 1 of the target, and the hook carries the marker itself: it refuses
the edit that would disarm it, and refuses it on the six other files too. The routes around it
(delete and recreate the file, or edit `.claude/settings.json`) circumvent a permission decision
and are not taken. So the freeze is lifted by hand, outside the agent's tools, on the five files
an agent must then write: this file's other steps assume that has happened. The two modules need
no such hand, since `git rm` is not a write the guard inspects (measured: exit 0). Done on
2026-08-12 with `sed -i '1{/do not edit in place/d}'` in the operator's own shell.

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
| `project.md:95,103` | the perimeter, plus "the only other narrowed `AGENTS.md` rule is the merge convention" | the perimeter unchanged, and one departure left to count instead of two, since the merge convention no longer departs from anything once both halves are in one file. |

The vocabulary of narrowing goes, since there is no third-party document left to narrow: both
"**the one genuine narrowing**" and "departures from the rules of this file" go with it. What it
carried, that a departure is counted and named rather than accumulated quietly, stays, and it now
has one subject rather than two. The merge convention was a departure from a generic file that
offered squash or rebase; that file is this one, it offers rebase only, and nothing departs from
it. The models-package exclusion remains a real one, because the norms here do ask for 100% inside
the perimeter with no per-category exemption.

The first draft of this section kept the count at two and left the narrowing vocabulary in place.
The holistic review found the paragraph unverifiable as delivered: rewritten on 2026-08-12, and the
count is now one.

### 4. What is deleted (closed list)

Nothing leaves the file except these. Anything else surviving in the diff is a defect.

1. The seven line-1 markers.
2. The hook's third rule (see 1 above).
3. `AGENTS.md` header: "Engineering baseline, byte-identical across projects: never edit it here."
4. `AGENTS.md` phase Improve: "A lesson true of every project is proposed to the shared baseline
   (`agents-baseline`), never edited here."
5. `project.md:122`: the `sourced` row of the documentation regime table. `AGENTS.md` joins the
   table as `living`; `agents/reviews/*` joins it as `living` too.
6. `project.md:291`: the whole sentence "A rule lands in **this file**, since `AGENTS.md` is generic
   and the hook refuses to edit it", plus the clause sending a generic lesson upstream. The main
   proposition goes with its subordinates: in a single file, "a rule lands in this file" opposes
   nothing, and the rule survives where it is load-bearing, in phase Improve, which names this file
   as the home of a judgement call. The first draft of this item named the subordinates only; the
   task review found the proposition deleted outside the list and it was added here on 2026-08-12.
7. The cross-file plumbing: nine renvois in `project.md`, four in the two modules, and the two `@`
   import lines.
8. The generic halves of the three restrictions in section 3.
9. Two historical vestiges about the pre-baseline `AGENTS.md`, which is not an instruction but a
   record: `project.md:233-247` ("Claims the old `AGENTS.md` made that the code disproved") and the
   trailing clause of `project.md:151` ("HTTP Basic is gone; the old `AGENTS.md` still announced
   it"). Both are quoted in ADR 0011 with their origin, and git keeps the rest.

### 5. One guard replaces the freeze

The freeze did two things and only one of them is being refused. Synchronising two repositories is
the cost. Preventing an agent from amending, mid-lot, the rules and the review criteria that govern
it is worth keeping and costs nothing. It becomes a convention in the merged file: a rule or a
review mandate changes only in a lot whose subject it is, never in passing.

**It has to yield to Simultaneity, and the first draft did not say so.** A rule this repository
discovers while writing the code that establishes it belongs in that code's commit: that is how
`cf9a547` shipped the uniqueness invariant, and the plans of the day asked for exactly that. Before
the merge the two regimes lived in two files and could not collide, `AGENTS.md` being frozen and
`agents/project.md` moving with the code. In one file they do collide, so the guard names what it
forbids, loosening a criterion that judges the work in flight, rather than prescribing one commit
shape. Found by the holistic review, 2026-08-12.

### 6. What the merge adds

Prose that was in none of the four sources. None of it carries a norm: each piece replaces plumbing
the split had made necessary, or answers a review.

- The header, and the amendment guard of section 5.
- Under Review mandates, why the three stay files of their own rather than becoming a section.
- Under Commands, what the guard still inspects now that its generic-file rule is gone (task review
  of the hook).
- Four cross-references between the pairs the merge made homonymous: Design against Design
  invariants, and Kotlin's "Tests and coverage" against Conventions' "Tests" (task review of the
  merge).

Listed here on 2026-08-12: the first draft of this document said only titles and section 4 changed,
which its own diff contradicted, and the holistic review said so.

### 7. The guard: one fault fixed, one "fault" that was not, and a test

Both were found by hand while removing the generic-file rule and both predate it. The first
disposition was a backlog item, because the file had no test; the operator read that item on the
pull request, asked for the work now, and then asked for a test file beside the hook: two parametric
tables, nothing more.

**Fixed, the crash.** `main()` answered a traceback and exit 1 to a valid JSON payload that was not
an object, where its own comment promises never to block on input it cannot read and its docstring
defines only 0 and 2. A non-object payload, a `tool_input` that is not an object, a `command` that is
not a string and a `cwd` that is neither a string nor absent now all leave through that same door.

**Reverted, the in-place allowance.** The second change let `sed`, `perl` and `ruby` run in place
when every operand was disposable. Its task review measured what that opened: an in-place editor's
script carries its own write capability, so the operand says nothing about where the write lands.
`sed -i -e '1w AGENTS.md' /tmp/x.md` was allowed by that version and rewrites a tracked file; a
`perl -e` or `ruby -e` script is arbitrary code and says even less. The allowance is withdrawn. These
editors are refused by command name, as before, and the reason now sits in the function's docstring,
where the reader who wonders why `cwd` goes unused will meet it. The original complaint, that the
check ignores `is_disposable` although it receives `cwd`, was a defect of the documentation and not
of the behaviour: the code was right and said nothing about why.

**The test**, `.claude/hooks/test_evidence_guard.py`: one table of commands the guard must allow, one
of commands it must block, each case running the guard the way the harness does, on stdin, with the
exit code as the verdict. `checkEvidenceGuard` hangs it off `gate` beside `checkNoLongDashes`, so the
gate reaches a file Kover cannot see, and it needs no new dependency since `python3` is already
required per clone.

Its red is not simulated. Reverting the allowance is what produced it: three cases in the allowed
table failed at once when the behaviour went back, which is the table catching a real change rather
than a mutation staged to prove a point. And a Gradle `Exec` fails the build on a non-zero exit,
measured, so a red test is a red gate.

**Then the four pre-existing holes, on the operator's call, in red-green.** The task review of the
revert had measured them and they were on the backlog; the cases went in first and failed, six at
once, and the fixes followed.

- `is_disposable` prefix-tested a path without normalising it, so `/tmp/..` walked back into the
  working tree and `echo hi > /tmp/../<repo>/AGENTS.md` was allowed. It normalises first now, which
  closes the same hole for redirection, `tee` and the truncating commands together, since all four
  ask that one function.
- In-place detection matched only `-i`, `-i.` and `--in-place` for sed, whose short flags cluster and
  whose suffix attaches, so `sed -ni`, `sed -in` and `sed -ibak` rewrote a tracked file unnoticed.
  A sed-specific pattern replaces that: no other short sed flag carries an `i`.
- `xargs -I{} sed -i 's/a/b/' {}` was allowed, because the token after a wrapper was taken as the
  command and `-I{}` claimed the slot. A wrapper's own flags and the placeholder are skipped now, and
  an in-place editor is judged wherever its name appears, since a wrapper this guard does not know
  still runs what sits behind it.
- `check_truncating` did not skip a flag's value and read `truncate -s 0 file` as writing to `0`. It
  blocked either way, so this one was a wrong reason rather than a hole, and the message now names
  the file.

Non-regression is measured the same way as before: the 59-payload corpus against the version that
opened this branch. Two verdicts move, the crash and that corrected `truncate` message; the eight
in-place cases are identical.

## Target structure

One file, sections in this order. Every bullet moves intact; what changes is section titles, the
sentences listed in section 4, and the prose the merge adds, listed in section 6.

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
   merged file alone. The three mandates are not in the comparison on either side: their content
   does not move, and counting them only after would show their fragments as arrivals. The diff
   shows only the removals of section 4. A surviving fragment that section 4 does not list is a
   rule silently dropped.

   **What it misses, and it is not small**: `comm` compares multisets, so a fragment deleted in one
   place and created in another cancels out and shows on neither side. The check establishes that
   every statement still exists somewhere, never that it still exists where its rule was. The task
   review demonstrated it on `**this file**`, whose count held at one across a deletion and a
   rewording, and closed the gap with three further diffs (backticked fragments, table rows, word
   multiset) that found nothing beyond the closed list.
2. **The layout is what it claims.** `git ls-files agents` returns the three review mandates and
   nothing else.
3. **The cut is clean.** `grep -rn "agents-baseline" --exclude-dir=.git --exclude-dir=docs .`
   returns nothing but paths to the two ADRs that record the adoption. `docs/` is excluded because
   ADR 0001, ADR 0005 and three handoffs are frozen dated documents, never rewritten. What the cut
   removes is the regime: no marker, no upstream to follow, no sentence sending a lesson there. A
   citation of `docs/adr/0001-adopt-agents-baseline.md` is a pointer to this repository's own
   history and stays, the way any decision keeps its record.
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
