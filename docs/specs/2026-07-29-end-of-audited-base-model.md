# End of `AuditedBaseModel` (block 2 of domain-owned timestamps)

Date: 2026-07-29
Status: Approved 2026-07-29
Branch: `refactor/end-audited-base-model`
Parent spec: `docs/specs/2026-07-29-domain-owned-timestamps.md`. Parent ADR:
`docs/adr/0006-domain-owned-timestamps.md` (decision D6 carries this block; no new ADR, see the end
of section 3). Section 6 of the parent is in force, **except** seven corrections this specification
pins, all of them mechanical and each grounded in the code or the consulted documentation rather than
in the frozen document's prose:

1. The count of dead columns is **eight**, not the seven the parent's prose states twice. The parent's
   own table (section 6.3) sums to eight.
2. **No new `created_at` columns are added.** `session_tokens.when_created` and
   `user_password_hashes.when_created` are reused as the domain-mapped `createdAt`, the convention
   `UserModel` (`UserModel.kt:17`) and `AuthoredBaseModel` (`AuthoredBaseModel.kt:17`) already follow.
   The parent's section 6.5 names two added columns that are not added.
3. **No table rebuild.** A column drop is a plain `alter table ... drop column` the store applies in
   place, measured on block 1's `1.14__dropsFor_1.13.sql`. The parent's section 6.5 expected a rebuild.
4. The tasks retention index follows the column the sweep filters on: `(state, when_modified)` becomes
   `(state, terminal_state_at)`.
5. There are **five** paths into a terminal state, not the four the parent's section 6.2 names. The
   exhausted-task kill inside `claimNext` (`EbeanTaskQueue.kt:104-112`) is the fifth.
6. `terminalStateAt` is written **explicitly** on every terminal transition, because a bulk
   `asUpdate().update()` bypasses the bean lifecycle that `@WhenModified` is part of
   (ebean.io/docs/query/update and /mapping/extensions/when-modified, consulted 2026-07-29). It is not
   the rename the parent implied: the current `when_modified`-based retention never moved on the four
   `asUpdate()` terminal paths in the first place, so this block fixes the sweep's semantics as well as
   its ownership.
7. The migration is **two events, not one pair**. T1 adds `tasks.terminal_state_at` and moves the tasks
   index (`1.15`); T2 drops the eight columns through a `pendingDrops` pair
   (`1.16` / `1.17__dropsFor_1.16`). The parent's section 6.5 anticipated one pair from a single model
   state. Made while planning, 2026-07-29, recorded in
   `docs/plans/2026-07-29-end-of-audited-base-model.md`.

The parent's decisions D1, D6, D7 are carried; D2 to D5 were block 1's and are done.

## 1. Goal

Delete `AuditedBaseModel` and the dead audit columns it spread, and give the business facts that were
read out of those columns a domain owner. Three Ebean-generated columns are read to decide something the
business owns, and two of them decide a permanent deletion:

| Generated column | What it decides | Site |
|---|---|---|
| `tasks.when_modified` | deletion of a terminal task | `EbeanTaskQueue.kt:229` |
| `user_password_hashes.when_created` | which password hash is current | `UserPasswordHashRepository.kt:32` |
| `session_tokens.when_created` | (none today; promoted to `SessionToken.createdAt` for incident debugging) | - |

The first is the same defect as account retention, which block 1 closed: a business instant invented by
the persistence adapter. The task case is worse than the account case was, because the terminal
transitions are bulk updates that bypass `@WhenModified`, so the column read by the sweep did not even
move when a task settled (correction 6 above). `AuditedBaseModel` is the source of all of it, so it is
deleted, and a Konsist assertion bars `@WhenCreated` / `@WhenModified` from coming back.

## 2. Scope

**In scope:**

- `AuditedBaseModel` is deleted. Its four subclasses (`TaskModel`, `SessionTokenModel`,
  `UserDataExportModel`, `UserPasswordHashModel`) extend `BaseModel` and declare only what they need.
  `UserModel` and `TagModel` lose the `@WhenModified` they carried directly.
- `Task.terminalStateAt: Instant?`, `SessionToken.createdAt: Instant`, `HashedPassword.createdAt:
  Instant` are added to the domain.
- Five terminal-state transitions write `terminalStateAt`, and `cancelPending` gains `now: Instant`.
  `deleteTerminalBefore` filters on `terminalStateAt`.
- The use cases that create a session token or a password hash stamp `createdAt` from `Clock`.
- Two migration events (correction 7): T1 adds `tasks.terminal_state_at`; T2 drops eight dead columns.
  Eight, not seven.
- A Konsist assertion: no production source imports `io.ebean.annotation.WhenCreated` or
  `io.ebean.annotation.WhenModified`.

**Out of scope:**

- Block 3 (current-password determinism, the `(user_id, created_at)` unique constraint and the minimum
  interval). It depends on `HashedPassword.createdAt` existing, which this block adds.
- Any backfill (parent D12): nothing is deployed.
- The three P2 items the soft-delete review surfaced (inherited Ebean finders, the one-insert session
  window, the export retention sweep aborting on a tombstoned owner).
- Flattening the migration history, a beta-time item.

## 3. Decisions (invariants)

- **D1 (kept).** No clock is read in `api-persistence-sqlite`. Business instants arrive as parameters.
- **D6 (kept).** `AuditedBaseModel` is deleted and the `@When*` annotations are banned, which requires
  zero remaining occurrences.
- **D7 (kept), applied to each column.** A column read by the business becomes a named domain fact; a
  column nobody reads is deleted, not promoted. The application:
  - `tasks.when_created` is read by nobody (the task has no creation instant in the domain) and is
    deleted.
  - `tasks.when_modified` was read by the sweep and is replaced by `terminal_state_at`.
  - `session_tokens.when_created` is read by nobody today, and is **promoted** to `SessionToken.createdAt`
    by this decision rather than deleted, for incident debugging. This is the one deviation from D7's
    default of deleting an unread column, and it is a deliberate design choice (user-confirmed
    2026-07-29), not an automatic promotion to a generic `createdAt`.
  - `user_data_exports.when_created` is unread: `requestedAt` is its creation instant and is already
    domain-mapped. Deleted.
  - `user_password_hashes.when_created` is read by `findCurrentPasswordHash` and becomes the domain fact
    `HashedPassword.createdAt`.
  - every `when_modified` other than the task one is unread and is deleted.
- **D18.** `terminalStateAt` is written explicitly on every terminal transition. A bulk
  `asUpdate().update()` does not pass through the bean lifecycle, so `@WhenModified` is not applied to
  it (ebean.io/docs/query/update, /mapping/extensions/when-modified; consulted 2026-07-29). The value is
  set in the update statement on the four `asUpdate()` paths and on the bean for the one save path, and a
  test asserts each of the five paths writes it. This is why the current `when_modified` retention is
  defective on the `asUpdate()` paths, not merely misplaced.
- **D19.** `session_tokens.when_created` and `user_password_hashes.when_created` are reused as
  domain-mapped `createdAt`, the column kept under its historical name so the change costs no add or
  drop for it. The property stops being auto-stamped and is written by the mapper from the domain value
  the use case stamped. No `created_at` column is added; this corrects the parent's section 6.5.
- **D20.** The tasks retention index tracks the filtered column: `@Index(columnNames = ["state",
  "terminal_state_at"])`, replacing `["state", "when_modified"]` (`TaskModel.kt:18`).

**No new ADR.** This block executes the parent's D6 and corrects mechanical errors in the frozen
section 6; it settles no architectural question the parent did not already settle. ADR 0006 remains the
authority for "delete `AuditedBaseModel` and ban `@When*`".

## 4. Design

### 4.1 Domain

- `Task` gains `terminalStateAt: Instant?`: the instant the task entered `SUCCEEDED`, `DEAD` or
  `CANCELLED`. Null while live. Never cleared.
- `SessionToken` gains `createdAt: Instant`.
- `HashedPassword` gains `createdAt: Instant`.

### 4.2 Task queue: the five terminal paths

Every path that settles a task into a terminal state writes `terminalStateAt = now` (D18). Four are
bulk updates that already take `now` and gain one `set`; the fifth is a bean save:

| Path | Shape today | After |
|---|---|---|
| `markSucceeded` (`:143`) | `asUpdate().set("state", SUCCEEDED)` | adds `.set("terminalStateAt", now)` |
| `markDead` (`:171`) | `asUpdate().set("state", DEAD)` | adds `.set("terminalStateAt", now)` |
| `markCancelledIfRequested` (`:184`) | `asUpdate().set("state", CANCELLED)` | adds `.set("terminalStateAt", now)` |
| `cancelPending` (`:196`) | `asUpdate()`, no `now` | gains `now: Instant`, adds `.set("terminalStateAt", now)` |
| `claimNext` exhausted kill (`:104`) | `database.save(model)` to DEAD | sets `model.terminalStateAt = now` before save |

`deleteTerminalBefore(cutoff)` filters `.terminalStateAt.lessThan(cutoff)` instead of
`.whenModified.lessThan(cutoff)` (`:229`), and its KDoc (`TaskQueueInterface.kt:63-66`) is updated in
the same commit to name `terminalStateAt`. `cancelPending`'s added parameter changes an internal port
signature, not the HTTP contract. `ReapTerminalTasks` already passes the cutoff from its own `Clock` and
is unchanged.

`TaskModelMapper` round-trips `terminalStateAt`.

### 4.3 Models: delete the superclass, declare what is needed

`AuditedBaseModel.kt` is deleted. The four subclasses extend `BaseModel`. `when_modified` is removed
from `UserModel` (`:24`) and `TagModel` (`:21`).

| Model | Declares | Drops |
|---|---|---|
| `TaskModel` | `terminalStateAt: Instant?` | `when_created`, `when_modified` |
| `SessionTokenModel` | `@Column(name = "when_created") createdAt: Instant` (D19) | `when_modified` |
| `UserDataExportModel` | nothing (`requestedAt` is its creation instant) | `when_created`, `when_modified` |
| `UserPasswordHashModel` | `@Column(name = "when_created") createdAt: Instant` (D19) | `when_modified` |
| `UserModel` | nothing (block 1 gave it `soft_deleted_at`) | `when_modified` |
| `TagModel` | nothing | `when_modified` |

Drops total **eight**. `users.when_created` and `tags.when_created` stay: both are already mapped from
the domain (`UserModel.kt:17`, `AuthoredBaseModel.kt:17`) and are not `@WhenCreated`. The `version`
optimistic-lock column on `TaskModel` is not an audit column and stays.

`SessionTokenModelMapper` and `UserPasswordHashModelMapper` round-trip `createdAt` from the domain; no
mapper and no repository supplies it. `UserPasswordHashRepository.findCurrentPasswordHash` reads
`.createdAt` where it read `.whenCreated` today (`:32`), ordering on the same column under its new
domain-mapped name.

### 4.4 Use cases

The use cases that create a session token or a password hash stamp `createdAt` from `Clock`.
`SessionCreator` (`:30`) and `SessionRenewer` (`:27`) already inject `Clock` and pass `clock.now()` as
`SessionToken.createdAt`. `UserCreator` (`:31`) and `PasswordChanger` (`:24`) stamp
`HashedPassword.createdAt`; `PasswordChanger` gains `Clock` (block 3 will read it too). The
`PasswordHasher.hash` contract gains the instant as a parameter (`hash(raw, createdAt)`), the same idiom
as `softDeletePin(pin, at)`: an adapter does not own a business instant, it receives it. The
constant-time `dummyHash` in `UserAuthenticator` (`:24`) is never persisted, so it takes a sentinel
instant rather than a `Clock` it would have no other use for, with a comment.

### 4.5 Migration

**Two events, not one pair** (correction 7). The block's two behaviours compile and test green
separately, so each carries its own migration event:

- **T1 adds `tasks.terminal_state_at` and moves the tasks index** (D20), one migration (`1.15`): a plain
  `alter table` (add column; drop and recreate the index). No `pendingDrops`, because nothing is dropped
  here. `TaskModel` still extends `AuditedBaseModel` at this point.
- **T2 drops the eight columns** through the `pendingDrops` two-run mechanic, one pair (`1.16`, which
  records the drops, and `1.17__dropsFor_1.16`, which emits them), selected by a second run with
  `JAVA_TOOL_OPTIONS="-Dddl.migration.pendingDropsFor=1.16"`. No add. This is when `AuditedBaseModel`
  goes and the last `@When*` disappears.

**No table rebuild** (correction 3). Both events emit plain `alter table ... drop column` and index
statements the store applies in place, as `1.14__dropsFor_1.13.sql` does. Each generated SQL is read
before it is committed, not assumed. The `tasks` index moves in T1, so by the time T2 drops
`tasks.when_modified` the column is no longer indexed and SQLite's refusal to drop an indexed column does
not bite; whether the T1 index change is an in-place drop-and-recreate is the thing looked for there.

**No backfill** (parent D12). `terminal_state_at` is nullable, so a terminal row that pre-existed the
pair would carry null and never satisfy `terminalStateAt < cutoff`, leaving it unswept. No database
holds such a row: the project is alpha, nothing is deployed, and the tests build the store from the full
history. The consequence and the recovery statement
(`update tasks set terminal_state_at = ... where state in (...) and terminal_state_at is null`) are
written into the migration, as block 1 did for its no-backfill case.

### 4.6 Structural enforcement

One Konsist assertion in `ArchitectureKonsistTest`, where the project's structural assertions live,
expressed as the prohibition and finishing on `assertEmpty()`:

```kotlin
@Test
fun `Given persistence sources, Then none imports the Ebean generated-timestamp annotations`()
```

covering `io.ebean.annotation.WhenCreated` and `io.ebean.annotation.WhenModified`. It can only land once
every occurrence is gone (end of T2), which is why it is the block's last task. It is checked the same
way the project's other structural assertions are: a mutation that should trip it (re-adding an import)
makes it fail, pasted in the introducing commit.

## 5. Testing strategy

Strict TDD, red before green, the failing test committed alone with the command and its output in the
message body. Project order: integration tests in `api-application`, then use-case tests in
`api-usecases`, then repository tests in `api-persistence-sqlite`. 100% branch coverage per package on
the modules inside the perimeter.

1. **Each of the five terminal transitions writes `terminalStateAt`** (D18). A repository test settles a
   task through each path and asserts the column equals the `now` handed in, including `cancelPending`
   with its new parameter and the `claimNext` exhausted kill. A sixth assertion holds that a live task
   has null `terminalStateAt`, so retries and reaps do not write it.
2. **Retention reads `terminalStateAt`**. The `backDateWhenModified` helper in `EbeanTaskQueueTest`
   becomes a back-date of `terminalStateAt`, and `deleteTerminalBefore` is asserted on both sides of the
   cutoff.
3. **`createdAt` is stamped by the use case from `Clock`**, for session tokens and password hashes, and
   round-tripped by the mapper. A use-case test asserts the instant handed to the repository comes from
   the injected `Clock`.
4. The Konsist assertion fails red while any `@When*` import remains, then passes once they are gone.

The red here is usually a compilation failure (a test naming a type its implementation has not
introduced yet), pasted from the run.

## 6. Risks and accepted trade-offs

- **The sweep's semantics change, not just its ownership.** Today `deleteTerminalBefore` filters
  `when_modified`, which the four `asUpdate()` terminal paths never moved; it measured claim time, not
  terminal time. Switching to an explicitly-written `terminalStateAt` makes the sweep mean what it
  always appeared to mean. Accepted under alpha status: the change is the intent of the work, and the
  five-path test pins it.
- **`SessionToken.createdAt` is a deliberate promotion of an unread column** (D7 deviation), confirmed
  by the operator. The alternative (delete it, strict D7) is a one-column difference and is recorded
  here so the choice is visible.
- **`cancelPending` and `PasswordHasher.hash` gain a parameter**, changing internal port signatures. Not
  the HTTP contract.
- **The `tasks` index drop-and-recreate** in T1 is read from the generated migration, not assumed.
- **Two migration events (three files) on an append-only history**, instead of the parent's one pair,
  already slated for flattening at beta.
- **The guarantee is a build-time prohibition, not a compile-time impossibility.** The Konsist assertion
  bars the import; nothing stops a hand-written SQL fragment from setting a generated-timestamp column
  out of band. The ordinary way of declaring the column is what is caught.
