# Backlog

**Living document.** The priority-ordered list of what is still open. What already shipped lives in git history,
the handoffs under `docs/handoffs/`, and the annotated `vX.Y.Z-*` tags, not here.

Last reviewed: 2026-07-26.

## How to use this file

- This file holds **open items only**. Do not keep a "shipped" log here: completed work is recorded by git
  history, the handoffs under `docs/handoffs/`, and the annotated `vX.Y.Z-*` tags.
- **Open items** are grouped by priority band, not by module. Each item states the context and what must be
  decided or done, with a pointer to the relevant spec/handoff when one exists.
- `P0` = product decisions that shape the data model and the UI; resolve these before building on top of them.
  `P1` = client ergonomics needed for the web UI and browser extension. `P2` = operational debt already flagged
  in handoffs (not UI blockers).
- When an item is picked up, note the branch/sub-project next to it; when it merges, **delete it from this file**
  (its record now lives in the handoff and the tag).

---

## Open items

### P0 — none open

### P1 — Client ergonomics (needed for the web UI and browser extension)

- **Browser-extension CORS origin.** Deferred from the CORS sub-project (decision B1): the extension
  does not exist yet and has no stable ID, so no origin is wired for it. When it ships, add its
  `chrome-extension://<id>` / `moz-extension://<id>` origin to `api.cors.origins`. See
  `docs/handoffs/2026-07-21 - handoff - cors.md`.
- **User data import (portability).** Export shipped (see `docs/handoffs/2026-07-22 - handoff - user-data-export.md`
  and the `v*-user-data-export` tag); the remaining half is **import**: let a user re-create their pins, boards,
  tags and images on this instance (or another one) from an export archive. The archive format is the contract:
  `docs/specs/2026-07-22-user-data-export.md` §4 defines the layout (`manifest.json`, `pins.jsonl`, `boards.jsonl`,
  `tags.jsonl`, `user.json`, `images/`), `formatVersion` is `1`, and every file's `sha256` is in the manifest.
  Open questions to spec: id remapping vs id preservation, conflict handling with existing rows, image de-dup on
  re-upload, and how much of the archive to trust (signature / manifest verification).

### P2 — Operational debt (flagged in handoffs; not UI blockers)

- **Expired session-token GC sweep.** `session_tokens` rows are inert once expired (verification rejects
  them), but they accumulate. No sweep in v1. See `docs/handoffs/2026-07-21 - handoff - session-token-auth.md`.

- **`animated` backfill migration** for pre-existing image rows. The only item with a correctness consequence:
  rows predating migration 1.6 are labelled `animated = false`, so `?animated=false` on such a row can serve the
  original animated bytes instead of flattening. See the renditions handoff, "NOT validated" section.
- **Cache GC sweep** for orphaned rendition subtrees. Eviction is best-effort; a failed eviction or a crash
  mid-write leaves a subtree forever. Spec §14 of the renditions sub-project.
- **Deleted-account residue GC.** If the `account.delete` task (profile management) fails partially or totally,
  an account can stay stuck in its Ebean soft-delete tombstone with orphaned child rows and on-disk files. A
  sweep should reclaim such tombstoned accounts and their residue. Note: the cleaner's disk loop wraps only
  `renditionCache.evictImage` in `runCatching`, not `imageStore.delete`, so a failed `imageStore.delete`
  propagates after the DB commit (the task then retries and no-ops, since the user is already gone), leaving
  byte residue; making the whole per-image cleanup best-effort would reduce this. New 2026-07-21.
- **Task worker observability: surface DEAD/failed tasks.** `TaskProcessor` swallows a throwing `TaskHandler`
  into a retryable outcome with no logging, so a task that exhausts its attempts and is marked DEAD is
  invisible to operators. A user who deleted their account gets a 202 but would silently stay tombstoned
  forever if the cleaner failed. Add logging/metrics on handler failure and on DEAD transitions. Exposed by
  profile management's end-to-end test. New 2026-07-22.
- **`findCurrentPasswordHash` tie-breaker.** "Current password" is the latest `user_passwords` row by
  `when_created` (an `@WhenCreated Instant`), with no secondary ordering key. Two hashes written in the same
  clock tick make the current-password determination nondeterministic. Practically unreachable for the human
  register-then-change flow, but a latent fragility on a security-critical read; a monotonic sequence column
  would remove it. New 2026-07-22.
- **`softDeletedAt` is still stamped inside the persistence adapter.** `PinRepository.softDeletePin` and
  `BoardRepository.softDeleteBoard` call `Instant.now()` themselves instead of taking the instant from the
  caller. That is the same layering problem that was just fixed for `createdAt`/`updatedAt`: when an entity
  was recycled is business data, so the use case should stamp it from the `Clock` port, as the creators now
  do. Fixing it means changing the repository interfaces (either passing the instant, or letting the recycle
  bin use cases go through `savePin`/`saveBoard` with an updated copy) and touching `PinRecycleBin` and
  `BoardRecycleBin`. Deliberately left out of the timestamps refactor to keep that change scoped. New
  2026-07-23.
- **Soft delete and restore no longer bump `updatedAt`.** Before the timestamps refactor, `pins.when_modified`
  and `boards.when_modified` were Ebean-generated, so recycling or restoring a row bumped them as a side
  effect. Those columns are now written by the mapper from the domain entity, and the four repository methods
  (`softDeletePin`, `restorePin`, `softDeleteBoard`, `restoreBoard`) mutate the model directly without
  touching `updatedAt`, so it now means "last content change" and no longer moves on a state change. Nothing
  reads it today except the export, and `deletedAt` carries the recycling state there, so this is a semantic
  decision to confirm rather than a bug: either accept the narrower meaning and document it in the export
  format, or bump `updatedAt` on those transitions (which the item above would make natural). New 2026-07-23.
- **`api-application` integration tests share a real on-disk database.** `EbeanDatabaseProducer` builds its
  DataSource from `System.getenv("DB_PATH")`, falling back to `data.db`, and ignores the `datasource.db.url`
  property that `api-application/src/test/resources/application.properties` sets to `jdbc:sqlite::memory:`.
  The `@QuarkusTest`s therefore all run against one gitignored file at the repo root that survives between
  runs, unlike `api-persistence-sqlite`, whose `RepositoryTest` truncates every table before each test. The
  practical symptom: editing an already-applied migration makes the whole suite fail with a checksum
  mismatch until the file is deleted by hand, and any leftover row can leak into a later run. Making the
  producer honour the configured URL (or pointing tests at a per-run temporary file) would isolate them. New
  2026-07-23.
- **Flatten the migration history at beta.** The project is alpha (see docs/project.md): breaking changes and data loss are
  acceptable, nobody should be running it yet. The migration history is nonetheless append-only, and that already
  constrains fixes: `1.2` is a hand-written case-insensitive unique index that `@Index(definition = ...)` would
  express today (`DbMigrationModelCoverageTest` lists it), and `users`/`pins`/`boards`/`tags` keep `when_created` /
  `when_modified` column names that no longer match the domain's `createdAt` / `updatedAt`, both kept only because
  rewriting an applied migration changes its checksum and breaks startup. At beta, collapse `1.0` to `1.n` into a
  single generated baseline and take both fixes with it. Until then, when a fix is blocked only by an already-applied
  migration, prefer the clean design and record the debt here rather than contorting around it. New 2026-07-23.
- **Perceptual `ImageHash` (pHash)** for pin deduplication / merging (deliberately YAGNI'd in sub-project 2b).
  Now promoted: it is the flagship of the sequenced **user-segmented base** (see the roadmap section below).

---

## Sequenced roadmap (deliberately ordered, not parked indefinitely)

**Audience / visibility is no longer parked indefinitely** *(re-scoped 2026-07-21; was parked 2026-07-20)*.
It is deliberately **sequenced after a solid user-segmented base**, in this order:

1. **User-segmented base — advanced pin / tag / board management.** Features that make the data model genuinely
   user-segmented and pleasant to use. Flagship: **pin merging via perceptual `ImageHash` / pHash** (see P2).
   Others to be explored when we get there.
2. **Audience mechanics (public / private).** Until this lands everything stays `@Authenticated` and
   owner-scoped (non-owner → 403); no anonymous browsing, no public gallery, no shareable links. It will
   interact with boards (public / shared boards) and with the profile items.

Gated on audience (mechanics to define):

- **Public profiles** — the deferred slice of profile management.
- **Hard-copy of a public pin or board** from user B into user A's own collection: a real, independent copy,
  not a soft link.

Security enrichment (not audience-gated; builds on the profile-management step-up brick):

- **Two-factor / step-up authentication** — TOTP + Passkey/WebAuthn, with a possible short-lived "sudo"
  elevation token for sensitive actions.
