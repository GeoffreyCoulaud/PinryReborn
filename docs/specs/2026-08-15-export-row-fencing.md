# Fencing the user data export row

Status: approved 2026-08-15, corrected on eleven points the six spec angles falsified and on five the
implementation falsified (all marked below).
Tier: Spec. Branch: `fix/export-row-fencing`. Decision record: `docs/adr/0016-fence-by-compare-and-set.md`.

## 1. Goal

A write to the export row never restores a state another actor committed in between, and a delete
the user asked for is never silently lost.

## 2. The defect

`Persistor.merge` writes every column, and no export model carries a version. So
`exportRepository.save(export.copy(...))`, where `export` was read earlier, restores every column
that copy carried, including the state another actor committed since.

The export row has five unfenced writes and four concurrent writers: the owner's request, the worker
building the archive, the retention sweep, and the requester that supersedes a previous export. Their
windows overlap by construction, because a build takes minutes on a multi-gigabyte account and a
`DELETE` is one click.

`UserDataExportBuilder.publish` already fences, and `build`'s KDoc names the danger: "a build racing
a cancellation or account deletion can never resurrect a row the user was told was gone". The other
five writes do not. This lot closes them with the same shape.

## 3. The five sites

| # | Site | Read at | Window between read and write |
|---|---|---|---|
| 1 | `UserDataExportBuilder.build`, stamping `storageKey` | `build`'s `findById` | `requireUser`, `requireFreeSpace` |
| 2 | `UserDataExportBuilder.markFailed` | same `findById` | up to the whole archive build: two pin walks, every image streamed |
| 3 | `UserDataExportDeleter.delete`, `PENDING` to `DELETED` | `getter.get` | `cancelTask.cancel` |
| 4 | `UserDataExportDeleter.delete`, `READY` to `DELETED` | `getter.get` | `archiveStore.delete`, a disk operation |
| 5 | `ReapExpiredUserDataExports.reapOne`, `READY` to `EXPIRED` | the batch `findExpiredReadyExports` | every earlier item of the batch, plus this item's `deleteQuietly` |

Site 2 carries the widest **unfenced** window in the repository; the import's comparable windows are
wider still and are already fenced. Site 5 is second, because the batch is read once and the rows are
written one by one afterwards.

### What the loss destroys

**A deleted export comes back downloadable.** Site 1 restores `PENDING` on a row the user deleted,
and that resurrection then **defeats `publish`'s own fence**, which tests for exactly that state. The
build completes, the row is written `READY` with live bytes, and a full archive of the account is
served again until retention expires. *(Corrected: the draft claimed a permanent lock-out on the
`uq_user_data_exports_pending` slot. The lock-out is real but transient, since a second `DELETE` is
honoured; the resurrection to `READY` is the worse and the load-bearing harm.)*

**An archive outlives the account that requested erasure.** The same resurrection under
`AccountDeletionCleaner` leaves a row and an archive for a hard-deleted user. `ReapOrphanedStorage`
keys on row *absence*, so the row blinds it; `ReapTombstonedAccounts` cannot re-drive, the cleaner
returning early once the user row is gone. Nothing reclaims the bytes before `expiresAt`. Foreign
keys do not stop it, enforcement being off on this datasource. *(Corrected: the draft attributed the
re-insert to site 1. `UserDataExportRepository.save` branches on state, and a `PENDING` save resolves
the account through `ActiveUserModels.resolve`, which throws when the user is gone. The re-insert
belongs to sites 2 to 5, which take `persistor.reference`.)*

**A history entry the user cannot explain.** Site 2 writes `FAILED` over a `DELETED` row, so a
deleted export reappears as failed and `isGone` turns false: a download answers `EXPORT_NOT_READY`
where it answered `EXPORT_GONE`. *(Corrected: `FAILED` sits outside the partial index, so site 2
never holds the pending slot.)*

*(Removed: the draft's third harm, sites 4 and 5 racing each other. Neither clears `storageKey` and
both write `isGone` states, so the loser's restore changes nothing a client can observe.)*

## 4. The remedy

`ExportAccess.saveFenced` and `ExportAccess.saveFencedOver`, the mirrors of `imports/ImportAccess`:
read the row, test a predicate, and apply the update **to the row just read**, all inside one
transaction. A refused predicate answers `null` and writes nothing.

Three properties the predicate alone does not carry, and that the shape must:

- **A missing row refuses.** `merge` is an upsert, so a fence that evaluates its predicate against
  the copy read earlier writes a deleted row back into existence while satisfying every stated
  predicate. `findById(id)?.takeIf(held)?.let { save(update(it)) }` is what closes the
  account-deletion harm, not the state test.
- **The update applies to the re-read row.** A fence that tests the fresh row and then writes
  `export.copy(...)` built from the stale one reintroduces the whole defect on the accept path.
- **The release arm comes from the row the write replaced**, not from the row read before the fence,
  which may be one state old. This is what `saveFencedOver` is for.

Two constructors gain a `TransactionRunner` (`UserDataExportDeleter`, `ReapExpiredUserDataExports`),
and `ExportProducers.reapExpiredUserDataExports` passes it. Both stay inside detekt's six-parameter
bound.

Not optimistic locking: `docs/adr/0016` records that choice and the correction behind it.

## 5. Per site: predicate, refusal, and what is logged

| # | Predicate | On refusal | Logged |
|---|---|---|---|
| 1 | `state == PENDING` | Return from `build` before staging. No task failure is raised, and the task settles `SUCCEEDED`: nothing was left half-done, and a retry would find the same row. | One line at INFO: export id, expected `PENDING`, state observed. |
| 2 | `state == PENDING` | Write nothing. The original error is rethrown as before, so the queue's retry and dead-lettering are untouched. | One line at INFO, same shape. |
| 3+4 | `!state.isGone`, one fence for both arms | Write nothing: the export was already gone. | Nothing. The caller gets its answer over HTTP. |
| 5 | `state == READY` | Write nothing; the per-item `try`/`catch` still isolates the batch. | One line at INFO, same shape. |

**Sites 3 and 4 become one fenced write.** `UserDataExportDeleter` fences once on `!state.isGone`,
writes `DELETED`, and picks its release arm from the state `saveFencedOver` returns: `PENDING`
cancels the task, `READY` deletes the bytes. This is `UserDataImportCanceller`'s shape exactly.

*(Corrected: the draft kept two state-specific predicates and branched before the fence. Three angles
independently showed the same regression: the row moves `PENDING` to `READY` between `getter.get` and
the write, both predicates refuse, and `MeExportController` answers `204` on an export that stays
`READY` and downloadable. Today's unfenced write honours the user's intent; the draft would have
broken it.)*

**The row moves before the bytes**, reversing today's order at sites 4 and 5. The reason today's
comment gives for the old order (a disk failure must reach the caller) survives: the exception still
propagates. What changes is the state left behind by such a failure, and the new one is strictly
better at site 5, where a write that fails after a *successful* delete currently leaves a `READY` row
naming bytes that are gone, and a download answering `500` instead of `410`. *(Corrected after the
holistic review: the draft blamed a failed `deleteQuietly`, which cannot be the cause since it
swallows and the write ran regardless.)* The cost is the mirror case: a `DELETED` or
`EXPIRED` row whose bytes survive a failed delete, which no sweep reclaims today because
`ReapOrphanedStorage` keys on row absence. §8 carries it as an accepted limit and a backlog item.

**Site 5's accounting changes.** `reapOne` answers a `Boolean` and `reap()` returns rows *moved*, not
rows *selected*, matching `ReapAbandonedUserDataImports`. Without it a fully-refused sweep and a
fully-successful one return the same number.

## 6. Acceptance criteria

Each is an observable, not an instrument. `S` is the state a racing actor commits inside the window.

| # | Given | When | Then |
|---|---|---|---|
| A1 | A `PENDING` export, `S = DELETED` committed after `build`'s read | the build runs | the row still reads `DELETED`, no archive is staged or promoted, the task settles `SUCCEEDED`, one INFO line names the export |
| A2 | Same, `S = row absent` (account deleted) | the build runs | no row exists afterwards |
| A3 | A `PENDING` export, nothing racing | the build runs | the row reads `READY` with `storageKey`, `byteSize` and `sha256` set |
| A4 | A `PENDING` export, `S = DELETED`, staging throws, `isLastAttempt` | the build runs | the row still reads `DELETED`, not `FAILED`; the staging error is rethrown |
| A5 | A `PENDING` export, staging throws, `isLastAttempt`, nothing racing | the build runs | the row reads `FAILED` with `failureCode = BUILD_FAILED` |
| A6 | A `PENDING` export, `S = READY` committed after `getter.get` | the owner deletes it | the row reads `DELETED` and the archive bytes are gone |
| A7 | A `READY` export, `S = EXPIRED` | the owner deletes it | the row still reads `EXPIRED`, and the bytes the sweep already released are not deleted a second time. *(Corrected after implementation: the draft wanted `DELETED` here, which contradicts this document's own predicate. `EXPIRED` satisfies `isGone`, so the fence refuses. The state that says why the archive is gone outranks the one the request asked for, and both are `isGone` to a client.)* |
| A7b | A `FAILED` export | the owner deletes it | the row reads `DELETED` and nothing is released |
| A8 | A `DELETED` export | the owner deletes it | the row still reads `DELETED`, the task is not cancelled a second time |
| A9 | An expired `READY` export, `S = DELETED` | the sweep runs | the row still reads `DELETED`, `reap()` counts 0, one INFO line names the export |
| A10 | Two expired `READY` exports, one raced | the sweep runs | the unraced row reads `EXPIRED`, `reap()` counts 1 |
| A11 | Any fenced write that is accepted while a non-state column moved | the write runs | the accepted write carries the moved column, not the value read before the fence |

`S` ranges over the states each site can actually meet: for `state == PENDING`, that is `READY`,
`FAILED`, `DELETED`, `EXPIRED`, `SUPERSEDED` and row-absent. A single-state refusal test cannot tell
the specified predicate from a looser one.

## 7. What a client observes

**Declared surface: unchanged.** No status, field or endpoint moves. Provable by
`git diff --exit-code docs/openapi.json`, which CI enforces independently.

**Observed behaviour, three changes**, all in the user's favour:

- An export deleted while it is being built stays deleted, and stays undownloadable.
- A `DELETE` crossing `PENDING` to `READY` releases the archive it finds, rather than cancelling a
  task and leaving the bytes.
- **A `DELETE` whose disk release fails now leaves the row `DELETED` and still answers `500`.** Before
  the lot the row stayed `READY`, so the error and the row agreed. They no longer do, which is the
  cost of decision 4 of the ADR. What the lot restores in exchange is the repair: a replayed `DELETE`
  meets the refused fence, sees a row that was already gone, and releases the bytes it names. Without
  that arm the first `500` would have been terminal, the replay answering `204` and touching nothing.
  *(Added after the holistic review, which found the lost repair rather than the status mismatch.)*
- **A `DELETE` on a `FAILED` export now marks it `DELETED`**, where
  `docs/specs/2026-07-22-user-data-export.md` declared a no-op for terminal states. `FAILED` is not
  `isGone`, so the phase-agnostic predicate this document mandates lets it through. The alternative
  was a predicate enumerating the live states, which a future state would silently fall out of. The
  no-op was the surprising half: a failed export could not be cleared from the history at all.
  *(Added after implementation: the draft counted two changes and missed this one, which follows from
  its own predicate.)*

## 8. Out of scope, accepted limits

- **Two attempts of the same build overlapping.** `tasks.lease_duration` is `PT1M` and the final
  stretch of a build renews nothing: the manifest write, the channel force on a multi-gigabyte ZIP
  and the promote. `EbeanTaskQueue.reapExpired` returns the task to `PENDING`, a second worker claims
  it, and both attempts read `PENDING`, so **both pass sites 1 and 2**. Both promote onto the same
  key, and the loser's `publish` fence then deletes the winner's bytes: a `READY` row with no
  archive, invisible to `ReapOrphanedStorage`. Two angles reached this independently. It is not
  closed here: the fix is a claim token on the export row, as `UserDataImportRunner.runToken` is on
  the import, which means a column and a migration, and that is a different tier of lot. Filed to
  `docs/backlog.md` with this scenario. Related and filed with it: `TaskContext.renewLease` is typed
  `() -> Unit`, so a handler is never told its lease is gone.
- **An export stuck `PENDING` for good.** `EbeanTaskQueue.claimNext` kills an attempts-exhausted task
  inline without invoking the handler, so `markFailed` never runs, and no sweep selects a `PENDING`
  export. The import has `failInterruptedRuns` for exactly this; the export has no twin. Filed.
- **Bytes surviving a failed delete under an `isGone` row**, per §5, narrowed by the replay arm: an
  owner who deletes again repairs it, and the expiry sweep, whose `deleteQuietly` swallows and which
  nobody replays, does not. Filed with the sweep above, and with the adjacent case this lot had no
  mandate to touch: superseding an export nulls its key inside the transaction and releases the bytes
  best-effort outside it, so a swallowed failure there strands an archive that no state names and
  `ReapOrphanedStorage` cannot find, its row still existing.
- **The detekt rule's reach.** Widening `ImportStateMergedOutsideTransaction` to the exports is one
  line in `config/detekt/detekt.yml`; renaming it, which its own KDoc anticipates, touches eight
  files. The split is for the rename's blast radius, not a necessity. It ships in the next pull
  request, and until it does the `exports` package has no static guard on the shape.
- **`PinModel` and `BoardModel`**, whose exposure needs two simultaneous requests from the same owner
  and whose loss the user repairs by repeating the action. They stay in the backlog.
- **The seven entities with no dangerous pair**: `TagModel`, `SessionTokenModel`,
  `UserPasswordHashModel`, `UserDataImportIssueModel`, `UserModel`, `ImageModel`,
  `ImageDownloadModel`. Insert-only, single-actor, or already compare-and-set. The two link tables
  are not counted, having no state to restore.
- **The tests pin the code's shape, not the database's isolation.** `PassthroughTransactionRunner`
  opens no transaction; it counts. Real exclusion comes from the single SQLite connection and is
  reachable only from `api-persistence-sqlite`.
- **The INFO line a refusal writes is asserted nowhere.** `api-usecases` binds `slf4j-nop` at test
  runtime by an explicit decision recorded in its build file, so a test captures no output. The line
  is written where the state that took the window is visible; pinning it needs either a different
  test-runtime binding or a logging seam, and neither is worth its change here. Criteria A1 and A9
  name the line as an observable and it is the one half of them the suite does not hold.

## 9. Tests

Five refusal paths, but a shared `saveFenced` yields four branches: three in the helper and one at
site 1's `?: return`. Sites 2 to 5 discard the result, so **the 100% bound forces one of the five**.
The other four are held by this document, not by the gate. They are named here so a reviewer can
count them: `UserDataExportBuilderTest` (A1 to A5), `UserDataExportDeleterTest` (A6 to A8),
`ReapExpiredUserDataExportsTest` (A9, A10), and A11 once per fenced helper.

What the fixtures need, none of which the export suites have today:

- **A row store**, so a refusal is asserted as "the row still reads `DELETED`" rather than as
  `verify(exactly = 0) { save(any()) }`, which asserts a call and not an outcome. Shape at
  `ReapAbandonedUserDataImportsTest`.
- **A racing answer keyed on the transaction, not on the call ordinal.** `returnsMany` answers by
  call index, so a build that re-reads twice and only then opens a transaction passes identically to
  a fenced one. The `reread` hook plus `PassthroughTransactionRunner.inside`
  (`UserDataImportRunnerFixtures`) is what discriminates.
- **`PassthroughTransactionRunner.current` recorded per call**, which is the instrument that pins
  read and write to the same transaction. *(Corrected: the draft attributed this to
  `UserDataImportRunnerTest`'s fence test, which records nothing and poisons a read instead. The
  recording test is the one covering the tag path.)*
- The runner is `internal` to the imports test package and `api-utilities` testFixtures cannot host
  it (no `api-domain` dependency), so the export suites import it across packages.

`UserDataExportBuilderTest` is 506 lines against detekt's 600-line bound, so the fixtures split into
a small shared file, as the import suite's did. *(Corrected after implementation: the draft also
blamed `stubBuildFailure` for stubbing a `save` the refusal path never reaches. The reverse is true.
Site 2's refusal does reach `save`, because site 1 stamps the key before staging is attempted; the
path that writes nothing at all is site 1's. The split was still needed, for the line bound and for
stub granularity. Moving `RecordingSink` also invalidates its detekt baseline entry, baseline ids
carrying the file name, so its KDoc was shortened and the entry deleted rather than re-pointed.)*

Site 1's fence and the pre-existing `state != PENDING` check at `build`'s entry are two branches
pinning two different windows. Neither is a duplicate of the other. detekt's `ReturnCount` (two, with
no guard-clause exemption) refuses a third `return` in `build`, so the entry guard moves into its own
function.

*(Corrected after implementation: the bound forces more than one refusal of five. `saveFencedOver` is
a second helper carrying its own three branches, both arms of each predicate function are counted,
and the deleter's `else` arm is one more; conversely sites 1 and 2 share one predicate function, so a
single enumeration pins both. A consequence for any future plan: `saveFencedOver` cannot ship in a
task of its own, since a helper with no caller fails the package's bound. Section 3's site 2 is also
three call sites rather than one, `requireUser`, `requireFreeSpace` and `stageOrFail` all reaching
`markFailed`, and only the third carries the window the table describes.)*
