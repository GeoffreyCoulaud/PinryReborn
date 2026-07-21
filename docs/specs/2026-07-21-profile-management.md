# Profile management — change password + delete account

Date: 2026-07-21
Status: approved design, pending implementation plan
Depends on: users (registration + `UserAuthenticator` password check), session tokens
(`SessionRevoker.revokeAll`), the task queue (`EnqueueTask` + a new `TaskHandler`), the image storage
ports (`ImageStore`, `RenditionCache`), and the existing pin / board / tag / image / image-download /
session-token repositories. No new external dependency.

## 1. Goal

Give an authenticated user control over their own account: **change their password** and **delete their
account**. This is the remaining P1 "client ergonomics" item in `docs/backlog.md`, unblocked now that the
client-auth model is fixed (Bearer session tokens).

Both are **sensitive** operations, so both sit behind a **step-up re-authentication**: the user must
re-present a credential (today the password; a second factor later) even though the request already
carries a valid Bearer token. This protects the account against a hijacked session.

**Public profiles are out of scope** — they depend on an audience/visibility model that is deliberately
sequenced after a user-segmented base (see the backlog's "Sequenced roadmap").

## 2. Scope

**In scope:**

- **Change password** (`PUT /api/v1/me/password`): verify the current password (step-up), set a new one,
  then revoke **all** of the user's sessions (the current one included).
- **Delete account** (`DELETE /api/v1/me`): an **asynchronous hard delete**. Step-up, then tombstone the
  account (a transient Ebean `@SoftDelete` state), revoke all sessions, and enqueue an `AccountDeletion`
  task that erases the user's data (DB rows + on-disk image bytes) in FK order and finally deletes the
  user row, freeing the username.
- A reusable **step-up re-authentication** brick (`Reauthenticator`), structured so a second factor can
  be added later without reworking each endpoint.
- The `@SoftDelete` tombstone makes a pending-deletion account **invisible to authentication** (login
  refused, token resolution refused) for free, via Ebean's automatic query filtering.

**Out of scope (deferred):**

- **Public profiles / any read of another user's profile.** Gated on audience (backlog).
- **A "deactivate / reactivate account" feature.** The tombstone is a one-way, internal state toward
  deletion, **not** a user-facing deactivation with a grace period or undo. See §3.
- **A sudo-mode elevation token** (a short-lived re-auth token à la GitHub). v1 carries the step-up factor
  **inline** in each sensitive request. The elevation token is a natural companion to 2FA, backlogged.
- **2FA (TOTP / Passkey).** The step-up brick is shaped for it, but no second factor ships here. Backlog.
- **User data export / import.** Separate backlog item (portability).
- **Deleted-account residue GC.** If the `AccountDeletion` task fails partially/totally, orphans remain; a
  sweep is a P2 backlog item, not built here (see §8, §14).

## 3. Key decisions (rationale captured for the plan)

- **Hard delete, not deactivation.** Everything is owner-scoped and nothing is shared, so a user owns only
  their own data. A true erasure keeps the data model clean (no cross-cutting "disabled user" state) and
  respects the user (no hostage data). Chosen over soft deactivation.
- **…but performed asynchronously, via a transient tombstone.** A hard delete enumerates the user's pins,
  deletes rows across many tables (all FKs are `ON DELETE RESTRICT`, so no DB cascade helps) and cleans
  on-disk bytes image-by-image — potentially slow. So `DELETE /me` does the minimum synchronously
  (tombstone + revoke + enqueue, `202`) and a **task** does the erasure. The tombstone is a one-way state:
  not reactivatable, no grace window, no undo. Async is for robustness/volume, not to give the user a
  change-of-mind window.
- **The tombstone is Ebean's native `@SoftDelete`, distinct from the Pin/Board soft-delete.** The project
  already has a soft-delete: Pin/Board carry a **manual `softDeletedAt: Instant?`** timestamp, which is
  domain-visible (the recycle bin shows *when* deleted) and filtered explicitly. The account tombstone is
  the opposite need — it must be **invisible** to the domain and auto-excluded everywhere — so it uses the
  **other** mechanism: Ebean `@SoftDelete` (a boolean flag Ebean auto-excludes from every query unless
  `setIncludeSoftDeletes` is set; `.delete()` sets it, `.deletePermanent()` physically removes). Two
  mechanisms for two genuinely different needs; this divergence is intentional and must be documented in
  code so a reviewer is not surprised.
- **Step-up re-authentication, not "compare the password".** The semantics are "require a re-auth factor
  for a sensitive action", not "prove you know the password". v1's factor is the password, verified by
  **reusing `UserAuthenticator`** (so the constant-time guard is reused). Modelled as a small
  `Reauthenticator` brick so TOTP/Passkey can slot in later.
- **Change-password revokes ALL sessions, current included.** The classic reason to change a password is
  "my account may be compromised" — and the current session's token may itself be the stolen one. So no
  exception: after a successful change, every token dies and the user re-logs in everywhere. Stricter than
  the common "revoke others, keep current", by explicit choice.
- **Step-up failure is `403`, not `401`.** The request is authenticated (valid Bearer); only the *extra*
  factor failed. Returning `401` would make clients treat it as session expiry and log the user out; `403`
  says "your identity is known, but this action needs a valid step-up you did not provide".
- **The account-deletion task handler is placed under `api-presentation-quarkus/.../tasks/` for now —
  knowingly.** That is where the existing `PinDownloadTaskHandler` lives. This placement is a known
  architecture smell (task workers are a *driving adapter*, not HTTP presentation); the whole task-worker
  subsystem is scheduled to move into a dedicated `api-worker-quarkus` module as the **next** sub-project
  (backlog P2). To keep this feature focused, the new handler follows the current convention and will move
  with the rest. The heavy logic lives in a use case (`AccountDeletionCleaner`), so only a thin adapter is
  "in the wrong place".

## 4. Domain & ports

The domain `User` entity is **unchanged** (`{ id, name }`). The tombstone flag is a persistence concern
(Ebean `@SoftDelete` on `UserModel`) and never surfaces in the domain; the mapper keeps mapping `id` +
`name` only.

Repository interface changes (all pure, in `api-domain`):

- **`UserRepositoryInterface`** — `findUserById` / `findUserByName` are unchanged in signature but now
  **auto-exclude tombstoned users** (Ebean filtering; this is what buys auth-invisibility for free). Add:
  - `markPendingDeletion(user: User)` — Ebean soft delete (sets the flag; row stays, children stay).
  - `findByIdIncludingDeleted(id: UUID): User?` — the **only** lookup that sees a tombstoned user; used by
    the deletion task. (`setIncludeSoftDeletes`.)
  - `permanentlyDeleteUser(user: User)` — Ebean `deletePermanent` (physically removes the row).
  - The currently-unused `deleteUser` is removed/replaced by the two explicit methods above.
- **`UserPasswordHashRepositoryInterface`** — `saveUserPasswordHash` becomes an **upsert** (see §12: today
  it always inserts, which would create a second hash on change-password). Add:
  - `deleteForUser(user: User)` — for account deletion.
- **`PinRepositoryInterface`** — add `permanentlyDeleteAllPinsForUser(user)` (all states; the existing
  bulk delete only covers soft-deleted pins). Enumeration reuses `findAllPinsForUser` +
  `findAllSoftDeletedPinsForUser` to collect images before the bulk delete.
- **`BoardRepositoryInterface`** — add `permanentlyDeleteAllBoardsForUser(user)` (all states; existing bulk
  covers recycled only).
- **`TagRepositoryInterface`** — add `deleteAllTagsForUser(user)` (no delete method exists today).
- **`SessionTokenRepositoryInterface.deleteAllForUser`**, **`ImageRepositoryInterface`**
  (`findByPinId`/`deleteByPinId`), **`ImageDownloadRepositoryInterface.deleteByPinId`** — reused as-is.
- **Ports reused by the task**: `ImageStore.delete(key)`, `RenditionCache.evictImage(imageId)`,
  `TransactionRunner`, `Clock` (via `EnqueueTask`).

New task identity (pure constants object in `api-usecases/tasks`, mirroring `PinDownloadTask`):

```
object AccountDeletionTask { const val KIND = "account.delete"; const val MAX_ATTEMPTS = 5 }
```

## 5. REST surface

All under `/api/v1`, on `MeController` (cohesive with `GET /me`). Both require a valid Bearer token.

| Method | Path | Auth | Body | Result |
|---|---|---|---|---|
| PUT | `/me/password` | Bearer | `{ currentPassword, newPassword }` | **204** (all sessions revoked) |
| DELETE | `/me` | Bearer | `{ password }` | **202** (deletion enqueued) |

- `PUT /me/password`: step-up on `currentPassword`; on success, replace the hash and revoke **all**
  sessions. `204`, no body. The presented token is now dead → the next request 401s.
- `DELETE /me`: step-up on `password`; on success, tombstone + revoke all + enqueue. `202`, no body.
  A body on `DELETE` is used to carry the step-up factor; RESTEasy Reactive supports it.

### DTOs (input)

- `PasswordChangeInputDto = { currentPassword, newPassword }`. Validation: `currentPassword` →
  `@NotBlank` only (a factor being *verified*, so a bad shape → 403 step-up, never 400); `newPassword` →
  `@NotBlank @Size(min = 8, max = 72)` (a value being *created*, reusing registration's constraints; 72 =
  the BCrypt byte ceiling).
- `AccountDeletionInputDto = { password }`. `@NotBlank` only (a factor being verified).

No new output DTOs. `GET /me` (`UserOutputDto`) is unchanged.

## 6. Change-password flow

Use case `PasswordChanger.changePassword(user, currentPassword, newPassword)`, `@Transactional`:

1. **Step-up:** `Reauthenticator.reauthenticate(user, currentPassword)` (§9). Failure → `403`.
2. **Replace the hash:** `userPasswordRepository.saveUserPasswordHash(user, HashedPassword(BCrypt.hashpw(newPassword, gensalt()), BCRYPT))` — now upsert semantics (§12), so the single row for the user is updated in place.
3. **Revoke all sessions:** `sessionRevoker.revokeAll(user)`.

The hash-replace and the revoke-all commit together (`@Transactional`): a change must never leave the new
password stored while old tokens survive, nor vice-versa. Returns `Unit` → controller emits `204`.

## 7. Delete-account request flow

Use case `AccountDeleter.requestDeletion(user, password)`, `@Transactional`:

1. **Step-up:** `Reauthenticator.reauthenticate(user, password)`. Failure → `403`.
2. **Tombstone:** `userRepository.markPendingDeletion(user)` (Ebean soft delete). The user is now invisible
   to `findUserById` / `findUserByName`.
3. **Revoke all sessions:** `sessionRevoker.revokeAll(user)`.
4. **Enqueue:** `enqueueTask.enqueue(kind = AccountDeletionTask.KIND, payload = user.id.toString(),
   maxAttempts = AccountDeletionTask.MAX_ATTEMPTS, dedupKey = "${AccountDeletionTask.KIND}:${user.id}")`.

All four commit in one transaction (all-or-nothing: a failure leaves the account intact, no orphan task).
Returns `Unit` → controller emits `202`.

**Re-entrancy is self-guarding.** After the first `DELETE /me`, all sessions are revoked, so a second call
cannot authenticate (`401`); and even if a token survived, `findUserById` would now return null
(auto-filtered), so the identity would not resolve. The `dedupKey` additionally prevents a duplicate task.

## 8. AccountDeletion task (worker path)

Thin adapter `AccountDeletionTaskHandler` (presentation, temporary — §3): `kind = AccountDeletionTask.KIND`;
`handle(payload, _) = accountDeletionCleaner.deleteAccountData(UUID.fromString(payload))`. No config needed.

Use case `AccountDeletionCleaner.deleteAccountData(userId)` (in `api-usecases`; uses only domain ports):

1. **Load including tombstoned:** `user = userRepository.findByIdIncludingDeleted(userId)`. If null → the
   account was already fully deleted → **return (no-op success)**. This is the idempotency anchor.
2. **DB erasure, in one `TransactionRunner` unit**, respecting the `ON DELETE RESTRICT` FKs (children
   first):
   1. Enumerate the user's pins (`findAllPinsForUser` + `findAllSoftDeletedPinsForUser`). For each pin:
      collect its image's `(storageKey, imageId)` (`imageRepository.findByPinId`) for later disk cleanup;
      clear its download (cancel the queued/running task + delete the `image_download` row, via the
      existing `ClearPinDownload`); delete the image row (`imageRepository.deleteByPinId`).
   2. `pinRepository.permanentlyDeleteAllPinsForUser(user)` — deletes `pin_tag` + `pin_board` join rows and
      the `pins` rows (all states).
   3. `boardRepository.permanentlyDeleteAllBoardsForUser(user)` — deletes remaining `pin_board` rows and
      the `boards` rows (all states).
   4. `tagRepository.deleteAllTagsForUser(user)` (the `pin_tag` rows are already gone with the pins).
   5. `sessionTokenRepository.deleteAllForUser(user.id)` — defensive (already revoked at request time).
   6. `userPasswordRepository.deleteForUser(user)`.
   7. `userRepository.permanentlyDeleteUser(user)` — physically removes the row, freeing the username.
3. **Disk cleanup, best-effort, after the transaction commits:** for each collected `(storageKey,
   imageId)`: `imageStore.delete(storageKey)` then `runCatching { renditionCache.evictImage(imageId) }`.
   Mirrors the recycle-bin pattern (DB rows first, disk after; disk failures do not fail the task).

**Idempotency / retry.** Any DB failure rolls back the transaction and propagates → `TaskProcessor` marks
the task *Retryable* (backoff, up to `MAX_ATTEMPTS`, then dead). A re-run re-enumerates (some rows already
gone; deletes are delete-if-exists) and continues; once the user row is gone, step 1 short-circuits to
success. **A committed-DB-but-failed-disk** case leaves orphaned bytes that a re-run will *not* revisit
(user already gone) — this is the residue the P2 "Deleted-account residue GC" sweep is for.

## 9. Step-up re-authentication

`Reauthenticator.reauthenticate(user: User, factor: String)` in `api-usecases`:

- v1: `try { userAuthenticator.authenticate(BasicAuthLogin(user.name, factor)) } catch
  (e: UserAuthenticationError) { throw ReauthenticationError() }`. Reuses the existing password check
  (including its constant-time dummy-hash guard). Success is discarded (we already hold the identity).
- Throws `ReauthenticationError` (a `BaseError`, `ErrorCode.REAUTHENTICATION_FAILED` → `403`) on any
  failure, collapsing "wrong password" / "no hash" into one 403 (no oracle).
- The name is deliberate: a later 2FA factor is a second `reauthenticate`-style check, without touching
  the callers (`PasswordChanger`, `AccountDeleter`).

## 10. Security wiring (hexagonal placement)

- **api-domain**: repository-interface additions (§4). No new entity; `User` unchanged.
- **api-usecases** (domain only): `Reauthenticator`, `PasswordChanger` (`@Transactional`), `AccountDeleter`
  (`@Transactional`), `AccountDeletionCleaner`, `AccountDeletionTask` constants, `ReauthenticationError`
  (+ `ErrorCode.REAUTHENTICATION_FAILED`). `UserAuthenticator`, `SessionRevoker` reused unchanged.
- **api-persistence-sqlite**: `UserModel` gains an Ebean `@SoftDelete` boolean; `UserRepository` gains
  `markPendingDeletion` (`.delete()`), `findByIdIncludingDeleted` (`setIncludeSoftDeletes`),
  `permanentlyDeleteUser` (`.deletePermanent()`); `UserPasswordHashRepository.saveUserPasswordHash` becomes
  upsert + `deleteForUser`; `Pin/Board/Tag` repositories gain the all-states bulk deletes; migration
  **1.9** (see §12).
- **api-presentation-quarkus**: `MeController` gains `PUT /me/password` and `DELETE /me` (+ the two input
  DTOs); `AccountDeletionTaskHandler` under `.../tasks/` (temporary placement, §3); a `BaseErrorMapper`
  entry (or `ErrorCode` mapping) for `REAUTHENTICATION_FAILED` → 403.
- **api-application**: wire the new repository methods; integration tests; regenerate `docs/openapi.json`.

## 11. Errors

Following the existing per-use-case `BaseError` / shared `ErrorCode` / `BaseErrorMapper` convention:

- `REAUTHENTICATION_FAILED` → **403**. Step-up factor missing/invalid on `PUT /me/password` or
  `DELETE /me`. Distinct from the auth-layer 401s so clients do not confuse it with session expiry.
- `newPassword` violating `@Size(8,72)` / `@NotBlank` → **400** (existing Bean-Validation mapper).
- No/invalid Bearer token → **401** (existing auth layer).

All RFC-7807 problem+json, consistent with existing mappers.

## 12. Persistence & migration

- **`users.deleted`** — Ebean `@SoftDelete` boolean on `UserModel`, default false/`0`, not null. Ebean
  auto-excludes `deleted = true` from every query except those opting in. Migration adds the column;
  existing rows default to not-deleted.
- **`user_password_hashes` upsert + uniqueness.** Today `saveUserPasswordHash` mints a new
  `UserPasswordHashModel` (fresh `id`) and inserts, and there is **no unique constraint on `user_id`** —
  so a second save for the same user would insert a duplicate and break `findOne()`. Change:
  - Make `saveUserPasswordHash` **upsert**: find the existing row by `user_id`; if present, mutate
    `hash` + `algorithm` in place and save (same `id`); else insert. Registration (no existing row) still
    inserts; change-password updates.
  - Add a **`unique (user_id)`** constraint on `user_password_hashes` to enforce the one-hash-per-user
    invariant at the DB level (no existing duplicates, so the migration is safe).
- **Migration 1.9** (last shipped is 1.8, session tokens): additive — the `users.deleted` column and the
  `user_password_hashes.user_id` unique index. No data backfill. Generated via
  `./gradlew :api-persistence-sqlite:generateDbMigration`.
- The pin/board/tag bulk-delete additions are new repository queries, not schema changes.

## 13. Testing strategy

Strict TDD, 100% branch coverage per package, failing test first. Order per AGENTS.md: integration
(REST Assured) → use-case unit (MockK) → repository (Ebean). Both sides of every conditional.

**Integration (`api-application`):**

- **Change password:** success → `204`, and the pre-change token is now rejected (`401`) **and** every
  other session the user had is dead, **and** login with the new password succeeds while the old password
  fails; wrong `currentPassword` → `403` and password unchanged (old still logs in); short `newPassword`
  → `400`; unauthenticated → `401`.
- **Delete account:** success → `202`; immediately after, the token is rejected and login is refused
  (account invisible); after the enqueued task is processed, the user's pins/boards/tags/images rows and
  on-disk bytes are gone and the **username is free to register again**; wrong `password` → `403` and the
  account survives; unauthenticated → `401`. A second `DELETE /me` with the (now dead) token → `401`.
- **Deletion completeness:** seed a user with a pin that has an uploaded image (on-disk original + a
  generated rendition) and a board membership and tags; after deletion, assert every row and both on-disk
  paths are gone.

**Use-case unit (MockK):**

- `Reauthenticator`: valid factor → passes; `UserAuthenticator` throwing → `ReauthenticationError`.
- `PasswordChanger`: happy path calls upsert + `revokeAll`; step-up failure short-circuits (no hash write,
  no revoke); the two writes are within one `@Transactional`.
- `AccountDeleter`: happy path tombstones + revokes + enqueues with the right kind/payload/dedupKey; step-up
  failure short-circuits.
- `AccountDeletionCleaner`: full order on a user with pins (active + soft-deleted), images, downloads,
  boards, tags; the null-user short-circuit (idempotent re-run); disk cleanup runs after the DB unit and a
  rendition-eviction failure is swallowed (best-effort).

**Repository (`api-persistence-sqlite`):**

- `@SoftDelete`: after `markPendingDeletion`, `findUserById`/`findUserByName` return null, but
  `findByIdIncludingDeleted` returns the user; `permanentlyDeleteUser` then removes it entirely.
- `saveUserPasswordHash` upsert: a second save updates in place (one row, new hash), `findUserPasswordHash`
  returns the new value; `deleteForUser` removes it.
- `permanentlyDeleteAllPinsForUser` / `…BoardsForUser` remove all states incl. join rows;
  `deleteAllTagsForUser` removes the user's tags.

## 14. Risks / open points

- **Per-user enumeration vs the tombstone.** The deletion task enumerates pins/boards/tags **after** the
  user is tombstoned. These queries key on the `author_id` / `user_id` FK **column** (no join to the
  soft-deleted `users` row), so Ebean's soft-delete filter does not hide them. **Verify** this holds for
  every reused finder; if any eagerly joins `UserModel`, the cleaner must `setIncludeSoftDeletes` on it.
- **Committed-DB / failed-disk residue.** As in §8: a crash after the DB commit but before/within disk
  cleanup orphans image bytes that no retry revisits. Accepted for v1; covered by the P2 residue-GC item.
- **Large accounts.** The task loops per-pin for image collection and download clearing. Fine for expected
  volumes; if accounts grow huge, batch the enumeration. Not optimised in v1.
- **DELETE with a body.** Some proxies/clients dislike a `DELETE` request body. Acceptable here (the API is
  consumed by our own SPA/extension); if it ever bites, the step-up factor can move to a header. Noted, not
  pre-solved.
- **Handler placement is knowingly temporary** (§3); it moves with the scheduled `api-worker-quarkus`
  extraction. No functional risk, just avoid entrenching more logic in the thin adapter.
