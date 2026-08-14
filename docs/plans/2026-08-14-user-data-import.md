# Plan: user data import

Date: 2026-08-14
Spec: `docs/specs/2026-08-14-user-data-import.md`
ADR: `docs/adr/0015-import-identifies-by-natural-key.md`
Branch: `feat/user-data-import`
Revised: 2026-08-14, after the three plan angles. What changed is listed at the bottom.

Seventeen tasks in ten blocks. A block holds tasks that do not depend on each other's result, so
each block's review can read a frozen range while the next one is built.

**Every task is red then green, in two commits.** The failing test is committed alone as
`test(scope): <behaviour>` with the pasted failure in its body, then the implementation. An
unresolved-reference compile failure is a valid red here, per `agents/engineering.md`. A build-file
change never goes in a red commit: it belongs to the green one, so the red stays test-only.

**Every task ends with `./gradlew gate`**, except the red commits, which run only the test they name.

**Two tasks touch the schema (1 and 3) and they are in different blocks, deliberately.**
`generateDbMigration` numbers by diffing against the newest `dbmigration/model/*.model.xml`, so two
schema tasks built from the same base would both emit the same version, each omitting the other's
index. No document names a version: the generator owns it.

**`DbMigrationModelCoverageTest` is not a guard against hand-written migrations.** It asserts every
`.sql` has a paired model file, which a hand-written pair satisfies; `1.2.sql` is exactly that and is
green. The no-hand-writing rule is a review obligation backed by ADR 0009 decision 5, not by a test.

**`UniqueConstraintOutcomeTest` asserts set equality both ways.** The workable red is therefore to
add the outcome row alone, which fails on a key naming a constraint no migration declares; the green
commit carries the migration and the row together.

---

## Block 1: identities and contracts

Two tasks, mutually independent: one schema and use-case change, one pure module plus its error
surface.

### 1. Names become identities

Tags and boards are one task, not two, because they share a migration and a single map literal in
`UniqueConstraintOutcomeTest`; splitting them would put two generators and two edits of one map into
one block.

**Files.** `api-persistence-sqlite/.../models/{TagModel,BoardModel}.kt`,
`.../repositories/{TagRepository,BoardRepository}.kt`,
`api-domain/.../repositories/{TagRepositoryInterface,BoardRepositoryInterface}.kt`,
`api-domain/.../boards/BoardNameAlreadyTakenException.kt` (new),
`api-usecases/.../exceptions/{ErrorCode,BoardCreationError}.kt`, `.../BoardCreator.kt`,
`.../BoardUpdater.kt`, `.../BoardRecycleBin.kt`,
`api-presentation-quarkus/.../mappers/BaseErrorMapper.kt`,
`api-application/src/test/.../{BoardsIntegrationTest,BoardRecycleBinIntegrationTest}.kt`,
repository tests, `UniqueConstraintOutcomeTest`, one generated migration pair.

**What.** Two unique indexes declared on their entities,
`create unique index ... on tags (author_id, name collate nocase)` and the board equivalent, both
covering every row so a recycled board holds its name.

Both name lookups compare through the column's own collation, `raw("name collate nocase = ?", name)`,
**not** through Ebean's `ieq`. `ieq` renders `lower(column) = ?` with the bind lowercased in Java,
which is Unicode aware while `collate nocase` is ASCII only: with `ÉTÉ` stored, a lookup for `été`
would miss while the reverse matched, so the read and the index would disagree in one direction and
the import's find-or-create would behave differently depending on which case landed first.

`BoardRepositoryInterface` gains `findBoardForUserByName(user, name): Board?`, reading **every** state
through `BoardQueries.any()`, with the state named in its KDoc as ADR 0008 requires.

**Translation lives at two repository methods, not one.** `saveBoard` covers creation and renaming;
`restoreBoard` persists its model directly and never passes through `saveBoard`, so it needs its own
catch. Both throw the domain exception, since `api-persistence-sqlite` cannot see a `BaseError`.
`BoardCreator`, `BoardUpdater` and `BoardRecycleBin.restore` each rethrow
`BoardNameAlreadyExistsError`, mapped to `409 BOARD_NAME_ALREADY_EXISTS`, whose detail says when the
name is held by a recycled board.

**Acceptance.**
- Repository tests, both fold directions: store `été` and look up `ÉTÉ`, then store `ÉTÉ` and look up
  `été`. Both must miss, since the fold is ASCII. A one-directional test passes against `ieq` and
  would not have caught the disagreement, which is why both are named.
- Repository test: `voyage` then `Voyage` collide; two authors may each hold `voyage`; an active and
  a recycled board cannot share a name; the board finder returns a recycled board.
- Three integration cases, in the two named suites: `POST /api/v1/boards` with a taken name, `PUT`
  renaming onto one, and restoring from the recycle bin onto one. Each returns `409
  BOARD_NAME_ALREADY_EXISTS`. **Before the change all three succeed** (`201` and two `200`s): there is
  no constraint today, so no `500` is reachable, and the red commit pastes three successes.
- `UniqueConstraintOutcomeTest` rows for both indexes. The tag row records "no translation,
  deliberately", the board row names the code.
- `./gradlew gate`.

### 2. The import domain and its error surface

**Files.** `api-domain/.../entities/{UserDataImport,UserDataImportIssue}.kt`,
`.../enums/{UserDataImportState,UserDataImportIssueKind}.kt`,
`.../imports/{ImportArchiveStore,ArchiveSource,ArchiveLine,ImportAlreadyInProgressException}.kt`,
`.../repositories/{UserDataImportRepositoryInterface,UserDataImportIssueRepositoryInterface}.kt`,
`api-usecases/.../exceptions/{ErrorCode,UserDataImportError}.kt`,
`api-presentation-quarkus/.../mappers/BaseErrorMapper.kt`, tests.

**What.** Spec section 5's shapes, plus **every** import error code and its `BaseErrorMapper` arm, in
one place. The codes land here rather than in the use-case tasks because `statusFor` is an exhaustive
`when` with no `else`: a task adding a code without its arm does not compile, and two use-case tasks
each adding some would collide on one file. `UserDataImportState` carries `isActive` and `isTerminal`,
the latter so the four terminal states are one tested accessor rather than four repeated arms in
every consumer.

`BaseErrorMapper`'s title fallback is generalised here: it is currently hardcoded to the 422 wording,
and `jakarta.ws.rs` has no `INSUFFICIENT_STORAGE` constant (verified with `javap`), so a 507 would
otherwise ship titled "Unprocessable Entity".

**Acceptance.**
- `UserDataImportStateTest` covers `isActive` and `isTerminal` over all seven values.
- `BaseErrorMapperTest` asserts the **status code** for each new code, not only that a problem
  document comes back: `409`, `404`, `403`, `413`, `507`. A criterion that omits the status passes
  against a mapper returning 422 for everything, and 413 and 507 are the two genuinely new mappings.
- A case asserting the 507 title is not the 422 title.
- A Konsist assertion that `api-domain`'s new files import nothing outside the JDK, arriving with the
  mutation that makes it fail pasted in its commit message. "`ArchitectureKonsistTest` stays green" is
  not an acceptance criterion: it is a pre-existing test that would assert nothing new.
- `./gradlew gate`.

---

## Block 2: storage and schema

Two tasks, mutually independent. Both depend on block 1's task 2.

### 3. Import persistence

**Files.** `api-persistence-sqlite/.../models/{UserDataImportModel,UserDataImportIssueModel}.kt`,
`.../models/ImageModel.kt`, `.../mappers/*`, `.../repositories/*`,
`.../pagination/UserDataImportModelSortStrategy.kt`, tests, one generated migration pair.

**What.** Two tables, the partial unique index over the three active states declared on the model, the
supporting indexes spec section 11 names (`(user_id, state)` on imports, `(import_id)` on issues), and
a **non-unique** index on `images (content_hash)`. All in one migration, since one task owns the
schema here. The repository translates the partial-index violation into
`ImportAlreadyInProgressException`.

**Acceptance.**
- Repository test: two active imports for one user are refused with the translated domain exception,
  never the Ebean one; a second import after a terminal one is accepted.
- Repository tests for `findAbandonableBefore`, `findReclaimableTerminal` and `findMissingImportIds`,
  each with a row that must not match.
- `UniqueConstraintOutcomeTest` row for the partial index.
- `./gradlew gate`.

### 4. The archive store

**Files.** `api-storage-filesystem/.../FilesystemZipImportArchiveStore.kt`, its test,
`api-storage-filesystem/build.gradle.kts`, `gradle/libs.versions.toml`,
`api-usecases/src/test/.../exports/ExportContentGoldenJsonTest.kt` (KDoc only).

**What.** Spec section 5's port: chunked append with offset checking, `finishUpload` fsyncing and
digesting, promote by atomic rename, and a bounded `ArchiveSource` over `java.util.zip.ZipFile`
(already driven over a 65 600-entry ZIP64 by `FilesystemZipExportArchiveStoreTest`, so random access
at that scale has precedent here). The reader-only `ObjectMapper` registers `JavaTimeModule` and
`jackson-module-kotlin` and sets `StreamReadConstraints` explicitly.

Adding the Kotlin module to this module's `implementation` puts it on the **writer's** runtime
classpath too, and `ExportContentGoldenJsonTest`'s KDoc asserts in prose that no such module exists
anywhere in the codebase. That sentence is corrected here, in the commit that falsifies it. The golden
test is not the proof the writer is unchanged, since it builds a replica mapper in another module; the
proof is the assertion below plus task 17's round trip.

**Acceptance.**
- The writer mapper's registered module ids are asserted in `api-storage-filesystem`, so a future
  `findAndRegisterModules()` or a stray registration fails here rather than silently changing the
  published format.
- Append tests: sequential chunks concatenate; a replayed offset is refused, carrying the current
  length; an offset past the end is refused; a chunk carrying the total past the maximum is refused
  and `uploadedBytes` is unchanged, so a client can resume rather than restart.
- Bound tests with an **observable** channel, because "the refusal arrives before the stream is
  exhausted" is not assertable against a `ZipFile` the adapter owns: the fixture's bytes past the
  bound are deliberately corrupt, so reading them would raise a different failure. That is what
  distinguishes a reader that stops from one that reads everything and then checks a size. For
  `entryNames`, no such channel exists (the central directory is read on open), so the criterion is
  only that the count is refused, and the plan says so rather than implying more.
- A cross-adapter round trip: write an archive through the export's sink, read it back through this
  source. It pins framing and mapper configuration between the two adapters, not the export's schema,
  since the `Exported*` types live in `api-usecases` and this module cannot see them.
- `forEachStorageKeyOnDisk` yields promoted archives and never `tmp/` entries, mirroring the export
  adapter's existing pair. Without this the sweep would reclaim an upload in flight.
- A malformed JSONL line yields an `ArchiveLine` carrying a failure, and the walk continues.
- `./gradlew gate`.

---

## Block 3: reads and the upload path

Three tasks, mutually independent. All depend on block 2.

### 5. Digest without staging, and the pin lookup

**Files.** `api-domain/.../images/ImageStore.kt`, `api-storage-filesystem/.../FilesystemImageStore.kt`,
`api-domain/src/test/.../ImagePortsTest.kt` (the anonymous `object : ImageStore` stops compiling),
`api-domain/.../repositories/PinRepositoryInterface.kt`,
`api-persistence-sqlite/.../repositories/PinRepository.kt`, their tests.

**What.** `ImageStore.digest(source, maxBytes)` reads and hashes **without writing a temp file**.
`findPinIdsByContentHashForUser` lives on `PinRepositoryInterface` because `ImageModel` carries
`pinId` as a plain column with no association to `PinModel`, so a query rooted on images can reach
neither author nor soft-delete state. It reads every state through `PinQueries.any()`, named in the
KDoc, and returns a list.

**Acceptance.**
- `digest` succeeds **with the store's `tmp` directory absent or unwritable.** This is the criterion
  that discriminates: `digest` implemented as `stage(...).also { discard(it) }` returns the right hash
  and leaves the directory looking untouched, so a listing before and after proves nothing.
  `FilesystemImageStore.stage` creates the directory and a temp file, so it cannot pass this.
- `digest` past `maxBytes` throws `ImageTooLargeException` without reading the rest of the stream.
- The lookup finds a recycled pin's image, finds two pins sharing a digest, and returns empty for the
  same bytes under another account. The cross-user case matters most: a content-addressed key is
  otherwise an oracle for what other people hold.
- `EXPLAIN QUERY PLAN` on `Query.getGeneratedSql()` for this lookup shows `ix_images_content_hash`
  rather than a scan. No such test exists in this repository today; the precedent
  (`docs/specs/2026-08-13-persistence-p2-debt.md`) is a measurement pasted into a commit message, and
  the plan reads on an empty table with no statistics, so the output is pasted in the commit body as
  well as asserted.
- `./gradlew gate`.

### 6. Create, receive chunks, complete

**Files.** `api-usecases/.../imports/{UserDataImportCreator,UserDataImportChunkReceiver,UserDataImportArchiveCompleter}.kt`,
`.../tasks/UserDataImportTask.kt`, tests.

**What.** Spec section 6. The creator inserts and lets the index refuse a second active import: no
read-before-write, because ADR 0009 decision 2 forbids one that exists solely to answer a uniqueness
question, and the export's retained read is that ADR's single exception, kept only because it orders a
409 ahead of a 429. This import has no minimum interval and so no second refusal to order. The
completer writes the storage key **before** promoting, enqueues at priority `-1` (every kind in this
system currently runs at the default `0`, and the kind is `account.delete`, so "below account
deletion" is only expressible as a negative number), and stores the task id.

**Acceptance.**
- Owner and state checks on both the receiver and the completer: `403` for a non-owner, `404` for an
  unknown id, `IMPORT_NOT_AWAITING_ARCHIVE` for an import past the upload phase. Six branches that
  block 3's other task does not cover.
- A second create surfaces `ImportAlreadyInProgressError` translated from the adapter. A strict fake
  repository fails the test if any listing method is called, which pins the prohibition; the outcome
  itself is pinned by task 3's repository test.
- An out-of-order offset yields `IMPORT_CHUNK_OFFSET_MISMATCH` carrying the current length.
- Free space below the margin yields `IMPORT_INSUFFICIENT_STORAGE` and `appendChunk` is never called;
  `uploadedBytes` is unchanged, so the client resumes rather than restarts. The on-disk half of this
  belongs to task 4, where a real directory can be listed.
- `UserDataImportTask.MAX_ATTEMPTS = 5` is asserted, since spec section 9's disk-full recovery rests
  on it.
- `./gradlew gate`.

### 7. Read, list issues, cancel

**Files.** `api-usecases/.../imports/{UserDataImportGetter,UserDataImportIssueLister,UserDataImportCanceller}.kt`,
tests.

**What.** Owner checked before state everywhere. Cancellation per spec section 6.

**Acceptance.**
- A non-owner gets `403` and an unknown id `404`, on all three use cases.
- One case per cancellable state, not one for "terminal": `AWAITING_ARCHIVE` cancels no task (a fake
  `CancelTask` fails the test if called) and removes the partial upload; **`PENDING` cancels the task
  and deletes the archive**; `RUNNING` writes `CANCELLED` and leaves the archive to the runner. The
  `PENDING` arm is the only one that does both things and it had no test in the first draft.
- Terminal states are a no-op, asserted once through `isTerminal` rather than four times.
- `./gradlew gate`.

---

## Block 4: the runner, opening and metadata

One task.

### 8. Claim, open, walk tags and boards

**Files.** `api-usecases/.../imports/{UserDataImportRunner,RunnableImport,ImportedContent,ImportFieldBounds,ImportInstantClamp}.kt`,
`agents/engineering.md`, tests.

**What.** Steps 1 to 5 of spec section 8. **`RunnableImport` is built after the claim, not before**:
the projection takes the freshly generated `runToken` as a parameter rather than reading back a column
that is null at step 1. Building it first would either make the token nullable, defeating the
projection, or leave a `?: throw` arm no test can reach, which is the exact trap the projection was
chosen to avoid. Only `storageKey` is validated there.

The metadata walks validate names and descriptions against spec section 4.1, restore timestamps
through the two-ended clamp, and renew the lease every `leaseRenewalLines` lines. **A board that
already exists is left untouched whatever its state** and counted in `skippedBoards`.

`agents/engineering.md` is amended here, in the commit that establishes the timestamp exception in
code, per ADR 0015's `Amends` relation and the simultaneity rule.

**Acceptance.**
- Timestamps, three assertions in one archive: a past `createdAt` restored **exactly**, a future one
  equal to the import instant, and the import row's own timestamps from the injected `Clock`. The
  first is what fails an implementation that re-dates everything.
- `updatedAt` earlier than the clamped `createdAt` is floored to it; an instant before the account's
  creation is raised to it.
- An archive whose recycled `Summer` meets an existing active `Summer`: the existing board keeps its
  `updatedAt`, description and active state, and `skippedBoards` is 1. This is the case that broke the
  first draft of the spec, pinned before the pin walk exists.
- A name held only by a recycled board yields `NAME_TAKEN_BY_RECYCLED`.
- `USER_GONE`: an absent or tombstoned user yields `FAILED`, the code, and `PermanentTaskException`.
- Each rejected-archive code, `UNSUPPORTED_FORMAT_VERSION`, `MANIFEST_MISSING`, `ARCHIVE_UNREADABLE`:
  `FAILED`, the code, `processedPins = 0`, nothing created, `PermanentTaskException` so no retry is
  spent. These are use-case unit tests, where the branches live, as spec section 13.8 says.
- `announcedPins` is recorded from the manifest, and a manifest whose count disagrees with the real
  line count is not an error.
- Field bounds for this walk: a 300-character board name, a blank tag name, a 2001-character board
  description. Each yields `FIELD_INVALID` and is skipped.
- Counters increment rather than assign: against an account **already holding** the archive's tags,
  running the walk twice leaves `skippedTags` at twice the line count. Against a fresh account the
  total would be the line count either way, which is why the pre-seeded fixture is named.
- `./gradlew gate`.

---

## Block 5: the runner, pins

One task. It extends task 8's file.

### 9. Walk pins, fence, cursor, report cap

**Files.** `api-usecases/.../imports/UserDataImportRunner.kt` (extended),
`.../imports/ImportIssueRecorder.kt`, tests.

**What.** Spec section 8's per-pin sequence, plus the two report rules the first draft left ownerless:
`imports.report_detail_limit` caps stored issue rows while `issueCount` keeps climbing and
`issueDetailTruncated` flips, and `subject`/`detail` are truncated to 200 characters so a hostile line
cannot make the report the payload.

Memberships are written through `Pin.boards` like any other writer: `savePinBoards` diffs only against
active memberships, deliberately, so a recycled board's join row survives. An earlier revision of the
spec forbade this on a false premise and has been corrected.

`LINE_REJECTED` is the catch-all that makes "a single bad entry never fails the import" structural. It
requires a broad `catch` around the per-line body, which `agents/engineering.md` forbids without an
inline suppression and a stated reason; the reason is written at the site and repeated in the commit
message.

**Acceptance.**
- Idempotence, counting **`ImageStore.stage`**, not the probe: the second run's `stage` count is zero.
  Counting the probe does not discriminate, because an implementation that stages first, digests the
  staged file and discards on a hit also probes zero times, having written and fsynced every byte,
  which is exactly the cost this ordering exists to avoid. The account projection is identical after
  both runs and the second reports `createdPins = 0`, `skippedPins = N`. Asserting a call count is
  deliberate here and noted at the test, since the outcome it stands for has no other channel.
- The fence: a fake repository returning a different `runToken` from the third pin leaves two pins
  written, the third refused, the state as the canceller wrote it, **and no promoted bytes or staged
  file left behind**. This stands for the lease-expiry race, which cannot be provoked deterministically
  through a real worker; that limit is stated rather than hidden behind a sleep.
- A per-pin transaction that throws triggers the same compensation, which the resumption test does not
  cover because it throws in the `ArchiveSource` before anything is staged.
- Resumption: a fake `ArchiveSource` throwing on line three, then a second `run()`. The account
  projection equals the uninterrupted run's, counters are sums, no pin and no issue row is duplicated,
  and the image store holds no orphan.
- One case per issue kind this walk produces: `PIN_HAS_NO_MEDIA`, `MEDIA_ENTRY_MISSING`,
  `ENTRY_PATH_INVALID` (including `images/..` and an unanchored candidate), `LINE_MALFORMED`,
  `MEDIA_TOO_LARGE`, `MEDIA_TOO_MANY_PIXELS`, `MEDIA_UNREADABLE`, `MEDIA_AMBIGUOUS`,
  `MEDIA_DIGEST_MISMATCH`, `FIELD_INVALID` (description, `sourceContextUrl`, over-long `tags[]` and
  `boards[]`), `LINE_REJECTED`. Every one of these is a branch in a measured package.
- A wrong declared `sha256` yields `MEDIA_DIGEST_MISMATCH` **and the pin is still created**, which is
  what distinguishes reporting from acting.
- The cap: with the limit injected, 501 anomalies store 500 rows, report `issueCount = 501` and set
  the flag; 499 leave it false. A `detail` of 300 characters is stored at 200.
- `DISK_FULL` during the walk is transient: the failure is rethrown without marking the row on an
  early attempt.
- `./gradlew gate`.

---

## Block 6: completion

One task.

### 10. Publish, fail, delete the archive

**Files.** `api-usecases/.../imports/UserDataImportRunner.kt` (extended), tests.

**What.** Steps 7 and 8 of spec section 8: the compare-and-set that writes `COMPLETED` only if the row
still holds the run, the archive deletion on every terminal path, and the catch-all marking
`IMPORT_FAILED` when `isLastAttempt`. Without the last one an unenumerated failure leaves the row
`RUNNING` for ever, the archive unreclaimed, and the account locked out by the partial unique index.

**Acceptance.**
- Completion is refused when the row no longer holds the run: the bytes are deleted and no `COMPLETED`
  is written.
- An unexpected throw marks `IMPORT_FAILED` on the last attempt and rethrows; on an earlier attempt it
  rethrows without marking.
- A cancelled-mid-walk run deletes the archive as it returns.
- `./gradlew gate`.

---

## Block 7: wiring

One task, and it must precede anything the container has to resolve.

### 11. Configuration and producers

**Files.** `api-worker-quarkus/.../ImportsConfig.kt`, `api-application/.../wiring/ImportProducers.kt`,
`api-application/src/main/resources/application.properties`, tests.

**What.** Every default in the `@ConfigMapping`, and producers for the use cases whose constructors
carry plain scalars. This exists as its own block because `ExportProducers` exists for exactly that
reason: ARC cannot resolve a `Duration`, an `Int` or a `String`. Without it, the moment the handler or
the controller lands, every `@QuarkusTest` in `api-application` fails to boot on an unsatisfied
dependency and the gate is unreachable for those tasks. `images.max_file_bytes` and `images.max_pixels`
are passed to the runner from here, since `ImagesConfig` lives in the presentation module.
`application.properties` gains `imports.*` to its prefix inventory.

**Acceptance.**
- A test asserting `imports.max_chunk_bytes` is **strictly** below `quarkus.http.limits.max-body-size`.
  It lives where both values are readable, and it fails on the defaults the spec first carried, which
  were exactly equal.
- Every existing `@QuarkusTest` still boots, which is the observable this task exists for.
- `./gradlew gate`.

---

## Block 8: worker and REST

Two tasks, mutually independent. Both depend on block 7.

### 12. The task handler

**Files.** `api-worker-quarkus/.../UserDataImportTaskHandler.kt`, tests.

**What.** A delegate, nothing more: `isLastAttempt = context.attempt >= context.maxAttempts` and the
kind. Every behaviour it triggers is tested in `api-usecases`, where the branches live and where
coverage is measured; `UserDataExportTaskHandlerTest` is the precedent for what a handler test
legitimately asserts.

**Acceptance.**
- The delegation, with `isLastAttempt` computed both ways.
- `./gradlew gate`.

### 13. The REST surface

**Files.** `api-presentation-quarkus/.../controllers/MeImportController.kt`, `.../dtos/output/*`,
`.../mappers/UserDataImportDtoMapper.kt`, tests, `docs/openapi.json` (regenerated by the hook).

**What.** Spec section 7. The chunk endpoint consumes an `InputStream` and is annotated blocking
deliberately, with the reason at the site: streaming holds only while the method is blocking and while
no extension installs a global body handler. No progress field. The `@Operation` copy states that an
import is not atomic and that cancellation leaves partial state, which spec section 14 promises to the
API documentation and not only to itself.

**Acceptance.**
- Controller tests per endpoint, asserting status and DTO. The problem-document shape is
  `BaseErrorMapperTest`'s (task 2), and the wire content type is task 17's; this criterion does not
  claim all three.
- A mapper test for `issueDetailTruncated`, which is a wire assertion only: the behaviour is pinned in
  task 9, since a mapper test passes against a field nothing ever sets.
- The `pre-commit` hook regenerates `docs/openapi.json` and a re-run commits clean. The CI sync check
  is a post-push gate with no local equivalent, which `AGENTS.md` states, so it is not claimed here.
- `./gradlew gate`.

---

## Block 9: operations

Three tasks, mutually independent. All depend on block 8.

### 14. Sweeps

**Files.** `api-usecases/.../imports/ReapAbandonedUserDataImports.kt`,
`api-usecases/.../ReapOrphanedStorage.kt`, `api-worker-quarkus/.../ImportLifecycle.kt`,
`api-application/src/test/.../MeImportSweepIntegrationTest.kt`, tests.

**What.** The three paths of spec section 6, plus the import half of `ReapOrphanedStorage`. Its own
`PeriodicScheduler`, injected by type per ADR 0004.

**Acceptance.**
- Use-case unit tests for the branches: a stale `AWAITING_ARCHIVE` row becomes `ABANDONED`; an upload
  still receiving chunks is **not** abandoned even when older than the grace, since the grace counts
  inactivity; a terminal row's bytes are reclaimed once and a second run reports zero reclaimed; a
  `RUNNING` row whose task is `DEAD` becomes `FAILED` with `IMPORT_INTERRUPTED`.
- An integration case driving the bean directly rather than waiting on the interval, following
  `MeExportCompletionIntegrationTest`, for the on-disk half a fake cannot show.
- `./gradlew gate`.

### 15. Account deletion erases imports

**Files.** `api-usecases/.../AccountDeletionCleaner.kt`, its unit test,
`api-application/src/test/.../AccountDeletionIntegrationTest.kt`.

**What.** Issues then import rows inside the transaction, before the user row; archive bytes after the
commit, keyed on the **derived** key so an archive promoted by a completer that died is still
reclaimed.

**Acceptance.**
- Unit test for the ordering and the derived key, in `api-usecases` where the branches are measured.
- An integration case against a real store: delete an account holding one `COMPLETED` and one
  `AWAITING_ARCHIVE` import, assert no rows in either table and nothing left in `imports.data_dir`.
  `api-usecases` has no filesystem adapter on its test classpath, so the on-disk half cannot live
  there.
- `./gradlew gate`.

### 16. Deployment

**Files.** `Dockerfile`, a startup check in `api-worker-quarkus` or the composition root, its test.

**What.** `IMPORTS_DATA_DIR` joins the two data directories the `Dockerfile` names, and the directory
is checked writable at startup rather than on first write, so an unwritable volume refuses to boot
instead of failing after a user has streamed twenty gigabytes.

**Acceptance.**
- A unit test on the check class: it throws with the path in the message when the directory is not
  writable. Deliberately not a boot test, which would assert a log line this project never asserts,
  and which is unreliable in a container running as root where `chmod` does not bite.
- `./gradlew gate`.

---

## Block 10: end to end

One task.

### 17. The integration suite

**Files.** `api-application/src/test/.../MeImportIntegrationTest.kt`, `.../MeImportTestProfile.kt`.

**What.** The scenarios spec section 13 marks as integration and that no earlier task could host. One
suite, not two: a new `@QuarkusTest` costs a full boot and nothing in the round trip needs a profile
the other cases cannot share. A dedicated profile rather than widening `MeExportTestProfile`, which
two suites already share.

**Acceptance.**
- The round trip asserts the enumerated equivalence of spec section 13.1, field by field, including
  the recycled board's membership, and two pins sharing one image producing one pin.
- Chunked upload: three chunks, a replayed offset refused with the current length, a resumed upload
  completing from the reported length.
- The anomaly archive reaches `COMPLETED` with the good pins created and each expected issue kind
  appearing once.
- The lying manifest: `image.mimeType` says `image/jpeg` over PNG bytes, the stored type is
  `image/png`.
- The ambiguous digest end to end, where "no promoted object, no staged temp file" can be observed
  against a real store rather than a fake.
- The non-empty account with differing case, and the recycled-name refusal.
- Cancelling a `PENDING` import: task cancelled, archive gone.
- The wire content type is `application/problem+json` on one error path.
- `./gradlew gate`, output pasted in the final message.

---

## What this plan does not settle

- **The lease-expiry race is pinned by a fake, not a real worker** (task 9). A deterministic
  provocation through the queue would be better and belongs in the wrap if one is found.
- **`EXPLAIN QUERY PLAN` is read on a table with no statistics** (task 5), which the p2-debt handoff
  already records as a limit of the method.
- **ADR 0015 is `Status: Proposed`.** It flips to `Accepted` at integration, which is the only field
  `agents/writing.md` allows to change after delivery.

## What changed after the plan angles

- **Tasks 1 and 2 of the first draft merged**, because they shared a migration and one map literal in
  `UniqueConstraintOutcomeTest` while being declared independent.
- **A wiring block was inserted before the handler and the controller.** The dependency ran backwards:
  those two cannot boot without producers the first draft scheduled two blocks later.
- **All import error codes moved into one task**, since `BaseErrorMapper`'s exhaustive `when` makes a
  code without its arm a compile failure, and two tasks were adding codes without listing the file.
- **The runner's completion and failure modes moved from the worker task into `api-usecases`**, where
  the branches live and where coverage is measured. The handler is now the three-line delegate its
  precedent is.
- **The idempotence test now counts `ImageStore.stage` rather than `ImageProbe`**, which is the only
  version that discriminates against staging before the lookup.
- **`digest` is pinned by running without a temp directory**, since a listing before and after passes
  against an implementation that stages and discards.
- **The report cap, the 200-character truncation and `MAX_ATTEMPTS`** gained owners; none had one.
- **Six unowned failure codes gained criteria**: `USER_GONE`, `LINE_MALFORMED`, `MEDIA_ENTRY_MISSING`,
  `DISK_FULL`, `IMPORT_NOT_AWAITING_ARCHIVE`, `LINE_REJECTED`.
- **Both fold directions are tested**, after the angle showed `ieq` and `collate nocase` disagree in
  one direction; the spec now specifies the collation-based read.
- **Task 1's red was restated**: all three board sites succeed today, so no `500` is reachable before
  the constraint exists.
- **`RunnableImport` is built after the claim**, so its validated field has a reachable failure.
- **The on-disk assertions moved to modules that can see a real store**, and the demoted integration
  scenarios are now named rather than silently unhosted.
