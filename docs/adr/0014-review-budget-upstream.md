# 0014. The review budget moves upstream, and block review runs behind the work

Status: Proposed
Date: 2026-08-13
Specification: `docs/specs/2026-08-13-review-regime-rework.md`
Related: `docs/adr/0001-adopt-agents-baseline.md` (the workflow that introduced the three reviews),
`docs/adr/0010-review-finding-dispositions.md` (the four exits a finding takes, unchanged here).

## Context

ADR 0001 adopted a workflow with three reviews: plan, task, holistic. ADR 0010 examined what they
produce and concluded "the reviews are not the problem". That remains true of what they find. It was
never true of when they run or how many there are, and neither ADR measured either.

Both were measurable. The session transcripts hold every subagent's token usage and timestamps, and
338 subagents across 17 lots between 2026-07-15 and 2026-08-13 say this:

- Task review is the second largest subagent line: 100 reviews plus 14 fixup agents, 67.5 Mtok
  normalised to input-token equivalents, against 27.1 Mtok for 19 holistic reviews and 5 fixups.
- The parallelism factor is 1.0 on every session. No review ever overlaps another, and none overlaps
  an implementer. Reviews are serial by construction of the workflow, not by necessity.
- Between consecutive implementers, 33.2 hours build nothing, of which 16.1 hours are windows
  holding a task review. The reviews themselves are 7.1 of those hours; the other 9 are the main
  loop writing briefs and arbitrating reports. Implementation itself is 20.9 hours. The task-review
  cycle is therefore 44 % of the time the work moves forward.
- Per serious finding, upstream review costs 0.58 Mtok, holistic 2.46 and task 2.11. Task review is
  not less efficient than holistic review. There are 4.75 times more of it.

The last number is the one that decides. The cheapest place to find a defect is the document that
has not been implemented yet, and the repository's own history says so twice over: the plan review
that refuted "nesting `inTransaction` opens two transactions" corrected three documents before any
code changed, while the same class of unmeasured claim, left standing in ADR 0006, cost three plan
revisions after the fact.

Deleting task review outright was considered and rejected on evidence. The two reviews catch
disjoint classes: task review caught a false scope note in a plan, a half-covered path the spec
named, and a proposed guard fix it measured as opening a hole. Holistic review caught a bug "every
per-task review had waved through", an incomplete task that had passed its own review, and a
cross-task Critical no single-task reviewer could see. Removing one loses its class rather than
moving it to the other.

## Decision

1. **Task review becomes block review, and runs in tier Plan only.** A block is the plan's grouping,
   or the span ending where a later task first depends on an earlier task's result. Tier Direct and
   tier Spec run no block review: their task diff is the branch diff that the holistic review reads
   anyway. `agents/reviews/task.md` becomes `agents/reviews/block.md` with its criteria unchanged.

2. **Block review runs one block behind the work.** Block N+1 starts as soon as block N is complete;
   the review of block N runs alongside it and its findings are arbitrated at the next boundary,
   before N+2 is dispatched. A CRITICAL finding interrupts the block in flight. The reviewer reads a
   frozen commit range, never the working tree.

3. **The upstream pass is a set of angles, and each angle is a mandate.** Seven files in
   `agents/reviews/`, flat beside the existing mandates, each declaring the artefact it reviews:
   evidence, falsifiability, precedent, security, operations, testability, and the existing plan
   mandate as the generalist. The main agent runs **all angles declaring the artefact under review**
   unless it states a reason to exclude one, and wrap reports the exclusions. Angles dispatch in
   parallel, so the pass costs the slowest angle in wall-clock, about twelve minutes.

4. **The angles report before the operator reads, and the plan is agent-reviewed only.** The five
   spec angles run on the draft, their findings are closed, and the corrected spec is what goes for
   approval: the operator's attention is spent on what only they can decide, not on defects an agent
   finds. The plan is written after that approval and reviewed by the three plan angles alone, with
   no approval gate of its own. A plan derives from an approved spec, so what it needs is a check
   that the derivation is faithful and complete.

5. **The reviewer's model does not change.** It is the largest single cost lever measured, about 4x
   between sonnet-5 and opus-5 at equal mandate, and it is declined: the finding rate per review
   roughly doubles with the more capable model, and this project buys the findings.

## Consequences

- **A defect can now be built upon before it is reported.** That is the price of decision 2, and it
  was accepted with the rate in hand: 0.28 serious findings per review means about one block in four
  carries something worth acting on. The compensating control is the CRITICAL interruption and the
  frozen commit range.
- **The rework rate is the number to watch, and nothing measures it.** If block review starts
  reporting findings whose fix touches work done after the block, decision 2 is costing more than it
  saves. Wrap is the place that would see it, since it already states each finding's exit.
- **Tier Direct now has exactly one review.** A one-file change is read once, by the holistic
  reviewer, after the gate. If a Direct lot ever ships a defect that a task review would have caught,
  that is the case against this part of the decision, and it should be recorded rather than argued.
- **Seven mandates are seven files to keep true.** Each was derived from defects the handoffs
  record, and an angle whose criteria stop matching what lots actually get wrong is dead weight the
  next Improve phase should cut.
- **The operator no longer gates the plan.** Decision 4 makes that explicit where the workflow was
  merely silent, and it means a plan can be wrong in a way no human saw until Act produces something
  odd. Three plan angles plus the block review are what stands in that place. The operator can still
  ask for a plan at any point; what changes is that the phase no longer waits for it.
- **The measurements behind this ADR are not reproducible from the repository.** They come from
  session transcripts outside it, produced by throwaway scripts. Re-measuring after three lots is a
  backlog item, and whether the repository should own that tool is a separate question.
- **ADR 0001's review regime is amended, not repealed.** The three reviews still exist, still run in
  fresh subagents, and still never edit. What changes is their count, their placement and their
  timing.
