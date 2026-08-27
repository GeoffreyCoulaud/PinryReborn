# 0017. The promote runs inside the transaction that publishes

Status: Proposed
Date: 2026-08-27
Specification: `docs/specs/2026-08-27-export-build-completion.md`

Amends:
- `docs/adr/0016-fence-by-compare-and-set.md`, last paragraph of Consequences: "That needs a claim
  token, as the import runner has. The specification names it, and it is filed." Decision 4 below
  reverses it. A delivered ADR is never rewritten, so 0016 keeps its text and this record carries the
  correction.
- `docs/specs/2026-08-15-export-row-fencing.md` section 8, which prescribes "a claim token on the
  export row, a column and a migration".
- `docs/backlog.md`, the P2 item on overlapping build attempts, whose stated remedy is that same
  token, and the P2 item on the stranded superseded archive, whose open question ("should the sweep
  key on no row *names* this key rather than no row has this id") is settled in the negative by the
  specification's section 8.

Related:
- `docs/adr/0009-unique-index-named-outcomes.md`, which measured and corrected the exact claim
  decision 1 rests on: what serialises a read-write pair is the transaction holding the single
  connection across both statements, not the single connection alone.
- `docs/adr/0012-one-datasource-declaration-and-one-transaction-seam.md`, for the one transaction
  seam. It is **not** the source for the connection count; see decision 1.
- `docs/adr/0003-periodic-gc-and-best-effort-cleanup.md` decision 1, for best-effort side-effect
  cleanup. Its text names `ExportArchiveStore.delete` as never propagating, which today's code
  contradicts: the swallowing lives in the `deleteQuietly` extension, and `UserDataExportDeleter`
  propagates on purpose. Cited with that correction.

## Context

An export archive is staged into a temporary file, promoted onto a key derived from the export id,
and the row is then published `READY` inside a transaction that re-reads it and refuses if it is no
longer `PENDING`.

Two attempts of one build can run at once: the lease is one minute, the tail of a build renews
nothing, and the reaper returns the task to `PENDING` for a second worker. Both attempts read a
legitimate `PENDING` row. **No predicate on state can tell them apart**, which is what
`docs/specs/2026-08-15-export-row-fencing.md` section 8 recorded when it left this open. Both derive
the same key, both promote onto it, and the loser's compensating delete removes the bytes the winner
published.

Three candidate remedies were weighed.

**A claim token on the row**, mirroring `UserDataImportRunner.runToken`, is what the backlog and the
fencing spec prescribed. The mirror is false. The import writes into the account's data continuously
and carries counters an evicted attempt must not advance, so its polarity is "the last to claim wins"
and it needs an identity on the row. The export produces an immutable artefact per attempt and wants
the opposite polarity. More decisively, a token does not close the defect: it changes which attempt is
refused, and the refused one has already promoted onto the shared key and still deletes it. The remedy
has to stop the loser from promoting, not tell it sooner that it lost.

**A key per attempt** works and is cheaper to reason about. It also removes the property that the key
is knowable without reading the row, being a function of the export id and the store's archive
format, and the orphan sweep depends on that property structurally:
`ReapOrphanedStorage.parseId` accepts only `<prefix><uuid>.<ext>` and, by its own written contract,
skips and never deletes a key it cannot parse; `forEachStorageKeyOnDisk` does not descend into
subdirectories. A per-attempt key is therefore invisible to the sweep, and the loser's archive
survives forever. Restoring the sweep means asking "does any row name this key", which `storage_key`
carries no index for, and which would still miss terminal rows that name their key.

*(An earlier draft of this record also argued that a per-attempt key would break account erasure,
because `AccountDeletionCleaner` derives the key after deleting the rows and the row "may never have
recorded the key". That argument does not hold: the builder stamps the key before staging and before
promoting, and the cleaner collects the ids while the rows still exist, so it could collect the keys
there too. The refusal stands on the orphan sweep alone.)*

**Fencing ahead of the promote, leaving the promote outside any transaction**, is the third candidate,
and it is not hypothetical: `UserDataImportArchiveCompleter` does exactly this on the mirror half,
with its reason at the site, and compensates on the refused path with a best-effort delete. It is the
arrangement this record departs from, so it deserves its sentence: a fence *before* the promote
refuses neither attempt here, because both attempts legitimately read `PENDING`. It closes the
import's race, where the fence discriminates, and cannot close the export's, where it does not.

## Decision

**1. The promote runs inside the transaction that publishes the row.** The order is: re-read, test the
predicate, promote, write `READY`. A losing attempt learns it has lost before it has touched the
canonical key, so it promotes nothing and deletes nothing; it discards its own staged file through a
handle that cannot name another attempt's bytes.

The premise is that `minConnections` and `maxConnections` are pinned to 1
(`api-application/src/main/resources/application.properties:15,16`, pinned by
`ProductionDatasourceDeclarationTest`) and that a transaction is what serialises a read-write pair
(`agents/engineering.md:202-208`, `docs/adr/0009`). **The guarantee is per JVM**: it does not survive a
second process on the same database file, nor raising `maxConnections`. Recorded here because the
decision is only as strong as that setting, and because `docs/adr/0016:44-45` attributes the same
premise to `docs/adr/0012`, which does not contain it.

No column, no migration.

**2. A non-database effect may sit inside a transaction only when four conditions hold together.**
This is the general rule decision 1 is an instance of, and it is narrow on purpose.

1. **It is irreversible**, so no compensating action can stand in for it.
2. **No predicate can replace it**, which for this defect means: the discriminating read and the
   effect cannot be separated, because every attempt reads the same legitimate state.
3. **Its residue is bounded and reclaimed in the same lot**, and the reclaiming sweep's selection
   predicate must still be able to *name* the residue. This third clause is not decoration: the defect
   in the specification's section 2.4 satisfies the first two conditions and a naive reading of the
   third, and is precisely the bug this lot fixes. What failed there is that the row stopped naming
   the key, and the sweep selected on "no row has this id", so nothing could find the bytes.
4. **Its cost is bounded and independent of the size of the data.** A `rename(2)` within one
   filesystem is; the `Files.move` fallback in `DataDirPaths.atomicMove` is not, and would copy a
   multi-gigabyte archive while holding the process's only connection, whose pool waits one second by
   default. `docs/specs/2026-07-08-task-queue.md` already lists holding a write transaction across a
   long operation in its anti-pattern table. The specification therefore makes single-filesystem
   staging a startup precondition rather than an assumption.

An effect that fails any of the four is not admitted.

**3. Clearing a residue flag on an already-terminal row deletes the bytes first, which is the reverse
of `docs/adr/0016` decision 4, and is not a contradiction.** Decision 4 reads: "A destructive release
is driven by the state the fence wrote, so a failure between the two leaves a row that promises less
than it holds rather than more." It governs the case where the row's state is what a client reads as
a promise. Clearing `storage_key` on a row that is already terminal promises nothing to anyone: that
column is **the sweep's only index into the residue**. Writing it first and failing the delete hides
the bytes from the one pass that could find them. The import half already reads it this way and says
so at the site (`ReapAbandonedUserDataImports.kt:77-78`).

The boundary runs on "is the column the sweep's index", not on "is this a transition".
`UserDataExportDeleter.releaseStranded` stays on the other side of it: it deletes bytes and never
touches the column, so it hides nothing. Its KDoc states "a gone state is terminal and never clears
its key" as a global invariant, which the new sweep makes false; the specification narrows that
sentence to the path it describes.

**4. The claim token is refused**, and with it the column and the migration the backlog and the
fencing spec attached to this defect.

## Consequences

- The lot that closes three backlog items adds no migration. That is the direct payoff of decisions 1
  and 4.
- `docs/adr/0016` decision 1 now has a second form: the fenced write may carry an irreversible effect,
  under decision 2's four conditions. A reader of 0016 alone would not find it.
- Decision 2 binds future work: anyone putting an effect inside a transaction owes a bounded residue,
  the sweep that reclaims it, a selection predicate that can still name it, and a cost independent of
  the payload. Nothing in the gate enforces this. The passthrough transaction runner used in
  `api-usecases` tests never rolls back, so no unit test can even construct the residue; what holds
  decision 2 is review, and the specification says so rather than implying a guard exists.
- The staging directory and the archive directory must share a filesystem, checked at startup. A
  deployment that splits them needs a different design for the promote.
- A promoted archive whose transaction rolled back is reclaimed after the row becomes terminal and the
  next sweep runs: `exports.purge_interval` plus whatever remains of the task's retry budget, not one
  interval as an earlier draft said. Bounded, invisible to any HTTP caller, and disk only.
- The key stays knowable without reading the row. That property is load-bearing in the builder, the
  account cleaner and the orphan sweep, and until this lot it lived as a duplicated string literal in
  two modules. The specification gives it one home, `ExportArchiveKey`, mirroring the import's.
- The transaction now holds a filesystem rename on the single write connection. The staging, which is
  the expensive part, stays outside, and condition 4 is what keeps that sentence true.
