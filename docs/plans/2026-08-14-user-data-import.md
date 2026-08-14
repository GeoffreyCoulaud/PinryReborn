# Plan: user data import

Date: 2026-08-14
Spec: `docs/specs/2026-08-14-user-data-import.md`
ADR: `docs/adr/0015-import-identifies-by-natural-key.md`
Branch: `feat/user-data-import`

Sixteen tasks in eight blocks. A block holds tasks that do not depend on each other's result, so
each block's review can read a frozen range while the next one is being built.

**Every task is red then green, in two commits.** The failing test is committed alone as
`test(scope): <behaviour>` with the pasted failure in its body, then the implementation. A task that
delivers no new behaviour (wiring, configuration) says so and carries the test that would have
failed without it.

**Every task ends with `./gradlew gate`**, except the red commits, which run only the test they name.

**Migrations are numbered in task order within a block.** Three tasks touch the schema (1, 2, 4) and
each generates its own pair through `./gradlew :api-persistence-sqlite:generateDbMigration`. No file
under `dbmigration/` is hand-written: `DbMigrationModelCoverageTest` holds
`handWritten = emptySet()`, and ADR 0009 decision 5 is why.

**Every new unique constraint gets its row in `UniqueConstraintOutcomeTest`** naming the answer a
client receives, "no translation, deliberately" included.

---

## Block 1: foundations

Three tasks, mutually independent: two tables and one pure module.

### 1. Tag names fold ASCII case and are unique per author

**Files.** `api-persistence-sqlite/.../models/TagModel.kt`,
`.../repositories/TagRepository.kt`, `.../UserDataImportMigrationTest` is not this task's;
`api-persistence-sqlite/src/test/.../TagRepositoryTest.kt`,
`.../migration/UniqueConstraintOutcomeTest.kt`, generated `dbmigration/1.20.sql` + `model/1.20.model.xml`.

**What.** `findUserTagByName` moves from `equalTo` to `ieq`, following `UserRepository.findByName`
against `ix_users_name_nocase`. `TagModel` declares
`@Index(name = "ix_tags_author_name_nocase", definition = "create unique index ix_tags_author_name_nocase on tags (author_id, name collate nocase)")`.
The outcome row records "no translation, deliberately": `TagCreator.findOrCreate` reads then writes,
and the single SQLite connection serialises the pair inside one transaction, so the violation is
unreachable from the API.

**Acceptance.**
- A repository test in the `UserRepositoryTest:193` shape: saving `voyage` then looking up `Voyage`
  returns the first row, and a second `saveTag` of `VOYAGE` for the same author is refused by the
  store. Fails before the change with the two rows coexisting.
- A test that `ÉTÉ` and `été` remain two tags, pinning the ASCII-only fold as spec section 12 states
  rather than leaving it to be discovered.
- Two authors may each hold `voyage`.
- `UniqueConstraintOutcomeTest` has its row; the test fails first with the index unnamed.
- `./gradlew gate`.

### 2. Board names are unique per author, recycled rows included, at three sites

**Files.** `api-persistence-sqlite/.../models/BoardModel.kt`, `.../repositories/BoardRepository.kt`,
`api-domain/.../boards/BoardNameAlreadyTakenException.kt` (new),
`api-usecases/.../exceptions/{ErrorCode,BoardCreationError}.kt`, `.../BoardCreator.kt`,
`.../BoardUpdater.kt`, `.../BoardRecycleBin.kt`,
`api-presentation-quarkus/.../mappers/BaseErrorMapper.kt`, their tests, generated `dbmigration/1.21.*`.

**What.** The unique index mirrors task 1 and covers every row, so a recycled board holds its name.
`BoardRepository.saveBoard` catches the Ebean violation and throws the **domain** exception, since
`api-persistence-sqlite` cannot see a `BaseError` (this is why `ExportAlreadyInProgressException`
exists in the domain). `BoardCreator`, `BoardUpdater` and `BoardRecycleBin.restore` each rethrow
`BoardNameAlreadyExistsError`, mapped to `409 BOARD_NAME_ALREADY_EXISTS`. The detail says the name is
held by a board in the recycle bin when that is the case, otherwise the client cannot act on the 409.
`api-domain/.../repositories/BoardRepositoryInterface.kt` gains
`findBoardForUserByName(user, name): Board?`, case insensitive, reading **every** state through
`BoardQueries.any()`, with the state named in its KDoc as ADR 0008 requires.

**Acceptance.**
- Three controller tests, one per site: `POST /api/v1/boards` with a taken name, `PUT
  /api/v1/boards/{id}` renaming onto one, and restoring from the recycle bin onto one. Each returns
  `409 BOARD_NAME_ALREADY_EXISTS` with a problem+json body. Before the change, the first returns
  `201` and the other two a `500` carrying a persistence exception, and the red commit shows all
  three.
- A repository test: an active board and a recycled board cannot share a name; the finder returns the
  recycled one.
- `UniqueConstraintOutcomeTest` row.
- `./gradlew gate`.

### 3. The import domain

**Files.** `api-domain/.../entities/{UserDataImport,UserDataImportIssue}.kt`,
`.../enums/{UserDataImportState,UserDataImportIssueKind}.kt`,
`.../imports/{ImportArchiveStore,ArchiveSource,ArchiveLine,ImportAlreadyInProgressException}.kt`,
`.../repositories/{UserDataImportRepositoryInterface,UserDataImportIssueRepositoryInterface}.kt`,
tests in `api-domain/src/test`.

**What.** Spec section 5, verbatim shapes. Pure module, no framework import. `isActive` follows
`UserDataExportState.isGone`'s precedent.

**Acceptance.**
- `UserDataImportStateTest` covers `isActive` over all seven values, in `UserDataExportStateTest`'s
  shape.
- `ArchitectureKonsistTest` stays green, which is what proves the module took no dependency.
- `./gradlew gate`.

---

## Block 2: adapters

Three tasks, mutually independent. All three depend on block 1's task 3.

### 4. Import persistence

**Files.** `api-persistence-sqlite/.../models/{UserDataImportModel,UserDataImportIssueModel}.kt`,
`.../mappers/*`, `.../repositories/*`, `.../pagination/UserDataImportModelSortStrategy.kt`, tests,
generated `dbmigration/1.22.*`.

**What.** Two tables. The partial unique index over `('AWAITING_ARCHIVE','PENDING','RUNNING')` is
declared on the model, not hand-written. A **non-unique** index on `images (content_hash)` lands here
too, declared on `ImageModel`. States are plain `String` columns converted in the mapper, following
`UserDataExportModel`. The repository translates the partial-index violation into
`ImportAlreadyInProgressException`.

**Acceptance.**
- A repository test: two active imports for one user are refused, a second import after a terminal
  one is accepted. It asserts the translated domain exception, not the Ebean one.
- `DbMigrationModelCoverageTest` green, which is what fails if any migration is hand-written.
- A test that reads `EXPLAIN QUERY PLAN` on the generated SQL of the digest lookup and asserts it
  uses `ix_images_content_hash` rather than scanning, following the precedent in
  `docs/specs/2026-08-13-persistence-p2-debt.md` that a partial or unused index is caught by reading
  the plan rather than the definition.
- `UniqueConstraintOutcomeTest` row for the partial index.
- `./gradlew gate`.

### 5. The archive store

**Files.** `api-storage-filesystem/.../FilesystemZipImportArchiveStore.kt`, its test,
`api-storage-filesystem/build.gradle.kts` (adds `jackson-module-kotlin`),
`gradle/libs.versions.toml`.

**What.** Spec section 5's port. Chunked append with offset checking, `finishUpload` fsyncing and
digesting into a `StagedFile`, promote by atomic rename, `open` returning a bounded `ArchiveSource`
over `java.util.zip.ZipFile` (which `FilesystemZipExportArchiveStoreTest` already drives over a
65 600-entry ZIP64, so random access at that scale has precedent in this repository). The reader-only
`ObjectMapper` registers `JavaTimeModule` and the Kotlin module and sets `StreamReadConstraints`
explicitly. `ArchiveLine` carries the line number and either a value or a failure string, so a
malformed line is a value rather than an exception that ends a walk.

**What must not happen.** The export's mapper is untouched. `ExportContentGoldenJsonTest` is the
proof and runs in the same gate.

**Acceptance.**
- Append tests: sequential chunks concatenate; a replayed offset is refused with the current length;
  an offset past the end is refused; a chunk carrying the total past the maximum is refused.
- Bound tests, each asserting the refusal arrives **before** the stream is exhausted: an archive
  whose entry count exceeds `maxEntries`, a metadata entry over `maxMetadataBytes`, and a
  `pins.jsonl` that is one line longer than `maxLineBytes` with no newline.
- A round-trip test through the export's own sink: write an archive with the export adapter, read it
  back with this one, assert every field survives. This is what pins the two adapters to one format.
- A malformed JSONL line yields an `ArchiveLine` with a failure and the walk continues.
- `./gradlew gate`.

### 6. Digest without staging, and the pin lookup

**Files.** `api-domain/.../images/ImageStore.kt`, `api-storage-filesystem/.../FilesystemImageStore.kt`,
`api-domain/.../repositories/PinRepositoryInterface.kt`,
`api-persistence-sqlite/.../repositories/PinRepository.kt`, their tests.

**What.** `ImageStore.digest(source, maxBytes): ContentDigest` reads and hashes without writing a temp
file, throwing the same `ImageTooLargeException` past the bound. It exists because
`FilesystemImageStore.stage` writes every byte and calls `force(true)`, so digesting through it would
cost a full write plus an fsync plus a delete for every image an import is about to skip, which is the
dominant path when merging or restoring. `findPinIdsByContentHashForUser` lives on
`PinRepositoryInterface` because `ImageModel` carries `pinId` as a plain column with no association to
`PinModel`, so a query rooted on images can reach neither author nor soft-delete state. It reads every
state through `PinQueries.any()`, named in the KDoc, and returns a list.

**Acceptance.**
- `digest` returns the same hash as `stage` for the same bytes, and leaves nothing behind: the test
  lists the temp directory before and after and asserts it is unchanged. That absence is the whole
  point of the method, so it is asserted rather than assumed.
- `digest` past `maxBytes` throws without having read the rest of the stream.
- The lookup finds a recycled pin's image, finds two pins sharing a digest, and never crosses users:
  the same bytes under another account return empty. The cross-user case is the one that matters,
  since a content-addressed key is otherwise an oracle.
- `./gradlew gate`.

---

## Block 3: the upload path

Two tasks, mutually independent. Both depend on block 2.

### 7. Create, receive chunks, complete

**Files.** `api-usecases/.../imports/{UserDataImportCreator,UserDataImportChunkReceiver,UserDataImportArchiveCompleter}.kt`,
`.../exceptions/UserDataImportError.kt`, `.../tasks/UserDataImportTask.kt`, tests.

**What.** Spec section 6. The creator inserts and lets the index refuse a second active import: there
is deliberately **no** read-before-write, because ADR 0009 decision 2 forbids one that exists solely
to answer a uniqueness question, and the export's retained read is that ADR's single exception, kept
only because it orders a 409 ahead of a 429. This import has no minimum interval and so no second
refusal to order. The receiver checks owner, then state, then free space, then appends. The completer
writes the storage key **before** promoting, enqueues below `account.deletion`'s priority, and stores
the task id.

**Acceptance.**
- A second create for the same user surfaces `ImportAlreadyInProgressError` translated from the
  adapter, with no repository read of the user's imports in the use case (asserted by a fake that
  fails the test if a listing method is called).
- An out-of-order offset yields `IMPORT_CHUNK_OFFSET_MISMATCH` carrying the current length.
- A chunk past `maxArchiveBytes` yields `IMPORT_ARCHIVE_TOO_LARGE`; free space below the margin
  yields `IMPORT_INSUFFICIENT_STORAGE`, and neither leaves a partial file.
- Completing writes the storage key before the promote: a fake store that throws inside `promote`
  leaves a row whose `storageKey` is set, which is what makes the bytes reclaimable.
- `./gradlew gate`.

### 8. Read, list issues, cancel

**Files.** `api-usecases/.../imports/{UserDataImportGetter,UserDataImportIssueLister,UserDataImportCanceller}.kt`,
tests.

**What.** Owner checked before state everywhere, `403` for a non-owner and `404` for an unknown id.
Cancellation per spec section 6: `AWAITING_ARCHIVE` discards the partial upload and has no task to
cancel; `PENDING` cancels the task and deletes the archive; `RUNNING` writes `CANCELLED` and leaves
the fence to stop the walk.

**Acceptance.**
- A non-owner gets `403` and an unknown id `404`, on all three use cases.
- Cancelling an `AWAITING_ARCHIVE` import cancels no task (a fake `CancelTask` that fails the test if
  called) and removes the partial file.
- Cancelling a `RUNNING` import writes `CANCELLED` and does not touch the archive, which is the
  runner's job as it returns.
- Cancelling a terminal import is a no-op.
- `./gradlew gate`.

---

## Block 4: the runner, metadata half

One task. It depends on block 2 and is the base the next block builds on.

### 9. Claim, walk tags and boards, validate, clamp

**Files.** `api-usecases/.../imports/{UserDataImportRunner,RunnableImport,ImportedContent,ImportFieldBounds,ImportInstantClamp}.kt`,
tests.

**What.** Steps 1 to 5 of spec section 8. The `RunnableImport` projection is built at one validation
site so the rest of the walk handles no nullables, following `OpenedExport` rather than the `!!` plus
suppression in `UserDataExportDeleter`. Claiming writes a fresh `runToken`. The metadata walks
validate each line against section 4.1's bounds, restore timestamps through the two-ended clamp, and
renew the lease every `leaseRenewalLines` lines. **A board that already exists is left untouched
whatever its state and counted in `skippedBoards`**; only a board this import creates carries the
archive's description, timestamps and recycled state.

**Acceptance.**
- The timestamp test, three assertions in one archive: a past `createdAt` restored **exactly**, a
  future one equal to the import instant, and the import row's own timestamps from the injected
  `Clock`. The first assertion is the one that fails an implementation which re-dates everything, and
  it is why the clamp-only test the spec first proposed was rejected.
- `updatedAt` earlier than the clamped `createdAt` is floored to it; an instant before the account's
  creation is raised to it.
- An archive whose recycled `Summer` meets an existing active `Summer`: the existing board keeps its
  `updatedAt`, its description and its active state, and the run records `skippedBoards = 1`. This is
  the case that broke the first draft of the spec, so it is pinned before the pin walk exists.
- A name held only by a recycled board yields `NAME_TAKEN_BY_RECYCLED` and creates nothing.
- Field bounds: a 300-character board name, a blank tag name, and a pin carrying 200 tags each yield
  `FIELD_INVALID` and are skipped.
- Counters increment rather than assign: running the walk twice against the same repository state
  leaves `skippedTags` at twice the line count, not at the line count.
- `./gradlew gate`.

---

## Block 5: the runner, pin half

One task. It depends on block 4.

### 10. Walk pins, fence, cursor, compensation

**Files.** `api-usecases/.../imports/UserDataImportRunner.kt` (extended), tests.

**What.** Spec section 8's per-pin sequence: validate, digest without staging, compare the declared
digest and report a mismatch without acting on it, look up by digest, and only on a miss reopen the
entry, stage, probe with `images.max_pixels`, promote, then in one transaction re-read the row,
proceed only if the `runToken` still holds, create the pin and its image, resolve tags and boards by
name, write memberships **without passing through `Pin.boards`** (the mapped value drops recycled
boards, so saving it back would delete those join rows), and increment the cursor and counters. Issue
rows are written in the transaction that settles their line. Compensation on any failure follows
`SetPinImage`: discard the staged file and delete the promoted key, then rethrow.

**Acceptance.**
- Idempotence, with a counting fake `ImageProbe`: importing twice leaves the account projection
  identical, the second run reports `createdPins = 0` and `skippedPins = N`, and **the probe call
  count is unchanged**, which is what pins the digest-before-stage ordering that the spec claims.
  Asserting a call count is deliberate here and noted in the test, because the outcome it stands for
  (CPU not spent) has no other observable channel.
- The fence: a fake repository returning a different `runToken` from the third pin on leaves two pins
  written, the third refused, and the state as the canceller wrote it. This is the test that stands
  for the lease-expiry race, which cannot be provoked deterministically through a real worker.
- Resumption: a fake `ArchiveSource` throwing on line three, then a second `run()` on the same state.
  The account projection equals the uninterrupted run's, counters are sums rather than the last run's,
  no pin and no issue row is duplicated, and the image store holds no orphan.
- A digest matching two existing pins yields exactly one `MEDIA_AMBIGUOUS`, no pin row, no image row,
  no promoted object and no staged file left behind.
- A wrong declared `sha256` yields `MEDIA_DIGEST_MISMATCH` **and the pin is still created**, which is
  what distinguishes reporting from acting.
- Oversize and over-pixel media, driven by `imageStore.stage` throwing `ImageTooLargeException` and
  the probe throwing the pixel refusal, in `SetPinImageTest`'s existing shape.
- A pin with no image yields `PIN_HAS_NO_MEDIA`; a malformed path yields `ENTRY_PATH_INVALID` and the
  test includes `images/..` and an unanchored candidate, since the spec justifies the check by its
  reporting.
- `./gradlew gate`.

---

## Block 6: task and REST

Two tasks, mutually independent. Both depend on block 5.

### 11. The task handler and its failure modes

**Files.** `api-worker-quarkus/.../UserDataImportTaskHandler.kt`, tests.

**What.** Delegates with `isLastAttempt = context.attempt >= context.maxAttempts`. Steps 3, 7 and 8
of spec section 8 land here or in the runner's completion: the rejected-archive failure codes with
`PermanentTaskException`, the compare-and-set on `COMPLETED`, the archive deletion, and the
catch-all that marks `IMPORT_FAILED` on the last attempt.

**Acceptance.**
- One test per rejected-archive code (`UNSUPPORTED_FORMAT_VERSION`, `MANIFEST_MISSING`,
  `ARCHIVE_UNREADABLE`): state `FAILED`, the code, `processedPins = 0`, nothing created, and
  `PermanentTaskException` so no retry is spent.
- An unexpected throw on the last attempt marks `IMPORT_FAILED` and rethrows; on an earlier attempt
  it rethrows without marking. Without this the row stays `RUNNING` for ever and the partial unique
  index locks the account out of importing.
- Completion is refused when the row no longer holds the run: the bytes are deleted and no
  `COMPLETED` is written.
- `./gradlew gate`.

### 12. The REST surface

**Files.** `api-presentation-quarkus/.../controllers/MeImportController.kt`,
`.../dtos/output/{UserDataImportOutputDto,UserDataImportListOutputDto,UserDataImportIssueOutputDto,...}.kt`,
`.../mappers/UserDataImportDtoMapper.kt`, `.../mappers/BaseErrorMapper.kt`, tests, `docs/openapi.json`
(regenerated by the hook).

**What.** Spec section 7. The chunk endpoint consumes an `InputStream` and is annotated blocking
deliberately, with the one-line reason: streaming holds only while the method is blocking and while
no extension installs a global body handler. No progress field; the two counters ship raw.

**Acceptance.**
- Controller tests for each endpoint and each error code, asserting `application/problem+json` and
  the `code` extension.
- The DTO carries `issueDetailTruncated`, and a mapper test pins it, since the spec promised the flag
  in one section and omitted it from another in its first draft.
- `docs/openapi.json` is regenerated and the CI sync check passes.
- `./gradlew gate`.

---

## Block 7: operations

Three tasks, mutually independent. All depend on block 6.

### 13. Sweeps

**Files.** `api-usecases/.../imports/ReapAbandonedUserDataImports.kt`,
`api-usecases/.../ReapOrphanedStorage.kt` (extended),
`api-worker-quarkus/.../ImportLifecycle.kt`, tests.

**What.** The three paths of spec section 6: abandon a stale `AWAITING_ARCHIVE` row by **inactivity**
rather than age, reclaim terminal rows' bytes once and stamp them so the hourly sweep does not
re-delete for ever, and move a `RUNNING` row whose task is `DEAD` or absent to `FAILED` with
`IMPORT_INTERRUPTED`. `ReapOrphanedStorage` gains the import half, pairing `forEachStorageKeyOnDisk`
with `findMissingImportIds`, without which an archive promoted by a completer that died is
unreclaimable. Its own `PeriodicScheduler`, injected by type per ADR 0004.

**Acceptance.**
- Each of the three paths has a test, called on the bean directly rather than waiting on the
  interval, following `MeExportCompletionIntegrationTest`'s handling of the export purge.
- An upload still receiving chunks is **not** abandoned even when older than the grace, which is the
  defect the first draft carried.
- A terminal row whose bytes are already gone is not re-processed on a second run, asserted on the
  reclaimed count.
- A promoted archive with no row is swept; an in-flight staged file of another store is not.
- `./gradlew gate`.

### 14. Account deletion erases imports

**Files.** `api-usecases/.../AccountDeletionCleaner.kt`, tests.

**What.** Issues then import rows inside the transaction, before the user row; archive bytes after the
commit, keyed on the **derived** key rather than the stored column, so an archive promoted by a
completer that died is still reclaimed. This mirrors the export half already in that class.

**Acceptance.**
- Delete an account holding one `COMPLETED` and one `AWAITING_ARCHIVE` import: no rows in either
  table, nothing left in `imports.data_dir`, and the assertion runs against a real store rather than
  a mock, since this is the one commitment whose failure is invisible to a mocked repository.
- `./gradlew gate`.

### 15. Wiring, configuration, deployment

**Files.** `api-worker-quarkus/.../ImportsConfig.kt`, `api-application/.../wiring/ImportProducers.kt`,
`api-application/src/main/resources/application.properties`, `Dockerfile`, a startup check, tests.

**What.** Every default in the `@ConfigMapping`, `imports.*` added to the prefix inventory in
`application.properties`, and the trap that file already documents extended: the image route's
30 MiB bound is enforced by the use case and the framework limit is a backstop, which chunking leaves
untouched. `images.max_file_bytes` and `images.max_pixels` are passed to the runner as constructor
parameters wired here, since `ImagesConfig` lives in the presentation module. `IMPORTS_DATA_DIR`
joins the two data directories the `Dockerfile` names, and the directory is checked writable at
startup rather than on first write.

**Acceptance.**
- A boot test with an unwritable `imports.data_dir` fails startup with a message naming the path.
  Without it, the failure lands after a user has streamed gigabytes.
- A configuration test asserting `imports.max_chunk_bytes` is not above
  `quarkus.http.limits.max-body-size`, so the two cannot drift apart silently.
- `./gradlew gate`.

---

## Block 8: end to end

One task. It depends on every block before it.

### 16. The integration suite

**Files.** `api-application/src/test/.../MeImportIntegrationTest.kt`,
`.../MeImportRoundTripIntegrationTest.kt`, `.../MeImportTestProfile.kt`.

**What.** The scenarios spec section 13 marks as integration and that no earlier task could host,
because they need the whole application: the round trip, the non-empty account with differing case,
chunked upload including resumption, the per-line anomaly archive, the lying manifest, and the
recycled-name refusal. A dedicated profile rather than widening `MeExportTestProfile`, which two
suites already share.

**What this task is not.** It does not carry the unit coverage of earlier tasks. Each behaviour was
pinned where it was built; these cases exist because the wiring itself is the thing under test.

**Acceptance.**
- The round trip asserts the enumerated equivalence of spec section 13.1, field by field, including
  the recycled board's membership and two pins sharing one image producing one pin. "Equivalent" as
  a word is not an assertion, which is why the list is in the spec.
- Chunked upload: three chunks, a replayed offset refused with the current length, a resumed upload
  completing from the reported length.
- The anomaly archive reaches `COMPLETED` with the good pins created and each expected issue kind
  appearing exactly once.
- `./gradlew gate`, and the full output pasted in the final message.

---

## What this plan does not settle

- **The lease-expiry race is pinned by a fake, not by a real worker.** Block 5 says so. If block 5's
  implementer finds a deterministic way to provoke it through the queue, that is better and the
  finding belongs in the wrap.
- **`jakarta.ws.rs` may have no `INSUFFICIENT_STORAGE` constant.** Task 12 checks it and falls back to
  the title path, reporting which it used.
- **`agents/engineering.md` is amended in task 9's commit**, the one that establishes the timestamp
  exception in code, per the ADR's `Amends` relation and the simultaneity rule in `agents/writing.md`.
