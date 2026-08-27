# Plan: export build completion

Date: 2026-08-27
Spec: `docs/specs/2026-08-27-export-build-completion.md`
ADR: `docs/adr/0017-promote-inside-the-publishing-transaction.md`
Branch: `fix/export-build-completion`
Revised: 2026-08-27, after the three plan angles. What changed is listed at the bottom.

Fourteen tasks in six blocks. A block holds tasks that do not depend on each other's result, so each
block's review can read a frozen range while the next one is built.

**Every task is red then green, in two commits.** The failing test is committed alone as
`test(scope): <behaviour>` with the pasted failure in its body, then the implementation. An
unresolved-reference compile failure is a valid red (`agents/engineering.md:222`). A build-file change
never goes in a red commit.

**Every task ends with `./gradlew gate`, green.** No task leaves the suite red for a later one to
repair. This is what forced tasks 10 to 12 of the first draft into a single task here: they share
`reap()` and `stubSweep()`, and any one of them alone leaves the seven existing sweep tests failing.

**No task touches the schema.** `git diff -- '*dbmigration*'` stays empty from first commit to last,
and `generateDbMigration` is never run. A task that finds itself wanting a column has hit something
the spec did not foresee and stops for arbitration.

**The coverage bound is per package, and this lot opens branches in five**: `usecases.exports`,
`domain.enums`, `domain.tasks`, `persistence.sqlite.repositories`, and `worker` (task 8's startup
check). `usecases.imports` is **not** among them: task 2 removes a branch there rather than adding
one.

**Three existing assertions are the red this lot starts from**: `UserDataExportBuilderTest.kt:420`
and `:436` assert the canonical-key delete that task 9 forbids, and
`UserDataExportRequesterTest.kt:229` asserts the null key that task 10 keeps.

**`checkUnnecessaryStub()` is mockk's, called by `BaseTest` in an `@AfterEach`**, and it fails a stub
no test reached. Every task that removes a call path must also remove the stub that fed it. The
fixture ladder to extend is `stubBuildEntry` / `stubBuildToStaging` / `stubHappyPathBuild` /
`stubFailingStage` (`UserDataExportBuilderFixtures.kt:217`, `:247-267`).

---

## Block 1: the shared vocabulary and the test instrument

Six tasks, mutually independent: two domain predicates, one key object, one port KDoc, one
configuration pair, one fixture. Nothing here reads anything else here.

### 1. A terminal export state

**Files.** `api-domain/.../enums/UserDataExportState.kt`, its test.

**What.** `isTerminal` = `FAILED`, `EXPIRED`, `DELETED`, `SUPERSEDED`, enumerated positively so a state
added later is neither terminal nor live and the partition test fails rather than silently admitting
it. `isGone` is expressed through it. Its KDoc stops saying "the archive bytes no longer exist", which
task 10 makes false for `SUPERSEDED`; the KDoc change lands here because the predicate is here, and
the commit message names task 10 as the reason.

**Acceptance.**
- All six states asserted against `isTerminal`, and its relation to `isGone` pinned. `READY` and
  `PENDING` answer false: an implementer who admits `READY` makes task 11's pass 3 delete live
  archives.
- `./gradlew gate`.

### 2. A live task attempt

**Files.** `api-domain/.../tasks/TaskState.kt`, its test,
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

**Files.** New `api-usecases/.../exports/ExportArchiveKey.kt`,
`api-usecases/.../exports/UserDataExportBuilder.kt`, `api-usecases/.../AccountDeletionCleaner.kt`,
`api-usecases/.../ReapOrphanedStorage.kt` and its test, plus the two consumers' tests.

**What.** Mirror `imports/ImportArchiveKey.kt`. The signature is **`forExport(exportId, fileExtension)`**,
two parameters, everywhere: the import hard-codes `.zip` where the export takes the extension from
`archiveStore.format`. Both existing call sites already read `archiveStore.format.fileExtension`, and
so must tasks 11 and 12. The KDoc states the property ADR 0017 makes load-bearing, in the ADR's
corrected wording: the key is knowable without reading the row.

**Acceptance.**
- The two existing call sites produce byte-identical keys, which their existing tests already assert.
- `ReapOrphanedStorageTest`'s hand-written literals (`:80`, `:121`, `:184`) are replaced by calls to
  `ExportArchiveKey`, so the round trip derivation-then-parse is exercised through `reap()`. `parseId`
  is private and stays private; the pairing is pinned through the public path, which is what the ADR
  calls load-bearing and what nothing tests today.
- No dedicated test asserting the literal alone: that would restate the implementation. The import
  twin has none either, for the same reason.
- `./gradlew gate`.

### 4. The port promises idempotent deletion

**Files.** `api-domain/.../exports/ExportArchiveStore.kt`.

**What.** KDoc on `delete`: deleting a key whose bytes are already gone is not an error. Task 11's
pass 3 converges on it, the adapter already behaves this way
(`FilesystemZipExportArchiveStore.kt:90-92`), and `FilesystemZipExportArchiveStoreTest.kt:166-176`
already pins it.

**Documentation only, so the TDD order does not apply** (the behaviour is already covered; the
exemption is on the order, never on the safety net).

**Acceptance.** `./gradlew gate`.

### 5. Two configuration keys

**Files.** `api-worker-quarkus/.../ExportsConfig.kt`, new
`api-application/src/test/.../ExportsConfigIntegrationTest.kt`.

**What.** `interruptedGrace` defaulting to **`PT6H`** and `sweepBatchSize` defaulting to `500`.

The grace's value is the spec's section 4.3 and is not negotiable downward without re-reading it: it
is anchored on `stagedFileMaxAge`, the age at which this repository already declares a build dead, not
on the lease. A grace shorter than the longest plausible staging lets task 11's pass 1 condemn a live
builder, which then discards a complete archive.

**There is no existing test of `ExportsConfig` defaults**, so this task creates one. The only precedent
is `ImportsConfigIntegrationTest` in `api-application`, a `@QuarkusTest`; that is the model, and its
cost (one boot) is accepted. `ImagesConfigTest`'s style is explicitly not the model: it instantiates an
anonymous implementation and therefore asserts nothing about `@WithDefault`.

**Acceptance.**
- Both defaults asserted against a container-resolved config, not an anonymous object.
- `./gradlew gate`.

### 6. A fake export archive store

**Files.** `api-usecases/src/test/.../exports/UserDataExportBuilderFixtures.kt` (or a new shared test
source file in the same package).

**What.** A fake `ExportArchiveStore` holding `promoted: MutableMap<String, StagedFile>` and
`discarded`/`deleted` lists, on the precedent of `RecordingSink` in the same file. `agents/engineering.md`
prefers fakes over mocks for ports the project owns, and asks for assertions on outcomes rather than on
the interactions just configured.

This is the instrument tasks 9, 11 and 12 need. With a mock, "the canonical key is untouched" can only
be written as `verify(exactly = 0) { promote(...) }`; with the fake it is written as state, which is
what the spec's criteria actually say and what a wrong implementation cannot satisfy by accident.

**Test-only change, no production behaviour**, so no red of its own: its proof is that the suites it
replaces stay green.

**Acceptance.**
- At least one existing builder test is migrated to the fake in this task, so the fixture is exercised
  rather than merely written.
- `./gradlew gate`.

---

## Block 2: what the sweep will read, and the precondition the build needs

Two tasks, mutually independent. Task 7 depends on block 1's tasks 1 and 5; task 8 depends on nothing
but is the precondition task 9's design rests on.

### 7. Two bounded selections on the export repository

**Files.** `api-domain/.../repositories/UserDataExportRepositoryInterface.kt`,
`api-persistence-sqlite/.../repositories/UserDataExportRepository.kt`, its test.

**What.** `findPending(limit)` and `findReclaimableTerminal(limit)`, both bounded at the query, not by
the caller: an unbounded select that the caller truncates would still materialise the whole history,
which is what the spec's first-run catch-up argument exists to avoid. `findPending` is **ordered by
`requestedAt` ascending**: its grace filter is applied after selection, so an unordered batch of recent
rows could starve the old ones forever. `findReclaimableTerminal` needs no order, because acting on a
row destroys its own selection predicate.

The terminal state set comes from task 1's predicate through a private object, on the precedent of
`TerminalImportStates` (`UserDataImportRepository.kt:113-115`), not from a literal.

**Acceptance.**
- Each selection exercised over all six states plus a terminal row with a null key. The precedent is
  `UserDataImportRepositoryTest.kt:273-287`: a single-state test cannot tell the specified predicate
  from a looser one.
- More rows than the limit yields the limit, and `findPending` yields the oldest.
- `./gradlew gate`.

### 8. Staging and archives share a filesystem, or the application refuses to start

**Files.** New `api-worker-quarkus/.../ExportDataDirectoryCheck.kt` and its test,
`api-application/src/test/resources/application.properties`.

**What.** Mirror `ImportDataDirectoryCheck`: an `@ApplicationScoped` bean observing `StartupEvent`,
delegating to a public function that takes its paths as parameters so a test can drive it. The
`FileStore` lookup is a seam, `storeOf: (Path) -> Any = { Files.getFileStore(it) }`, because
`@TempDir` cannot produce two filesystems and `DataDirPaths.kt:36-38` records that this project
already refused `mockkStatic(java.nio.file.Files::class)` for deadlocking the test JVM. The check
refuses to start when the two stores differ, naming both directories.

It lives in `api-worker-quarkus`, not in `api-storage-filesystem`, whose KDoc declares itself
framework-light and unit-testable with a temp directory; a `StartupEvent` observer there would
contradict it.

**`exports.data_dir` must be added to the test properties in the same commit.** Its production default
is `/var/lib/pinry/exports`, which CI cannot create, and this check probes it at startup: without the
entry, every `@QuarkusTest` in `api-application` refuses to boot from this block onward. The import
side already carries exactly this entry, with the comment saying why.

**Acceptance.**
- Two different store tokens refuse, and the message names both directories.
- One token starts.
- The whole `api-application` suite still boots, which is the point of the properties entry.
- `./gradlew gate`.

---

## Block 3: the build completes or fails, and nothing else

Two tasks, mutually independent: one rewrites the build's tail, one stops the requester nulling a key.
Task 9 depends on block 1's tasks 3 and 6, and on block 2's task 8.

### 9. The promote joins the publish, and the failure net covers both

**Files.** `api-usecases/.../exports/UserDataExportBuilder.kt`,
`api-usecases/src/test/.../exports/UserDataExportBuilderTest.kt`, a new
`UserDataExportCompletionTest.kt`, `UserDataExportBuilderFixtures.kt`.

**What.** One task, not two: both changes rewrite the same three lines at the tail of `build`
(`:78-80`), invert the same two existing assertions, and force the same split of
`stubHappyPathBuild()`. Splitting them would have the second rewrite the first.

The promote moves inside the transaction that re-reads the row and tests `PENDING`; on refusal the
attempt discards its own staged file and touches no key. The `try` extends over the completion,
discarding the staged file on **every** attempt and marking `FAILED` on the last, which is step 8 of
`docs/specs/2026-07-22-user-data-export.md:386`, all three clauses. `requireUser` and `requireFreeSpace`
stay outside it.

**The test class splits.** `UserDataExportBuilderTest.kt` is 508 lines for 20 tests against
`LargeClass: allowedLines: 600`, which does not exclude tests; five new cases would cross it. The
completion cases move to `UserDataExportCompletionTest`, sharing `UserDataExportBuilderFixtures`. The
fixtures file records that this class has already been split once for this reason.

**Acceptance.**
- The refusal arm, asserted on state through task 6's fake: the canonical key still holds the winner's
  bytes, and the loser's staged file is discarded. Not `verify(exactly = 0)`.
- The complement: the promote and the row write share a transaction number, through a new recording
  list on the fake. `PassthroughTransactionRunner.current` records the outermost open transaction and
  cannot see ordering within it, so this assertion alone would pass against an implementation that
  promotes before testing the predicate. It is the complement, never the pin.
- The rival is installed through the `reread` hook keyed on `stageCalls > 0`, never on a call ordinal:
  the hook also fires on `stampStorageKey`'s own fenced read (`ExportAccess.kt:12-20`), and a rival
  landing there exits the build before it stages.
- `UserDataExportBuilderTest.kt:420` and `:436` invert, and their now-unreached
  `every { archiveStore.delete(any()) }` stubs go with them.
- Promote throws on the last attempt: `FAILED` / `BUILD_FAILED`, staged file discarded. Publish throws
  on the last attempt: same. Promote throws on a non-final attempt: discarded, row still `PENDING`.
- `./gradlew gate`.

### 10. A superseded export keeps naming its bytes

**Files.** `api-usecases/.../exports/UserDataExportRequester.kt`, its test.

**What.** `createPending` stops nulling `storageKey`. The best-effort `deleteQuietly` outside the
transaction stays: it is the fast path, and a multi-gigabyte archive must not wait for a sweep. The
comment at `:49-52` loses its false last sentence and shortens.

**Acceptance.**
- `UserDataExportRequesterTest.kt:229` inverts: the superseded row names the key of the bytes it
  released. This is the task's only red.
- `UserDataExportRequesterTest.kt:295` (a delete that throws still succeeds) stays green unchanged:
  non-regression, not a new criterion.
- `./gradlew gate`.

---

## Block 4: the sweep becomes three passes, and what DELETE releases

Two tasks, mutually independent: one rewrites `ReapExpiredUserDataExports`, one rewrites
`UserDataExportDeleter`. Task 11 depends on blocks 1 and 2; task 12 depends on blocks 1 and 3.

### 11. Three passes, one rule

**Files.** `api-usecases/.../exports/ReapExpiredUserDataExports.kt`, its test,
`api-application/.../wiring/ExportProducers.kt`.

**What.** One task, for the reason task 9 is one task: the three changes share `reap()`'s composition
and `stubSweep()`, and any one alone leaves the class's seven existing tests failing on an unstubbed
selection against a strict mock. `reap()` becomes:

1. `failInterruptedBuilds`: a `PENDING` export whose task is absent or not `isLiveAttempt`, and whose
   `requestedAt` is older than `interruptedGrace`, fenced to `FAILED` with
   `failureCode = "EXPORT_INTERRUPTED"`.
2. the expiry purge, which **stops deleting the bytes** and writes only the state.
3. `reclaimTerminalArchives`: for a terminal row still naming a key, delete the bytes **first**, then
   fence on `isTerminal` and clear the column.

**Pass 3 deletes both keys when they differ.** The derived key is what a dead builder's archive is
named by; the column is what this row actually claims. Deleting only the derived one succeeds
vacuously when the column disagrees (deletion is idempotent), the column is then cleared, and the real
bytes become unreachable by every sweep: the defect of section 2.4, reintroduced. That divergence is
not hypothetical, `MeExportCompletionIntegrationTest.kt:352` seeds a row with `exports/$id.txt`.

**`reap()` returns typed counts** (`failed`, `expired`, `reclaimed`) rather than one `Int`, and the
class carries `@Suppress("LongParameterList")` for its seven constructor parameters, as its twin does.
The counts are a return value, not a log line: `api-usecases` binds `slf4j-nop` in test, so a log line
would be the one deliverable the gate cannot see.

**Acceptance.**
- Pass 1, one case per branch, on the twin's four (`ReapAbandonedUserDataImportsTest.kt:198` dead,
  `:216` queue no longer holds it, `:234` no task named, `:250` task ended). The twin folds `SUCCEEDED`
  and `CANCELLED` into `:250`; here they are separate cases, because they are what a predicate spelled
  "dead or absent" would miss.
- Pass 1: task `PENDING`, task `RUNNING`, and row younger than the grace: nothing written.
- Pass 1: fence refused because the row moved to `DELETED` between selection and write.
- Pass 2 no longer deletes bytes, and the row still loses them in the same `reap()` because pass 3
  follows.
- Pass 3: a row whose column names a key different from the derived one loses **both**.
- Pass 3: a delete that throws leaves the column set, so the next run retries, and the row is not
  counted. This is the pin; the ordering assertion is the complement, and the twin asserts the outcome
  rather than the order for the same reason.
- Pass 3: fence refused, nothing written, key kept.
- Each count reports its own pass, and a row acted on by two passes counts in both.
- `ReapExpiredUserDataExportsTest.kt:95` is renamed or retired: after pass 2 stops deleting, its title
  no longer describes why it passes.
- `./gradlew gate`.

### 12. Deleting a pending export releases the residue

**Files.** `api-usecases/.../exports/UserDataExportDeleter.kt`, its test.

**What.** The `PENDING` arm deletes `ExportArchiveKey.forExport(id, archiveStore.format.fileExtension)`,
best-effort. Its current reason for releasing nothing is that a build between its promote and its
publish will delete the bytes itself at its own fence; after task 9 that build never promoted, so the
reason is gone, and the residue ADR 0017 decision 2 admits would otherwise survive a `204` the user
reads as erased.

**`releaseStranded` becomes best-effort in the same commit.** After task 10 a `SUPERSEDED` row names a
key, so a `DELETE` on it now reaches `releaseStranded`, which propagates: a disk that refuses the
unlink would answer `500` where it answered `204`. That is residue cleanup, not the primary operation,
so ADR 0003 decision 1 applies, unlike the `READY` arm which stays propagating because it *is* the
user's operation.

The KDocs of `release`, `releaseStranded` and the class change here: `releaseStranded` loses its
exclusivity, not its purpose, and its "never clears its key" narrows to the path it describes.

**Acceptance.**
- The `PENDING` arm deletes the derived key; a delete that throws there does not fail the request.
- A `SUPERSEDED` row **naming a key** is deleted without failing the request. The existing case
  (`UserDataExportDeleterTest.kt:169-186`) iterates gone states with `storageKey = null` and so cannot
  see this path: task 10's change would otherwise pass under the tests.
- `UserDataExportDeleterTest.kt:109` and `:125` take the `PENDING` arm and gain the stubs it now needs.
- The `READY` arm still propagates.
- `./gradlew gate`.

---

## Block 5: what reads the sweep

One task, depending on block 4's task 11.

### 13. The sweep speaks, and cannot fail the boot

**Files.** `api-worker-quarkus/.../ExportRetentionLifecycle.kt`, its test.

**What.** `start()` calls `safeReap()` rather than `reap()` bare, and the lifecycle logs the typed
counts task 11 returns.

**The reason is not that the new passes can fail the boot**; task 11 isolates each row, so one refused
delete cannot. The defect is already there and predates this lot: `reap()` calls
`discardOrphanedStagedFiles` outside any isolation (`ReapExpiredUserDataExports.kt:41`), and a
`Files.list` on the staging directory can throw. Three passes make the surface wider, they do not
create it. Stating the true reason matters, because a false one invites the guard's removal at the
next cleanup.

**Acceptance.**
- A sweep that throws at startup leaves the application started.
- The periodic path is unchanged.
- The counts appear where an operator reads them.
- `./gradlew gate`.

---

## Block 6: end to end

One task, depending on everything.

### 14. The integration cases

**Files.** `api-application/src/test/.../MeExportCompletionIntegrationTest.kt`.

**What.** Three cases joining the existing class, never a new `@QuarkusTest`.

**Acceptance.**
- Spec criterion 6: a superseded export whose archive delete failed loses its bytes and its key at the
  next sweep.
- Spec criterion 4: an export whose task is `DEAD` reads `FAILED` / `EXPORT_INTERRUPTED` after the
  sweep, and the next `POST` is not `409`. Reached through a **task of a kind with no registered
  handler**, which `TaskProcessor:37-39` marks `DEAD` on its own; seeding a row with no task gives
  "absent", not `DEAD`, and forcing a real `DEAD` races the live worker. `requestedAt` is backdated
  past the grace, which is now `PT6H`.
- Spec criterion 2: a `READY` archive's downloaded body has the length and SHA-256 the row declares.
  Nothing asserts this today: `assertEveryEntryDigestMatches` compares the digests of entries *inside*
  the ZIP, not of the archive. This is the observable that separates the fix from the second loss of
  section 2.1.
- `./gradlew gate`.

---

## What this plan does not settle

- **Spec criterion 7**, the promoted-then-rolled-back residue, is pinned by no test at any level. The
  first draft placed it here; `api-application` has no fault-injection facility (no
  `quarkus-junit5-mockito`, no `QuarkusMock` anywhere in the repository), so a hand-seeded version
  would exercise criterion 8 and be labelled criterion 7. ADR 0017 already says what holds decision 2:
  review, not the gate. Introducing fault injection is its own lot.
- **The heartbeat's return value**, out of the lot by the spec's section 8, rewritten as a backlog item
  with its real reach: both handlers, the exception type, and its exclusion from task 9's widened net.
- **`claimNext` killing a task whose handler still runs.** Task 11's grace makes the collision
  improbable, not impossible: an account whose staging outlasts `PT6H` can still have a builder
  condemned under it. The upstream fix is filed.
- **No measurement of the two new selections**, which scan a table with no index on `state` or
  `storage_key`. The import twin has the same shape on its own table; this lot inherits it rather than
  measuring it, and the spec's section 8 says so.
- **The rename of `ReapExpiredUserDataExports`**, which now runs three passes under a name that says
  one. Its class KDoc is rewritten in task 11; the rename is a backlog item.
- **Widening `ImportStateMergedOutsideTransaction` to the `exports` package**, which this lot makes
  more valuable and which is not the one-line change the backlog claims.
- **`SUPERSEDED` rows already written with a null key**, invisible to both sweeps while the account
  lives, reclaimed only by account erasure.
- **`ImportLifecycle`'s bare startup sweep**, and the wrong pass named in the KDoc of
  `ReapAbandonedUserDataImports.reap()` (`:30-33`, not the class KDoc): both found here, both filed.
- **Correcting `docs/adr/0016:44-45`**, which attributes the single-connection premise to an ADR that
  does not contain it. A delivered ADR is not rewritten; ADR 0017 carries the correction.

## What changed after the plan angles

- **The grace was dangerous.** `PT15M` was justified against `lease_duration x max_attempts` = `PT3M`,
  a product that bounds nothing: an attempt lasts as long as its staging, which is unbounded. On a
  large account, pass 1 would have condemned live builders and destroyed valid archives. Now `PT6H`,
  anchored on `stagedFileMaxAge`. The spec's section 4.3 carries the corrected reasoning.
- **Tasks 10 to 13 of the first draft became one task.** Each left the sweep suite red for a later one
  to repair, which contradicted this plan's own rule, and the first draft applied the opposite standard
  to task 9 for an identical situation.
- **Task 13 of the first draft disappeared.** It re-passed a bound that a mandatory parameter already
  forced, and its acceptance duplicated task 7's at a level where it asserted the test's own fake.
- **Pass 3 now deletes both keys when they differ**, which the first draft's derived-key-only deletion
  turned into a fresh instance of section 2.4.
- **The filesystem check moved to `api-worker-quarkus` with a seam**, and gained the test properties
  entry without which every integration test would have stopped booting from block 2 onward.
- **A fake archive store became its own task**, so the criteria are asserted as state rather than as
  interactions, per the repository's own preference.
- **`reap()` returns typed counts** instead of logging them: `api-usecases` binds `slf4j-nop` in test,
  so the log line was the one deliverable the gate could not see.
- **Task 12 gained `releaseStranded`**, whose propagating delete would have turned task 10 into a new
  `500` on a path the existing test cannot see.
- **`ExportArchiveKey.forExport` has one signature**, two parameters, everywhere.
- Citations corrected: `:420`/`:436` rather than the declarations above them; `stubFailingStage` rather
  than `stubBuildFailure`, deleted from the repository in `6c126980`; the twin's four pass-1 cases
  rather than five, two of which pointed at other branches.
