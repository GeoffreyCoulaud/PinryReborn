# Export build completion

Status: Draft
Date: 2026-08-27
Branch: `fix/export-build-completion`
Closes backlog items: the overlapping build attempts, the stranded superseded archive, the export
that stays `PENDING` for good.
Architectural decision: `docs/adr/0017-promote-inside-the-publishing-transaction.md`.

Revision note: this draft is the one the spec angles corrected. Where a claim moved, the correction
is marked *(Corrected: ...)* rather than silently replaced, following the precedent of
`docs/specs/2026-08-15-export-row-fencing.md`.

## 1. Goal

An export build ends in one of two ways and nothing else: the row reads `READY` and names bytes that
exist, or the row reads a terminal state and the bytes it produced are reclaimed. Today it can end in
four other ways, and the three backlog items this lot closes are three views of one cause: the
completion of a build is neither fenced nor covered by the failure net that covers the rest of it,
and no sweep looks at what a build left behind.

The remedy needs no schema change. That is the finding that shaped this lot: the backlog proposed a
claim token and a migration, and a claim token does not close the defect.

## 2. The defect

### 2.1 The promote is outside the transaction that decides

`UserDataExportBuilder.build` (`api-usecases/.../exports/UserDataExportBuilder.kt:69-81`) runs, in
order: stage into a temporary file, `archiveStore.promote(staged, storageKey)` at `:79`, then
`publish` at `:80`, whose transaction re-reads the row and refuses if it is no longer `PENDING`
(`:140-155`).

The promote targets a key derived from the export id and the archive format's extension (`:108`), so
two attempts of one build derive the same key. `tasks.lease_duration` is `PT1M`
(`api-worker-quarkus/.../TaskQueueConfig.kt:15`) and the **tail** of a build renews nothing: the
manifest write, the channel force, and the promote. *(Corrected: an earlier draft said "a build
longer than a minute", which is wrong. `renewLease` fires per page of pins and per image
(`UserDataExportBuilder.kt:236`, `:318`), so it is the unrenewed tail that must exceed the lease, as
section 3 already said.)* `EbeanTaskQueue.reapExpired` then returns the task to `PENDING`, a second
worker claims it, and **both attempts read a legitimate `PENDING` row**, so no predicate on state can
tell them apart. Both promote onto the same key, the second overwriting the first. The loser's fence
then refuses and `:137` calls `deleteQuietly` on that key: the bytes the winner published.

The row reads `READY`, the archive is gone, and the download answers `500` rather than `410`
(`UserDataExportDownloader.kt:67` opens the stream before any status).

Between the loser's promote and its delete there is a second loss: the row carries the `byteSize` and
`sha256` of one attempt while the key holds the bytes of the other. A loser that dies in that window
leaves a `READY` row whose declared length contradicts its own body. Section 4.1 closes this by
construction, and criterion 2 pins it.

### 2.2 The promote and the publish are outside the failure net

`stageOrFail` (`:100-106`) is the only `try` in the build, and it wraps the staging alone. Any throw
from the promote or the publish, on the last attempt, skips `markFailed` and leaves the row `PENDING`.
A single disk failure is enough: no crash, no exhausted attempts, no race.

The export spec writes step 8 for the whole of steps 1 to 7
(`docs/specs/2026-07-22-user-data-export.md:386`) and its three clauses are `discard(staged)`, mark
`FAILED` if last attempt, rethrow. **Two of the three are missing today**, not one: the staged file is
only removed when the write block itself throws, so a throw from the promote leaves a full archive
under `tmp/` until `exports.staged_file_max_age`.

### 2.3 Nothing sweeps a row the build left behind

`ReapExpiredUserDataExports.reap` (`ReapExpiredUserDataExports.kt:38-43`) selects `READY` rows past
`expiresAt`, and discards orphaned staged files. Nothing else. A row left `PENDING` by 2.2, or by a
task the queue killed at claim time without ever invoking the handler (`EbeanTaskQueue.kt:112-120`),
is swept by nothing. The user holds his one `uq_user_data_exports_pending` slot and every
`POST /api/v1/me/exports` answers `409` until he happens to `DELETE` the stuck row.

The import half already has both passes this lacks: `ReapAbandonedUserDataImports.failInterruptedRuns`
(`ReapAbandonedUserDataImports.kt:56-71`) and `reclaimTerminalArchives` (`:73-85`).

### 2.4 The supersede clears the key that would have named the residue

`UserDataExportRequester.createPending` moves the previous `READY` row to `SUPERSEDED` **and nulls its
`storageKey`** (`UserDataExportRequester.kt:66`), then deletes the bytes outside the transaction with
`deleteQuietly` (`:53`). The order and the best-effort delete are both correct and argued at the site.
The closing sentence of that comment is not: "The orphan archive is reclaimed by the periodic garbage
collection" (`:52`). It is not. `ReapOrphanedStorage` reclaims a key only when no row carries its id
(`ReapOrphanedStorage.kt:73-74`), and the superseded row is still there.

*(Corrected: an earlier draft cited `:100`, `:87`, `:86` and `:83-86` for this section. The file has
never exceeded 100 lines; those numbers were never valid. The real sites are `:66`, `:53`, `:52` and
`:49-52`.)*

## 3. What the loss costs

| Symptom | What is lost | When |
| --- | --- | --- |
| 2.1 | The user's complete account archive; download answers `500` | Any build whose unrenewed tail exceeds one lease |
| 2.2 | The export slot, plus one full archive under `tmp/` for about seven hours | Any I/O failure in promote or publish |
| 2.3 | The export slot, permanently | Attempts exhausted, or 2.2 |
| 2.4 | Disk, permanently, invisible to every sweep | A failed delete after a supersede |

## 4. The remedy

### 4.1 The promote joins the transaction that publishes

The promote moves inside the transaction that re-reads the row and tests the predicate. The order
becomes: read, test `PENDING`, promote, write `READY`. A losing attempt learns it has lost **before**
it has touched the canonical key, so it promotes nothing and deletes nothing. It discards its own
staged file through `ExportArchiveStore.discard`, which takes a `StagedFile` and cannot name another
attempt's bytes.

The datasource pins `minConnections` and `maxConnections` to 1
(`api-application/src/main/resources/application.properties:15,16`, pinned by
`ProductionDatasourceDeclarationTest`), and a transaction is what serialises a read-write pair
(`agents/engineering.md:202-208`). The two publish transactions therefore serialise, and "the first to
publish wins" needs no token. **The guarantee is per JVM**: it does not survive a second process on
the same database file, nor raising `maxConnections`. *(Corrected: an earlier draft cited
`docs/adr/0012` for this. That ADR is about one datasource declaration and one transaction seam and
says nothing about the connection count. The misattribution is inherited from
`docs/adr/0016-fence-by-compare-and-set.md:44-45`, which carries the same wrong pointer; correcting
0016 is out of this lot's scope and is named in section 8.)*

**Precondition, and it is load-bearing.** `promote` is a `rename(2)` only while the staging directory
and the archive directory share a filesystem. `DataDirPaths.atomicMove:40-46` catches
`AtomicMoveNotSupportedException` and falls back to `Files.move(..., REPLACE_EXISTING)`, which is a
full byte copy. Inside this transaction, on the single connection, that copy would stall every other
writer and reader in the process, and the connection pool's default wait is one second. The lot
therefore adds a startup check that `tmp/` and `exports/` resolve to the same `FileStore`, and
**refuses to start** when they do not, rather than discovering it under load. A deployment that
genuinely needs them split needs a different design for the promote, not a slower transaction.

### 4.2 The failure net covers the completion, and restores the discard

The `try` extends over the promote and the publish. On any failure it discards the staged file (**on
every attempt**, not only the last), marks the export `FAILED` on the last attempt, and rethrows.
That is step 8 of the original spec, all three clauses.

`requireUser` and `requireFreeSpace` stay outside it: they mark the row themselves. *(Corrected: an
earlier draft justified this by the log line it avoids. `markFailed` is fenced on `PENDING`, so
widening the net over them would produce the same observable, and `api-usecases` binds `slf4j-nop` in
test. The honest reason is that they already write the more precise `failureCode`, and nothing pins
the difference.)*

### 4.3 The export sweep gains the two passes its import twin has

`ReapExpiredUserDataExports.reap` becomes three passes plus the staged-file discard:

1. **`failInterruptedBuilds`**: a `PENDING` export whose task is absent or in no live state, **and
   whose `requestedAt` is older than `exports.interrupted_grace`**, is fenced to `FAILED` with
   `failureCode = "EXPORT_INTERRUPTED"`.
2. **the expiry purge**, which now writes `EXPIRED` and **stops deleting the bytes itself**.
3. **`reclaimTerminalArchives`**: a terminal export that still names a key has the bytes at its
   **derived** key deleted first, and only then stops naming them.

Three things this settles, each of which an angle found wrong in the first draft:

**The grace in pass 1 is not decoration, and its value is not derived from the lease.**
`EbeanTaskQueue.claimNext:112-120` moves a task to `DEAD` inline as soon as its attempts are spent,
without regard for handlers still running, so a `DEAD` task does not mean no builder is working.
Without a grace, pass 1 writes `FAILED` under those builders, and each of them then meets a
non-`PENDING` row at its fence and throws away a complete, valid archive. **This lot would then close
three defects and open a fourth, worse than any of them**, on precisely the accounts whose archive
costs most to rebuild.

*(Corrected: an earlier draft set the grace to `PT15M` and justified it as "comfortably past
`lease_duration x max_attempts`", which is `PT3M`. That product does not bound anything relevant. The
grace is measured from `requestedAt`, and the danger runs from `requestedAt` until the **last**
builder stops. An attempt is not bounded by the lease: `renewLease` fires per page of pins and per
image (`UserDataExportBuilder.kt:236`, `:318`), so an attempt lasts as long as its staging progresses.
Three attempts over a multi-gigabyte account exceed fifteen minutes easily.)*

The grace must therefore dominate the longest staging this instance can plausibly perform, and the
repository has already put a number on exactly that question: `exports.staged_file_max_age`, the age
past which a staged file is presumed orphaned, that is, the age at which the repository already
declares a build dead. `exports.interrupted_grace` takes the same default, `PT6H`, and its KDoc says
why it is that number and not a derivative of the lease.

The asymmetry is deliberate. Condemning too early destroys a valid archive; condemning too late keeps
one user's slot busy for longer, and `DELETE` already frees it by hand. The costs are not
comparable, so the grace is generous.

The real fix is upstream: a task whose handler is still running should not be killed. That is
`claimNext`'s inline kill, an argued decision outside this lot, and section 8 files it.

**Passes 2 and 3 no longer overlap.** In the first draft, `expire()` kept deleting the bytes without
clearing the key, so pass 3 selected the same row in the same run, counted it twice and reissued the
delete. Moving the delete out of `expire()` leaves one rule instead of two: **a state transition
writes the state and nothing else; reclaiming bytes is pass 3's job, for every terminal state.** The
row still loses its bytes within the same `reap()`, since pass 3 runs after pass 2.

**Pass 3 deletes both keys when they differ.** *(Corrected: this section first said "the derived key,
not the column's", citing the import twin, which does delete only the derived one. Deleting the
derived key alone succeeds vacuously when the column disagrees, because deletion is idempotent; the
column is then cleared, and the bytes it named become unreachable to every sweep. That is section
2.4 reintroduced, in the pass written to close it. The delivered code deletes the union.)* The column
drives *selection* (`storage_key IS NOT NULL`, so the pass converges); the *deletion* targets both
`ExportArchiveKey.forExport(id)` and the column's key. A row whose column disagrees with the
derivation would otherwise
keep its bytes for good.

The bytes go before the key is cleared, which is the reverse of `docs/adr/0016` decision 4. The reason
is `ReapAbandonedUserDataImports.kt:77-78`: stamping over a failed delete hides the residue from the
only sweep that can still name it. `docs/adr/0017` decision 3 records where that boundary runs.

`archiveStore.delete` is `Files.deleteIfExists` (`FilesystemZipExportArchiveStore.kt:90-92`) and its
idempotence is already pinned (`FilesystemZipExportArchiveStoreTest.kt:166-176`). The lot documents
that idempotence **on the port**, since pass 3's termination now depends on it and
`ExportArchiveStore.delete` currently promises nothing.

**Both new selections are bounded** by `exports.sweep_batch_size` (default 500, the figure
`garbage-collection.orphan_batch_size` already uses), **and pass 1's selection is ordered by
`requestedAt` ascending**. Pass 3 converges without an order because acting on a row destroys its own
selection predicate; pass 1 does not, because the grace filter is applied after the selection, so an
unordered batch of recent `PENDING` rows could starve the old ones indefinitely. The first run after deployment is a backlog
catch-up, not a steady state: today every terminal row keeps its key on purpose, so pass 3's first
selection is the whole history of terminal exports. It converges over successive ticks.

**The startup sweep moves behind `safeReap()`.** `ExportRetentionLifecycle.start():31` calls `reap()`
bare. Nothing about reclaiming residue should stop the API from serving.

*(Corrected: an earlier draft justified this by "a disk that refuses one delete would fail the boot".
That is false, and stating a reason narrower than the truth invites the next cleanup to take the
guard back out. Each row is isolated, so one refused delete never reaches the caller. What is outside
every net is the three selections and the per-row task lookup pass 1 makes inside its filter. The
three passes widen that surface, they do not create it, and the defect predates this lot.)*

*(Corrected twice, which is worth recording: the note above first listed the staged-file sweep among
the unprotected calls. It was, until this lot gave it a net of its own, so the correction of a false
sentence introduced another one. That net is not prescribed anywhere above because it came out of a
block review: it exists so `reap()` returns its counts even when the staging walk throws, which is
the one failure the guard is named for. Its refusal logs at WARN and is pinned.)*

### 4.4 The supersede keeps its key

`createPending` stops nulling `storageKey`. The best-effort `deleteQuietly` outside the transaction
stays: it is the fast path, and a multi-gigabyte archive must not wait for the next sweep. When it
fails, the row still names the bytes and pass 3 reclaims them. The comment at `:49-52` becomes true
and shorter.

### 4.5 One derivation of the archive key

The literal `"exports/$id.$extension"` lives in `UserDataExportBuilder.kt:108` and
`AccountDeletionCleaner.kt:89-90`, its prefix again in `ReapOrphanedStorage` and
`FilesystemZipExportArchiveStore`, and pass 3 would add a third copy. The import already has the
object this needs (`imports/ImportArchiveKey.kt`), whose KDoc states the property. The lot adds
`exports/ExportArchiveKey.kt`, mirroring it, and the three use-case sites consume it.

This is not tidying. `docs/adr/0017` makes the derivability of the key load-bearing for account
erasure and for the orphan sweep, and a property held in two copied literals is one edit from being
false.

### 4.6 What `DELETE` releases on a `PENDING` row

`UserDataExportDeleter.kt:47-49` releases nothing on a `PENDING` row, and its reason is that a build
between its promote and its publish will meet the `DELETED` row at its own fence and delete the bytes
itself. After 4.1 that build never promoted, so the reason no longer holds, and the residue admitted
by `docs/adr/0017` decision 2 (promoted bytes, rolled-back transaction) would survive a `204` the
user reads as "erased".

The `PENDING` arm therefore deletes the derived key, best-effort: it is residue cleanup, not the
primary operation, so `docs/adr/0003` decision 1 applies, unlike the `READY` arm which propagates.
There is no window against a concurrent attempt: the two transactions serialise, so either the delete
commits first and the attempt refuses at its fence, or the attempt publishes first and this request
takes the `READY` arm.

The KDocs of `release`, `releaseStranded` and the class change in the same commit. `releaseStranded`
loses its exclusivity, not its purpose: it is the fast repair, pass 3 is the guaranteed one.

## 5. Per write: predicate, refusal, residue

| Write | Predicate | On refusal | Residue |
| --- | --- | --- | --- |
| Publish (4.1) | row is `PENDING` | discard own staged file | none, unless `discard` throws: then a staged file swept by age |
| Mark failed (4.2) | row is `PENDING` | nothing written | none: the staged file is discarded on every attempt |
| Fail interrupted (4.3) | row is `PENDING` | nothing written | none |
| Expire (4.3) | row is `READY` | nothing written | the bytes, reclaimed by pass 3 in the same run |
| Reclaim terminal (4.3) | state is terminal | nothing written, key kept | key kept, retried next run |
| Supersede (4.4) | inside the request transaction | request fails, nothing written | the bytes, if `deleteQuietly` fails: reclaimed by pass 3 |
| Delete, `PENDING` arm (4.6) | state is not gone | nothing written | none |

`isTerminal` is `FAILED`, `EXPIRED`, `DELETED`, `SUPERSEDED`: enumerated positively, so a state added
later is neither terminal nor live and the partition test fails rather than silently admitting it.
`isGone` keeps its current three states and is expressed through `isTerminal` rather than beside it.
An implementer who reads `READY` into this set makes pass 3 delete live archives; the enumeration is
the guard.

## 6. Acceptance criteria

Each names an observable, not an instrument.

| # | Given | When | Then |
| --- | --- | --- | --- |
| 1 | a build whose row is no longer `PENDING` when its publish fence reads it | the build completes | no archive is promoted, the canonical key is untouched, and the attempt's staged file is gone |
| 2 | a `READY` export | its archive is downloaded | the body's length and SHA-256 equal the row's `byteSize` and `sha256` |
| 3 | a build whose promote or publish throws on its last attempt | the failure returns | the row reads `FAILED`, and no file of that attempt remains under `tmp/` |
| 4 | a `PENDING` export whose task is absent or settled, older than the grace | a sweep runs | the row reads `FAILED` with `failureCode = "EXPORT_INTERRUPTED"` |
| 5 | a `PENDING` export whose task is `RUNNING`, or younger than the grace | a sweep runs | the row is untouched and the build still publishes `READY` |
| 6 | a superseded export whose archive delete failed | a sweep runs | the file is gone from disk and the row names no key |
| 7 | an export whose bytes were promoted and whose publish rolled back | the row becomes terminal and a sweep runs | the file is gone from disk |
| 8 | any terminal export | a sweep has run and its deletes succeeded | no file under `exports/` is named by the derived key of a terminal row |
| 9 | a stuck export that a sweep has failed | the user requests a new export | the answer is not `409 EXPORT_ALREADY_IN_PROGRESS` |

Criterion 9 says "not `409`" on purpose: `exports.minimum_interval` (`PT1H`) is measured across all
states (`UserDataExportRepositoryInterface.kt:24`), so the honest promise is that the permanent `409`
becomes a temporary `429 EXPORT_TOO_SOON`. *(Corrected: the first draft promised the user "can request
a new export", which production refuses for up to an hour, and which the test profile's
`minimum_interval = PT0S` would have hidden.)*

Criterion 8 is stated on the disk, not on the row. *(Corrected: the first draft said "no terminal row
names bytes that still exist", which is satisfied by nulling the key, that is by the very defect of
section 2.4.)*

The absence of a schema change is a design constraint, not a criterion: it is stated in section 8,
because `git diff -- '*dbmigration*'` is empty before any work is done, and a check that cannot fail
is not a check.

## 7. What a client observes

Nothing changes on the happy path. Two refusals change shape:

- A download whose archive a losing attempt destroyed answered `500`. That case no longer occurs.
- An export stuck `PENDING` answered `409` on every subsequent request, forever. It now becomes
  `FAILED` within one sweep interval past the grace, after which a request is accepted, or refused
  with `429` while the cooldown runs.

One more, on a path the first draft missed: after 4.4 a `SUPERSEDED` row names a key, so a `DELETE`
on it reaches `releaseStranded` and issues a delete where it previously did nothing. The response is
unchanged.

`EXPORT_INTERRUPTED` joins `USER_GONE`, `DISK_FULL` and `BUILD_FAILED` as a `failureCode` value. It is
data on the row, not a new HTTP status: `failureCode` is an unconstrained `String?` on the output DTO.

## 8. Out of scope, accepted limits

- **The heartbeat's return value is not in this lot.** `TaskContext.renewLease` is `() -> Unit`
  (`TaskContext.kt:14`) while `TaskQueueInterface.renewLease` answers a `Boolean` documenting that the
  caller must stop, and `TaskProcessor.kt:45` coerces it away. The first draft included the fix. It is
  removed for three reasons the angles established: it closes none of the three defects, since 4.1
  makes a lost lease harmless to correctness; `() -> Boolean` is not assignable to a `() -> Unit`
  parameter in Kotlin, so both handlers break and the natural repair is a lambda that swallows the
  answer again, hiding the defect better than it hides today; and a thrown abandonment would land
  inside 4.2's widened net, letting an evicted attempt write `FAILED` over a row whose winner is still
  building. The backlog item is rewritten with that reach: both handlers, the exception type, and its
  exclusion from the failure net.
- **Rows already written with `state = SUPERSEDED` and `storageKey = null`** are invisible to both
  sweeps: the id has a row, and the row names no key. Two halves, because stating only the first
  misleads: **account erasure still reclaims them**, since `AccountDeletionCleaner` derives the key
  from the id and never reads the column; but while the account lives, they are a complete personal
  archive that never expires, outliving the `P7D` retention it was produced under. Alpha-stage
  residue; this lot does not migrate them.
- **`ReapExpiredUserDataExports` keeps its name** although it will run three passes. Renames are their
  own task (`agents/workflow.md`, Scope), and the repository's precedent is to keep an outgrown name
  and make the class KDoc enumerate what it really does, as `ReapAbandonedUserDataImports` does. The
  KDoc rewrite is in this lot; the rename is proposed as a backlog item, not to Improve, which is not
  one of the four exits (`docs/adr/0010`).
- **`EbeanTaskQueue.claimNext` keeps killing an exhausted task inline**, while its handler may still
  be running. It is an argued decision (`docs/specs/2026-07-22-user-data-export.md:506`) and a
  notification would stay best-effort, so this lot does not change it. Pass 1's grace does not make it
  harmless, it makes the collision improbable: an account whose staging outlasts
  `exports.staged_file_max_age` can still have a builder condemned under it. The upstream fix, not
  killing a task whose handler holds a live lease, is filed as its own item.
- **No measurement is taken of the two new selections.** Both scan `user_data_exports` without an
  index on `state` or `storage_key`, as the import twin already does on its own table. The repository
  asks for a measurement on a query that grows with the data; this lot inherits the twin's shape
  rather than measuring it, and says so instead of implying the question was settled.
- **`ReapOrphanedStorage` is not re-keyed**, which settles the question the backlog item left open.
  Two arguments, both verified: the key is knowable without reading the row, which is what lets
  `AccountDeletionCleaner` reclaim bytes after the row is deleted; and `storage_key` carries no index
  (`dbmigration/1.10.sql`, `1.11.sql`), so keying on it is a full scan per batch. *(Corrected: the
  first draft carried a third argument, that `DELETED` and `EXPIRED` rows keep their key on purpose.
  Pass 3 stops that in this same lot, so the argument is withdrawn.)*
- **The single-connection guarantee is not asserted by this lot's tests.**
  `PassthroughTransactionRunner` opens no transaction, it counts
  (`docs/specs/2026-08-15-export-row-fencing.md:201-203`), and real exclusion is reachable only from
  `api-persistence-sqlite`. Section 9 pins the code's shape; the serialisation is held by the
  configuration and its own test.
- **No test in `api-usecases` can build the residue of `docs/adr/0017` decision 2**, since the
  passthrough runner never rolls back. Criterion 7 is pinned at the integration level, outside the
  coverage perimeter. What holds decision 2 for future work is review, not the gate.
- **The `exports` package still has no static guard** on the fenced-write shape:
  `ImportStateMergedOutsideTransaction` includes only `**/api-usecases/**/imports/**`. This lot adds
  fenced writers to that unguarded package. Widening the rule is a separate backlog item, and its own
  finding is that widening is not the one-line change the backlog claims.
- **The 413 above `quarkus.http.limits.max-body-size`** and other framework-generated refusals belong
  to the error-format lot.
- **Two defects found in the import half, filed not fixed**: `ReapAbandonedUserDataImports`'s class
  KDoc names the wrong pass as the reason for its ordering (`abandonStaleUploads` selects rows that
  have no key yet, so it is `failInterruptedRuns` that makes a key-holding row terminal), and
  `ImportLifecycle` calls its startup sweep outside its own `safe` wrapper, exactly as the export did.

## 9. Tests

Red first, in the project's order: integration, then use case, then domain. The coverage bound is per
package, and this lot opens branches in five: `usecases.exports`, `usecases.imports` (the shared task
predicate), `domain.enums`, `domain.tasks`, `persistence.sqlite.repositories`.

**The red the lot starts from is three existing assertions that must invert**, none of which the first
draft named:

- `UserDataExportBuilderTest.kt:409` and `:424` assert `verify { archiveStore.delete(storageKey) }` on
  the two refusal arms: exactly what 4.1 forbids. Both call `stubHappyPathBuild()`, which stubs
  `promote`; under 4.1 the refusal path never promotes, and `BaseTest.checkUnnecessaryStub()` fails an
  unmet stub. The fixture splits, following the precedent of `stubBuildFailure`.
- `UserDataExportRequesterTest.kt:229` asserts `storageKey == null` after a supersede: the red for 4.4.

**Integration** (joining `MeExportCompletionIntegrationTest`, no new `@QuarkusTest` class):

- criterion 6, the superseded archive whose delete failed;
- criterion 4, reached by a **task of a kind with no registered handler**, which `TaskProcessor:37-39`
  marks `DEAD` on its own. Seeding a row with no task gives "absent", not `DEAD`, and forcing a real
  `DEAD` races the live worker;
- criterion 7, the promoted-then-rolled-back residue, which no use-case test can construct.

**Use case, `UserDataExportBuilderTest`:**

- the refusal arm: **`promote` is never called** and the canonical key is never deleted. This is the
  pin for 4.1, not the co-transaction assertion. *(Corrected: the first draft made
  `PassthroughTransactionRunner.current` the pin. That instrument records the outermost open
  transaction and cannot see ordering within it, so an implementation that promotes first and tests
  the predicate after records the same number and passes. It also does not exist for a non-repository
  port: recording `promote` needs a new list in the fixtures.)* The co-transaction assertion is kept
  as a complement, with that new list;
- *(Corrected: the rival is **not** installed through the `reread` hook. Written that way it lands
  during the loser's own fenced read, so an implementation that promotes before testing the predicate
  overwrites and is then overwritten by the rival, leaving the expected end state and passing. Two
  serialised transactions cannot interleave that way in the first place. The rival is a whole
  transaction landing between the staging and the completion, and the mutation proving the difference
  is in the commit that moved it. What follows was the reasoning for the original instrument.)*
- the rival was to be installed through the `reread` hook keyed on `stageCalls > 0`, not on an ordinal: the
  same hook also fires on `stampStorageKey`'s own fenced read, and a rival landing there exits the
  build before it ever stages;
- promote throws on the last attempt: `FAILED` / `BUILD_FAILED`, and `discard` was called. Red today;
- publish throws on the last attempt: same;
- promote throws on a non-final attempt: `discard` called, row still `PENDING`.

**Use case, `ReapExpiredUserDataExportsTest`:** one case per branch, not one case per sentence. The
twin's five cases are the model (`ReapAbandonedUserDataImportsTest.kt:198`, `:216`, `:234`, `:272`,
`:292`):

- pass 1, task absent because `taskId` is null; task absent because the queue has forgotten it; task
  `DEAD`; task `SUCCEEDED`; task `CANCELLED`. The last two are what a predicate spelled "dead or
  absent" would miss, the mistake `docs/specs/2026-08-14-user-data-import.md:409-411` records;
- pass 1, task `PENDING` and task `RUNNING`: nothing written;
- pass 1, row younger than the grace: nothing written;
- pass 1 fence refused (row moved to `DELETED` between selection and write): nothing written;
- pass 2 no longer deletes bytes;
- pass 3 deletes the derived key before clearing the column, and a delete that throws leaves the
  column so the next run retries;
- pass 3 fence refused: nothing written, key kept;
- a refused sweep is not counted;
- `stubSweep()` gains the two new selections, and each existing test states what it still asserts once
  all three passes run.

**Use case, `UserDataExportRequesterTest`:** the superseded row still names its key.

**Use case, `UserDataExportDeleterTest`:** the `PENDING` arm deletes the derived key; a delete that
throws there does not fail the request.

**Repository, `UserDataExportRepositoryTest`:** `findPending` and `findReclaimableTerminal` each
exercised over all six states, plus a terminal row with a null key. The precedent is
`UserDataImportRepositoryTest.kt:273-287`, "one row per state, so a predicate widened by accident is
caught here".

**Domain:** `UserDataExportState.isTerminal` over all six states, and its relation to `isGone`;
`TaskState.isLiveAttempt` over all five. `ReapAbandonedUserDataImports` switches to the shared
predicate in the same commit, so the value has one source.

**Adapter:** the startup check refuses to start when `tmp/` and `exports/` are on different file
stores.

*(Corrected: the first draft listed a `TaskContextTest` case for the heartbeat. `TaskContext` is a
data class holding a lambda and knows no queue; the case could only assert Kotlin. The heartbeat is
out of scope entirely, see section 8.)*

## 10. Observability

`reap()` returns the count of rows acted on, and a row acted on by two passes in one run counts twice:
the same accounting the import twin declares. Today nothing reads that number, at either call site.
The lot logs one line per sweep with a count per pass (`failed`, `expired`, `reclaimed`), so an
operator whose exports all turn `EXPORT_INTERRUPTED` at three in the morning has something to read.
*(Corrected: an earlier draft closed on "a best-effort delete says so where the number is read". The
lot does not satisfy it and this is the honest statement instead: the counts name rows acted on, and
a row whose delete threw is not among them, which is what the reader needs. Saying so **in the line
itself** was never specified beyond that one sentence, and the sentence is withdrawn rather than left
prescribing something nothing implements. The staged-file sweep's own refusal logs separately, at
WARN, and is pinned.)*
