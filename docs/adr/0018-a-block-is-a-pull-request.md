# 0018. A block is a pull request, and the blocks of a lot ship in series

Status: Accepted
Date: 2026-09-04
Amends: `docs/adr/0014-review-budget-upstream.md`, whose block review this keeps and lightens, whose
plan pass this removes, and whose spec pass this cuts from six angles to three.
Related: `docs/adr/0010-review-finding-dispositions.md` (the four exits a finding takes; decision 5
below changes which exit is the default, not the set), `docs/adr/0001-adopt-agents-baseline.md`.

## Context

ADR 0014 moved the review budget upstream and named its own re-measurement as a backlog item, due
once three lots had run under the new regime. Three have: the user data import, the export row
fencing, and the export build completion. This ADR is that re-measurement.

It needed no session transcript. The repository answers the question, and the answer is not about
reviews.

**The pull request is not read, because the code in it is 9 % of it.** The export build completion
lot, measured over `4d9b4852..efa43340`:

| Nature | Files | Added | Share |
|---|---|---|---|
| Dated documents (spec, plan, ADR, handoff) | 4 | 1212 | 37 % |
| Tests and fixtures (`src/test`, `src/testFixtures`) | 17 | 1681 | 52 % |
| Production code (`src/main`) | 17 | 305 | 9 % |
| Dockerfile and backlog | 2 | 58 | 2 % |

The operator has to find 305 lines across 17 files inside 3256. On this lot they did not, and the
project paused while that reading was owed. That is the failure this ADR addresses; every number
below is downstream of it.

**The lot is not an outlier.** Across the 41 lots the handoffs delimit, the median is 1401
insertions and 17 lots exceed 2000. The largest is the user data import at 14392 insertions over
151 commits and 10 blocks.

**The blocks were already the right size.** The same lot's six blocks, measured over their commit
ranges:

| Block | Diff | of which production |
|---|---|---|
| 1. Shared vocabulary and test instrument | +247 -29 | 49 |
| 2. Bounded selections and the filesystem precondition | +247 -5 | 75 |
| 3. The build completes or fails, and nothing else | +355 -149 | 44 |
| 4. The sweep becomes three passes | +635 -97 | 130 |
| 5. What reads the sweep | +204 -23 | 25 |
| 6. End to end | +546 -86 | 15 |

Median 300 lines, maximum 635, and no block above 130 lines of production code. Nothing had to be
cut differently: the six had to stop converging into one pull request. The import lot shows the cut
is not reliable on its own, at 10 blocks for 14392 insertions, so a size budget is added to the
rule rather than left to the plan's judgement.

**The plan is long because it was written for subagents.** The export lot's plan is 465 lines for
14 tasks, each carrying files, a prose statement and acceptance criteria, because the implementer
was a subagent that had not read the specification. Task 3 spends eleven lines restating a
signature, its call sites and the reason for the choice, all of which the specification already
holds. The two largest plans in the repository are 2371 and 2278 lines.

**The backlog grows because the scope rule fills it.** The lot closed three P2 items and filed
seven. Classified by the size of the fix each needs:

| Item | Fix |
|---|---|
| A wrong pass named in `ReapAbandonedUserDataImports`'s KDoc, and `ImportLifecycle.start()` calling its sweep outside `safe` | A documentation line, and a call to move |
| `ReapExpiredUserDataExports` named for one pass out of three, and `ExportArchiveKey.DIRECTORY`'s two rivals | A rename and a constant to unify |
| The reclaim pass has no order, and a permanently refused delete blocks its head | A real defect, contained |
| The export test fixtures close only one direction | A test base to split |
| A task handler is never told it lost its lease | Real work: two handlers break at compile time |
| `claimNext` kills a task whose handler may still run | A design decision, needs its own specification |
| `TaskQueueBootIntegrationTest` counts every row in `tasks` | Refused on a principle, not on scope: the mechanism was never reproduced |

Two of the seven were filed only because `agents/workflow.md` forbade fixing what was not asked,
and the backlog entry describing each is longer than its fix. Two more were within the lot's reach.
The first of them says so in its own text: "the export half was corrected in its own lot, this one
was left alone on purpose."

**And the entries themselves grew.** The P2 band held 3 items over 7 lines on 2026-07-20, and holds
15 items over 116 lines today. Per item: 2 lines then, 8 now, 13 at the peak on 2026-08-14. The
reasoning each entry carries is already written in the handoff of the lot that filed it, so the
backlog stores it a second time and is unreadable at the length that costs.

**What the reviews produced is the one thing that did not need changing.** The lot ran sixteen
review passes: six spec angles, three plan angles, six block reviews, one holistic. The handoff
records what the six block reviews found: "None of the block reviews found a functional defect in
delivered production code: they found tests that could not fail, and documents that had stopped
describing the code." That is worth keeping and it is a fraction of the mandate they were given.

## Decision

1. **A block is the smallest change that can be merged to `main` on its own.** Three conditions,
   each checkable:
   - **Green alone**: `./gradlew gate` passes at the block's tip. A block therefore never ends
     between a red test commit and the implementation that answers it.
   - **Coherent alone**: nothing the block adds is unreachable. Every new port method has a caller,
     every configuration key is read, every new state is produced somewhere. Where the real
     consumer of a surface arrives in a later block, the pull request names it and the
     specification says so.
   - **Readable alone**: the diff excluding dated documents stays under 600 lines, of which under
     200 are production code. Past that the block splits, or the specification states in one line
     why it cannot.

   The existing rule, that a block ends where a later task first depends on an earlier task's
   result, is kept: it says where the cut is forced. These three say where it happens anyway.

2. **One block, one pull request, in series.** Each pull request branches from `main` and is merged
   before the next block starts. No stacking, no cascading rebase, and `main` is always the base.
   A lot spans as many pull requests as it has blocks, and the operator reads each one at about 300
   lines.

3. **The specification and the plan are one document**, `docs/specs/<ISO date>-<slug>.md`. The plan
   is a section of it and it is a table: one row per block, naming what the block delivers, the
   files it touches, and the specification's acceptance criteria it satisfies. Acceptance criteria
   live in the specification and are not restated per task.

4. **Act is inline.** The implementation of a block is written by the main loop, not dispatched to a
   subagent. Decision 5 requires it: a subagent cannot interrupt, its final message being its return
   value, so an implementer that must ask at discovery has to be the agent talking to the operator.
   A block that exceeds the budget in decision 1 anyway may still be dispatched.

5. **An adjacent defect has three tiers, and the middle one asks at discovery.**
   - Trivial, obviously correct and contained to one site: fixed, and named in the final message.
     This is the existing boy-scout rule, unchanged.
   - Anything larger the agent judges it could fix inside the lot: it stops and asks the operator
     then and there, stating the defect, the size of the fix and the block it would join. Not at
     the next boundary: the operator prefers being interrupted while the context is live over
     rebuilding it afterwards.
   - Refused by the operator, or genuinely another lot's: the backlog.

6. **A lot closes the backlog items adjacent to its subject, and the specification names them.**
   Binding, not advisory. Where an adjacent item is left open, the specification states which and
   why, and that reason is the operator's to accept. A lot with no adjacent item states that.

7. **A backlog item holds in two lines** plus a pointer to the handoff section carrying its
   reasoning. There is no cap on how many items the backlog holds: a cap would discard findings to
   satisfy a number, and the growth this addresses is in the entries, not in their count.

8. **The review passes become three, one, and one.**
   - **On the specification, before the operator reads it**: three angles. `evidence` and
     `falsifiability` always, because a false premise and an unfailable criterion are the two
     defects that are cheapest to fix here and most expensive anywhere else. The third is chosen by
     subject from `precedent`, `security`, `operations` and `testability`, and the specification
     names which and why.
   - **On each block's pull request, before the operator reads it**: one block review, lightened to
     what block review was measured to find. It reads the pull request's diff and the
     specification, and nothing else.
   - **On the last block's pull request, before it merges**: the holistic review, over
     `git diff <lot base>..<that pull request's head>`, so it sees the whole lot and still gates a
     merge. It runs when a lot has more than one block; a one-block lot is read whole by its block
     review already.

   `agents/reviews/plan.md` is deleted. The block cut it would have judged is enforced where it is
   observable, at each block, by the gate and by the budget in decision 1.

## Consequences

- **The operator is the pacer.** In series, block N+1 does not start until pull request N is merged.
  Work stops while a review is owed, which is the point: an unread pull request now blocks one block
  instead of accumulating into an unreadable lot. The cost of a slow review is visible immediately
  rather than at the end.
- **Continuous integration cost multiplies by the number of blocks.** A pull request run takes about
  12 minutes, image build included. Six blocks is 72 minutes of machine time instead of 12. This is
  accepted: it is not the operator's attention, and the last lot established that the image build is
  the one check no local command reproduces.
- **Inline Act puts implementation in the main loop's context.** The isolation that subagent dispatch
  bought is now bought by the session boundary instead, which is stronger: a session ends at a merge
  and the next block starts from nothing but `main` and the specification. What is lost is the
  parallel dispatch of independent tasks inside one block, which the measurements in ADR 0014 showed
  never happened anyway (parallelism factor 1.0 on every session).
- **The block review keeps only what it was measured to find.** Branch coverage is dropped from its
  mandate, the gate enforcing it at 100 %; so is "green after refactor", which the pull request's own
  continuous integration establishes. The frozen-commit-range machinery goes with series: nothing is
  in flight while a block is read. What remains is test discrimination, concrete values, error and
  boundary paths, fidelity to the specification, coupling to internals, red before green, scope,
  minimality, constraint escapes and hygiene.
- **A CRITICAL finding now blocks one pull request and nothing else.** The category ADR 0014 created,
  a finding whose fix touches work built after the block it concerns, cannot arise: nothing is built
  on an unmerged block. Wrap's count of those findings is removed with it.
- **The plan stops being reviewed.** Three angles read it before; none does now. What replaces that
  is the block itself: a cut that was wrong shows up as a block that cannot be green alone or that
  overflows the budget, and it is split then. If a lot ships a defect that a plan angle would have
  caught, that is the case against this part and it should be recorded rather than argued.
- **Three spec angles instead of six means three subjects go unread on most lots.** ADR 0014
  anticipated this exact cut ("an angle whose criteria stop matching what lots actually get wrong is
  dead weight the next Improve phase should cut") but the choice here is not that the four are dead:
  it is that reading all six on every lot costs more than the subjects it covers on the lots where
  they do not apply. Naming the third angle and its reason in the specification is what keeps the
  choice visible and reversible.
- **Decision 6 can conflict with decision 1.** Adopting an adjacent backlog item enlarges a block,
  and a block has a budget. The resolution is that the adopted item is its own block when it does
  not fit, not that it is dropped.
- **The measurements in this ADR are reproducible from the repository**, which the ones in ADR 0014
  were not. Every table above comes from `git diff --numstat` over a named range or from
  `git show <commit>:docs/backlog.md`. The backlog item asking for the ADR 0014 re-measurement is
  closed by this document.
