# 0020. Two reviews, an inline Act, a freeze at the last merge, and a closing block

Status: Accepted
Date: 2026-09-05
Specification: `docs/specs/2026-09-05-p2-debt-elimination.md`, section 3 (D1, D10, D11, D12) and
section 4.1.
Amends: `docs/adr/0018-a-block-is-a-pull-request.md`, decision 4 (the dispatch exemption is removed)
and decision 8 (the block review is removed and the three spec angles become one review);
`docs/adr/0019-review-before-the-pull-request.md`, whose block review has nothing left to place and
whose decision 5 restored a mandate that no longer exists. Everything else in both stands.
Related: `docs/adr/0010-review-finding-dispositions.md` (the four exits; decision 4 below gives a
holistic finding against a merged block its exit), `docs/adr/0014-review-budget-upstream.md` (three
reviews, now two), `docs/adr/0001-adopt-agents-baseline.md`.

## Context

**The mandates were deleted and the documents kept citing them.** Commit `b3a8671a` of 2026-09-05,
"trim prose and theatre", removed the eight files under `agents/reviews/`. After it,
`agents/workflow.md:89` still said "re-run the block review over the new commits only",
`agents/writing.md:13` still listed `agents/reviews/*` as a living document, and `AGENTS.md:9` still
sent the reader to "review mandates". Asked which regime the deletion meant, the operator answered:
an adversarial review of the specification, an adversarial holistic review of the lot, and no block
review, the human being enough for a pull request. That answer is the load-bearing claim of the lot
this ADR belongs to, and it is recorded in the specification's section 3 and nowhere else.

**Four questions were filed by the previous lot rather than answered**
(`docs/handoffs/2026-09-04 - handoff - review-before-the-pull-request.md`, next step). ADR 0018
decision 4 justifies inline Act by a subagent being unable to interrupt, and lets an over-budget
block be dispatched anyway, where the mandatory tier-2 question is impossible. A specification is
delivered in an early block's pull request and freezes at delivery, while later blocks must correct
it and record adjacent items in it. A holistic finding against an already merged block fits none of
the four exits. And the ADR-existence check went with `agents/reviews/plan.md` at `7e89996e` and
landed nowhere.

**One figure to correct.** `docs/handoffs/2026-09-04 - handoff - blocks-as-pull-requests.md` line 47
says "six of fourteen items had no such document". The count is four, as ADR 0019 line 113 already
records; the handoff is frozen and this is the second place the correction is written.

## Decision

1. **Two reviews, and each pull request is read by the human alone.** The specification is reviewed
   once, in a fresh subagent, on `agents/reviews/spec.md`, before the operator reads it: evidence,
   falsifiability, and the decision record, which is where the ADR-existence check now lives. The
   lot is reviewed once, in a fresh subagent, on `agents/reviews/holistic.md`, after the last code
   block's gate passes and its handoff is written, on that block's branch, over the whole lot with
   the merged blocks included. It runs on every lot in tier Spec; tier Direct skips it. There is no
   block review. ADR 0018 decision 8's three angles become the one specification review.

2. **Act is always inline.** ADR 0018 decision 4's sentence "a block that exceeds the budget anyway
   may still be dispatched" is removed. A subagent cannot stop to ask, and tier 2 requires stopping;
   a block over budget is long, or splits as decision 1 of that ADR already provides.

3. **A lot's dated documents freeze when its last block merges.** Not at writing, and not at the
   first pull request that carries them. Until then a block's pull request may correct them, in the
   `(Corrected: ...)` form at the sentence it corrects, never a rewrite. After the freeze, changes go
   in a new dated document, cross-linked both ways.

4. **The holistic review's findings are the lot's closing block**, with its own pull request. The
   last code block writes the handoff so the review reads it; the closing block fixes the findings,
   corrects the handoff, names each finding's exit, and states how many findings touched an already
   merged block. That number is what series costs in rework, and Wrap reports it.

## Consequences

- **A pull request's code is read by one human and no agent.** What the block review found on the
  export completion lot, two functional defects (ADR 0018 context), is now the human's to find or
  the holistic review's, one lot later. The operator chose that trade with the defects named.
- **The holistic review's latency lands once per lot** instead of a block review's once per block,
  and it lands where a finding is dearest, against code already on `main`. Decision 4 makes that
  cost visible as a count rather than hidden as a backlog item.
- **The closing block is a pull request whose size nobody predicts.** It is bounded by ADR 0018
  decision 1 like any block: over budget, it splits.
- **A dated document may be edited by several pull requests.** Decision 3 bounds the edit to a
  marked correction at the sentence it corrects, so the document keeps recording what was believed
  when, and the handoff of the last code block records what the closing block then changed.
- **What review costs is still unmeasured, and the question is now closed rather than open.** The
  operator refused the measurement (specification, D2). This ADR changes the regime without it, as
  0014, 0018 and 0019 did; the difference is that it says so as a decision instead of leaving the
  item to the next lot.
- **ADR 0014's "three reviews" are two, ADR 0001's status line says so**, and the review tables the
  trim removed from `agents/workflow.md` are not restored: the two mandates are the regime's whole
  text.
