# 0019. Review runs on the branch, and opening a pull request is handing it over

Status: Accepted
Date: 2026-09-04
Amends: `docs/adr/0018-a-block-is-a-pull-request.md`, decision 8, on where the block and holistic
reviews run. Everything else in that ADR stands, decisions 1 to 7 included.

## Context

ADR 0018 decision 8 places both reviews on a pull request: the block review "on each block's pull
request, before the operator reads it", the holistic review "on the last block's pull request,
before it merges". `agents/workflow.md` phase 4 spelled that out as opening the pull request first
so continuous integration runs while the reviewers read, and closed the paragraph with "Nothing is
offered to the user yet."

That sentence is false, and the operator demonstrated it on the lot that introduced it: pull request
#74 was merged while its block review and its holistic review were still running.

**There is no state in which a pull request is open and the user has not been offered it.** It is in
their list, it notifies them, and they can merge it. An open pull request is the offer. A review
still reading while one is open is a review whose findings can arrive after the merge, which is the
exact failure mode series was adopted to remove: ADR 0018 decision 2 exists so that nothing is built
on unreviewed work, and opening early reintroduces it one level up, at the merge instead of at the
next block.

The placement came from closing a block review finding on ADR 0018's own branch. That finding said
three statements in the mandates assumed a pull request that phase 4 had not yet created, and it
offered two repairs: open the pull request earlier, or point the mandates at the local gate. The
first was taken. It was the wrong one, and it traded a documentation inconsistency for a process
defect.

## Decision

1. **Verify runs entirely on the local branch, and no pull request exists during it.** The gate, the
   block review, the holistic review where it applies, and the closing of their findings all happen
   before anything is pushed for integration.

2. **Opening a pull request is the act of handing it over**, so it belongs to Integrate. The link
   goes to the operator together with the state of its continuous integration run.

3. **The mandates read a branch, not a pull request.** `agents/reviews/block.md` reviews a block on
   its local branch and its "green suite" carve-out points at the gate run that has already passed,
   with continuous integration named as what re-establishes it afterwards rather than as what is
   running alongside the review. `agents/reviews/holistic.md` says the last block is still on its
   branch.

## Consequences

- **Continuous integration no longer overlaps the reviews.** That was the whole benefit of the
  earlier placement and it is given up. The cost is about seven minutes of wall clock per block, on
  the median of the twelve most recent runs, and it buys back the guarantee that a merge cannot
  precede a review.
- **A review that finds nothing still delays the pull request.** The reviews are the last thing
  between a green gate and the operator's inbox, so their latency is now on the critical path of
  every block. If that becomes the friction the operator reports, the answer is fewer or faster
  reviews, not an earlier pull request.
- **Continuous integration no longer has a head start, so Integrate has to wait for it.** Under the
  old order the run finished while the reviews read. Under this one it starts when the pull request
  opens, and handing over a link with a pending run would move "the merge precedes the evidence"
  from the reviews onto continuous integration rather than removing it. That already happened:
  PR #74's `validate / gate` reported success 4 minutes 45 after the merge, `enforce_admins` being
  false. `agents/workflow.md` phase 5 therefore waits for the run to settle before the link is
  handed over.
- **The checks no local command reproduces now run after the block review, not beside it**: the
  container image build, its smoke test, and the `docs/openapi.json` sync. A failure in any of them
  produces a fix commit that no block review has read. Both mandates say so rather than implying
  continuous integration merely repeats the gate.

## Findings filed

ADR 0018's own lot ran under the defective order, and its two reviews reported after their pull
request had merged. That lot's Wrap is closed and its handoff is frozen, so this is the only place
left to record where its findings went. All of them are open work; none was refused.

- **Block review of `d644257f`** (one CRITICAL, five MAJOR, six MINOR), on the backlog compression:
  the `renewLease` item lost the two sentences saying why the work matters, which exist nowhere else,
  and points at a section arguing the fix was dropped; the first item's pointer names the wrong bullet
  of the right document and credits it with an entity list it does not carry; the item marked
  "reasoning is only here" is in fact carried by that same section; `agents/workflow.md` still states
  the two-line rule with the refuted premise; the "six of fourteen" figure is four; and six MINOR on
  pointer precision, a KDoc the rule does not admit, and a stale line count in `agents/writing.md`.
  Filed as one backlog item, to be closed by the next block.
- **Holistic review of the lot** (three CRITICAL, eight MAJOR, six MINOR). Four are their own backlog
  items because they are decisions rather than edits: decisions 4 and 5 colliding on an over-budget
  block, the specification freezing before the regime stops changing it, a holistic finding against a
  merged block having no exit, and the lost ADR-existence check. Four more are filed as small
  corrections: `AGENTS.md` on what no local command covers, the `P0` band neither file has, the
  missing ADR back-links with ADR 0014 still `Proposed`, and the branch-coverage premise. The rest are
  closed in this block: the review table timing both reviews against a pull request, the plurality
  `agents/reviews/holistic.md` assumed, the twelve-minute figure, and the silently dropped
  verification of red before green, which both mandates now require again.
