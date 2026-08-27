# Export build completion

Status: Draft
Date: 2026-08-27
Branch: `fix/export-build-completion`
Closes backlog items: the overlapping build attempts, the stranded superseded archive, the export
that stays `PENDING` for good.

## 1. Goal

An export build ends in one of two ways and nothing else: the row reads `READY` and names bytes that
exist, or the row reads a terminal state and names no bytes anybody still holds. Today it can end in
four other ways, and the three backlog items this lot closes are three views of the same cause: the
completion of a build is neither fenced nor covered by the failure net that covers the rest of it,
and no sweep looks at what a build left behind.

The remedy needs no schema change. That is the finding that shaped this lot: the backlog proposed a
claim token and a migration, and a claim token does not close the defect.

## 2. The defect

### 2.1 The promote is outside the transaction that decides

`UserDataExportBuilder.build` (`api-usecases/.../exports/UserDataExportBuilder.kt:69-81`) runs, in
order: stage the archive into a temporary file, `archiveStore.promote(staged, storageKey)` at `:79`,
then `publish` at `:80`, whose transaction re-reads the row and refuses if it is no longer `PENDING`
(`:140-155`).

The promote is a `rename(2)` onto a key derived from the export id alone (`:108`), so two attempts of
one build derive the same key. `tasks.lease_duration` is `PT1M` and the last stretch of a build
renews nothing, so a build longer than a minute is reclaimed by `EbeanTaskQueue.reapExpired`, put
back to `PENDING`, and claimed by a second worker. Both attempts read a legitimate `PENDING` row, so
no predicate on state can tell them apart. Both promote onto the same key, the second overwriting the
first. The loser's fence then refuses, and `:137` calls `deleteQuietly` on that key: the bytes the
winner published.

The row reads `READY`, the archive is gone, and the download answers `500` rather than `410`.
`ReapOrphanedStorage` cannot see it, because it reclaims only keys whose id has no row
(`ReapOrphanedStorage.kt:70-80`).

Between the loser's promote and its delete there is a second, quieter loss: the row carries the
`byteSize` and `sha256` of one attempt while the key holds the bytes of the other. A loser that dies
in that window leaves a `READY` row whose `Content-Length` contradicts its own body.

### 2.2 The promote and the publish are outside the failure net

`stageOrFail` (`:100-106`) is the only `try` in the build, and it wraps the staging alone. The
promote at `:79` and the publish at `:80` sit after it. So any throw from either one, on the last
attempt, skips `markFailed` entirely and leaves the row `PENDING`. A single disk failure is enough:
no crash, no exhausted attempts, no race.

The export spec writes step 8 for the whole of steps 1 to 7
(`docs/specs/2026-07-22-user-data-export.md:386`) and states the invariant this breaks: the row's
`FAILED` state and `failureCode` are the user-visible truth. This is therefore a non-conformance, not
only a defect.

### 2.3 Nothing sweeps a row the build left behind

`ReapExpiredUserDataExports.reap` (`ReapExpiredUserDataExports.kt:38-43`) selects `READY` rows past
`expiresAt`, and nothing else. A row left `PENDING` by 2.2, or by a task the queue killed at claim
time without ever invoking the handler (`EbeanTaskQueue.kt:112-120`), is swept by nothing. The user
holds his one `uq_user_data_exports_pending` slot and every `POST /api/v1/me/exports` answers `409`
until he happens to `DELETE` the stuck row.

The import half already has both passes this lacks: `ReapAbandonedUserDataImports.failInterruptedRuns`
(`ReapAbandonedUserDataImports.kt:56-71`) fails a row whose task is gone, and `reclaimTerminalArchives`
(`:73-85`) deletes the bytes of a terminal row and then stops the row naming them.

### 2.4 The supersede clears the key that would have named the residue

`UserDataExportRequester.createPending` moves the previous `READY` row to `SUPERSEDED` **and nulls its
`storageKey`** inside the transaction (`UserDataExportRequester.kt:100`), then deletes the bytes
outside it with `deleteQuietly` (`:87`). The order and the best-effort delete are both correct and
argued at the site. The closing sentence of that comment is not: "The orphan archive is reclaimed by
the periodic garbage collection" (`:86`). It is not. `ReapOrphanedStorage` reclaims a key only when no
row carries its id, and the superseded row is still there. A `deleteQuietly` that swallows a real
failure therefore leaves an archive that no state names and no sweep can find, for the life of the row.

## 3. What the loss costs

| Symptom | What is lost | When |
| --- | --- | --- |
| 2.1 | The user's complete account archive; download answers `500` | Any build whose tail exceeds one lease |
| 2.2 | The export slot, until the user deletes the row by hand | Any I/O failure in promote or publish on the last attempt |
| 2.3 | The export slot, permanently | Attempts exhausted, or 2.2 |
| 2.4 | Disk, permanently, invisible to every sweep | A failed delete after a supersede |

## 4. The remedy

### 4.1 The promote joins the transaction that publishes

The promote moves inside the transaction that re-reads the row and tests the predicate. The order
becomes: read, test `PENDING`, promote, write `READY`. A losing attempt now learns it has lost
**before** it has touched the canonical key, so it promotes nothing and deletes nothing. It discards
its own staged file through `ExportArchiveStore.discard`, which takes a `StagedFile` and cannot name
another attempt's bytes.

The datasource holds one connection (`docs/adr/0012`), so the two publish transactions serialise, and
"the first to publish wins" needs no token to distinguish the attempts. This is why no column and no
migration are required.

This puts a non-database effect inside a transaction, which `docs/adr/0016` did not contemplate: a
rollback cannot undo a `rename(2)`. That is an architectural decision and it is recorded in
`docs/adr/0017-promote-inside-the-publishing-transaction.md`. The residue it admits (bytes promoted,
transaction rolled back) is bounded by 4.2 and reclaimed by 4.3, which is why the three arrive
together and must not be split across lots.

### 4.2 The failure net covers the completion

The `try` that marks the export `FAILED` on the last attempt extends over the promote and the publish,
not the staging alone. `requireUser` and `requireFreeSpace` stay outside it: they mark the row
themselves and a second `markFailed` would only log a refusal.

`markFailed` is fenced on `PENDING` already (`:117-121`), so it writes nothing when the row moved on.

### 4.3 The export sweep gains the two passes its import twin has

`ReapExpiredUserDataExports.reap` becomes three passes plus the staged-file discard, mirroring
`ReapAbandonedUserDataImports.reap`:

1. `failInterruptedBuilds`: a `PENDING` export whose task is absent, or whose task is in no live
   state, is fenced to `FAILED` with `failureCode = "EXPORT_INTERRUPTED"`.
2. the existing expiry purge, unchanged.
3. `reclaimTerminalArchives`: a terminal export that still names a key has its bytes deleted first,
   and only then stops naming them.

Order matters and is the import's: failing a build first is what makes its row terminal, hence
reclaimable in the same run.

The bytes go before the key is cleared, which is the reverse of `docs/adr/0016` decision 4. The
reason is `ReapAbandonedUserDataImports.kt:77-78`: stamping over a failed delete hides the residue
from the only sweep that can still name it. Decision 4 governs a state transition, where the row must
promise less than it holds. Clearing a residue flag on an already-terminal row is not a transition,
and there the reverse order is required. `docs/adr/0017` records this reading.

`archiveStore.delete` is `Files.deleteIfExists`
(`api-storage-filesystem/.../FilesystemZipExportArchiveStore.kt:90-92`), so reclaiming a key whose
bytes are already gone is not an error, and the pass converges after one run.

### 4.4 The supersede keeps its key

`createPending` stops nulling `storageKey`. The best-effort `deleteQuietly` outside the transaction
stays: it is the fast path, and a multi-gigabyte archive must not wait for the next sweep. When it
fails, the row still names the bytes, and 4.3 reclaims them. The comment at `:83-86` becomes true and
shorter.

### 4.5 The heartbeat answers

`TaskQueueInterface.renewLease` returns a `Boolean` documented as "tells the caller it has lost the
task and must stop working on it" (`TaskQueueInterface.kt:25-30`). `TaskContext.renewLease` is typed
`() -> Unit` (`TaskContext.kt:14`) and `TaskProcessor.kt:60` coerces the result away, so a handler is
never told its lease is gone and keeps working.

`TaskContext.renewLease` becomes `() -> Boolean`. The export builder routes its heartbeats through one
helper that throws when the answer is false, so a build that has lost its lease stops instead of
spending disk and CPU on an archive it can no longer publish.

This half does not close any of the three defects: 4.1 already makes a lost lease harmless to
correctness. It is included because the backlog filed it as the enabling half of 2.1, and because
without it a superseded attempt keeps writing gigabytes for nothing. It is the last block of the plan
so it can be dropped without touching the rest.

## 5. Per change: predicate, refusal, residue

| Write | Predicate | On refusal | Residue |
| --- | --- | --- | --- |
| Publish (4.1) | row is `PENDING` | discard own staged file | staged file, swept by age |
| Mark failed (4.2) | row is `PENDING` | nothing written | none |
| Fail interrupted (4.3) | row is `PENDING` | nothing written | none |
| Reclaim terminal (4.3) | state is terminal | nothing written, key kept | key kept, retried next run |
| Supersede (4.4) | inside the request transaction | request fails, nothing written | none |

## 6. Acceptance criteria

1. When two attempts of one build run concurrently, the archive the row names is downloadable after
   both have ended.
2. When a build's promote or publish fails on its last attempt, the row reads `FAILED` and the user
   can request a new export.
3. When a build's task ends without the handler running, the row reads `FAILED` with
   `failureCode = "EXPORT_INTERRUPTED"` after the next sweep, and the user can request a new export.
4. When the bytes of a superseded export could not be deleted, a later sweep deletes them and the row
   stops naming them.
5. A terminal export row never names bytes that still exist, once a sweep has run.
6. No export row that a build has ended is left `PENDING`.
7. The schema is unchanged: no new column, no new index, no migration.

## 7. What a client observes

Nothing changes on the happy path. Two refusals change shape:

- A download whose archive was destroyed by a losing attempt answered `500`. That case no longer
  occurs.
- An export stuck `PENDING` answered `409 EXPORT_ALREADY_IN_PROGRESS` on the next request, forever.
  It now becomes `FAILED` within one sweep interval (`exports.purge_interval`, `PT1H`), after which a
  new request is accepted.

`EXPORT_INTERRUPTED` joins `USER_GONE`, `DISK_FULL` and `BUILD_FAILED` as a `failureCode` value. It is
data on the row, not a new HTTP status.

## 8. Out of scope, accepted limits

- **Rows already written with `state = SUPERSEDED` and `storageKey = null`** are invisible to both
  sweeps: the id has a row, and the row names no key. This lot does not migrate them. Alpha-stage
  residue, named here so nobody re-derives it.
- **`ReapExpiredUserDataExports` keeps its name** although it will run three passes. Renaming it is a
  rename, and renames are their own task (`agents/workflow.md`, Scope). Proposed for Improve.
- **`EbeanTaskQueue.claimNext` keeps killing an exhausted task inline** without invoking the handler.
  It is an argued decision (`docs/specs/2026-07-22-user-data-export.md:506`), a notification would
  stay best-effort, and 4.3 makes it harmless for exports. Import already had its own sweep.
- **The 413 above `quarkus.http.limits.max-body-size`** and every other framework-generated refusal
  stay outside this lot; they belong to the error-format lot.
- **`ReapOrphanedStorage` is not re-keyed.** "No row names this key" was considered and refused: the
  key is a pure function of the id (`UserDataExportBuilder.kt:108`,
  `AccountDeletionCleaner.kt:89-90`), which is what lets the account cleaner reclaim bytes after the
  row is deleted, and what `ReapOrphanedStorage.parseId` inverts. `storage_key` carries no index
  (`dbmigration/1.10.sql`, `1.11.sql`), so keying on it means a full table scan per batch, and it
  would still miss `DELETED` and `EXPIRED` rows that keep their key on purpose. 4.3 covers those rows
  instead.

## 9. Tests

Red first, in the project's order: integration, then use case, then domain.

**Integration** (joining `MeExportCompletionIntegrationTest`, no new `@QuarkusTest` class):

- a superseded export whose archive delete failed: the next sweep removes the bytes and the row stops
  naming them;
- an export whose task is forced `DEAD`: the next sweep reads `FAILED` / `EXPORT_INTERRUPTED`, and the
  following `POST` is accepted rather than `409`.

**Use case, `UserDataExportBuilderTest`:**

- a rival attempt that published first: no promote, no delete of the canonical key, the rival's
  `byteSize` intact, and the loser's staged file discarded;
- the promote runs in the transaction that publishes. Asserted by recording the transaction in effect
  inside the `promote` stub and comparing it to the one the write used, the instrument section 9 of
  `docs/specs/2026-08-15-export-row-fencing.md` established. Asserting "inside a transaction" alone
  would pass against the wrong transaction;
- promote throws on the last attempt: row reads `FAILED` / `BUILD_FAILED`. Red today: it reads
  `PENDING`;
- publish throws on the last attempt: same;
- a lost lease: the build abandons and writes nothing.

**Use case, `ReapExpiredUserDataExportsTest`:**

- task `DEAD`, task absent, task `SUCCEEDED`, task `CANCELLED`: all four fence to `FAILED`. The last
  two are what a predicate spelled "dead or absent" would miss, the mistake
  `docs/specs/2026-08-14-user-data-import.md:409-411` records having made once;
- task `PENDING`, task `RUNNING`: nothing written;
- a row that moved to `DELETED` between selection and write: refused, nothing written;
- reclamation deletes the bytes before it clears the key, and a delete that throws leaves the key so
  the next run retries;
- a refused sweep is not counted as a swept row.

**Repository, `UserDataExportRepositoryTest`:** `findPendingExports` and `findReclaimableTerminal`
each exercised over all six states plus a terminal row with a null key, per the convention of the
fencing spec section 6: a single-state test does not distinguish the specified predicate from a
looser one.

**Domain:** `UserDataExportState.isTerminal` over all six states; `TaskState.isLiveAttempt` over all
five.

**Task plumbing:** `TaskContextTest` answers false when the queue refuses the renewal;
`TaskProcessorTest` carries that refusal through to the handler.
