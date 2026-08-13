# Handoff: the review regime moves upstream

Date: 2026-08-14
Branch: `docs/review-regime-rework`
Specification: `docs/specs/2026-08-13-review-regime-rework.md`
ADR: `docs/adr/0014-review-budget-upstream.md`

## Current state

Five commits, documentation only, no code. `./gradlew gate` green with `checkEvidenceGuard` and
`checkNoLongDashes` executed. The holistic review ran, reported ten MAJOR and five MINOR, and all
fifteen are fixed inside the lot. Not integrated: the pull request is open and awaits the human
review `agents/workflow.md` requires before a rebase merge.

Nothing has run under the new regime yet. That is the single most important sentence in this file.

## What was built, and the two numbers that were not what they looked like

**The regime.** Task review becomes block review: it reads a block of tasks instead of one task,
from the block's frozen commit range instead of the working tree, and it runs while the next block
is being built. Findings are arbitrated at the next boundary; a CRITICAL interrupts the block in
flight. Tiers Direct and Spec run no block review at all, since their diff is the branch diff the
holistic review reads anyway. Upstream, six angle mandates now read the spec before the operator
does, and three read the plan, which no longer has an approval gate of its own.

**The measurement was wrong twice, in the same direction.** Both errors were counting errors, in a
document that exists to justify a decision by counting.

The first: the return table counted 114 task reviews and 24 holistic ones. Those were the reviews
plus their fixup agents. An agent applying findings does not produce them, and counting it as a
review flatters both the rate and the cost per finding. On reviews alone: 100 task reviews (6
CRITICAL, 25 MAJOR), 19 holistic (0, 11), 12 upstream (5, 20). Per serious finding, 1.48 Mtok for
task, 1.76 for holistic, 0.58 for upstream.

That reversal matters and it survived into the decision: **task review is the cheaper of the two
downstream reviews per serious finding, not the dearer.** The case against it was never efficiency,
it was multiplicity, 5.26 to 1. Anyone re-opening this decision should start from that sentence
rather than from the intuition that per-task review is wasteful.

The second: six angles declare the spec as their artefact and the spec and the ADR both said five,
while `agents/workflow.md` listed six all along. An operator working from the ADR would have run
five, dropped one without knowing which, and silently broken the rule that requires a written
reason to drop any.

Both were found by the holistic review. Neither was found by me, twice over: I wrote them, then I
read the diff to check the acceptance criteria and did not recount.

## Pitfalls, in the order they will bite again

- **Classifying subagents by their description is fragile, and it failed twice.** The first pass
  classified `Review Task 1 (spec + quality)` as a plan review, because the description contains
  "spec" and the plan branch was tested before the task branch. The second pass fixed the ordering
  and still counted fixup agents (`Apply the T4 review findings`) as reviews, because their
  descriptions contain "review". Any measurement over these transcripts needs the fixup filter and
  needs its buckets printed with their members before the numbers are believed.
- **The harness leaves its evidence outside the repository.** Everything quantitative here comes
  from `~/.claude/projects/.../subagents/*.jsonl`, which no clone and no CI job can see. The scripts
  were throwaway by decision. A future session re-deriving these numbers rewrites them from scratch,
  and the backlog carries the item.
- **The cost of a review is set by the model, not by the mandate.** `agents/reviews/task.md` went
  from 52 lines to 55 between 2026-07-23 and 2026-08-12 while the cost of running it went from about
  250k tokens (sonnet-5, 8 to 45 turns) to about 1 Mtok (opus-5, 32 to 204 turns), with one review
  on 2026-08-13 at 4.6 Mtok. Any future argument about what a review costs should name the model
  before it names the criteria.
- **A `tail` inside a backgrounded command truncates the file the harness writes.** `./gradlew gate
  | tail -25 &` leaves a 27-line log with no evidence that the tasks you care about ran. Redirect
  the whole stream to a file and grep it afterwards.
- **The gate says almost nothing about a documentation lot.** It finishes in one second, executes 13
  of 168 tasks, and the only two that read these files are `checkNoLongDashes` and
  `checkEvidenceGuard`. Green here means "no long dash and no forbidden write", not "correct".
- **A lot whose subject is a review mandate is the one case where the main agent reads the
  mandates.** `agents/workflow.md` states the exemption; it applied to every step of this lot and
  the holistic brief had to say so explicitly, because the reviewer would otherwise have found its
  own subject off limits.

## What is not validated

- **No lot has run under this regime.** The seven angles have never been dispatched, the projected
  cost (about 10 Mtok, two parallel waves of roughly twelve minutes) is arithmetic on past
  per-review figures, and the block boundary has never been drawn by a plan.
- **The accepted rework rate is measured on the wrong unit.** 0.31 serious findings per review comes
  from reviews of one task each. A block review reads three or four tasks, so it will mechanically
  find more per review: "one block in three carries something worth acting on" is a floor, and the
  real figure could be materially higher. This is the first thing the re-measurement should settle.
- **The asynchronous dispatch has never been exercised.** No reviewer has yet read a frozen commit
  range while an implementer wrote in the same repository. The two suites run on `:memory:`
  databases, so there is no store to collide on, but the claim is reasoned, not observed.
- **The red-before-green criterion added to `agents/reviews/holistic.md` has never fired.** It
  replaces an enforcement that lived in the mandate two tiers no longer run, and nothing mechanical
  backs it: no hook and no gate task checks commit order.
- **CI has not run on this branch.** The container build and the OpenAPI sync check only run in
  `validate.yml`, and neither is exercised by a documentation change.

## Where each review finding went

Fifteen findings from the holistic review, all fixed inside the lot. None refused, none deferred to
the backlog, none recorded as an accepted limit. Two of them (the recount, the angle count) changed
figures in the spec and the ADR, which were still editable because a dated document freezes at
integration and this branch is not integrated.

No angle was excluded from a review pass, because no angle existed when this lot's own spec was
written: it went to the operator unreviewed, which is precisely what this lot stops. The first lot
to run the pass will be the first test of it.

No block finding had to be arbitrated as work, because this lot is tier Spec and ran no block
review.

## Suggested next step

**Improve, which has not run.** Three things are worth the discussion. The first is that a
documentation lot written carefully still yielded ten MAJOR to a single holistic review, which is an
argument against this lot's own claim that small tiers need only that one review, and it deserves an
answer rather than silence. The second is that both defects the review found in the measurements
were counting errors in a document whose purpose is to count, and the `evidence` angle written in
this very lot exists to catch exactly that: the angle would have had to review its own specification
to save it. The third is the model question the operator declined, which stays declined but is now
recorded with its figure.

After Improve, the first lot in tier Plan under the new regime is the real test, and the backlog item
`Re-measure the review regime after three lots` is what closes the loop.
