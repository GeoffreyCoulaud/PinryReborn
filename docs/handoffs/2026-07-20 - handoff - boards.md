# Handoff — Boards (collections)

**Date:** 2026-07-20
**Branch:** `feat/boards` (17 code/doc commits on top of `main` @ `a4f6755`; base after the two main-side
backlog commits is `12f0569`)
**Spec:** `docs/specs/2026-07-20-boards.md` · **Plan:** `docs/plans/2026-07-20-boards.md`
**Status:** Feature complete. Full local gate green (`detekt test koverVerify`, JDK 25) across all modules,
100% branch coverage held. Whole-branch holistic review (opus) found 1 Critical + 1 Important, both fixed
and re-reviewed (READY TO MERGE = YES). Merge-ready pending CI (`validate / gate`).

## Why this exists

Boards are Pinry's core organising concept and the prerequisite the UI/extension work was blocked on.
Decided with the operator: a pin belongs to **0..N boards** (optional, many-to-many); visibility/sharing
and board-name uniqueness are explicitly out of scope (see the spec §2 and `docs/backlog.md`).

## What this delivers

A `Board` is a named, describable, owner-scoped collection.

- **Board CRUD:** `POST /api/v1/boards`, `GET /api/v1/boards` (all active, sorted by name
  case-insensitively with id tie-break), `GET /api/v1/boards/{id}`, `PUT /api/v1/boards/{id}`,
  `DELETE /api/v1/boards/{id}` (soft-delete).
- **Board pins:** `GET /api/v1/boards/{id}/pins` (active pins, cursor pagination + `PinSortStrategy`).
- **Membership (pin side, set-based, mirrors tags):** `PUT /api/v1/pins/{id}/boards` replaces the pin's
  board set from a list of **existing, active, owned** board ids (invalid id → 400, whole request fails).
- **Recycle bin (mirror of `PinRecycleBin`):** `GET /api/v1/boards/recycled`,
  `POST /api/v1/boards/recycled/{id}/restore`, `DELETE /api/v1/boards/recycled/{id}`,
  `DELETE /api/v1/boards/recycled`.
- **DTO surface:** `PinOutputDto` gains `boards: List<BoardRefDto>{id,name}`; `BoardOutputDto` carries a
  live `pinCount` (active pins); the recycled listing uses a light `RecycledBoardDto` (no `pinCount`,
  operator-confirmed option b).

Invariants: owner-scoping matches the pin precedent (403 non-owner, 404 unknown/recycled on an active
route); soft-delete **preserves** `pin_board_model` rows so restore is exact; permanent delete (of a board
or a pin) clears the relevant join rows and never deletes the counterpart; a recycled board never appears
in a pin's `boards`.

## Structure (per module)

- **api-domain**: `Board` entity; `Pin` gains `boards: List<Board>` (no default, mirrors `tags`);
  `BoardRepositoryInterface` (10 methods incl. the state-agnostic `findBoardById`);
  `PinRepositoryInterface.findActivePinsForBoard`.
- **api-persistence-sqlite**: `BoardModel` (`@Table("boards")`), `PinBoardModel` (join → `pin_board_model`),
  `BoardModelMapper`, `BoardRepository`; `PinRepository` boards plumbing (`getBoardsForPin` active-only,
  `savePinBoards` active-only set-diff, `findActivePinsForBoard`, join cleanup on pin permanent-delete);
  migration **1.7** (additive).
- **api-usecases**: `BoardCreator`, `BoardUpdater`, `BoardGetter` (+ `countActivePinsForUserBoard`),
  `BoardPinLister`, `PinBoardSetter` (mirror of `PinTagger`), `BoardRecycleBin` (mirror of `PinRecycleBin`);
  board `ErrorCode`s + exception hierarchies.
- **api-presentation-quarkus**: board DTOs, `BoardMapper`, `PinOutputDto.boards`, `BoardController`,
  `BoardRecycleBinController`, `PinController.setBoards`, `BaseErrorMapper` arms.
- **api-application**: 23 REST-Assured integration tests (CRUD, membership, recycle-bin lifecycle,
  owner-scoping, empty board, and the two regression tests from the holistic review); regenerated
  `docs/openapi.json`.

## Learned pitfalls (read before touching this again)

1. **`savePin` now re-diffs the board join on EVERY save, and `pin.boards` is loaded active-only.** The
   diff (`savePinBoards`) MUST compute `existingBoardIds` over **active** join rows only
   (`.board.softDeletedAt.isNull`), exactly like `getBoardsForPin`. The first shipped version diffed over
   *all* rows, so any unrelated `savePin` (e.g. `PinTagger.setTags`) on a pin that belonged to a
   **recycled** board silently deleted that join row, breaking "restore is exact". This was the Critical
   the per-task reviews structurally could not catch (each saw only Task 2) and the holistic review did.
   Fixed in `cd64a24` with regression tests at repo + integration level. Do not weaken the filter.
2. **Recycle-bin ownership-vs-state ordering.** `BoardRecycleBin` must find the board **state-agnostically**
   (`findBoardById`), validate ownership FIRST, then check `softDeletedAt` — mirroring `PinRecycleBin`.
   The first version used state-specific finders, which returned 404 for a double soft-delete (spec §8
   requires 409) and 409/404 for a non-owner (spec requires 403), and left `BOARD_ALREADY_SOFT_DELETED`
   dead. Fixed in `fa5e04f`.
3. **Ebean join table name.** A join model with no `@Table` becomes `pin_board_model` (snake of the class),
   NOT `pin_board`. It has no composite PK (follows `pin_tag_model`); duplicate rows are prevented by the
   `savePinBoards` set-diff, not by a DB constraint.
4. **Kover data-class trap.** `Pin.boards: List<Board>` has NO default (a non-null defaulted param generates
   a synthetic constructor branch the 100%-branch gate flags). `Board.softDeletedAt: Instant? = null` is
   fine (nullable `= null`). Adding `Pin.boards` required updating ~11 usecase + 1 presentation test files
   that construct `Pin(...)` directly.
5. **detekt `ThrowsCount`** on `PinBoardSetter.setBoards` (3 guard-clause throws) is baselined, matching the
   established `PinTagger`/`DeletePinImage`/`GetPinImage`/`SetPinImage` precedent — not restructured.

## NOT validated against real environment / hardware

- **CI has not run yet.** Green only on the local JDK-25 gate.
- **`pinCount` is 1+2N queries on `GET /boards`** and double-validates ownership on `getBoard`/`updateBoard`
  (`countActivePinsForUserBoard` re-calls `getActiveBoardForUser`). Accepted as a v1 tradeoff (spec §11);
  unmeasured under real load. A cheap fix is a count overload taking the already-validated board.
- **`findActivePinsForBoard` loads all of a board's pin ids up front** (`id.isIn(pinIds)`); fine for
  moderate boards, unmeasured for very large ones (spec §11).
- **Concurrency unvalidated:** two concurrent `PUT /pins/{id}/boards` are last-writer-wins, as with tags.
- **Only small test data** exercised; no real-world board/pin volumes.
- A **test-strength follow-up** the re-review flagged (non-blocking): the 403 recycle-bin tests use a
  correct-state board, so they don't independently distinguish the ownership-before-state ordering fix
  (correctness is structurally guaranteed and branch-covered). Optional.

## Suggested next step

The UI/extension is now unblocked on the data model. Per `docs/backlog.md`, the next priorities are the
**P1 client-ergonomics** items (a token/API-key auth story + `GET /me`, and CORS) needed before the SPA and
browser extension, then the parked visibility/sharing model if/when wanted (it will interact with boards).
Operational debt (P2): the `animated` backfill, the rendition cache GC sweep, perceptual `ImageHash`.
