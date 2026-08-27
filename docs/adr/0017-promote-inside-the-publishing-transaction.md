# 0017. The promote runs inside the transaction that publishes

Status: Proposed
Date: 2026-08-27
Specification: `docs/specs/2026-08-27-export-build-completion.md`
Amends: `docs/backlog.md`, P2 item "Two attempts of one export build can overlap, and the loser
deletes the winner's archive", whose stated remedy is a claim token on the export row plus a column
and a migration. This record reverses that remedy, so the backlog is never left prescribing one the
repository has decided against.
Related: `docs/adr/0016-fence-by-compare-and-set.md`, whose decision 1 this extends to a non-database
effect and whose decision 4 this reads a boundary onto. `docs/adr/0012`, whose single connection is
the premise both rest on. `docs/adr/0003-periodic-gc-and-best-effort-cleanup.md`, whose decision 1
keeps side-effect cleanup best-effort.

## Context

An export archive is produced by staging it into a temporary file and promoting it with an atomic
rename onto a key derived from the export id. The row is then published `READY` inside a transaction
that re-reads it and refuses if it is no longer `PENDING`.

Two attempts of one build can run at once: the lease is one minute, the tail of a build renews
nothing, and the reaper returns the task to `PENDING` for a second worker to claim. Both attempts
read a legitimate `PENDING` row. **No predicate on the row's state can tell them apart**, which is
what `docs/specs/2026-08-15-export-row-fencing.md` section 8 recorded when it left this defect open.
Both derive the same key, both promote onto it, and the loser's compensating delete removes the bytes
the winner published.

The backlog's remedy was a claim token, mirroring `UserDataImportRunner.runToken`. Reading the two
sides showed the mirror is false. The import writes into the account's data continuously and carries
counters an evicted attempt must not advance, so its polarity is "the last to claim wins" and it
needs an identity on the row to enforce it. The export produces an immutable artefact per attempt and
wants the opposite polarity. More decisively, a token does not close the defect: it changes which
attempt is refused, and the refused one has already promoted onto the shared key and still deletes
it. The remedy has to stop the loser from promoting at all, not tell it sooner that it lost.

Giving each attempt its own key was the other candidate, and it is the one this record spent the most
effort refusing. It works, and it is cheaper to reason about. It also removes the property that the
key is a pure function of the export id, and two safety mechanisms depend on that property and say so
at their sites. `AccountDeletionCleaner` deletes the rows first and then derives each archive key
from its id, because the row is gone and may never have recorded the key
(`api-usecases/.../AccountDeletionCleaner.kt:76-79`). `ReapOrphanedStorage.parseId` inverts the same
derivation to ask the database whether a file on disk still has a row, and a key it cannot parse is
skipped and never deleted (`ReapOrphanedStorage.kt:82-92`). A per-attempt key therefore breaks
account erasure, silently, and blinds the orphan sweep. Restoring the sweep means asking "does any row
name this key", which `storage_key` carries no index for, and which would still miss the `DELETED` and
`EXPIRED` rows that keep their key on purpose.

## Decision

**1. The promote runs inside the transaction that publishes the row.** The order is: re-read, test the
predicate, promote, write `READY`. A losing attempt learns it has lost before it has touched the
canonical key, so it promotes nothing and deletes nothing; it discards its own staged file through a
handle that cannot name another attempt's bytes. The datasource holds one connection
(`docs/adr/0012`), so the two publish transactions serialise and "the first to publish wins" needs no
identity on the row. No column, no migration.

**2. A non-database effect may sit inside a transaction when it is irreversible, when no predicate can
replace it, and when its residue is bounded and reclaimable.** This is the general rule decision 1 is
an instance of, and it is narrow on purpose. A rollback cannot undo a `rename(2)`, so the transaction
no longer defines the whole outcome: it defines it up to a residue. The residue admitted here is a
promoted archive whose row is still `PENDING`, and it is bounded by two things that ship in the same
lot: the failure net that marks such a row `FAILED`, and the sweep that deletes the bytes of a
terminal row and then stops the row naming them. **An effect admitted under this rule without both is
not admitted**: the residue would be exactly the orphan the rule claims to bound.

**3. Clearing a residue flag on an already-terminal row deletes the bytes first, which is the reverse
of `docs/adr/0016` decision 4, and is not a contradiction.** Decision 4 governs a state *transition*,
where the row must end up promising less than it holds: state first, bytes after, so a failed delete
leaves a row that promises nothing rather than one that promises bytes that are gone. Clearing
`storage_key` on a row that is already terminal is not a transition. There the flag is the only thing
that still names the residue, and stamping over a failed delete hides it from the one sweep that
could find it. The import half already reads it this way and says so at the site
(`ReapAbandonedUserDataImports.kt:76-78`). This record makes the boundary explicit rather than
leaving two orders in the codebase with one rule.

**4. The claim token is refused**, and with it the column and the migration the backlog attached to
this defect.

## Consequences

- The export build has no schema change, and the lot that closes three backlog items adds no
  migration. That is the direct payoff of decisions 1 and 4.
- `docs/adr/0016` decision 1 now has a second form: the fenced write may carry an irreversible effect.
  A reader of 0016 alone would not find it, so 0016's decision 1 is to be read with decision 2 here.
- Decision 2 is a rule with teeth against future work: anyone putting an effect inside a transaction
  owes a bounded residue and the sweep that reclaims it, in the same lot.
- The key stays a pure function of the export id, so account erasure and the orphan sweep keep
  working unchanged. This is a property that is now load-bearing in three places and is written down
  in only one; the specification's section 8 records it.
- A promoted archive whose transaction rolled back is reclaimed within one sweep interval
  (`exports.purge_interval`, `PT1H`) rather than immediately. Accepted: it is disk, it is bounded,
  and it is invisible to the user.
- The transaction now holds a filesystem rename, which lengthens it by one `rename(2)` on the single
  write connection. The staging, which is the expensive part, stays outside.
