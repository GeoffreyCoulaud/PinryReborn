# Handoff — User data export (portability, export half)

**Date:** 2026-07-23
**Branch:** `feat/user-data-export`
**Spec:** `docs/specs/2026-07-22-user-data-export.md` · **Plan:** `docs/plans/2026-07-22-user-data-export.md`

## What this is

The **export** half of the backlog's "user data export / import (portability)" item. An authenticated
user requests an archive of all their data, the archive is built asynchronously on the task queue,
and they download it over a range-aware HTTP endpoint. Import was deliberately split out and is now an
import-only backlog item; the archive format (spec §4) is its contract.

Scope decision (operator, at kickoff): **ship export only**. Quality bar: "robuste, fiable, évolutif...
l'utilisateur maître de ses données". Design cross-checked against GitHub migrations, Mastodon, Google
Takeout / Data Portability, and the Data Transfer Initiative (spec §16).

## What was built (in commit order)

Two pre-existing bugs first, each its own commit, because the export is the first consumer that trips them:

- **0a — cursor pagination tie-breaker.** `PinModelSortStrategy` ordered on `whenCreated` (or
  `softDeletedAt`) alone. When a page boundary fell inside a group sharing that instant, the cursor
  never advanced: the API served a stuck page; an export draining the cursor would write an unbounded
  ZIP. Fixed with the keyset `(timestamp, id)` on every pin sort strategy.
- **0b — queue re-claim + lease renewal.** `tasks.lease_duration` is `PT1M`, the reaper runs every 30s,
  and `claimNext` never compared `attempts` to `maxAttempts`, so a task whose handler never returns was
  reclaimed forever. Fixed in three parts: `claimNext` now marks an attempts-exhausted task `DEAD`; a
  fenced `renewLease(id, leaseId, until)` was added to the queue; and the lease heartbeat was plumbed
  from `TaskProcessor` (which computes `now + leaseDuration`, the duration flowing in from
  `TaskDispatcher`) through a new `TaskContext.renewLease` no-op-by-default callback to the handler.

Then the feature, layer by layer:

- **Domain:** `UserDataExport` entity + `UserDataExportState` (`PENDING/READY/FAILED/EXPIRED/DELETED/SUPERSEDED`,
  `isGone`), `UserDataExportRepositoryInterface`, `ExportArchiveStore` port (`ArchiveSink`,
  `ArchiveFormat`, `ArchiveEntryDigest`), `ExportAlreadyInProgressException`. `StagedFile` moved from
  `domain.images` to `domain.storage` (shared by images and exports). Creation timestamps promoted onto
  `User/Pin/Board/Tag`, **non-nullable and stamped by the creating/updating use case** from the `Clock`
  port (see "Timestamps are domain data" below). `findBoardsForPinIncludingRecycled`
  added so the export sees recycled-board memberships.
- **Persistence:** `user_data_exports` table (migrations 1.10 + 1.11, the latter a partial unique index
  `WHERE state = 'PENDING'` declared on the model with `@Index(definition = ...)` so it lives in the
  migration model rather than in hand-written SQL), model, mapper, repository (`state` stored as `String`, never an ordinal;
  the mapper never dereferences the associated user, which may be a tombstone).
- **Storage adapter:** `FilesystemZipExportArchiveStore` (stage into a temp file, measure size + SHA-256,
  fsync, promote by atomic rename), `ZipArchiveSink`, `CountingDigestOutputStream`. Jackson is
  adapter-only (BOM + jsr310 added to `api-storage-filesystem`).
- **Use cases:** `UserDataExportRequester` (step-up re-auth, one live archive per user, cooldown,
  supersede-then-delete-outside-the-transaction), `UserDataExportBuilder` (the walk + the build state
  machine with a compare-and-set publish), content types + `ExportReadme` + `ExportImageExtension` +
  golden-JSON test, `UserDataExportGetter/Downloader/Deleter` (+ the non-nullable `OpenedExport`
  projection), `ReapExpiredUserDataExports`.
- **Worker:** `UserDataExportTaskHandler` (`account.export`), `ExportsConfig`, `ExportRetentionLifecycle`
  on its **own** single-thread scheduler (`EXPORT_PURGE_SCHEDULER`) so multi-GB deletes never block task
  claiming.
- **Wiring:** `ExportProducers` in `api-application` (composition root) produces `ExportArchiveStore`,
  the requester, the builder and the reaper; the getter/downloader/deleter are `@ApplicationScoped`.
- **Presentation:** `MeExportController` (`/api/v1/me/exports`: POST/GET-list/GET-one/GET-download/DELETE),
  `RangeHeader` (single range only; suffix/multi ignored), `ContentDispositionFileName` (RFC 5987/6266,
  hand percent-encoded), `Retry-After` and `416` mappers.
- **Account deletion:** `AccountDeletionCleaner` now erases export rows in the transaction and reclaims
  archive bytes after the commit, deriving each key from the id (not the stored column).
- **Tests:** unit tests everywhere (100% branch per package), plus `api-application` end-to-end
  (`MeExportIntegrationTest`, `MeExportCompletionIntegrationTest`) with a **real worker**: the central
  test downloads real bytes, opens the ZIP, and verifies the recycled-board membership and byte-identical
  image content.

## Pitfalls learned (read before touching this)

- **`Pin.image` is ALWAYS null.** `PinModelMapper.toDomain` never populates it, so the builder resolves
  images via `imageRepository.findByPinId`. Reading `pin.image` would silently produce an archive with
  zero images and unit tests that pass. This is exactly why the archive-content integration test downloads
  real bytes instead of trusting mocked shapes.
- **Timestamps are domain data, not a persistence concern.** `createdAt`/`updatedAt` are non-nullable on
  `User/Pin/Board/Tag` and stamped by the use case (`UserCreator`, `TagCreator`, `BoardCreator`,
  `PinCreator`, `PinTagger`, `PinBoardSetter`, `BoardUpdater`) from the `Clock` port, mirroring
  `Image.createdAt`. The models must therefore NOT inherit Ebean's `@WhenCreated`/`@WhenModified`:
  `GeneratedInsertJavaTime` returns the clock unconditionally and would silently discard the domain
  value. `AuditedBaseModel` keeps the generated columns for the entities whose domain type has no
  timestamp (tasks, session tokens, password hashes, exports); the others declare ordinary columns
  keeping the historical `when_created`/`when_modified` names via `@Column(name = ...)`, so the change
  costs no migration. Because `User.toModel()` now carries a real `createdAt`, the author placeholder is
  complete and `save*` maps the merged model directly instead of re-reading it by id.
- **`SystemClock` truncates to the millisecond, deliberately.** The SQLite store round-trips instants at
  millisecond resolution. A nanosecond-precision `Instant.now()` written into an entity comes back
  *different* on the next read, and since entities are compared by value, an authorization check as
  blunt as `pin.author != user` starts rejecting the legitimate owner. Matching the clock's resolution
  to the store's kills that class of bug at the source; `RepositoryTest.storableNow()` is the test-side
  equivalent for entities built by hand.
- **The queue re-claim bug** (0b) is subtle: without lease renewal, two concurrent builds share one
  deterministic storage key and the compare-and-set loser deletes the winner's bytes, leaving a READY row
  pointing at a missing file. Lease renewal is the primary defense; the CAS is the backstop.
- **The unique-index violation surfaces as `jakarta.persistence.PersistenceException`**, NOT
  `io.ebean.DuplicateKeyException`, and only a PENDING write may be translated to "already in progress"
  (a failed state transition must keep its own error). The persistence adapter throws the domain
  `ExportAlreadyInProgressException`; the requester translates it to the use-case `ExportAlreadyInProgressError`.
- **detekt gotchas that bit multiple subagents:** `ThrowsCount`/`ReturnCount` max 2 per function (extract
  helpers, don't suppress); `SwallowedException` (rethrow or pass as `cause`); a literal `*/` in a KDoc
  closes the comment. A `@ConfigMapping` interface and a pure data class need no test.
- **CDI ordering across tasks:** a class taking a raw `Duration`/`Int`/`String`/`Long` cannot be a
  discovered `@ApplicationScoped` bean; it must be produced. That is why the requester, builder and reaper
  are produced and the getter/downloader/deleter are annotated. A single unsatisfied bean fails ALL
  `@QuarkusTest`s at boot, not just one.

## What is NOT validated

- **Real hardware / production.** Everything below is green in the local + CI gate (all module tests,
  `api-application` integration with a real async worker, `koverVerify` 100% branch, detekt) under JDK 25
  with libvips installed. No deploy has run.
- **Very large accounts.** The design accepts N+1 reads (spec §3, §11): two queries per pin for tags and
  boards, one for the image, one for the unfiltered memberships, and the second pin walk doubles the pin
  reads. Hundreds of thousands of SQLite queries on a huge account. Acceptable for a rare async export; a
  bulk read path is a recorded future optimisation.
- **ZIP64 beyond the synthetic test.** The adapter test exercises 65 600 entries (past the classic 65 535
  cap) in ~350ms. Real multi-GB archives, and the >4 GB boundary, are untested.
- **Resumed downloads through a real proxy/CDN.** `Range`/`206`/`416` and the eager stream open are unit-
  and integration-tested against RESTEasy, not against a real reverse proxy or a browser's resume.
- **The exports on-disk volume in a real container.** The Dockerfile documents that `images.data_dir` and
  `exports.data_dir` must be redirected to writable mounted paths, but no container run has confirmed the
  export archive round-trips through a mounted volume.

## Suggested next step

Import (the backlog item). The archive format is the contract; spec §4 and the golden-JSON test pin it.
Open questions to spec before building: id remapping vs preservation, conflict handling with existing
rows, image de-dup on re-upload, and how much of the archive to trust (manifest/signature verification).

## Integration

Non-doc changes reach `main` through a PR (branch protection requires `validate / gate`; rebase-only, no
merge commits). After merge, confirm the finished item is removed from `docs/backlog.md` (done here: the
portability item is now import-only) and tag `vX.Y.Z-user-data-export`.
