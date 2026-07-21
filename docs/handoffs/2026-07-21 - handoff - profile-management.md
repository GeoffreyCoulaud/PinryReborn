# Handoff: Profile management (change password + delete account)

**Date:** 2026-07-21
**Branch:** `feat/profile-management` (in-place, off `main`)
**Spec:** `docs/specs/2026-07-21-profile-management.md` · **Plan:** `docs/plans/2026-07-21-profile-management.md`
**Status:** Feature complete. Full local gate green (`./gradlew check koverVerify`, all modules,
`detekt` + `koverVerify` 100% branch coverage per package, JDK 25, libvips-backed image tests ran for
real). Merge-ready pending CI (`validate / gate`). 19 commits.

## What this delivers

Two authenticated self-service capabilities on `/api/v1/me`, both behind a **step-up
re-authentication** (the current password today; a second factor later).

- **Change password (`PUT /api/v1/me/password`)** `{currentPassword, newPassword}`: verifies the
  current password (403 `REAUTHENTICATION_FAILED` on mismatch), rejects any password ever used before
  by this user (422 `PASSWORD_PREVIOUSLY_USED`, checked against the full history), appends the new
  hash, and **revokes ALL sessions including the caller's own token** (decision: a stolen current
  token must not survive a password change). Returns **204**.
- **Delete account (`DELETE /api/v1/me`)** with `X-Reauthentication: password <base64url>`: an
  **async hard delete**. Verifies the step-up factor (missing header → 403; malformed / unsupported
  factor kind → 400 `UNSUPPORTED_REAUTHENTICATION_FACTOR`), marks the account with a one-way Ebean
  `@SoftDelete` tombstone (mirrored on the domain by `User.softDeleted`), revokes all sessions, and
  enqueues an `account.delete` task. Returns **202**. The worker's `AccountDeletionCleaner` then
  erases the user's rows in FK order in one transaction (per-pin image/download cleanup → pins →
  boards → tags → sessions → password history → **user last**), best-effort deletes the on-disk image
  bytes after commit, and finally hard-deletes the user, **freeing the username**.

**Public profiles are excluded** (gated on audience; see the sequenced roadmap in the backlog).

## Architecture (Clean/Hexagonal, enforced by the gate)

- **`PasswordHasher` domain port** inverts BCrypt out of the use cases; the `BcryptPasswordHasher`
  adapter lives in the `api-application` composition root (jBCrypt moved there from `api-usecases`).
  Consolidating it into a dedicated `api-security`/`api-system` module is a P2 backlog item.
- **`TransactionRunner` port everywhere, never `@Transactional`**: `PasswordChanger`, `AccountDeleter`,
  `AccountDeletionCleaner` wrap their writes in `transactionRunner.inTransaction { }`. `UserCreator`
  was **migrated off `@Transactional`** onto the port while it was touched (spec-mandated; the
  remaining `Session*` pair is a P2 item).
- **Account tombstone = Ebean `@SoftDelete`** on `UserModel` (the FIRST in this codebase), **mirrored**
  by domain `User.softDeleted`, so the async contract is explicit in the domain and not a silent
  cross-adapter coupling. Normal finders auto-exclude tombstoned rows; `*IncludingDeleted` +
  `setIncludeSoftDeletes()` see them. Distinct from Pin/Board's manual `softDeletedAt`.
- **Password storage = append-only history.** Current = latest by `when_created`
  (`findCurrentPasswordHash`); reuse is checked against ALL rows. No upsert, no `unique(user_id)`.
- **Username reservation includes tombstones**: registration uses `findUserByNameIncludingDeleted`, so
  a pending-deletion account keeps its username reserved until the cleaner frees it.
- Errors: `REAUTHENTICATION_FAILED` → 403, `PASSWORD_PREVIOUSLY_USED` → 422,
  `UNSUPPORTED_REAUTHENTICATION_FACTOR` → 400; each added to the exhaustive `BaseErrorMapper` `when`
  (no `else`) with a matching `BaseErrorMapperTest` arm. Migration `1.9` adds only `users.deleted`.

## Pitfalls learned (read these before touching the delete flow)

- **CRITICAL bug, caught only by the end-to-end test: soft-deleted author mapping NPE.** The account
  is tombstoned (Ebean `@SoftDelete`) *before* the async cleaner runs. If the cleaner enumerates the
  user's pins as full domain `Pin` objects (`findAllPinsForUser`), `PinModelMapper.toDomain` navigates
  `PinModel.author` and re-maps the now-soft-deleted `UserModel`; Ebean's soft-delete predicate on that
  join returns a partial `UserModel` (`name == null`) and the Kotlin non-null `User(...)` ctor throws
  `NullPointerException`. `TaskProcessor` swallows it into a retry → after `maxAttempts` the task is
  DEAD → **the account stays tombstoned forever, username/data never freed.** Deletion was broken for
  *any user owning ≥1 pin*. **Fix:** the cleaner enumerates pin **ids** (`findAllPinIdsForUser` =
  `QPinModel().author.id.equalTo(user.id).findList().map { it.id }`) and never dereferences `.author`.
  All other delete-flow ops were already id-only / FK-delete (verified by an adversarial review). **The
  lesson: this slipped past every unit test (mocked repos) and every code-read review; only the
  real-worker end-to-end test caught it. Any operation on a tombstoned user's associations must avoid
  mapping the author to a domain `User`.**
- **MockK `checkUnnecessaryStub()` is global (`@AfterEach` in `BaseTest`).** Any use case that verifies
  step-up/reuse *before* opening the transaction (`PasswordChanger`, `AccountDeleter`) must place the
  `tx.inTransaction` passthrough stub **inside the test that reaches the transaction**, never in a
  `@BeforeEach`: the early-throwing tests never call `inTransaction`, so a shared stub is "unused" and
  fails the test. Conversely, `UserCreator` wraps its whole body, so its passthrough belongs in a
  **subclass** `@BeforeEach` (superclass `clearAllMocks()` runs first and would wipe an `init{}` stub).
- **New `BaseErrorMapper` `when` arms need a matching test.** Each arm is a branch under 100% branch
  coverage. 422/400 that have no `jakarta.ws.rs` `Response.Status` constant (e.g. 422) assert the raw
  status code + `ProblemDetail` fields, mirroring the existing `IMAGE_INVALID` test.
- **`RestResponse<Unit>` leaks a bogus OpenAPI `Unit` schema + wrong 200.** No-body responses use
  `RestResponse<Void>` (204 via `RestResponse.noContent()`); documented non-default statuses (202) use
  `@APIResponse(responseCode = "202", ...)`, mirroring `ImageController.requestImageDownload`.
- **SQLite FK enforcement is OFF in this datasource** (no `foreign_keys=ON` pragma), so FK-order bugs
  fail silently in tests, not loudly. Follow the spec's FK deletion order anyway; the bulk deletes clean
  junction rows before parents defensively.

## NOT validated

- **Real hardware / production.** Everything is local-gate + `@QuarkusTest` only.
- **On-disk image byte deletion is not asserted end-to-end.** `MeDeleteCompletionIntegrationTest`
  proves the full DB erasure + username freeing (a real pin+image seeded, deleted, then the username
  becomes re-registerable, which only happens after `permanentlyDeleteUser`, the last step). But the
  best-effort disk cleanup (`imageStore.delete`) runs *after* commit and is not asserted; a residue is
  possible if it fails.
- **Task failure / residue path.** If `account.delete` fails totally or partially, the account stays
  tombstoned with orphaned rows/bytes. The "Deleted-account residue GC" P2 backlog item is the safety
  net; it has no implementation yet.
- **Silent task failure.** `TaskProcessor` swallows handler exceptions with no logging, so a DEAD
  `account.delete` task is invisible to operators (new P2 backlog item).
- **Second factors.** Only the `password` factor is implemented; TOTP/Passkey step-up is a sequenced
  roadmap item, and the `X-Reauthentication` header + `Reauthenticator` are the brick they build on.

## Suggested next step

The **`api-worker-quarkus` module extraction** (P2, scheduled as the next sub-project): the task-worker
driving runtime currently lives in the HTTP presentation module, including the
`AccountDeletionTaskHandler` added here (knowingly placed there temporarily). Extract it into a
dedicated adapter module. After that, the sequenced roadmap resumes with the user-segmented base
(advanced pin/tag/board features, pHash pin merging) before audience/visibility mechanics.
