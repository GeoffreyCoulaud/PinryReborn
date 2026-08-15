# Handoff: fencing the user data export row

Date: 2026-08-15
Branch: `fix/export-row-fencing`
Specification: `docs/specs/2026-08-15-export-row-fencing.md`
ADR: `docs/adr/0016-fence-by-compare-and-set.md`

## Current state

17 commits, 16 files, about 1 290 insertions. `./gradlew gate` green, and green once with
`--rerun-tasks` on all 168 tasks. Not integrated: the pull request awaits the human review.

Five unfenced writes on the export row are closed. The import half was fenced a day earlier; the two
halves of portability now behave the same way under a race.

## What was built, and what decided it

**A row two actors can write is fenced by compare-and-set, not by a version column.** ADR 0016
records the reversal, since `docs/backlog.md` prescribed optimistic locking. The argument that
survives scrutiny is the datasource's single connection: a read and a write inside one transaction
already serialise, so a version column adds a second mechanism over a guarantee the transaction gives.

**One fence per intent, not one per state.** Two writes on one entity that differ only in which arm
they release are one fenced write with a phase-agnostic predicate, and the release arm is taken from
the state the write replaced. The draft's two narrow predicates would have let a `DELETE` answer
`204` on an export that stayed downloadable.

**The state moves before the bytes**, so a failure between them leaves a row promising less than it
holds. The repair that makes this safe is the refused fence releasing bytes a gone row still names.

## Pitfalls, in the order they will bite again

- **A narrow predicate per state splits one intent across mutually exclusive fences**, and the
  transition between them falls in the gap. Three review angles found the same regression
  independently. Fence on the phase, branch on what the write replaced.
- **A guard predicate is not the whole fence.** A missing row must refuse (`merge` is an upsert, so a
  predicate tested against a stale copy writes a deleted row back into existence), and the update
  must apply to the row just read, not to the copy the caller held.
- **Reversing "bytes then row" removes a retry path unless something replaces it.** With the row gone
  first, a replayed request meets the fence, is refused, and answers success without touching the
  bytes. The refusal arm has to release what a gone row still names.
- **`PassthroughTransactionRunner.inside` does not pin a fence.** It says a read happened inside some
  transaction. Splitting `saveFenced` into two successive transactions passes every test that uses
  only `inside`. `current`, recorded per call at the read and at the write and compared, is what
  pins ADR 0016 decision 1. Measured: under that split, only the two cases that record `current` fail
  and 62 others pass.
- **`returnsMany` answers by call ordinal, not by state.** A racing answer built on it cannot tell a
  fenced implementation from one that re-reads twice and only then opens a transaction. Key the
  racing stub on the transaction state.
- **A helper cannot ship in a task of its own.** `saveFencedOver` with no caller leaves its branches
  uncovered and fails the package's bound, so it arrived in the commit that gave it one. Worth
  knowing for any plan that puts "write the helpers" first.
- **The coverage bound holds far less of this than it looks.** Five refusal paths, but callers that
  discard the result create no branch. What the bound actually forced is listed in spec section 9;
  the rest is held by the document.
- **`TaskModel`'s `@Version` is not decorative**, contrary to what this lot's draft asserted from
  three true observations. Ebean arms the lock on the two paths that save a bean loaded by query, and
  the queue maintains the column across seven bulk updates. It is a live back-stop with no domain
  surface.

## What is not validated

- **The INFO line each refusal writes is asserted nowhere.** `api-usecases` binds `slf4j-nop` at test
  runtime by an explicit decision, so no test captures output. Criteria A1 and A9 name that line.
- **Nothing here proves the fence excludes a concurrent writer.** `PassthroughTransactionRunner` opens
  no transaction; it counts. Real exclusion comes from the single SQLite connection and is reachable
  only from `api-persistence-sqlite`. The only real-concurrency instrument in the repository is
  `SerialisingTransactionRunner`, which this lot does not use.
- **The `exports` package has no static guard on the shape it now holds.**
  `ImportStateMergedOutsideTransaction`'s filter is one package, so a new unfenced export writer
  merges green until the filter widens.
- **Three export test files depend on `internal PassthroughTransactionRunner` in the imports test
  package**, the only route available (`api-utilities` testFixtures has no `api-domain` dependency).
  Moving the import fixtures breaks them.
- **CI has not run.**

## Where each review finding went

Seven review passes: six spec angles on the draft, one holistic on the branch. 4 CRITICAL, 15 MAJOR,
14 MINOR from the angles; 3 MAJOR and 7 MINOR from the holistic.

**Fixed inside the lot**: every CRITICAL and every MAJOR. The four that mattered: the deleter's
regression; the missing-row rule that alone closes the account-erasure harm; the lost retry path the
ordering inversion created; and the absence of any test pinning read and write to one transaction.

**Backlog items**, three new: two attempts of one build overlapping (with `TaskContext.renewLease`'s
`() -> Unit` typing, which enables it); an export stuck `PENDING` for good, the twin of the import's
`failInterruptedRuns`; and superseding an export stranding its archive, whose comment promises a
reclamation that cannot happen. The P2 fencing item is rewritten to what ADR 0016 decides.

**Accepted limits**, in spec section 8: the unasserted INFO line, the shape-not-isolation tests, the
unguarded package, and the narrowed byte-stranding.

**Refused, with the reason recorded**: narrowing the fence predicate to preserve the no-op on a
`DELETE` of a `FAILED` export. A predicate enumerating the live states is one a future state falls out
of silently, and an export nobody can clear from their history was the more surprising behaviour. The
change is recorded in section 7 rather than hidden.

## What the regime cost and found

The six spec angles falsified eleven points of a document written from a verified survey, three of
them design rather than prose. The implementer falsified five more that only building could show. The
holistic review then found a class neither could reach: **documents asserting work the lot had not
done.** The spec and the ADR both said three defects were "filed" and none was. A block reader cannot
see that, because it reads the code, and the code was right.

Two defects adjacent to the lot were named and left: the requester's stranding, and a stale detekt
baseline entry, which was cleaned since this lot caused it.

## Suggested next step

The detekt rule's reach, which spec section 8 defers to its own pull request: widening the filter to
the exports is one line, and the rename its KDoc anticipates touches eight files. Until it lands,
nothing structural stops the next unfenced export writer.
