# Domain-owned timestamps and a single soft-delete mechanism

Date: 2026-07-29
Status: Section 5 (block 1) superseded by
`docs/specs/2026-07-29-single-representation-soft-delete.md`; sections 6 and 7 (blocks 2 and 3) in
force, as is decision D4's replacement there.
Branch: `docs/domain-owned-timestamps` (this specification and its ADR only)
ADR: `docs/adr/0006-domain-owned-timestamps.md`, partially superseded by
`docs/adr/0007-single-representation-soft-delete.md`
Supersedes nothing. Related: `docs/specs/2026-07-22-user-data-export.md` (section 4 defines the
archive layout whose `updatedAt` semantics section 3 D2 below settles).
Depends on: `api-domain` entities and repository interfaces, `api-persistence-sqlite` models, mappers
and repositories, `api-usecases` recycle bins and password change, `api-presentation-quarkus` error
mapping, the migration history, `ArchitectureKonsistTest`.

## 1. Goal

Close the three backlog items that all describe the same defect: a business instant invented by the
persistence adapter instead of being stamped by a use case. The invariant they violate was decided on
2026-07-23 and is recorded in `agents/project.md`; the two soft-delete residues were explicitly left
open by the timestamps refactor, and the `findCurrentPasswordHash` tie-breaker was filed separately.

Scoping them in the code showed the defect is wider than the backlog recorded. Three Ebean-generated
columns are read to decide something the business owns, and two of the three decide a permanent
deletion:

| Generated column | What it decides | Site |
|---|---|---|
| `users.when_modified` | permanent deletion of a tombstoned account | `UserRepository.kt:81` |
| `tasks.when_modified` | deletion of a terminal task | `EbeanTaskQueue.kt:229` |
| `user_password_hashes.when_created` | which password hash is current | `UserPasswordHashRepository.kt:35` |

`@WhenModified` is rewritten on every write to the row, so a retention countdown driven by it restarts
whenever anything touches the row.

The work also unifies the two soft-delete mechanisms the codebase carries (Ebean's `@SoftDelete` on
users, hand-written filtering on pins and boards) onto the one with automatic filtering, and encodes
both invariants as Konsist tests so the debt cannot return.

## 2. Scope

**In scope:**

- `User.softDeleted: Boolean` becomes `User.softDeletedAt: Instant?`; `Pin` and `Board` are unchanged
  in the domain.
- `@SoftDelete deleted: Boolean` on `PinModel` and `BoardModel`, derived by the mapper from
  `softDeletedAt`; `UserModel` keeps its own and gains `soft_deleted_at`.
- The five soft-delete transitions take their instant as a parameter, and `updatedAt` is bumped on
  the four pin and board transitions.
- `AuditedBaseModel` is deleted; the six affected models declare what they need and nothing else.
- `Task.terminalStateAt`, `SessionToken.createdAt`, `HashedPassword.createdAt` are added; seven dead
  audit columns are dropped.
- Account and task retention read the new named instants.
- A `(user_id, created_at)` unique constraint on `user_password_hashes`, a configurable minimum
  interval between password changes defaulting to 30 seconds, and two new error codes.
- Two Konsist assertions: no `Instant.now()` and no `@WhenCreated` / `@WhenModified` in
  `api-persistence-sqlite`.
- Three migrations, one per block.

**Out of scope:**

- Limiting authentication attempts (brute force on `changePassword` and on `POST /api/v1/sessions`).
  Added to the backlog by this work. The rate limit specified here counts successful changes only and
  is explicitly not a brute-force defence (D10).
- Bumping the export archive `formatVersion` (D11).
- Any backfill of existing rows (D12).
- Flattening the migration history, which stays a beta-time item.
- The remaining P2 backlog items (`discardQuietly`, DEAD-task observability, periodic maintenance via
  the task queue).
- Making "current password" an explicit marker rather than an ordered read (considered and rejected,
  D8).

## 3. Decisions (invariants)

Settled in discussion on 2026-07-29; the ADR is `docs/adr/0006-domain-owned-timestamps.md`, whose
Decision section carries the reasoning. Condensed here as the invariants the three blocks must hold:

- **D1.** No clock is read in `api-persistence-sqlite`. Business instants arrive as parameters.
- **D2.** `updatedAt` means any modification, including recycling and restoration. No field-by-field
  or transition-by-transition arbitration.
- **D3.** Soft-delete transitions take the instant; they do not route through `savePin` / `saveBoard`,
  whose join resynchronisation they have no reason to trigger.
- **D4.** One soft-delete mechanism: Ebean's `@SoftDelete`, generalised to pins and boards, with the
  boolean derived by the mapper from the single domain field `softDeletedAt`.
- **D5.** `User.softDeleted: Boolean` becomes `User.softDeletedAt: Instant?`.
- **D6.** `AuditedBaseModel` is deleted and the `@When*` annotations are banned, which requires zero
  remaining occurrences and therefore all six models in scope.
- **D7.** A column read by the business becomes a named domain fact; a column nobody reads is deleted,
  not promoted to a generic `createdAt` / `updatedAt`.
- **D8.** The current password stays an ordered read, made deterministic by a unique constraint plus a
  minimum interval, rather than by an explicit "current" marker.
- **D9.** A residual unique-constraint violation is a 409 (`PASSWORD_CHANGE_COLLISION`), not a 500;
  the interval refusal is a 429 (`PASSWORD_CHANGED_TOO_SOON`).
- **D10.** The rate limit counts successful changes, not attempts, and does not protect against brute
  force.
- **D11.** The export archive keeps `formatVersion = 1`. Under D2, `updatedAt` in `pins.jsonl` and
  `boards.jsonl` now moves when a pin or board is recycled or restored; `deletedAt` remains the
  authority on recycling state. This paragraph is the specification of that field's meaning, which
  version 1 never stated.
- **D12.** No backfill: nothing is deployed.

## 4. Delivery

Three blocks, one session each (Plan, Act, Verify, Wrap, Improve), each integrating through its own
pull request. The order is imposed by dependency, not preference:

1. **Uniform soft delete.** Account retention cannot stop reading `when_modified` before
   `softDeletedAt` exists.
2. **End of `AuditedBaseModel`.** `UserPasswordHashModel` leaves the superclass here, which block 3
   needs.
3. **Current-password determinism.**

Between blocks 2 and 3, `findCurrentPasswordHash` orders on a `createdAt` the use case now stamps but
which carries no uniqueness guarantee yet. That is the state the codebase is in today, so the interval
is not a regression; block 3 closes it.

## 5. Block 1: uniform soft delete

### 5.1 Domain

- `User`: `softDeleted: Boolean = false` becomes `softDeletedAt: Instant? = null`.
- `UserRepositoryInterface.markPendingDeletion(user)` becomes `markPendingDeletion(user, at: Instant)`.
- `UserRepositoryInterface.findTombstonedUsersModifiedBefore(cutoff)` is renamed
  `findTombstonedUsersSoftDeletedBefore(cutoff)`: the old name described the column, the new one
  describes the fact.
- `PinRepositoryInterface`: `softDeletePin(pin, at: Instant)`, `restorePin(pin, at: Instant)`.
- `BoardRepositoryInterface`: `softDeleteBoard(board, at: Instant)`, `restoreBoard(board, at: Instant)`.

### 5.2 Persistence

- `PinModel` and `BoardModel` gain `@SoftDelete var deleted: Boolean = false` alongside the existing
  `softDeletedAt`.
- `UserModel` gains `var softDeletedAt: Instant? = null` and keeps `@SoftDelete var deleted`.
- The three mappers derive the boolean on the way to the model: `deleted = softDeletedAt != null`.
  Nothing else writes it. The domain direction reads `softDeletedAt` only, never `deleted`.
- The four transitions write both the instant they receive and `updatedAt` (pins and boards), and stop
  calling `Instant.now()`.
- `markPendingDeletion` stops calling `database.delete(model)` and saves the mapped model instead, so
  the `@SoftDelete` column is written through the same path as everything else. See section 9 for the
  risk this carries.
- The six recycle-bin queries add `setIncludeSoftDeletes()`: `PinRepository.kt:216, 237, 255`,
  `BoardRepository.kt:45, 68`. Without it they return empty once `@SoftDelete` is in place, because
  Ebean's automatic predicate and the `.softDeletedAt.isNotNull` filter exclude each other.
- The eight `.softDeletedAt.isNull` filters become redundant and are removed, along with the two
  join-side filters (`PinRepository.kt:62, 126`, `BoardRepository.kt:83`), whose predicate Ebean now
  applies automatically to the joined table.
- `PinModelSortStrategy` (`kt:123-141`) paginates the recycle bin by `softDeletedAt`; its queries
  inherit the same `setIncludeSoftDeletes()` requirement.
- `findTombstonedUsersSoftDeletedBefore` filters on `softDeletedAt.lessThan(cutoff)` instead of
  `deleted.isTrue.whenModified.lessThan(cutoff)`, keeping `setIncludeSoftDeletes()`.

### 5.3 Use cases

`PinRecycleBin.softDelete` / `.restore`, `BoardRecycleBin`'s equivalents, and the account-deletion use
case that calls `markPendingDeletion` read `Clock` and pass the instant. Under D2 the pin and board
transitions also set `updatedAt` to the same instant on the entity they hand to the repository.

### 5.4 Migration

One migration adding `pins.deleted` and `boards.deleted` (not null, default false) and
`users.soft_deleted_at` (nullable). No backfill (D12), so existing rows keep `deleted = false` and a
null instant.

### 5.5 Konsist

```kotlin
@Test
fun `Given persistence sources, Then none calls Instant now`()
```

Expressed as the prohibition, finishing on `assertEmpty()` so a failure names every offender. Two
occurrences exist today (`PinRepository.kt:195`, `BoardRepository.kt:50`); both disappear in this
block.

## 6. Block 2: end of `AuditedBaseModel`

### 6.1 Domain additions

- `Task.terminalStateAt: Instant?`: the instant the task entered `SUCCEEDED`, `DEAD` or `CANCELLED`.
  Null while the task is live. Never cleared, because no transition leaves a terminal state.
- `SessionToken.createdAt: Instant`, kept for incident debugging (D7).
- `HashedPassword.createdAt: Instant`.

### 6.2 Task queue

`markSucceeded`, `markDead` and `markCancelledIfRequested` already take `now: Instant`; they write it
to `terminalStateAt`. `cancelPending(id)` does not take one and gains `now: Instant`: it is the fourth
path into a terminal state. `deleteTerminalBefore(cutoff)` filters on `terminalStateAt` instead of
`whenModified`, and its KDoc (which currently names `whenModified`) is updated in the same commit.

### 6.3 Model changes

`AuditedBaseModel` is deleted. Its four subclasses (`TaskModel`, `SessionTokenModel`,
`UserDataExportModel`, `UserPasswordHashModel`) extend `BaseModel` and declare only what they need:

| Model | Declares | Drops |
|---|---|---|
| `TaskModel` | `terminalStateAt: Instant?` | `when_created`, `when_modified` |
| `SessionTokenModel` | `createdAt: Instant` (mapped from the domain) | `when_modified` |
| `UserDataExportModel` | nothing new (`requestedAt` is its creation instant) | `when_created`, `when_modified` |
| `UserPasswordHashModel` | `createdAt: Instant` (mapped from the domain) | `when_modified` |
| `UserModel` | nothing new (block 1 gave it `soft_deleted_at`) | `when_modified` |
| `TagModel` | nothing new | `when_modified` |

`UserModel` and `TagModel` carry `@WhenModified` directly rather than through the superclass; both
lose it. Note that `users.when_created` and `tags.when_created` are already mapped from the domain and
stay.

### 6.4 Use cases

The use cases that create a session token and a password hash stamp `createdAt` from `Clock`. Which
use cases those are is a Plan-time detail; the invariant is that no mapper and no repository supplies
the value.

### 6.5 Migration

One migration adding `tasks.terminal_state_at` (nullable), `session_tokens.created_at` and
`user_password_hashes.created_at` (both not null), and dropping the seven dead columns. SQLite
rebuilds the table for a column drop; `generateDbMigration` produces that, and the result is read
before it is committed rather than assumed.

### 6.6 Konsist

```kotlin
@Test
fun `Given persistence sources, Then none imports the Ebean generated-timestamp annotations`()
```

Covering `io.ebean.annotation.WhenCreated` and `io.ebean.annotation.WhenModified`. It can only land
once every occurrence is gone, which is why all six models are in this block (D6).

## 7. Block 3: current-password determinism

### 7.1 Constraint

A unique constraint on `user_password_hashes (user_id, created_at)`. Two hashes for one user can no
longer share an instant, so ordering on `created_at` is total per user and
`findCurrentPasswordHash` is deterministic.

### 7.2 Minimum interval

`PasswordChanger` refuses a change made less than a configured duration after the current hash's
`createdAt`, following the shape of `UserDataExportRequester.kt:58-63`: read the instant already in
the database, compare to `now - interval`, throw carrying the remaining seconds. No new state.

Configuration key `auth.password_change_minimum_interval`, default `PT30S`, matching the existing
`auth.*` ISO-8601 duration keys (`auth.persistent_ttl`, `auth.ephemeral_ttl`).

### 7.3 Error codes

| Code | Status | When |
|---|---|---|
| `PASSWORD_CHANGED_TOO_SOON` | 429 | the change falls inside the minimum interval |
| `PASSWORD_CHANGE_COLLISION` | 409 | the unique constraint is violated anyway |

Both are added to `BaseErrorMapper.statusFor`, whose `when` over `ErrorCode` has no `else`, so a
missing entry fails compilation. The 429 follows the `EXPORT_TOO_SOON` precedent and carries the same
retry information.

The 409 path requires the adapter to translate Ebean's unique-constraint exception into a domain
error, per the rule that exceptions cross layers only as domain types. The collision is reachable
when two concurrent requests from the same user pass the interval check together and insert in the
same tick.

### 7.4 Migration

One migration adding the unique index. No backfill (D12).

## 8. Testing strategy

Strict TDD, red before green, the failing test committed alone with the command and its output in the
message body. Project testing order: integration tests in `api-application`, then use-case unit tests
in `api-usecases`, then repository tests in `api-persistence-sqlite`. 100% branch coverage per package
on the ten modules inside the perimeter.

**Block 1.**

1. The `@SoftDelete`-written-by-mapper behaviour is proven **first**, before anything is built on it
   (section 9). A repository test soft-deletes a user through `markPendingDeletion`, then asserts the
   row is excluded from a normal query and returned by one with `setIncludeSoftDeletes()`, and that
   `softDeletedAt` and `deleted` agree.
2. Recycle-bin integration tests already exist end to end; they fail loudly if a query misses
   `setIncludeSoftDeletes()`. They are the guard for the generalisation and are run early.
3. Use-case tests assert the instant handed to the repository comes from the injected `Clock`, and
   that `updatedAt` moves on recycling and restoration (D2), which is a new assertion.
4. Retention: a repository test back-dates `softDeletedAt` (no longer `when_modified`) and asserts the
   cutoff behaviour, replacing the `backDateWhenModified` helper in `UserRepositoryTest`.
5. The Konsist assertion fails red while the two `Instant.now()` calls remain.

**Block 2.**

1. Task queue tests back-date `terminalStateAt` instead of `when_modified` (the helper in
   `EbeanTaskQueueTest` changes accordingly) and assert each of the four terminal transitions writes
   it, including `cancelPending` with its new parameter.
2. A test asserts a live task has a null `terminalStateAt`, so the column is not written on retry or
   reap.
3. The Konsist assertion fails red while any `@When*` annotation remains.

**Block 3.**

1. A repository test inserting two hashes for one user at the same instant asserts the constraint
   refuses the second, and that the adapter surfaces a domain error rather than an Ebean exception.
2. A `PasswordChanger` test asserts the refusal inside the interval and the success outside it, with a
   controlled `Clock`.
3. A controller test asserts 429 and 409 carry the RFC 7807 shape and the right `code` member.

## 9. Risks and accepted trade-offs

- **Principal risk: Ebean honouring a `@SoftDelete` property written by the mapper.** Today
  `markPendingDeletion` calls `database.delete(model)`, which is the documented way to set the flag.
  Block 1 writes it through a normal save instead. Nothing in the consulted documentation says whether
  Ebean interferes with a hand-written `@SoftDelete` property on save. This is proven by the test in
  section 8 before the rest of the block is built; if it does not hold, the fallback is to keep
  `database.delete()` for the flag and write `softDeletedAt` in the same transaction, which weakens
  the single-source derivation of D4 and would be brought back for a decision rather than absorbed.
- **The recycle bin breaks loudly during block 1.** Six queries return empty until they gain
  `setIncludeSoftDeletes()`. Accepted because existing end-to-end tests catch it immediately; it is
  visible work, not a silent regression.
- **`updatedAt` bumping on state transitions changes cursor ordering** for any pagination sorted on
  it. No such strategy is known to exist, but this is verified during block 1 rather than assumed.
- **Two new error codes are a public contract change** on the password-change endpoint. Mandated
  escalation to Spec is satisfied by this document; the codes are additive, so no existing client
  response changes.
- **The export archive's `updatedAt` changes meaning without a version bump** (D11). Accepted: no
  consumer exists, version 1 never specified the field, and section 3 D11 is now the written
  specification of its meaning.
- **`user_password_hashes` keeps an ordered read.** Making "current" explicit would have removed the
  tie-break by construction; the constraint plus interval closes the hole with a smaller contract
  change, and the residual concurrent case has a named 409 rather than being swallowed.
- **Three migrations rather than one**, since the blocks ship separately. The history is append-only
  until the beta flattening, so this adds three entries to a list that is already slated for collapse.
