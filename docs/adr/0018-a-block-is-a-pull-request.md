# 0018. A block is a pull request, and the blocks of a lot ship in series

Status: Accepted; decision 8 is amended by `docs/adr/0019-review-before-the-pull-request.md` on two
counts: the placement of the two reviews, which move off the pull request and onto the branch, and
two of its cuts to the block mandate, branch coverage and commit-range pinning, which are restored.
Decisions 1 to 7 stand as written.
Date: 2026-09-04
Amends: `docs/adr/0014-review-budget-upstream.md`, whose block review this keeps, whose plan pass
this folds into the spec pass, and whose spec pass this cuts from six angles to three.
Related: `docs/adr/0010-review-finding-dispositions.md` (the four exits a finding takes; decision 5
below changes which exit is the default, not the set), `docs/adr/0001-adopt-agents-baseline.md`.

## Context

**The pull request is not read, because the code in it is 9 % of it.** The export build completion
lot, over `4d9b4852..efa43340`:

| Nature | Files | Added | Share |
|---|---|---|---|
| Dated documents (spec, plan, ADR, handoff) | 4 | 1212 | 37 % |
| Tests and fixtures (`src/test`, `src/testFixtures`) | 17 | 1681 | 52 % |
| Production code (`src/main`) | 17 | 305 | 9 % |
| Dockerfile and backlog | 2 | 58 | 2 % |

The operator has to find 305 lines across 17 files inside 3256. They report not having done so, and
the project pausing while that reading was owed. That report is the reason this ADR exists; the
table is the only part of it the repository can settle.

**The lot is not an outlier.** Taking each file added under `docs/handoffs/` as the end of a lot,
there are 36, their median is 1926 insertions, 18 exceed 2000, and this lot at 3256 ranks 25th. The
largest is the user data import at 14392 insertions over 151 commits and 10 blocks.

**The blocks were already close to the right size.** The lot's six blocks, over the commit ranges
that carry their tasks as the plan groups them:

| Block | Range | Added | Excluding dated documents | of which production |
|---|---|---|---|---|
| 1. Shared vocabulary and test instrument (tasks 1 to 6) | `c1387154..44ba5fa4` | 244 | 244 | 49 |
| 2. Bounded selections, filesystem precondition (7, 8) | `44ba5fa4..c7117e81` | 238 | 238 | 75 |
| 3. The build completes or fails, nothing else (9, 10) | `09c66afa..d59cee86` | 296 | 296 | 29 |
| 4. The sweep becomes three passes (11, 12) | `27662dc6..85fb6529` | 581 | 581 | 118 |
| 5. What reads the sweep (13) | `4f13bc90..98dd9e4d` | 39 | 39 | 10 |
| 6. End to end (14) | `98dd9e4d..3397d1f6` | 711 | 559 | 30 |

Median 270 lines excluding dated documents, maximum 581, and no block above 118 lines of production
code: every one sits inside the budget decision 1 sets. **The ranges are a reconstruction and the
lot recorded no boundaries**, which is itself an argument for decision 2: a block whose extent is a
pull request has its boundary in the history instead. Three commits closing block review findings
(`09c66afa`, `27662dc6`, `4f13bc90`) sit between the ranges above, belonging to the block they
correct rather than to the one that follows, and `3397d1f6..efa43340` (the container image fix) falls
inside the lot and inside no block.

So the six blocks did not need different subjects. They needed to stop converging into one pull
request. The import lot shows the cut is not reliable on its own, at 10 blocks for 14392 insertions,
so a size budget is added to the rule rather than left to the plan's judgement.

**The plan is long because it was written for subagents.** The export lot's plan is 465 lines for 14
tasks, each carrying a file list, a prose statement and its own acceptance criteria, because the
implementer was a subagent that had not read the specification and needed a self-contained brief.
The two largest plans in the repository are 2371 and 2278 lines. A plan that size also drifts from
the spec it derives from: this one's task 3 prescribes `forExport(exportId, fileExtension)`, two
parameters, where the spec still writes `forExport(id)` at section 4.5, and the code shipped the
plan's version. The plan was correcting the spec rather than restating it, and the correction landed
in the document nobody reads afterwards.

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

Two of the seven are documentation lines and renames, and the backlog entry describing each is
longer than its fix. Two more were within the lot's reach. One of those records that it was "left
alone on purpose" without saying why.

**And the entries themselves grew.** Counting content lines at both ends: the P2 band held 3 items
over 6 lines on 2026-07-20 and 15 items over 114 lines on 2026-08-27. Per item, 2.0 then and 7.6
now. The reasoning each entry carries is already written in the handoff of the lot that filed it, so
the backlog stores it a second time, at the length that stops it being reread.

**What the reviews produce is the one thing that did not need cutting.** The lot ran six block
reviews and one holistic review, which its handoff records, plus three plan angles, which its plan
records; that six spec angles also ran is stated nowhere in the repository. That handoff says "None
of the block reviews found a functional defect in delivered production code: they found tests that
could not fail, and documents that had stopped describing the code." **That sentence is false**, and
the lot's own commits are the counter-example:

- `4f13bc90`, closing block 3's review, changes `UserDataExportBuilder.kt` and `StorageCleanup.kt`.
  Its message: the failure net's `discard` propagated, so "a temp file that will not unlink therefore
  skipped the marking, masked the original failure, and left the row `PENDING` for good on the last
  attempt". That is the defect the whole lot exists to fix, reintroduced in delivered code and caught
  by a block review.
- `27662dc6`, closing block 2's review, changes `ExportDataDirectoryCheck.kt`, `ExportArchiveKey.kt`
  and a port interface: a half-created data directory surfaced a bare `IOException` at boot instead
  of naming `exports.data_dir`, and a port KDoc promised an "exactly once" reclaim that contradicts
  ADR 0017 decision 3.

The plan pass has the same shape. Its angles found that the interrupted-build grace, `PT15M`, was
justified against `lease_duration x max_attempts`, "a product that bounds nothing", and that on a
large account "pass 1 would have condemned live builders and destroyed valid archives". The grace
shipped at `PT6H`.

Both passes therefore earn their dispatch on this lot. Decision 8 is built on that, not against it.

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
   live in the specification and are not restated per task. A correction the block table discovers
   is made in the specification, where the next reader will look.

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

8. **The review passes become three, one, and one.** Both surviving passes were shown above to
   catch functional defects, so what changes is where they run and how many angles they cost, never
   how hard they look.
   - **On the specification, before the operator reads it**: three angles rather than six.
     `evidence` and `falsifiability` always, because a false premise and an unfailable criterion are
     the two defects cheapest to fix here and most expensive anywhere else. The third is chosen by
     subject from `precedent`, `security`, `operations` and `testability`, and the specification
     names which and why.
   - **On each block's pull request, before the operator reads it**: one block review. Its mandate
     loses branch coverage (the gate enforces it per package), "green after refactor" (the same gate
     run and the pull request's own continuous integration establish it) and the frozen-commit-range
     machinery (in series nothing is in flight while a block is read). Every criterion that finds
     defects is kept, the two counter-examples above being what that means concretely.
   - **On the last block's pull request, before it merges**: the holistic review, over
     `git diff <lot base>..<that pull request's head>`, so it sees the whole lot and still gates a
     merge. **It runs on every lot in tier Spec, whatever the block count.** Only tier Direct skips
     it, and only because that tier's own trigger excludes what the mandate is about: no design
     decision, no dependency, no public surface. The block mandate is narrower by construction and
     disclaims reading across blocks, so it does not stand in for the holistic one: a one-block lot
     that escalated to Spec gets both, over the same diff, by two mandates that do not overlap.

   `agents/reviews/plan.md` is deleted, and the pass it served is folded rather than dropped. Its
   findings on this lot were about values and reasoning that decision 3 moves into the specification
   (a grace justified against a product that bounds nothing is exactly `evidence`'s subject), and its
   findings about task mechanics are now about the block table, which the three conditions of
   decision 1 make checkable at the block rather than by prediction.

## Consequences

- **The operator is the pacer.** In series, block N+1 does not start until pull request N is merged.
  Work stops while a review is owed, which is the point: an unread pull request now blocks one block
  instead of accumulating into an unreadable lot. The cost of a slow review is visible immediately
  rather than at the end.
- **Continuous integration cost multiplies by the number of blocks.** Over the twelve most recent
  pull request runs the median is 7.2 minutes and the maximum 13.0, image build included. Six blocks
  is roughly three quarters of an hour of machine time instead of eight minutes. This is accepted: it
  is not the operator's attention, and the container image remains one of the two checks no local
  command reproduces (`AGENTS.md` names the other, the `docs/openapi.json` sync), though the last lot
  moved the Dockerfile half of that gap into the gate.
- **Inline Act puts implementation in the main loop's context.** The isolation that subagent dispatch
  bought is now bought by the session boundary instead, which is stronger: a session ends at a merge
  and the next block starts from nothing but `main` and the specification. What is lost is the
  parallel dispatch of independent tasks inside one block, which the measurements in ADR 0014 showed
  never happened anyway (parallelism factor 1.0 on every session).
- **A CRITICAL finding now blocks one pull request and nothing else.** The category ADR 0014 created,
  a finding whose fix touches work built after the block it concerns, cannot arise: nothing is built
  on an unmerged block. Wrap's count of those findings is removed with it.
- **Three spec angles instead of six means three subjects go unread on most lots.** The claim is not
  that the four rotating angles are dead weight: it is that reading all six on every lot costs more
  than the subjects they cover on the lots where they do not apply. Naming the third angle and its
  reason in the specification is what keeps that choice visible and reversible.
- **Decision 6 can conflict with decision 1.** Adopting an adjacent backlog item enlarges a block,
  and a block has a budget. The resolution is that the adopted item is its own block when it does
  not fit, not that it is dropped.
- **The measurements here are reproducible, and they are not the ones ADR 0014's re-measurement
  asked for.** Every table above comes from `git diff --numstat` over a named range or from
  `git show <commit>:docs/backlog.md`, with two qualifications: the block ranges are reconstructed
  rather than recorded, and the "Fix" column of the backlog table is judgement. But the open item
  asks for the share of spend that goes to reviews, the hours between consecutive implementers, and
  the findings per review by kind, all of which live in session transcripts and none of which this
  document measures. **That item stays open**, re-scoped by this ADR rather than closed by it, and
  the correction to the handoff sentence above is a first result against it: the question of what
  review costs and what it returns is still unanswered, and this ADR changes the regime before the
  answer exists.
- **A dated document said something false and the correction lives here.** The export completion
  handoff's claim about block reviews is left standing where it is, per the append-only regime; this
  ADR is the superseding record.
