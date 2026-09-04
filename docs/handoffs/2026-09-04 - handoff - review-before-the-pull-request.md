# Handoff: review before the pull request

Date: 2026-09-04
Branch: `fix/review-before-the-pull-request`
Decision: `docs/adr/0019-review-before-the-pull-request.md`, amending
`docs/adr/0018-a-block-is-a-pull-request.md` decision 8.
Tier: Spec, one block. Its ADR is its specification, which phase 2 now provides for. One block
review, one holistic review, both on the branch.

## Current state

`./gradlew gate` green. This handoff, the ADR and the backlog items are in the block's own diff, as
phase 4 requires, so both reviews read them.

**This supersedes the placement in `2026-09-04 - handoff - blocks-as-pull-requests.md`.** That
document's "What was built" says "one block review per pull request, the holistic on the last one
before it merges". Both now run on the branch, before any pull request for that block opens. It is a
frozen dated document and is not edited; this is the correction.

## What was built

ADR 0018's phase 4 opened the pull request and then dispatched the reviews, so continuous integration
would run while they read, and closed the paragraph with "Nothing is offered to the user yet". The
operator refuted that sentence by merging PR #74 while both its reviews were still running.

- **Verify runs entirely on the branch.** Gate, block review, holistic where it applies, findings
  closed. No pull request for that block exists during any of it.
- **The pull request opens as a draft.** Waiting behind a non-draft one delays the message and not
  the merge, `enforce_admins` being false. GitHub refusing a merge on a draft is what holds it. The
  run settles, it is marked ready, the link goes over with the result named.
- **A red run or a requested change returns the block to Verify**, back to draft, and the block
  review re-runs over the new commits.
- **Two of decision 8's cuts are reversed**: branch coverage returns to the block mandate, scoped to
  what the gate does not cover, and the pinning to a named commit range returns outright.
- **A lot whose subject is this process writes its ADR and no separate spec**, the ADR then carrying
  the block table, the third angle with its reason, and the adjacency statement.

## Pitfalls, in the order they cost time

1. **Every correction in this lineage introduced a new defect, four rounds running.** Fixing "the
   review ran before the pull request existed" produced "the pull request is open while the review
   runs". Fixing that produced "the merge races continuous integration instead". Fixing the
   plurality in one mandate reintroduced it in three other places. The reviews caught each one and
   nothing else would have. **A document set this interlocked cannot be edited by reading the
   paragraph you are changing.**
2. **The prose was rewritten and the table was not, twice.** `agents/workflow.md` states the review
   regime in a tier table, a review-mandates table and the phase text. A correction to one of the
   three leaves the others standing, and the tables are what a reader consults.
3. **A premise was dropped in one mandate and left in its twin.** The branch-coverage carve-out was
   corrected in `agents/reviews/block.md` and the identical false premise sat in
   `agents/reviews/holistic.md` criterion 6 until the second holistic review.
4. **The gate does not enforce coverage everywhere.** `api-application` and the Ebean model packages
   are outside the Kover perimeter, so "the gate has already checked every branch" is false exactly
   where the composition root and the end-to-end suite live.
5. **A rule that says "point at the dated document" needs the dated document to exist.** Four backlog
   items filed by this lot argued their case in the backlog and pointed at nothing, which is the
   defect the previous lot filed about `renewLease`. They point here now.

## What is not validated

- **Still no code lot has run under this regime.** Everything so far is documents. The size budget,
  the "coherent alone" condition and the tier-2 interruption have met no implementation.
- **The draft mechanism is untested.** No pull request has yet been opened as a draft, held through
  a run and marked ready by this process.
- **The return path from a red run has never run**, and neither has the re-review it prescribes.
- **What review costs is still unmeasured**, and remains a backlog item. Three lots in a row have
  now changed the review regime without it.

## Next step

Improve, whose input is pitfall 1: the reviews are carrying this work, and they are the most
expensive place to catch a contradiction between two paragraphs of the same file. Then the four
design questions this lot filed rather than answered, and after them the first code lot.
