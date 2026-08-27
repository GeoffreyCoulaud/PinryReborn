# Handoff: export build completion

Date: 2026-08-27
Branch: `fix/export-build-completion`
Specification: `docs/specs/2026-08-27-export-build-completion.md`
Decision: `docs/adr/0017-promote-inside-the-publishing-transaction.md`
Plan: `docs/plans/2026-08-27-export-build-completion.md`
Tier: Plan. Fourteen tasks, six blocks, six block reviews plus a holistic one.

## Current state

Fifty-six commits. `./gradlew clean gate` green, 180 tasks executed.
`git diff main...HEAD -- '*dbmigration*'` is empty.

Three P2 backlog items are closed and removed from `docs/backlog.md`: the overlapping build attempts,
the stranded superseded archive, and the export that stays `PENDING` for good. Seven items are filed
in their place, all named below.

## What was built

An export build now ends in one of two ways and nothing else: the row reads `READY` and names bytes
that exist, or the row reads a terminal state and its bytes are reclaimed.

- **The promote runs inside the transaction that publishes.** Read, test `PENDING`, promote, write.
  A losing attempt learns it lost before touching the canonical key, so it promotes nothing and
  discards its own staged file. This is ADR 0017 decision 1, and it is why no migration was needed.
- **The failure net covers the completion**, all three clauses of the original spec's step 8: discard
  on every attempt, mark `FAILED` on the last, rethrow. The discard is best-effort, because a
  propagating one skipped the marking and left the row `PENDING` for good.
- **The sweep runs three passes**: fail interrupted builds past a grace, expire `READY` rows past
  retention writing state only, reclaim the bytes of terminal rows. One rule holds them together: a
  transition writes the state, reclaiming bytes belongs to pass 3 for every terminal state.
- **A superseded export keeps naming its bytes**, so a failed best-effort delete is reclaimable.
- **The application refuses to start** when staging and archives are on different filesystems, which
  is what keeps the promote a rename rather than a copy holding the only write connection.

## Pitfalls, in the order they cost time

1. **A test that cannot fail is not a test, and this lot proved it five times.** Every instance was
   found by mutation, none by reading. The one that matters most: the test pinning the central
   correction passed against an implementation that promoted before testing the predicate, because
   the rival was installed inside the loser's own fenced read, where two serialised transactions
   cannot in fact interleave. **Mutate before believing a test**, and paste the mutation's output in
   the commit. Several commits here do.
2. **A justification can rot during the lot.** Criterion 7 was dropped because the repository had no
   fault-injection facility. It gained one, in the very class that needed it, forty commits later. The
   holistic review caught it; nothing else would have. When a plan says "impossible because X", X is
   worth re-checking before wrap.
3. **Corrections land in comments and not in documents.** Read literally, the specification was
   prescribing two of the bugs the lot had fixed, and one correction of a false sentence introduced
   another. Carry every Act finding back into the spec and the plan, in the `(Corrected: ...)` form,
   or the next reader re-derives the mistake.
4. **The grace is anchored on the staged-file age, not on the lease.** An attempt lasts as long as its
   staging progresses, since `renewLease` fires per page and per image, so `lease_duration x
   max_attempts` bounds nothing. A grace shorter than the longest plausible staging condemns live
   builders, each of which then discards a complete archive.
5. **`CommentCarriesDocumentation` caps a comment at four lines, delimiters included**, so two lines
   of prose. It bit three times. The argument goes in the document that owns it.
6. **The reclaim pass must delete both keys when they differ.** Deleting only the derived one succeeds
   vacuously over a divergent column, and the bytes become unreachable to every sweep.
7. **Adding a class to the default test profile changes what else runs beside it.** One integration
   suite counts every row in `tasks` and expects one.
8. **A green gate is not a green CI, and this lot proved that too.** `AGENTS.md` says CI enumerates
   the gate's parts and also builds the container image, which no local command covers. The startup
   check refused every boot: `/var/lib` is root-owned, uid 1001 cannot create under it, and the
   Dockerfile prepared the import directory only. Its comment even said why, and this lot made that
   comment false by giving the export half the same startup observer. **A startup check that creates
   a directory is a change to the image, not only to the code.** The fix carries the twin of
   `ImportDataDirectoryImageTest`, which reads the Dockerfile from inside the gate, so the next
   occurrence is local rather than reachable only by building the image.

## What is not validated

- **The single-connection serialisation is not asserted by this lot.** `PassthroughTransactionRunner`
  opens no transaction, and real exclusion is reachable only from `api-persistence-sqlite`. The
  configuration and its own test hold it.
- **One integration failure was seen once, never captured, and never reproduced** in nineteen
  subsequent runs. Its most likely cause was found by review and fixed: two new cases returned while a
  real build was still in flight, on a pool with one connection whose default wait is one second. If
  it recurs, **read the exception type first**: a pool wait timeout confirms that diagnosis, an
  assertion failure on `"READY"` points at the ten-second poll bound instead.
- **A `DELETE` on a `FAILED` row releases nothing**, and such a row can hold bytes when a promote
  landed and the row write did not. Bounded by pass 3 and invisible to the client; the reason is
  written at the site rather than fixed unreviewed at the end of the lot.
- **The two new configuration keys are in no table.** `exports.interrupted_grace` (`PT6H`) and
  `exports.sweep_batch_size` (`500`) are described in the spec's prose only.

## What the process cost, and one thing to change

Six block reviews and one holistic review produced findings on every block. Two were CRITICAL against
specification drafts, one MAJOR against the plan, and the holistic review blocked integration on six
points. **None of the block reviews found a functional defect in delivered production code**: they
found tests that could not fail, and documents that had stopped describing the code. That is where
the value was.

One thing to change in the next plan: **task 14 collected the end-to-end tests into a final task**,
which `agents/workflow.md` names as the degraded shape. Those tests arrived green against already
delivered behaviour, leaving mutation-after-the-fact as the only evidence they hold. A mutation proves
sensitivity to that mutation, not to the class of wrong implementations the criterion excludes. Each
task should own the end-to-end case that pins its own behaviour.

## Next step

Integration through a pull request, rebase only. After that, the natural successors are the two items
this lot filed against the task queue: the heartbeat's return value, whose real reach is both handlers
and the export builder's failure net, and `claimNext` killing a task whose handler still runs, which
is the upstream cause the `PT6H` grace works around.
