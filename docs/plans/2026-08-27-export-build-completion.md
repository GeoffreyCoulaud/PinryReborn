# Plan: export build completion

Date: 2026-08-27
Spec: `docs/specs/2026-08-27-export-build-completion.md`
ADR: `docs/adr/0017-promote-inside-the-publishing-transaction.md`
Branch: `fix/export-build-completion`

Sixteen tasks in seven blocks. A block holds tasks that do not depend on each other's result, so each
block's review can read a frozen range while the next one is built.

**Every task is red then green, in two commits.** The failing test is committed alone as
`test(scope): <behaviour>` with the pasted failure in its body, then the implementation. An
unresolved-reference compile failure is a valid red, per `agents/engineering.md`. A build-file change
never goes in a red commit.

**Every task ends with `./gradlew gate`**, except the red commits, which run only the test they name.

**No task touches the schema.** That is the lot's headline and its check: `git diff -- '*dbmigration*'`
stays empty from first commit to last, and `generateDbMigration` is never run. Any task that finds
itself wanting a column has hit something the spec did not foresee and stops for arbitration.

**The coverage bound is per package, and this lot opens branches in five**: `usecases.exports`,
`usecases.imports`, `domain.enums`, `domain.tasks`, `persistence.sqlite.repositories`. A task's green
commit covers its own package; no task defers coverage to a later one.

**Three existing assertions are the red this lot starts from**, and they are named in the tasks that
invert them: `UserDataExportBuilderTest.kt:409` and `:424` (both assert the canonical-key delete that
task 8 forbids), and `UserDataExportRequesterTest.kt:229` (asserts the null key that task 9 keeps).

**`BaseTest.checkUnnecessaryStub()` fails an unmet stub**, so `stubHappyPathBuild()` in
`UserDataExportBuilderFixtures` must split once the refusal path stops calling `promote`. Task 8 owns
that split, following the precedent of `stubBuildFailure`.

**Logging is not deferred.** Task 14 carries the per-pass counts the spec's section 10 asks for; no
earlier task leaves a silent sweep behind on the promise that a later one will speak.

---

## Block 1: the shared vocabulary

Five tasks, mutually independent: two domain predicates, one key object, one port KDoc, one
configuration pair. Nothing here reads anything else here.

### 1. A terminal export state

**Files.** `api-domain/.../enums/UserDataExportState.kt`, `api-domain/src/test/.../UserDataExportStateTest.kt`.

**What.** `isTerminal` = `FAILED`, `EXPIRED`, `DELETED`, `SUPERSEDED`, enumerated positively so a state
added later is neither terminal nor live and the partition test fails rather than silently admitting
it. `isGone` is expressed through it rather than beside it, and its KDoc stops saying "the archive
bytes no longer exist", which task 9 makes false for `SUPERSEDED`.

**Acceptance.**
- All six states asserted against `isTerminal`, and the relation to `isGone` pinned. `READY` and
  `PENDING` are the two that must answer false: an implementer who admits `READY` makes task 11
  delete live archives.
- `./gradlew gate`.

### 2. A live task attempt

**Files.** `api-domain/.../tasks/TaskState.kt`, `api-domain/src/test/.../tasks/TaskStateTest.kt`,
`api-usecases/.../imports/ReapAbandonedUserDataImports.kt` and its test.

**What.** `isLiveAttempt` = `PENDING` or `RUNNING`. `ReapAbandonedUserDataImports` drops its private
`LIVE_ATTEMPT_STATES` and reads the shared predicate, so the value has one source. The import spec
records having once spelled this "dead or absent", which took a `SUCCEEDED` task for a live one.

**Acceptance.**
- All five states asserted. `SUCCEEDED` and `CANCELLED` answer false.
- The import sweep's existing tests stay green unchanged, which is what proves the substitution is
  behaviour-preserving.
- `./gradlew gate`.

### 3. One derivation of the export archive key

**Files.** New `api-usecases/.../exports/ExportArchiveKey.kt` and its test,
`api-usecases/.../exports/UserDataExportBuilder.kt`, `api-usecases/.../AccountDeletionCleaner.kt`,
their tests.

**What.** Mirror `imports/ImportArchiveKey.kt`: one object, one function, a KDoc stating the property
the ADR makes load-bearing. The export takes the extension from the archive format where the import
hard-codes `.zip`, so the signature is `forExport(exportId, fileExtension)`. `storageKeyFor` and
`exportStorageKey` are deleted in favour of it.

**Acceptance.**
- The two existing call sites produce byte-identical keys before and after, which their existing tests
  already assert.
- `ReapOrphanedStorage.parseId` round-trips a key this object produces. That pairing is what the ADR
  calls load-bearing and nothing tests it today.
- `./gradlew gate`.

### 4. The port promises idempotent deletion

**Files.** `api-domain/.../exports/ExportArchiveStore.kt`.

**What.** KDoc on `delete`: deleting a key whose bytes are already gone is not an error. Task 11's
convergence depends on it, the adapter already behaves this way, and
`FilesystemZipExportArchiveStoreTest.kt:166-176` already pins it.

**Documentation only, so the TDD order does not apply** (`agents/engineering.md`: exemptions are on
the order, never on the safety net; the behaviour is already covered).

**Acceptance.** `./gradlew gate`.

### 5. Two configuration keys

**Files.** `api-worker-quarkus/.../ExportsConfig.kt`, its test,
`api-application/src/main/resources/application.properties` if a non-default value is wanted (it is
not).

**What.** `interruptedGrace` defaulting to `PT15M`, comfortably past `lease_duration x max_attempts`,
and `sweepBatchSize` defaulting to `500`, the figure `garbage-collection.orphan_batch_size` uses. Both
in the existing `@WithDefault` style.

**Acceptance.**
- The defaults are asserted where the config's other defaults are.
- `./gradlew gate`.

---

## Block 2: what the sweep will read, and the precondition the build needs

Two tasks, mutually independent. Task 6 depends on block 1's task 1; task 7 depends on nothing but is
the precondition task 8's design rests on, so it lands before it.

### 6. Two selections on the export repository

**Files.** `api-domain/.../repositories/UserDataExportRepositoryInterface.kt`,
`api-persistence-sqlite/.../repositories/UserDataExportRepository.kt`, its test.

**What.** `findPending(limit)` and `findReclaimableTerminal(limit)`. Named after the state, following
the import twin's `findRunning()` rather than inventing an entity suffix. `findReclaimableTerminal`
selects terminal states with a non-null `storage_key`; the state set comes from task 1's predicate,
not from a literal. Both take the batch bound from task 5, applied by the caller.

**Acceptance.**
- Each selection exercised over all six states plus a terminal row with a null key. The precedent is
  `UserDataImportRepositoryTest.kt:273-287`, "one row per state, so a predicate widened by accident is
  caught here": a single-state test cannot tell the specified predicate from a looser one.
- The bound is honoured: more rows than the limit yields the limit.
- `./gradlew gate`.

### 7. Staging and archives share a filesystem, or the application refuses to start

**Files.** `api-storage-filesystem/.../FilesystemZipExportArchiveStore.kt` or its paths collaborator,
its test, and the wiring that runs the check.

**What.** At startup, resolve the `FileStore` of the staging directory and of the archive directory
and refuse to start when they differ, with a message naming both paths and why. `DataDirPaths.atomicMove`
falls back to a full byte copy when `ATOMIC_MOVE` is unsupported, and task 8 puts that call inside a
transaction holding the process's only connection.

**Acceptance.**
- Two different stores refuse, and the message names both directories.
- One store starts.
- The failure is loud at boot, never a warning that lets the instance serve.
- `./gradlew gate`.

---

## Block 3: the build completes or fails, and nothing else

Two tasks, mutually independent: one rewrites the build's tail, one stops the requester nulling a key.
Task 8 depends on block 1's task 3 and block 2's task 7.

### 8. The promote joins the publish, and the failure net covers both

**Files.** `api-usecases/.../exports/UserDataExportBuilder.kt`,
`api-usecases/src/test/.../exports/UserDataExportBuilderTest.kt`, `.../UserDataExportBuilderFixtures.kt`.

**What.** One task, not two, because both changes rewrite the same six lines of `build`: splitting them
would have the second rewrite the first. The promote moves inside the transaction that re-reads the
row and tests `PENDING`; on refusal the attempt discards its own staged file and touches no key. The
`try` extends over the completion, discarding the staged file on **every** attempt and marking `FAILED`
on the last, which is step 8 of `docs/specs/2026-07-22-user-data-export.md:386`, all three clauses.
`requireUser` and `requireFreeSpace` stay outside it.

**Acceptance.**
- The refusal arm: **`promote` is never called** and the canonical key is never deleted. This is the
  pin, not a co-transaction assertion: `PassthroughTransactionRunner.current` records the outermost
  open transaction and cannot see ordering within it, so an implementation that promotes first and
  tests the predicate after would record the same number and pass.
- The complement, recorded through a new list in the fixtures: the promote and the row write share a
  transaction number.
- The rival is installed through the `reread` hook keyed on `stageCalls > 0`, never on a call ordinal:
  the hook also fires on `stampStorageKey`'s own fenced read, and a rival landing there exits the
  build before it stages.
- `UserDataExportBuilderTest.kt:409` and `:424` invert: `discard`, not `delete(storageKey)`.
- Promote throws on the last attempt: `FAILED` / `BUILD_FAILED`, and `discard` was called. Publish
  throws on the last attempt: same. Promote throws on a non-final attempt: `discard` called, row still
  `PENDING`.
- `stubHappyPathBuild()` splits so the refusal path stubs no `promote`.
- `./gradlew gate`.

### 9. A superseded export keeps naming its bytes

**Files.** `api-usecases/.../exports/UserDataExportRequester.kt`, its test.

**What.** `createPending` stops nulling `storageKey`. The best-effort `deleteQuietly` outside the
transaction stays: it is the fast path, and a multi-gigabyte archive must not wait for a sweep. The
comment at `:49-52` loses its false last sentence and shortens.

**Acceptance.**
- `UserDataExportRequesterTest.kt:229` inverts: the superseded row names the key of the bytes it
  released.
- A `deleteQuietly` that throws still returns the new export, unchanged.
- `./gradlew gate`.

---

## Block 4: the two new passes

Two tasks, mutually independent: two new methods on the same class, neither reading the other. Both
depend on blocks 1 and 2.

### 10. Interrupted builds are failed, once they are certainly not coming back

**Files.** `api-usecases/.../exports/ReapExpiredUserDataExports.kt`, its test,
`api-application/.../wiring/ExportProducers.kt`.

**What.** `failInterruptedBuilds`: a `PENDING` export whose task is absent or not `isLiveAttempt`, and
whose `requestedAt` is older than `interruptedGrace`, is fenced to `FAILED` with
`failureCode = "EXPORT_INTERRUPTED"`. The grace is what makes this safe: `EbeanTaskQueue.claimNext:112-120`
moves a task to `DEAD` inline the moment its attempts are spent, without regard for handlers still
running, so without the grace this pass writes `FAILED` under up to three live builders and each of
them then discards a complete archive. The class gains `TaskQueueInterface`, so `ExportProducers`
gains the `@Suppress("LongParameterList")` its neighbours carry.

**Acceptance.**
- One case per branch, following the twin's five (`ReapAbandonedUserDataImportsTest.kt:198`, `:216`,
  `:234`, `:272`, `:292`): `taskId` null; task absent from the queue; task `DEAD`; task `SUCCEEDED`;
  task `CANCELLED`. The last two are what "dead or absent" would miss.
- Task `PENDING` and task `RUNNING`: nothing written.
- Row younger than the grace: nothing written, whatever its task says.
- Fence refused because the row moved to `DELETED` between selection and write: nothing written.
- `./gradlew gate`.

### 11. Terminal rows stop naming bytes that exist

**Files.** `api-usecases/.../exports/ReapExpiredUserDataExports.kt`, its test.

**What.** `reclaimTerminalArchives`: for each terminal row that still names a key, delete the bytes at
`ExportArchiveKey.forExport(id)` **first**, propagating, then fence on `isTerminal` and clear the
column. The column drives selection so the pass converges; the deletion targets the derived key, as
the import twin does, so a row whose column disagrees with the derivation does not keep its bytes for
good. The reverse of `docs/adr/0016` decision 4 on purpose: stamping over a failed delete hides the
residue from the only pass that can name it.

**Acceptance.**
- The bytes go before the column is cleared, asserted on order, not just on both happening.
- A delete that throws leaves the column set, so the next run retries, and the row is not counted.
- Fence refused: nothing written, key kept.
- A terminal row with a null key is never selected.
- `./gradlew gate`.

---

## Block 5: the sweep becomes one rule

Three tasks. Task 12 depends on block 4's task 11 existing, or the bytes it stops deleting are deleted
by nobody. Tasks 13 and 14 depend on both new passes.

### 12. Expiry writes a state and nothing else

**Files.** `api-usecases/.../exports/ReapExpiredUserDataExports.kt`, its test.

**What.** `expire` drops its `deleteQuietly`. One rule replaces two: a state transition writes the
state, and reclaiming bytes is task 11's job for every terminal state. Without this, pass 3 reselects
every row pass 2 just expired, in the same run, counts it twice and reissues the delete.

**Acceptance.**
- Expiry no longer deletes, and the row still loses its bytes within the same `reap()` because task
  11's pass runs after it.
- The existing expiry tests state what they still assert once all three passes run.
- `./gradlew gate`.

### 13. Both new selections are bounded

**Files.** `api-usecases/.../exports/ReapExpiredUserDataExports.kt`, its test, `ExportProducers.kt`.

**What.** Task 5's `sweepBatchSize` is passed to the two new selections. The first run after deployment
is a backlog catch-up, not a steady state: every terminal row today keeps its key on purpose, so pass
3's first selection is the whole history of terminal exports. It converges over successive ticks, and
the class KDoc says so.

**Acceptance.**
- More reclaimable rows than the bound yields the bound, and the next run takes the next batch.
- `./gradlew gate`.

### 14. The sweep speaks, and cannot fail the boot

**Files.** `api-worker-quarkus/.../ExportRetentionLifecycle.kt`, its test,
`api-usecases/.../exports/ReapExpiredUserDataExports.kt`.

**What.** `start()` calls `safeReap()` rather than `reap()` bare: with three passes added, one disk
refusing one delete would otherwise fail the boot, and nothing about reclaiming residue should stop
the API serving. One log line per sweep carrying a count per pass, so an operator whose exports all
turn `EXPORT_INTERRUPTED` overnight has something to read; `reap()` keeps returning rows acted on, a
row acted on twice counting twice, which is the twin's accounting and is now written in its KDoc.

**Acceptance.**
- A sweep that throws at startup leaves the application started.
- The periodic path is unchanged.
- `ImportLifecycle` has the same bare-`reap` defect; it is **not** fixed here, it is filed. Named in
  the commit so the next reader knows it was seen.
- `./gradlew gate`.

---

## Block 6: what a delete releases

One task, depending on blocks 1 and 3.

### 15. Deleting a pending export releases the residue

**Files.** `api-usecases/.../exports/UserDataExportDeleter.kt`, its test.

**What.** The `PENDING` arm deletes `ExportArchiveKey.forExport(id)`, best-effort. Its current reason
for releasing nothing is that a build between its promote and its publish will meet the `DELETED` row
at its own fence and delete the bytes itself; after task 8 that build never promoted, so the reason is
gone, and the residue ADR 0017 decision 2 admits would otherwise survive a `204` the user reads as
erased. Best-effort because this is residue cleanup, not the primary operation, unlike the `READY` arm
which propagates. The KDocs of `release`, `releaseStranded` and the class change in the same commit:
`releaseStranded` loses its exclusivity, not its purpose, and its "never clears its key" narrows to
the path it describes.

**Acceptance.**
- The `PENDING` arm deletes the derived key; a delete that throws there does not fail the request.
- The `READY` arm still propagates.
- No window against a concurrent attempt, argued in the commit message from the serialisation, since
  no test in this module can show it.
- `./gradlew gate`.

---

## Block 7: end to end

One task, depending on everything.

### 16. The integration cases

**Files.** `api-application/src/test/.../MeExportCompletionIntegrationTest.kt`.

**What.** Three cases joining the existing class, never a new `@QuarkusTest`.

**Acceptance.**
- Criterion 6: a superseded export whose archive delete failed loses its bytes and its key at the next
  sweep.
- Criterion 4: an export whose task is `DEAD` reads `FAILED` / `EXPORT_INTERRUPTED` after the sweep,
  and the next `POST` is not `409`. Reached through a **task of a kind with no registered handler**,
  which `TaskProcessor:37-39` marks `DEAD` on its own: seeding a row with no task gives "absent", not
  `DEAD`, and forcing a real `DEAD` races the live worker.
- Criterion 7: bytes promoted, publish rolled back, row terminal, sweep run, file gone. This is the
  residue ADR 0017 decision 2 admits, and no use-case test can construct it because
  `PassthroughTransactionRunner` never rolls back.
- `./gradlew gate`.

---

## What this plan does not settle

- **The heartbeat's return value**, removed from the lot by the spec's section 8 and rewritten as a
  backlog item with its real reach: both handlers, the exception type, and its exclusion from task 8's
  widened net.
- **The rename of `ReapExpiredUserDataExports`**, which will run three passes under a name that says
  one. The class KDoc is rewritten in task 13; the rename is a backlog item.
- **Widening `ImportStateMergedOutsideTransaction` to the `exports` package**, which this lot makes
  more valuable by adding fenced writers to an unguarded package, and which is not the one-line change
  the backlog claims.
- **`SUPERSEDED` rows already written with a null key**, invisible to both sweeps while the account
  lives, reclaimed only by account erasure.
- **`ImportLifecycle`'s bare startup sweep** and the wrong pass named in
  `ReapAbandonedUserDataImports`'s class KDoc: both found here, both filed.
- **Correcting `docs/adr/0016:44-45`**, which attributes the single-connection premise to an ADR that
  does not contain it. A delivered ADR is not rewritten; ADR 0017 carries the correction.
