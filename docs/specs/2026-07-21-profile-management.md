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

Both are **sensitive** operations, so both require the user to **re-prove themselves** even though the
request already carries a valid Bearer token, protecting the account against a hijacked session. The two
do it differently, on purpose (§3): change-password needs the **actual current password** (intrinsic to
changing it); delete-account takes a **generic step-up factor** (a header, extensible to a second factor).

**Public profiles are out of scope** — they depend on an audience/visibility model deliberately sequenced
after a user-segmented base (see the backlog's "Sequenced roadmap").

## 2. Scope

**In scope:**

- **Change password** (`PUT /api/v1/me/password`): verify the supplied current password, reject a new
  password the user has **ever used before** (password history), store the new one, then revoke **all** of
  the user's sessions (the current one included).
- **Delete account** (`DELETE /api/v1/me`): an **asynchronous hard delete**. A generic **step-up factor**
  (in a header) is verified, then the account is **tombstoned** (Ebean `@SoftDelete`, mirrored by a domain
  `User.softDeleted` flag), all sessions are revoked, and an `AccountDeletion` task is enqueued to erase
  the user's data (DB rows + on-disk image bytes) in FK order and finally delete the user row, freeing the
  username.
- A **step-up re-authentication** brick (`Reauthenticator`) plus a `factor-kind`-carrying request header,
  structured so a second factor (TOTP/passkey) slots in later without reworking the endpoint.
- **Password history**: every password a user has ever set is retained (hashed) and the reuse check runs
  against all of it.

**Out of scope (deferred):**

- **Public profiles / reading another user's profile.** Gated on audience (backlog).
- **A "deactivate / reactivate account" feature.** The tombstone is a one-way, internal state toward
  deletion, **not** a user-facing deactivation with a grace period or undo (§3).
- **A sudo-mode elevation token** (a short-lived re-auth token). v1 carries the step-up factor **inline**
  in the delete request. The elevation token is a companion to 2FA, backlogged.
- **2FA (TOTP / Passkey).** The step-up header + brick are shaped for it, but no second factor ships here.
  The domain `ReauthenticationFactor` sealed type is deferred until then (v1 only supports the password
  kind, validated at the edge).
- **User data export / import.** Separate backlog item (portability).
- **Deleted-account residue GC.** If the `AccountDeletion` task fails partially/totally, orphans remain; a
  sweep is a P2 backlog item, not built here (§8, §14).
- **Capping password history length.** Full history retained in v1 (§14).

## 3. Key decisions (rationale captured for the plan)

- **Hard delete, not deactivation.** Everything is owner-scoped and nothing is shared, so a user owns only
  their own data. True erasure keeps the data model clean (no cross-cutting "disabled user" state) and
  respects the user (no hostage data).
- **…performed asynchronously, via a tombstone.** A hard delete enumerates the user's pins, deletes rows
  across many tables (every FK is `ON DELETE RESTRICT`, so no DB cascade helps) and cleans on-disk bytes
  image-by-image — potentially slow. So `DELETE /me` does the minimum synchronously (tombstone + revoke +
  enqueue, `202`) and a **task** does the erasure. The tombstone is one-way: not reactivatable, no grace
  window, no undo. Async is for robustness/volume, not a change-of-mind window.
- **The account tombstone is Ebean `@SoftDelete`, *mirrored into the domain*.** Ebean's `@SoftDelete`
  gives the mechanics for free: normal queries auto-exclude tombstoned rows (so auth-invisibility costs
  nothing), `.delete()` sets the flag, `.deletePermanent()` removes, `setIncludeSoftDeletes` opts in. But a
  soft-delete that only the persistence adapter knows about would make the async-deletion lifecycle a
  **cross-adapter contract absent from the domain** — a Clean-Architecture red flag. So the state is also
  **explicit in the domain**: `User` carries a `softDeleted: Boolean`, the mapper maps the Ebean flag onto
  it, and the transition is a domain port method (`markPendingDeletion`). The mechanism stays Ebean's; the
  *concept* lives in the domain.
- **This is a different soft-delete mechanism than Pin/Board — deliberately.** Pin/Board use a **manual
  `softDeletedAt: Instant?`** timestamp (domain-visible, filtered by hand, **reversible** via the recycle
  bin). The account tombstone uses **Ebean `@SoftDelete`** (boolean, auto-filtered, **one-way**). Both are
  now domain-visible; the mechanisms differ because the needs differ (a recycle bin that shows *when* vs an
  auto-excluded one-way tombstone). Document this divergence in code so a reviewer is not surprised.
- **Change-password requires the actual current password, in the body — not a generic factor.** Changing a
  password inherently means proving you know the *old* one; a second factor (a TOTP code) could not stand
  in for it. So `currentPassword` travels in the request body and is checked against the current hash. This
  is distinct from the generic step-up.
- **Delete-account uses a generic step-up factor, in a header, with its factor-kind.** A deletion has no
  intrinsic "old value" to know — it just needs re-proof. So it takes a generic step-up factor whose
  **kind** is explicit on the wire (`X-Reauthentication: <factor-kind> <value>`, kind = `password` in v1),
  making 2FA a drop-in later. The value is **base64url-encoded**, mirroring `Authorization: Basic
  base64(...)`, so an arbitrary-Unicode password survives the Latin-1 header charset.
- **New password must never have been used before by this user (password history).** A simple, sound
  security prerequisite. It also means storage is **append-only** (a new hash per change), which removes
  any need for an upsert or a `unique(user_id)` on the hash table: the *current* password is just the
  latest row.
- **Change-password revokes ALL sessions, current included.** The classic reason to change a password is a
  suspected compromise, and the current session's token may itself be the stolen one. No exception.
- **A failed re-auth is `403`, not `401`.** The request is authenticated (valid Bearer); only the extra
  proof failed. `401` would make clients treat it as session expiry and log the user out; `403` says
  "identity known, but this action needs a valid proof you did not give".
- **Framework/persistence concerns stay behind domain ports, not annotations or concrete libraries in the
  use cases.** Two couplings are inverted here: (1) **transactions** — the use cases use the existing
  `TransactionRunner` domain port (`inTransaction { }`), never `jakarta.transaction.@Transactional` (a
  persistence concern); `UserCreator` is migrated off `@Transactional` while it is touched, and the
  remaining `Session*` use cases are a backlog cleanup. (2) **password hashing** — BCrypt (a concrete
  library that generates a random salt, i.e. non-determinism) goes behind a `PasswordHasher` **domain
  port**, exactly as `SecureRandom` sits behind `TokenGenerator`, keeping the use cases deterministic and
  branch-testable. `@ApplicationScoped` (a behaviourless DI marker) and deterministic pure computation
  (SHA-256, URI parsing) are left as-is. Enforcing these boundaries mechanically (Konsist) is backlogged.
- **The account-deletion task handler is placed under `api-presentation-quarkus/.../tasks/` for now —
  knowingly.** That is where `PinDownloadTaskHandler` lives. Task workers are a *driving adapter*, not HTTP
  presentation; the whole subsystem is scheduled to move into a dedicated `api-worker-quarkus` module as
  the **next** sub-project (backlog P2). To stay focused, the new handler follows the current convention
  and moves with the rest. Its heavy logic lives in a use case (`AccountDeletionCleaner`), so only a thin
  adapter is "misplaced".

## 4. Domain & ports

### `User` gains a domain-visible tombstone flag

```
data class User(
    override val id: UUID,
    val name: String,
    val softDeleted: Boolean = false,   // NEW — mirrors the persistence @SoftDelete flag; one-way
) : Identifiable
```

`softDeleted` is `false` for every normally-loaded user (Ebean auto-excludes tombstoned rows); it reads
`true` only when a user is loaded through an `…IncludingDeleted` lookup. It is never exposed to clients
(`UserOutputDto` stays `{ id, name }`; the mapper ignores it). Every `User(...)` construction and the
`UserModel` ↔ `User` mapper get the new field (default `false`).

### `PasswordHasher` — a domain port (was: direct BCrypt in the use cases)

Password hashing is currently `org.mindrot.jbcrypt.BCrypt` called **directly** inside `UserCreator` and
`UserAuthenticator` — a concrete crypto library, with a **random salt** (non-determinism), living in the
application layer. Invert it behind a **domain port** (pure interface in `api-domain`), mirroring how
`SecureRandom` sits behind `TokenGenerator`:

```
interface PasswordHasher {
    fun hash(raw: String): HashedPassword                      // random salt
    fun matches(raw: String, stored: HashedPassword): Boolean  // dispatch on stored.algorithm
}
```

- **Adapter**: `BcryptPasswordHasher` (BCrypt), `@ApplicationScoped`, placed in the **`api-application`
  composition root** for now — *not* `api-presentation-quarkus` (it is not an HTTP concern). This is a
  pragmatic home; a backlog item consolidates it with the other misplaced infra/security adapters
  (`SecureTokenGenerator`, `SystemClock`) into a dedicated adapter module.
- **Consumers**: `UserCreator` (hash on registration), `UserAuthenticator` (verify — its constant-time
  guard now matches against a **precomputed dummy `HashedPassword`** obtained from the port),
  `PasswordChanger` and `Reauthenticator` (verify + hash). All become deterministic under a mocked port,
  so they stay fully branch-testable.

### Repository interface changes (pure, in `api-domain`)

**`UserRepositoryInterface`** — the normal finders auto-exclude tombstoned users (Ebean), which is exactly
what auth wants; add the opt-in and lifecycle methods:

- `findUserById(id): User?` / `findUserByName(name): User?` — **unchanged signatures**, now auto-exclude
  tombstoned rows (auth / identity resolution get invisibility for free).
- `findUserByNameIncludingDeleted(name): User?` — **for the registration uniqueness check** (remark 2): a
  name held by a pending-deletion account stays reserved until GC frees it. (`setIncludeSoftDeletes`.)
- `findUserByIdIncludingDeleted(id): User?` — the deletion task's loader (sees the tombstone).
- `markPendingDeletion(user: User)` — Ebean `.delete()` (sets the flag; row + children stay). One-way.
- `permanentlyDeleteUser(user: User)` — Ebean `.deletePermanent()` (physically removes the row).
- The currently-unused `deleteUser` is removed in favour of the two explicit lifecycle methods.

**`UserPasswordHashRepositoryInterface`** — password history (append-only):

- `saveUserPasswordHash(user, hashed): HashedPassword` — **unchanged**: already inserts a fresh row, which
  is exactly the append we want.
- `findCurrentPasswordHash(user): HashedPassword?` — **replaces** the current `findUserPasswordHash`, which
  does `findOne()` and would now break with multiple rows. Returns the **latest** by `when_created`.
- `findAllPasswordHashesForUser(user): List<HashedPassword>` — the full history, for the reuse check.
- `deleteForUser(user)` — for account deletion (drops all history rows).

**`PinRepositoryInterface`** — add `permanentlyDeleteAllPinsForUser(user)` (all states; the existing bulk
delete covers soft-deleted pins only). Enumeration reuses `findAllPinsForUser` +
`findAllSoftDeletedPinsForUser` to collect images before the bulk delete.

**`BoardRepositoryInterface`** — add `permanentlyDeleteAllBoardsForUser(user)` (all states).

**`TagRepositoryInterface`** — add `deleteAllTagsForUser(user)` (no delete method exists today).

**Reused as-is**: `SessionTokenRepositoryInterface.deleteAllForUser`, `ImageRepositoryInterface`
(`findByPinId`/`deleteByPinId`), `ImageDownloadRepositoryInterface.deleteByPinId`, and the `ImageStore` /
`RenditionCache` / `TransactionRunner` / `Clock` ports.

### New task identity

Pure constants object in `api-usecases/tasks`, mirroring `PinDownloadTask`:

```
object AccountDeletionTask { const val KIND = "account.delete"; const val MAX_ATTEMPTS = 5 }
```

## 5. REST surface

All under `/api/v1`, on `MeController` (cohesive with `GET /me`). Both require a valid Bearer token.

| Method | Path | Auth | Re-auth | Body | Result |
|---|---|---|---|---|---|
| PUT | `/me/password` | Bearer | current password in body | `{ currentPassword, newPassword }` | **204** |
| DELETE | `/me` | Bearer | `X-Reauthentication` header | — | **202** |

- `PUT /me/password`: verify `currentPassword` against the current hash; reject a `newPassword` already in
  the user's history; store the new hash; revoke **all** sessions. `204`, no body. The presented token is
  now dead → the next request 401s.
- `DELETE /me`: verify the step-up header; tombstone + revoke all + enqueue. `202`, no body.

### The step-up header (delete)

```
X-Reauthentication: <factor-kind> <base64url(factor-value)>      e.g.  X-Reauthentication: password <b64>
```

Parsed in presentation (split on the first space). v1 supports `factor-kind = password` only; the decoded
value is the password. Header **absent** → 403 (re-auth required); **unparseable / unsupported kind** →
400; present-but-**wrong** password → 403. The exact header name/encoding are finalizable in the plan; the
base64url value mirrors HTTP Basic so a Unicode password survives the header charset. **Never log it.**

### DTOs (input)

- `PasswordChangeInputDto = { currentPassword, newPassword }`. Validation: `currentPassword` → `@NotBlank`
  (a value being *verified*; blank → 400, present-but-wrong → 403); `newPassword` → `@NotBlank @Size(min =
  8, max = 72)` (a value being *created*, reusing registration's constraints; 72 = the BCrypt byte ceiling).
- `DELETE /me` has **no body**; the factor rides in the header. No new output DTO. `GET /me`
  (`UserOutputDto`) is unchanged.

## 6. Change-password flow

Use case `PasswordChanger.changePassword(user, currentPassword, newPassword)` (writes wrapped in
`transactionRunner.inTransaction { }`):

1. **Verify current password:** `passwordHasher.matches(currentPassword, findCurrentPasswordHash(user))`.
   Absent hash or mismatch → `ReauthenticationError` (403).
2. **Reject reuse:** if `findAllPasswordHashesForUser(user).any { passwordHasher.matches(newPassword, it) }`
   → `PasswordPreviouslyUsedError` (422). (The current password is in the history, so "new == current" is
   rejected too.)
3. **Append the new hash:** `saveUserPasswordHash(user, passwordHasher.hash(newPassword))`.
4. **Revoke all sessions:** `sessionRevoker.revokeAll(user)`.

Steps 3–4 run inside one `transactionRunner.inTransaction { }`: never store the new password while old
tokens survive, nor vice-versa. (Steps 1–2 are reads/validation, before the transaction.) Returns `Unit` →
controller emits `204`.

## 7. Delete-account request flow

Use case `AccountDeleter.requestDeletion(user, factor)` (`factor` = the step-up password parsed from the
header; writes wrapped in `transactionRunner.inTransaction { }`):

1. **Step-up:** `reauthenticator.reauthenticate(user, factor)` (§9). Failure → 403.
2. **Tombstone:** `userRepository.markPendingDeletion(user)` (Ebean `.delete()`). The user is now invisible
   to `findUserById` / `findUserByName`.
3. **Revoke all sessions:** `sessionRevoker.revokeAll(user)`.
4. **Enqueue:** `enqueueTask.enqueue(kind = AccountDeletionTask.KIND, payload = user.id.toString(),
   maxAttempts = AccountDeletionTask.MAX_ATTEMPTS, dedupKey = "${AccountDeletionTask.KIND}:${user.id}")`.

Steps 2–4 run in one `transactionRunner.inTransaction { }` (all-or-nothing: a failure leaves the account
intact, no orphan task; the port's KDoc guarantees the enqueue joins the ambient transaction). Step 1 is
the step-up check, before the transaction. Returns `Unit` → controller emits `202`.

**Re-entrancy is self-guarding.** After the first `DELETE /me`, all sessions are revoked, so a second call
cannot authenticate (`401`); even if a token survived, `findUserById` now returns null (auto-filtered), so
the identity would not resolve. The `dedupKey` additionally prevents a duplicate task.

## 8. AccountDeletion task (worker path)

Thin adapter `AccountDeletionTaskHandler` (presentation, temporary — §3): `kind = AccountDeletionTask.KIND`;
`handle(payload, _) = accountDeletionCleaner.deleteAccountData(UUID.fromString(payload))`. No config needed.

Use case `AccountDeletionCleaner.deleteAccountData(userId)` (in `api-usecases`; uses only domain ports):

1. **Load including tombstoned:** `user = findUserByIdIncludingDeleted(userId)`. If null → already fully
   deleted → **return (no-op success)**. This is the idempotency anchor.
2. **DB erasure in one `TransactionRunner` unit**, respecting the `ON DELETE RESTRICT` FKs (children first):
   1. Enumerate the user's pins (`findAllPinsForUser` + `findAllSoftDeletedPinsForUser`). For each pin:
      collect its image `(storageKey, imageId)` (`findByPinId`) for later disk cleanup; clear its download
      (cancel the queued/running task + delete the `image_download` row, via the existing
      `ClearPinDownload`); delete the image row (`deleteByPinId`).
   2. `permanentlyDeleteAllPinsForUser(user)` — deletes `pin_tag` + `pin_board` join rows and the `pins`
      rows (all states).
   3. `permanentlyDeleteAllBoardsForUser(user)` — deletes any remaining `pin_board` rows and the `boards`
      rows (all states).
   4. `deleteAllTagsForUser(user)` (the `pin_tag` rows are already gone with the pins).
   5. `sessionTokenRepository.deleteAllForUser(user.id)` — defensive (already revoked at request time).
   6. `userPasswordRepository.deleteForUser(user)` (all history rows).
   7. `permanentlyDeleteUser(user)` — Ebean `.deletePermanent()`, freeing the username.
3. **Disk cleanup, best-effort, after commit:** for each collected `(storageKey, imageId)`:
   `imageStore.delete(storageKey)` then `runCatching { renditionCache.evictImage(imageId) }`. Mirrors the
   recycle-bin pattern (DB rows first, disk after; disk failures do not fail the task).

**Idempotency / retry.** A DB failure rolls back the transaction and propagates → `TaskProcessor` marks it
*Retryable* (backoff, up to `MAX_ATTEMPTS`, then dead). A re-run re-enumerates (some rows gone; deletes are
delete-if-exists) and continues; once the user row is gone, step 1 short-circuits. A **committed-DB /
failed-disk** case orphans bytes a re-run will not revisit — the residue the P2 GC sweep is for.

## 9. Re-authentication for sensitive actions

Two forms, on purpose (§3):

- **Change-password — intrinsic.** The current password (body) is checked against the current hash inside
  `PasswordChanger` (§6.1). Not the generic mechanism; a password change needs the *old password*, which a
  second factor could not substitute.
- **Delete-account — generic step-up.** `Reauthenticator.reauthenticate(user, factor)` in `api-usecases`:
  v1 verifies the password factor via `passwordHasher.matches(factor, findCurrentPasswordHash(user))`;
  failure → `ReauthenticationError` (403). The factor arrives in the `X-Reauthentication` header with its
  kind (§5); presentation parses/validates the kind and passes the value. When 2FA lands, the kind grows a
  domain `ReauthenticationFactor` sealed type and `Reauthenticator` dispatches — without touching
  `AccountDeleter`.

## 10. Security wiring (hexagonal placement)

- **api-domain**: `User.softDeleted` field; the `PasswordHasher` port; the repository-interface additions
  (§4). No new entity.
- **api-usecases** (domain only): `Reauthenticator`, `PasswordChanger`, `AccountDeleter`,
  `AccountDeletionCleaner` (all using the `TransactionRunner` port for their writes, **not**
  `@Transactional`), `AccountDeletionTask` constants, `ReauthenticationError` +
  `PasswordPreviouslyUsedError` (+ their `ErrorCode`s). `UserAuthenticator`/`UserCreator` switch to the
  `PasswordHasher` port (and `UserCreator` drops its `@Transactional` for `TransactionRunner` while it is
  touched); `UserAuthenticator` reads `findCurrentPasswordHash`; `UserCreator` uses
  `findUserByNameIncludingDeleted` for uniqueness. `SessionRevoker` reused unchanged.
- **api-persistence-sqlite**: `UserModel` gains an Ebean `@SoftDelete` boolean; `UserRepository` gains the
  `…IncludingDeleted` finders, `markPendingDeletion` (`.delete()`), `permanentlyDeleteUser`
  (`.deletePermanent()`); `UserPasswordHashRepository` gains `findCurrentPasswordHash` /
  `findAllPasswordHashesForUser` / `deleteForUser`; `Pin/Board/Tag` repositories gain the all-states bulk
  deletes; migration **1.9** (§12). Mappers map `softDeleted`.
- **api-presentation-quarkus**: `MeController` gains `PUT /me/password` (+ `PasswordChangeInputDto`) and
  `DELETE /me` (+ the `X-Reauthentication` header parse); `AccountDeletionTaskHandler` under `.../tasks/`
  (temporary placement, §3); `BaseErrorMapper` entries for the new `ErrorCode`s.
- **api-application**: the `BcryptPasswordHasher` adapter (impl of the `api-domain` `PasswordHasher` port —
  pragmatic home, see §4 + backlog); wire the new repository methods; integration tests; regenerate
  `docs/openapi.json`.

## 11. Errors

Following the existing `BaseError` / `ErrorCode` / `BaseErrorMapper` convention:

- `REAUTHENTICATION_FAILED` → **403**. Wrong `currentPassword` (change-password) or a missing/wrong step-up
  factor (delete). Distinct from the auth-layer 401s so clients do not confuse it with session expiry.
- `UNSUPPORTED_REAUTHENTICATION_FACTOR` → **400**. The `X-Reauthentication` header is present but
  unparseable or names a `factor-kind` the server does not support (protocol error, not a bad credential).
- `PASSWORD_PREVIOUSLY_USED` → **422**. The `newPassword` matches one of the user's historical passwords.
- Bean-Validation failures (`newPassword` size, blank `currentPassword`) → **400** (existing mapper).
- No/invalid Bearer token → **401** (existing auth layer).

All RFC-7807 problem+json, consistent with existing mappers.

## 12. Persistence & migration

- **`users` Ebean `@SoftDelete`** — a boolean flag column on `UserModel` (default `false`/`0`, not null).
  Ebean auto-excludes flagged rows from every query except those calling `setIncludeSoftDeletes`; `.delete()`
  sets it, `.deletePermanent()` removes. The mapper maps it onto `User.softDeleted`.
- **`user_password_hashes` stays append-only** — **no** schema change: it already has its own `id` PK, a
  `user_id` FK and **no** unique on `user_id`, so multiple rows per user (the history) are already legal.
  Behaviour changes are code-only: `saveUserPasswordHash` keeps inserting (append); reads switch to
  latest-by-`when_created` (`findCurrentPasswordHash`) and full-list (`findAllPasswordHashesForUser`).
- **Migration 1.9** (last shipped is 1.8, session tokens): additive — only the `users` soft-delete boolean
  column. No data backfill (existing rows default to not-deleted). Generated via
  `./gradlew :api-persistence-sqlite:generateDbMigration`.
- The pin/board/tag bulk-delete additions are new queries, not schema changes.

## 13. Testing strategy

Strict TDD, 100% branch coverage per package, failing test first. Order per AGENTS.md: integration
(REST Assured) → use-case unit (MockK) → repository (Ebean). Both sides of every conditional.

**Integration (`api-application`):**

- **Change password:** success → `204`, the pre-change token is rejected (`401`) **and** every other
  session is dead, **and** login with the new password succeeds while the old one fails; wrong
  `currentPassword` → `403`, password unchanged; **reusing any past password** (the current one, and an
  earlier one after two changes) → `422`; short `newPassword` → `400`; unauthenticated → `401`.
- **Delete account:** success → `202`; immediately after, the token is rejected and login is refused
  (account invisible); a new registration of the **same username is blocked while pending** and allowed
  **after** the task completes; after processing, the user's pins/boards/tags/images rows and on-disk bytes
  are gone; missing `X-Reauthentication` → `403`, unsupported kind → `400`, wrong factor → `403`;
  unauthenticated → `401`; a second `DELETE /me` with the dead token → `401`.
- **Deletion completeness:** seed a user with a pin that has an uploaded image (on-disk original + a
  generated rendition), a board membership, and tags; after deletion assert every row and both on-disk
  paths are gone.

**Use-case unit (MockK):**

- `BcryptPasswordHasher` (adapter): `hash` then `matches` round-trips; `matches` false on a different
  password; algorithm dispatch. (Use-case tests mock the `PasswordHasher` port.)
- `Reauthenticator`: correct factor passes; wrong → `ReauthenticationError`.
- `PasswordChanger`: happy path appends + `revokeAll`; wrong current → 403 short-circuit (no write, no
  revoke); reused new → 422 short-circuit; append + revoke share one `@Transactional`.
- `AccountDeleter`: happy path tombstones + revokes + enqueues with the right kind/payload/dedupKey; step-up
  failure short-circuits.
- `AccountDeletionCleaner`: full order on a user with pins (active + soft-deleted), images, downloads,
  boards, tags; null-user short-circuit (idempotent re-run); disk cleanup after the DB unit, with a
  rendition-eviction failure swallowed.

**Repository (`api-persistence-sqlite`):**

- Ebean `@SoftDelete`: after `markPendingDeletion`, `findUserById`/`findUserByName` return null but
  `findUserByIdIncludingDeleted` / `findUserByNameIncludingDeleted` return the user (with `softDeleted =
  true`); `permanentlyDeleteUser` then removes it entirely.
- Password history: two `saveUserPasswordHash` calls yield two rows; `findCurrentPasswordHash` returns the
  latest; `findAllPasswordHashesForUser` returns both; `deleteForUser` removes all.
- `permanentlyDeleteAllPinsForUser` / `…BoardsForUser` remove all states incl. join rows;
  `deleteAllTagsForUser` removes the user's tags.

## 14. Risks / open points

- **Per-user enumeration vs the Ebean tombstone.** The deletion task enumerates pins/boards/tags **after**
  the user is tombstoned. Ebean applies a `@SoftDelete` predicate when a query joins/fetches the soft-delete
  entity. The reused finders key on the `author_id` / `user_id` **FK column** (no join to the `users` row),
  so they should not be filtered out — **verify** this for every reused finder; if any eagerly joins
  `UserModel`, the cleaner must `setIncludeSoftDeletes` on it.
- **Committed-DB / failed-disk residue.** A crash after the DB commit but before/within disk cleanup orphans
  image bytes no retry revisits. Accepted for v1; covered by the P2 residue-GC item.
- **Step-up factor in a header.** Base64url mirrors HTTP Basic and survives the header charset, but the
  header carries a secret — it **must be excluded from access logs** (like `Authorization`). Noted for the
  plan.
- **Password history growth.** Unbounded rows per user over many changes. Fine for expected use; capping the
  retained window (and the reuse check) is a possible later refinement (out of scope, §2).
- **Large accounts.** The task loops per-pin for image collection and download clearing. Fine for expected
  volumes; batch if accounts grow huge. Not optimised in v1.
- **Handler placement is knowingly temporary** (§3); it moves with the scheduled `api-worker-quarkus`
  extraction. No functional risk; avoid entrenching more logic in the thin adapter.
- **`BcryptPasswordHasher` lives in the `api-application` composition root** as a pragmatic home (§4), not a
  layering violation; it moves to a dedicated adapter module with the other misplaced infra/security
  adapters (backlog). No functional risk.
