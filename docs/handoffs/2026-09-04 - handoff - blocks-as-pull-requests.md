# Handoff: a block is a pull request

Date: 2026-09-04
Branches: `docs/workflow-blocks-as-pull-requests` (merged, PR #73), `docs/backlog-two-line-items`
Decision: `docs/adr/0018-a-block-is-a-pull-request.md`
Tier: Spec. Two blocks, two pull requests, one block review each plus `evidence` on the ADR.

## Current state

`./gradlew gate` green on both blocks. The regime the ADR describes is in force from its merge, and
this lot is the first thing to have run under it, partially: block 2 was cut, implemented inline,
reviewed and integrated as its own pull request, which is the shape the ADR prescribes.

## What was built

The operator's complaint was that a 3256-line pull request could not be read. The measurement said
why: 305 of those lines are production code, 9 %.

- **A block is the smallest change mergeable to `main` on its own**, under three checkable
  conditions (green alone, coherent alone, readable alone at 600 lines excluding dated documents and
  200 of production code), and it is one pull request. Blocks ship in series.
- **The specification and the plan are one document**, the plan being a table with one row per block.
  `docs/plans/` is closed to new files; `agents/reviews/plan.md` is deleted.
- **Act is inline**, which is what lets an agent stop and ask the operator at the moment it finds an
  adjacent defect. That question is the middle of three tiers, and it is where the backlog drains.
- **A lot closes the backlog items adjacent to its subject**, binding.
- **Three spec angles instead of six**, one block review per pull request, the holistic on the last
  one before it merges, on every tier Spec lot whatever its block count.
- **The P2 band went from 115 lines to 53**, at 3.5 lines per item.

## Pitfalls, in the order they cost time

1. **A handoff sentence was false and a whole decision was built on it.** The export completion
   handoff says no block review found a functional defect in delivered production code. `4f13bc90`
   and `27662dc6` refute it: the first repairs a failure net that had reintroduced the very defect
   its lot existed to fix. The `evidence` angle found this; nothing else would have. **A quotation
   from a dated document is a dated claim**, and the rule about re-running measurements applies to
   sentences too.
2. **Three counts in the first draft were wrong**, all of them mine, all produced by a command that
   looked right: 41 lots instead of 36 (counting commits whose message matched "handoff" rather than
   handoffs added), a median of 1401 instead of 1926, and 12 minutes of continuous integration
   instead of a 7.2 minute median. Each was quoted to the operator before it was checked.
3. **The block table's ranges were reconstructed from review-closure commit subjects**, so every row
   carried the previous block's remediation. Block 5 read as 204 lines and is 39. Derive a block's
   extent from the tasks it carries, or better, from the pull request that is now its boundary.
4. **The two-line backlog rule assumed something false about half its subjects.** It says an item
   points at the dated document carrying its reasoning. Six of fourteen items had no such document:
   their argument was only ever written into the backlog, and dated documents are append-only, so it
   cannot be moved out now. The rule gained the exception rather than the items losing their
   content. **Verify a pointer before compressing what it points away from.**
5. **A mandate that tells the reviewer what it usually finds tells it to find less.** The first
   draft of `agents/reviews/block.md` said it had been cut to what block review "was measured to
   find". Even had the measurement been right, that sentence steers a reviewer away from everything
   else. It now names the two functional defects instead.

## What is not validated

- **No code lot has run under this regime.** Both blocks here are documents. The size budget, the
  "coherent alone" condition and the tier-2 interruption have never met a real implementation.
- **The block review's lightening is untested against code.** Branch coverage and "green after
  refactor" were dropped as redundant with the gate and continuous integration. That reasoning is
  sound on paper and has reviewed nothing but Markdown so far.
- **What review costs and what it returns is still unmeasured**, and remains an open backlog item.
  This lot changed the regime without answering it, which is the second time that has happened
  (`docs/adr/0014-review-budget-upstream.md` was the first).
- **Series has never been paced by a real review queue.** Its cost is the operator becoming the
  bottleneck, and one document lot does not test that.

## Next step

Improve, whose question is already written by pitfalls 1 and 2: two decisions in a row have rested
on a figure nobody recounted, and the angle that caught both is the most expensive place to catch
them. Then the first code lot under the regime, which is where the budget and the interruption get
their real test.
