# Backlog

**Living document.** What is still open, banded by nature first and by priority second. What already shipped lives
in git history, the handoffs under `docs/handoffs/`, and the annotated `vX.Y.Z-*` tags, not here.

## How to use this file

- This file holds **open items only**. Do not keep a "shipped" log here: completed work is recorded by git
  history, the handoffs under `docs/handoffs/`, and the annotated `vX.Y.Z-*` tags.
- **Four bands, by nature.** *Open work* is what someone will do. *Known limits* points at the document that
  records each one and holds no copy of it. *Before beta* holds dated events no session can start early.
  *Features* is the roadmap, unsequenced. A limit is not debt and is not counted as debt.
- **Open work is grouped by priority**, not by module. `P0` = product decisions that shape the data model and
  the UI. `P1` = client ergonomics needed for the web UI and the browser extension. `P2` = operational debt
  (not UI blockers). A priority may hold nothing; `P0` holds nothing today.
- **An item holds in two lines**: the symptom and where it lives, plus a pointer to the dated document that
  carries the reasoning. Never a copy of that reasoning (`agents/writing.md`, Rules). The exception is an item
  whose reasoning was only ever written here, which keeps it: dated documents are append-only, so compressing
  such an item destroys the argument rather than relocating it. Those are marked below. There is **no cap on
  the number of items**.
- **A lot closes the items adjacent to its subject**, and its spec says why it leaves any of them
  (`docs/adr/0018-a-block-is-a-pull-request.md`, decision 6). This file is not where adjacent work waits.
- **A review finding has four exits and only one is this file**: fixed inside the lot, a backlog item, an
  accepted limit written where the decision lives, or refused with the reason in the handoff
  (`docs/adr/0010-review-finding-dispositions.md`). Wrap states which exit each finding took. The first is the
  default: this file receives what the operator declined, not what was merely outside the original scope.
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

Every item below is taken by `docs/specs/2026-09-05-p2-debt-elimination.md`; the branch on each item is the
block that closes it.

- **`PinModel` and `BoardModel` keep a dangerous read-then-write pair unfenced, and the `exports` package has no
  static guard.** `docs/adr/0016-fence-by-compare-and-set.md`; `docs/specs/2026-08-15-export-row-fencing.md`
  section 8 (the two models, and the seven entities that need no fence);
  `docs/specs/2026-08-27-export-build-completion.md` section 8, "the `exports` package still has no static guard".
  Branches `fix/p2-debt-fences`, `fix/p2-debt-fence-rule`.
- **A task handler is never told it lost its lease**: `TaskContext.renewLease` is `() -> Unit` where the queue
  answers `Boolean`. Its reach, and why it was not done in the lot that found it:
  `docs/specs/2026-08-27-export-build-completion.md` section 8, first item. Branch `fix/p2-debt-lost-lease`.
- **`EbeanTaskQueue.claimNext` kills a task whose handler may still hold a live lease**, which the export sweep's
  `PT6H` grace only makes improbable. `docs/specs/2026-08-27-export-build-completion.md` section 8, fourth item.
  Branch `fix/p2-debt-lost-lease`.
- **The export test fixtures close only one direction.** `UserDataExportBuilderFixtures` extends the fake-store
  base, so a case driven by the mock still sees the fake store and an assertion on it would pass vacuously. No
  case does it today; the shape that closes both directions is a shared base with two siblings.
  *(Reasoning is only here.)* Branch `test/p2-debt-export-fixtures`.
- **`TaskQueueBootIntegrationTest` counts every row in `tasks` and expects exactly one**, in a shared profile where
  another class enqueues one (`docs/handoffs/2026-08-27 - handoff - export-build-completion.md`, pitfall 7).
  Counting its own kind removes the coupling. Left open on purpose: the mechanism was never reproduced, and
  repairing an unexplained symptom hides the next one. *(Last sentence: reasoning is only here.)* Refused by
  `docs/specs/2026-09-05-p2-debt-elimination.md` D7; leaves with the handoff, branch `fix/p2-debt-lost-lease`.
- **`MeExportController` declares no `@APIResponse`**, so `docs/openapi.json` publishes `200` where the endpoint
  answers `202` and carries none of the export refusals a client must handle. The import half is done and shows
  the shape to copy: `docs/specs/2026-08-14-user-data-import.md` section 7. Branch `fix/p2-debt-openapi`.
- **A request body that fails to deserialize gets Quarkus's own `400`, not a `ProblemDetail`**, and the import put
  every REST body through `KotlinModule` (`docs/specs/2026-08-14-user-data-import.md` section 5), which moved
  which bodies land there. To decide: a mapper over `JsonProcessingException` and which `code` it publishes.
  Branch `fix/p2-debt-error-format`.
- **`ImportStateMergedOutsideTransaction` reads a construction as an insert**, so a row rebuilt from an earlier
  read walks through untouched. The inversion is deliberate and the rule's own KDoc says why; open is whether a
  second condition can tell a rebuild from an insert without type resolution. Branch `fix/p2-debt-fence-rule`.
- **The tag respelling is a contract change nobody published.** `PUT /api/v1/pins/{pinId}/tags` answers the stored
  spelling and `docs/openapi.json` says nothing: no `@Operation`, no summary of the ASCII fold.
  `docs/specs/2026-08-14-user-data-import.md` section 12. Branch `fix/p2-debt-openapi`.
- **An absent `offset` on a chunk upload defaults to 0, undocumented.** `PUT /api/v1/me/imports/{id}/archive`;
  `docs/specs/2026-08-14-user-data-import.md` section 7 writes the parameter as `?offset=N` and states no default.
  Branch `fix/p2-debt-openapi`.
- **Measure what review costs and what it returns**, from the session transcripts: the share of
  spend that goes to reviews, and the findings per review by kind. Re-scoped from ADR 0014's
  re-measurement by `docs/adr/0018-a-block-is-a-pull-request.md`, which changed the regime without
  answering it and dropped the third quantity (hours between consecutive implementers) as meaningless
  once Act runs inline. Refused by `docs/specs/2026-09-05-p2-debt-elimination.md` D2; leaves with the handoff,
  branch `fix/p2-debt-lost-lease`.

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
