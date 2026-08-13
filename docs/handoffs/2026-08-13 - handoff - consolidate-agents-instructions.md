# Handoff: the repository owns its agent instructions

Date: 2026-08-13
Branch: `docs/consolidate-agents-instructions`
Spec: `docs/specs/2026-08-12-consolidate-agents-instructions.md`
ADR: `docs/adr/0011-own-the-agent-instructions.md`

## Current state

`AGENTS.md` is one file, about 820 lines, and this repository owns it. It carries what was in four:
the generic baseline, the situated project document, the Kotlin module and the backend module. No
file carries the `agents-baseline` marker any more, nothing points upstream, and `agents/` holds the
three review mandates and nothing else.

`.claude/hooks/evidence-guard.py` has two rules instead of three. It no longer refuses to edit a file
because of what its first line says. It still refuses content written by a command and a check that
cannot fail, and those two behave exactly as before: measured across 59 payloads passed to both
versions, the only differences are the three the removal intended (`Edit`, `Write`, `MultiEdit` on a
marked file).

The gate is green. Nothing in the build, the CI workflow or the git hooks ever named the merged
files, so nothing else had to move.

## What was built, and why it looks like this

The freeze bought one thing, a rule written once for several projects, and cost a round trip through
a second repository for every lesson. The operator carries both alone, so the round trip had become
the reason lessons went unwritten. ADR 0011 records the cut as clean: there is no upstream to follow
and no provenance to maintain.

The four files were four because one of them could not be edited here. With that gone, the split had
no reason left, and it was costing sixteen cross-references whose only job was to point from one file
to another. Merging them removed the plumbing, not the content: the loaded context is identical,
since the `@` imports were already resolved at load time.

The three review mandates stayed separate on a technical ground, not a stylistic one. The main agent
is forbidden from reading them so that an implementer does not write towards its own criteria; a
section inside `AGENTS.md` would be loaded into every session and would destroy that.

## Pitfalls, in the order they will bite again

1. **The freeze is self-locking and only the operator can lift it.** The guard denies the write, it
   keys on line 1 of the target, and it carries the marker itself: it refuses the edit that would
   disarm it, and refuses it on the six other files too. Delete-and-recreate, or editing
   `.claude/settings.json`, are circumventions of a permission decision and were not taken. The
   operator removed line 1 by hand in their own shell. **Anyone re-adopting a regime like this should
   know the exit needs a human.**
2. **The task order was constrained and the plan did not see it.** The merge could not start before
   the hook was disarmed, and the hook could not be disarmed before the operator acted. Two tasks
   dispatched as parallel were a chain of three.
3. **A multiset diff of bold statements misses two things**, and both happened. A fragment deleted in
   one place and created in another cancels out (it hid a deletion outside the closed list, caught by
   the task review); and a fragment that should have gone and stayed appears on neither side (it hid
   `**the one genuine narrowing**`, caught by the holistic review). The check proves every statement
   still exists somewhere, never that it exists where its rule was.
4. **Merging two files merges their regimes.** `AGENTS.md` was frozen, `agents/project.md` moved with
   the code in the same commit. In one file, the new amendment guard and Simultaneity asked for
   opposite things about the same hunk, and `cf9a547` is the precedent that decides it: a rule
   discovered while writing the code that establishes it ships in that code's commit.
5. **A background subagent signals "idle" without delivering its report.** All three reviews had to be
   asked for their findings after the fact. The spawn prompt must demand `SendMessage` to `main`
   explicitly; the holistic brief did and the two task briefs did not.

## Not validated against real conditions

- **The hook has no automated test and cannot get one where it lives.** `.claude/` is outside the
  gate perimeter by location, and the perimeter is decided by location on purpose. Every claim about
  the guard in this lot rests on hand-run invocations, including the 59-payload differential. Nothing
  will catch the next regression, and two fixes went into the file after that sentence was first
  written, on the operator's call, still with no test behind them.
- **Nobody has worked a real task through the merged file yet.** The reviews read it; no session has
  used it to build something. Whether one file of 820 lines is easier or harder to work from than
  four is untested, and the two pairs of near-homonymous sections (Design against Design invariants,
  Kotlin's "Tests and coverage" against Conventions' "Tests") are the place to watch: they got
  cross-references, not a rename.
- **The amendment guard is a convention with no mechanism.** The marker used to make it impossible to
  edit the instructions or the mandates mid-lot. Now a sentence asks for it. ADR 0011 accepts this
  and notes a hook could enforce it later.
- **CI has not run on this branch.** The gate is green locally, and the container build plus the
  OpenAPI sync check only run in `validate.yml`.

## Where each review finding went

Twelve findings across three reviews. Eleven fixed inside the lot: the misleading sentence about what
the guard enforces, the amendment guard against Simultaneity, the departure count and the narrowing
vocabulary, the regime-table renvoi, the dead `docs/project.md` reference in
`ArchitectureKonsistTest`, the two cross-references between homonymous sections, the `.gitignore` hole
for `__pycache__`, three spec amendments (the closed list, the blind spot of criterion 1, the prose
the merge adds), and the two faults in the evidence guard. One became a known limit: the matcher
fires the guard on more tools than it inspects, deliberately, because narrowing it would make a
future edit-inspecting rule fail silently.

The guard faults were filed to the backlog first and pulled back in at the operator's request, read
on the pull request. The alternative was put and declined: give the file a safety net first, a Gradle
task running `python3 -m unittest` hung off `gate`. They are therefore fixed without one, which is
the most fragile thing this lot ships. What replaces the test is the holistic reviewer's 59-payload
corpus replayed against both versions plus eight cases aimed at the fixes, and the backlog keeps the
safety net itself as an open item.

## Suggested next step

Use the file. The next lot of any size is the real test of whether the merge helped, and its Improve
phase is where a section that turned out to be hard to find should be moved rather than
cross-referenced.

If instead the next session wants to close what this one opened, the backlog item on the evidence
guard is well specified and small, but its first question is not the two faults: it is how a Python
file under `.claude/` gets a safety net at all, given that the gate perimeter is decided by location
and that location is outside it.
