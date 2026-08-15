# Backlog

**Living document.** What is still open, banded by nature first and by priority second. What already shipped lives
in git history, the handoffs under `docs/handoffs/`, and the annotated `vX.Y.Z-*` tags, not here.

## How to use this file

- This file holds **open items only**. Do not keep a "shipped" log here: completed work is recorded by git
  history, the handoffs under `docs/handoffs/`, and the annotated `vX.Y.Z-*` tags.
- **Three bands, by nature.** *Open work* is what someone will do. *Known limits* points at the document that
  records each one and holds no copy of it. *Before beta* holds dated events no session can start early. A limit
  is not debt and is not counted as debt.
- **Open work is grouped by priority**, not by module. Each item states the context and what must be decided or
  done, with a pointer to the relevant spec or handoff when one exists. `P0` = product decisions that shape the
  data model and the UI. `P1` = client ergonomics needed for the web UI and the browser extension. `P2` =
  operational debt (not UI blockers).
- **A review finding has four exits and only one is this file**: fixed inside the lot, a backlog item, an
  accepted limit written where the decision lives, or refused with the reason in the handoff
  (`docs/adr/0010-review-finding-dispositions.md`). Wrap states which exit each finding took.
- When an item is picked up, note the branch or sub-project next to it; when it merges, **delete it from this
  file** (its record now lives in the handoff and the tag).

---

## Open work

### P1: Client ergonomics (needed for the web UI and browser extension)

- **Browser-extension CORS origin.** Deferred from the CORS sub-project (decision B1): the extension
  does not exist yet and has no stable ID, so no origin is wired for it. When it ships, add its
  `chrome-extension://<id>` / `moz-extension://<id>` origin to `api.cors.origins`. See
  `docs/handoffs/2026-07-21 - handoff - cors.md`.
- **Import follow-ons.** Import shipped (`docs/specs/2026-08-14-user-data-import.md`,
  `docs/adr/0015-import-identifies-by-natural-key.md`, branch `feat/user-data-import`); what it
  deliberately left out is here rather than in the spec's out-of-scope list, because these are work
  someone will do rather than limits: **selective import** (one board, or skipping the recycle bin)
  and its mirror **partial export**; **merging metadata onto a pin that already exists**, which is
  the option the v1 "skip" rule forecloses; and **making a pin with no medium travel**, which needs
  the export to carry `ImageDownload` so a pending or failed download survives the round trip.

### P2: Operational debt

- **The rows two actors can write are fenced one by one, and nothing structural forces the next one.**
  `Persistor.merge` writes every column, so saving an entity read earlier restores that entity's whole
  state, including whatever another actor committed in between. The general answer this item used to
  propose, a version column on every model that lacks one, is refused:
  `docs/adr/0016-fence-by-compare-and-set.md` decides that a shared row is fenced by re-reading it and
  testing a predicate inside the transaction that writes it, the datasource holding one connection
  (`docs/adr/0012`). `TaskModel`'s `@Version` stays as the live back-stop it is, with no domain
  surface. The imports are fenced (`docs/specs/2026-08-14-user-data-import.md` sections 6 and 8) and so
  are the exports (`docs/specs/2026-08-15-export-row-fencing.md`). What is left is two halves. **The
  writers with a dangerous pair that are still exposed**: `PinModel` and `BoardModel`, whose exposure
  needs two simultaneous requests from the same owner and whose loss the user repairs by repeating the
  action, which is why they were left out of the export lot. **The rule that catches the writers
  nobody fenced**: `ImportStateMergedOutsideTransaction`'s reach is one package, so the `exports`
  package has no static guard on the shape it now holds. Widening it is one line in
  `config/detekt/detekt.yml`; renaming it, which its own KDoc anticipates, touches eight files. The
  seven entities with no dangerous pair are insert-only, single-actor or already compare-and-set, and
  are named in the export spec's section 8 so nobody re-derives the list.
- **Two attempts of one export build can overlap, and the loser deletes the winner's archive.**
  `tasks.lease_duration` is `PT1M`, and the last stretch of a build renews nothing: the manifest
  write, the channel force on a multi-gigabyte ZIP, and the promote. `EbeanTaskQueue.reapExpired`
  returns the task to `PENDING`, a second worker claims it, and **both attempts read a legitimate
  `PENDING` row**, so no predicate on state can tell them apart and the export lot's fences pass both
  (`docs/specs/2026-08-15-export-row-fencing.md` section 8). Both derive the same storage key from the
  export id and both promote onto it; the loser's `publish` fence then refuses and calls
  `deleteQuietly` on the key the winner just published. The row reads `READY`, the archive is gone,
  the download answers `500` rather than `410`, and `ReapOrphanedStorage` cannot see it because it
  reclaims only keys with no row. The fix is a claim token on the export row, as
  `UserDataImportRunner.runToken` is on the import: a column and a migration, which is why the export
  lot left it out. Filed with it, and enabling it: **`TaskContext.renewLease` is typed `() -> Unit`**
  while `TaskQueueInterface.renewLease` answers a `Boolean` documenting that it no-ops once the task
  is no longer `RUNNING` under this lease. The result is coerced away, so a handler is never told its
  lease is gone and keeps working. Surfacing it, or having the context throw, is what would let a long
  handler stop itself. Named by the export fencing lot and deliberately left out of it.
- **An export can stay `PENDING` for good, and no sweep clears it.** `EbeanTaskQueue.claimNext` kills
  an attempts-exhausted task inline (`state = DEAD`, `return null`) **without ever invoking the
  handler**, so `UserDataExportBuilder.markFailed` never runs and the export row keeps its `PENDING`
  state. `ReapExpiredUserDataExports` selects only `READY` rows past `expiresAt`, so nothing sweeps
  it; `ReapTerminalTasks` then deletes the `DEAD` task after `garbage-collection.terminal_task_grace`
  (`P7D`), removing the forensic link. The user holds his one `uq_user_data_exports_pending` slot and
  every `POST /api/v1/me/exports` answers `409` until he happens to `DELETE` the stuck row, which does
  work. The import half has exactly the sweep the export half lacks:
  `ReapAbandonedUserDataImports.failInterruptedRuns` finds `RUNNING` imports whose task is gone and
  fences them to `FAILED`. An export twin is the shape. Named by the export fencing lot and
  deliberately left out of it.
- **The export endpoints publish a status they do not answer, and no error at all.**
  `MeExportController` carries no `@APIResponse`, so SmallRye reads each status off the return type
  and a runtime `ResponseBuilder` carries none: `POST /api/v1/me/exports` is published as `200` where
  it answers `202`, and the only failures in `docs/openapi.json` are the framework's own `401` and
  `403`. None of the refusals a client has to handle is there: `409 EXPORT_ALREADY_IN_PROGRESS`,
  `429 EXPORT_TOO_SOON`, `404 EXPORT_DOES_NOT_EXIST`, `409 EXPORT_NOT_READY`, `410 EXPORT_GONE`, and
  the download's `206` and `416`. **The import half of this defect is fixed and the export half is
  not**: `MeImportController` declares every status by hand, with the reason in a comment on the
  first one (`docs/specs/2026-08-14-user-data-import.md` section 7). Two halves of one feature now
  describe themselves differently, which is the drift this item exists to close. Named by the import
  lot and deliberately left out of it.
- **A malformed request body does not answer in this project's error format, and the import lot moved
  which bodies land there.** `agents/engineering.md` requires one error format everywhere, framework
  generated malformed payloads included, and no mapper covers Jackson's own failures:
  `ConstraintViolationExceptionMapper` catches only the bodies that deserialize and then fail
  `@Valid`, so a body that fails to deserialize gets Quarkus's own `400` rather than a `ProblemDetail`
  built by `ProblemResponses`. The user data import declared `jackson-module-kotlin` on
  `api-storage-filesystem`, and an `implementation` dependency reaches `api-application`'s runtime
  classpath, where `quarkus-kotlin` registers `KotlinModule` on the CDI `ObjectMapper` with no opt-in
  (`docs/specs/2026-08-14-user-data-import.md` section 5). Every REST request body now binds through
  it, and a field a DTO declares non-nullable but the body omits fails inside Jackson instead of in
  the Kotlin constructor's null check. Nothing asserts either shape. To decide: an exception mapper
  over `JsonProcessingException` and its `MismatchedInputException` subtypes, and which `code` it
  publishes. Named by the import lot and deliberately left out of it.
- **`EbeanTaskQueue.reapExpired` selects every expired lease at once.** `findList()` carries no
  `setMaxRows`, so one sweep materialises and re-saves as many task rows as have expired, row by row
  inside a single transaction, on the one write connection. Bound the selection the way a claim is
  bounded. Named by the import lot and deliberately left out of it.
- **`ImportStateMergedOutsideTransaction` reads a construction as an insert.** A `save` whose argument
  starts with an upper-case callee is passed as a fresh row, so a row rebuilt from one read elsewhere
  (`UserDataImport(id = old.id, ...)`) walks through the rule untouched. The inversion is deliberate
  and its reasons are in the rule's KDoc; what is open is whether a second condition can tell a
  rebuild from an insert without type resolution. Named by the import lot and deliberately left out
  of it.
- **The tag respelling is a contract change nobody published.** `PUT /api/v1/pins/{pinId}/tags` with
  `Landscape` when `landscape` is stored now answers the stored spelling and creates no second tag
  (`docs/specs/2026-08-14-user-data-import.md` section 12), and `docs/openapi.json` says nothing about
  it: the endpoint carries no `@Operation` and no summary of the ASCII fold. Named by the import lot
  and deliberately left out of it.
- **An absent `offset` on a chunk upload defaults to 0, undocumented.**
  `PUT /api/v1/me/imports/{id}/archive` treats a missing `offset` as the start of the upload, with the
  reason at the site, but the parameter is published as a plain nullable integer with no default and
  spec section 7 writes it as `?offset=N`. A client reading either cannot tell whether omitting it
  starts the upload or is refused. Named by the import lot and deliberately left out of it.
- **Re-measure the review regime after three lots.** `docs/adr/0014-review-budget-upstream.md` moved
  the review budget upstream on figures taken from the session transcripts, and nothing in this
  repository reproduces them. After three lots have run under the new regime, re-measure the three
  quantities the decision moves: the share of spend that goes to reviews, the hours between
  consecutive implementers, and the findings per review by kind. The question the numbers answer is
  whether asynchronous block review is paying for its rework and whether the angles are earning
  their dispatch. Whether the repository should own a transcript-measuring tool rather than a
  throwaway script is part of the item, not a prerequisite.

## Known limits

Recorded where the decision lives. None is a copy: follow the pointer.

- **Soft-delete read isolation leaves residuals.**
  `docs/adr/0008-structural-soft-delete-read-isolation.md`, and
  `docs/specs/2026-07-29-single-representation-soft-delete.md` section 4.6.
- **A unique constraint's named outcome is not checked against what the code does.**
  `docs/adr/0009-unique-index-named-outcomes.md`, decision 1.
- **The evidence guard is fired on more tools than it inspects**, deliberately, and narrowing it
  would set a worse trap than the cost it saves.
  `docs/adr/0011-own-the-agent-instructions.md`, consequences.
- **The partial-index state guard has a declared reach**, and one part of it is a correctness gap
  rather than a documentation one: it pins the predicate, not the uniqueness columns that make
  `findOne()` return at most one row. `PartialUniqueIndexStates` and
  `api-persistence-sqlite/src/test/kotlin/.../migration/PartialUniqueIndexStatesTest.kt`.
- **Authentication attempt counters are per process, and holding one account's login closed is
  cheap.** `docs/adr/0013-in-memory-authentication-attempt-limiting.md`: decision 1 (counters reset
  on restart, correct only while the deployment is one instance), decision 3 (the measured cost of
  keeping a named account out, and the `forget_after` / last-step interaction behind it), decision 4
  (eviction is a bypass, and nothing purges outside a recorded failure).

## Before beta

Dated events. No session starts these early.

- **Flatten the migration history.** The migration history is append-only, and that already constrains
  fixes: `users`/`pins`/`boards`/`tags` keep `when_created` / `when_modified` column names that no
  longer match the domain's `createdAt` / `updatedAt`, kept only because rewriting an applied migration
  changes its checksum and breaks startup. At beta, collapse `1.0` to `1.n` into a single generated
  baseline and take that fix with it. Until then, when a fix is blocked only by an already-applied
  migration, prefer the clean design and record the debt here. New 2026-07-23.

## Features

- **Perceptual `ImageHash` (pHash)** for pin deduplication / merging. Flagship of the sequenced **user-segmented base
  ** (see the roadmap section below).
- **Advanced pin / tag / board management** : Features that make the data model genuinely user-segmented and pleasant to
  use. To be explored.
- **Import from 3rd party sites**
  Initial candidates :
    - Pinterest (board import),
    - Danbooru / Gelbooru / Other booru (favorites import),
    - Instagram (saved collection import),
    - Reddit / Twitter / Pixiv (saved posts import).

  On some of these sites, a post may contain multiple media. We're not changing our semantic 1 pin = 1 media rule.
  Can be either a one-time import, or to sync a local pinry board with a remote source periodically, as the user
  chooses.
- **Video support** : Completes the 3rd party use case, since those allow posting videos as well. Videos are a 1st class
  citizen, just like images. Their renditions are the video's thumbnail in case of a still rendition, or an animated
  image of the first few seconds of the video (eg. 3s)
- **RBAC and quota system** : Allow admins to toggle features and define quotas per-role, from the API
- **Audience mechanics (public / private).** Until this lands everything stays `@Authenticated` and owner-scoped (
  non-owner → 403); no anonymous browsing, no public gallery, no shareable links. It will interact with boards (public /
  shared boards) and with the profile items.
- **Two-factor / step-up authentication**: TOTP + Passkey/WebAuthn, with a possible short-lived "sudo" elevation token
  for sensitive actions.

Gated on audience mechanics :

- **Hard-copy of a public pin or board** from user B into user A's own collection: a real, independent copy, not a soft
  link.
- **Public profiles**: the deferred slice of profile management.
