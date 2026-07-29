# Plan: end of `AuditedBaseModel` (block 2 of domain-owned timestamps)

Date: 2026-07-29
Spec: `docs/specs/2026-07-29-end-of-audited-base-model.md`
ADR: none new. Parent `docs/adr/0006-domain-owned-timestamps.md` carries decision D6 ("delete
`AuditedBaseModel` and ban `@When*`"); this block executes it and corrects mechanical errors in the
parent's frozen section 6. See "Corrections to the specification, made while planning" below for the one
spec change this plan makes.
Branch: `refactor/end-audited-base-model`

## Conventions for every task

- **TDD, red first.** Each task commits its failing artefacts alone before any implementation, as
  `test(scope): <behaviour>`, the message body carrying the command and the failure pasted from the
  run. The red here is usually a compilation failure (a test naming a type or member its
  implementation has not introduced yet breaks `compileTestKotlin`), pasted from the run, never
  retyped.
- **Every task ends on a green gate.** The red windows are inside tasks, never between them.
- **Scope.** Touch only the files a task lists. Adjacent defects go to `docs/backlog.md`, not to this
  branch. Block 3 (current-password determinism) is not started here: no `(user_id, created_at)`
  constraint, no minimum interval, no new error codes. `HashedPassword.createdAt` is added because
  block 3 needs it, but nothing reads it for ordering yet beyond `findCurrentPasswordHash`.
- **Coverage.** Everything new is inside the gate perimeter at 100% branch per package. The `models`
  package stays excluded (operator decision B1), so the model surgery is measured through the
  mappers and repositories that exercise it, not through the model classes directly.
- **Living documents move with the code.** `agents/project.md` is updated in the same commit as the
  change it describes, never in a follow-up `docs:` commit.
- **Baseline.** `./gradlew gate` was measured green on `main` before this branch (BUILD SUCCESSFUL,
  exit 0), which is what every task compares against.
- **Drops go through `pendingDrops`.** Ebean never puts a destructive change in the apply output: a
  dropped column goes into a `pendingDrops` change set, selected by a second run of the generator with
  `JAVA_TOOL_OPTIONS="-Dddl.migration.pendingDropsFor=<version>"`, which writes a `__dropsFor_` pair
  (ebean.io/docs/setup/dbmigration, "Pending Drops"; consulted 2026-07-29). Both files are committed
  together. The generated SQL is read before commit, not assumed.

## What the block rests on

Verifiable with `git grep`, and each task carries the command:

| Fact | Command | Output today |
|---|---|---|
| The five terminal-state paths in `EbeanTaskQueue` | `git grep -n "TaskState\.\(SUCCEEDED\|DEAD\|CANCELLED\)\.name" -- api-persistence-sqlite/src/main` | `markSucceeded:150`, `markDead:179`, `markCancelledIfRequested:192`, `cancelPending:201`, and the `claimNext` exhausted kill at `:105` (a bean save to `DEAD`, the fifth path the spec's section 6.2 missed) |
| The sweep reads `when_modified` | `git grep -n "whenModified" -- api-persistence-sqlite/src/main` | `EbeanTaskQueue.kt:229` (`.whenModified.lessThan(cutoff)`) and `TaskModel.kt:14,18` (KDoc + the `@Index(state, when_modified)`) |
| `@When*` annotations in production | `git grep -n "@When\(Created\|Modified\)" -- '*/src/main/*'` | four annotations: `AuditedBaseModel.kt:23,26`, `UserModel.kt:23`, `TagModel.kt:20` (`AuditedBaseModel.kt:15` is a KDoc sentence, not an annotation). Three files import the two annotation classes |
| `findCurrentPasswordHash` reads `whenCreated` | `git grep -n "whenCreated" -- '*/src/main/*'` | `AuditedBaseModel.kt:24` (declaration), `UserPasswordHashRepository.kt:32` (the read being promoted to `createdAt`) |
| `SessionToken` construction sites | `git grep -n "SessionToken(" -- '*/src/main/*'` | `SessionCreator.kt:30`, `SessionRenewer.kt:27` (both already inject `Clock`), `SessionTokenModelMapper.kt:9` (`toDomain`) |
| `HashedPassword` construction sites | `git grep -n "HashedPassword(" -- '*/src/main/*'` | `UserPasswordHashModelMapper.kt:9` (`toDomain`), `BcryptPasswordHasher.kt:12` (the hasher factory; `UserCreator.kt:31` and `PasswordChanger.kt:24` call it) |
| The retention use case already passes a cutoff | `ReapTerminalTasks.kt` | `taskQueue.deleteTerminalBefore(clock.now() - terminalTaskGrace)`; unchanged by this block, only the repository's filter moves |

## Corrections to the specification, made while planning

Section 4.5 says the migration is **one pair**: the add of `tasks.terminal_state_at` and the eight
drops, both from a single model state. Planning showed the block's two behaviours compile and test
green separately, so it splits along them (T1 `terminalStateAt`, T2 `createdAt` and the deletion), and
each carries its own migration event:

- T1 adds `tasks.terminal_state_at` and moves the tasks index: one migration, `1.15`, a plain
  `alter table` (add column, drop and recreate the index). No `pendingDrops`.
- T2 drops the eight columns: one `pendingDrops` pair, `1.16` (records the drops) and
  `1.17__dropsFor_1.16` (emits them). No add.

That is three migration files where the specification anticipated two, on a history the project keeps
append-only only until the beta flattening. Section 4.5 is corrected in the same commit as this plan,
the specification not being frozen until the branch integrates. Nothing else in the specification
moves: the count stays eight, the column reuse (D19) stays, the "no rebuild" finding stays, the five
terminal paths (D18) and the index move (D20) stay.

## Tasks

### T1: `terminalStateAt` and the task lifecycle

Closes the task half of D18 and D20. `TaskModel` gains `terminalStateAt` while **still extending
`AuditedBaseModel`**: it leaves the superclass in T2, when the other three subclasses do. The five
terminal paths write the instant; the sweep filters it; the retention use case is untouched.

**Files**

- `api-domain`: `tasks/Task.kt` (`val terminalStateAt: Instant? = null`); `repositories/TaskQueueInterface.kt`
  (`cancelPending(id, now: Instant)`, and the `deleteTerminalBefore` KDoc at `:63-66` rewritten to name
  `terminalStateAt`).
- `api-persistence-sqlite`:
  - `models/TaskModel.kt`: `var terminalStateAt: Instant? = null`; the index becomes
    `@Index(columnNames = ["state", "terminal_state_at"])`. `TaskModel` keeps `: AuditedBaseModel(id)`
    for now. The KDoc at `:13-17` describes the `when_modified` index and is rewritten to name
    `terminal_state_at`.
  - `repositories/EbeanTaskQueue.kt`: `markSucceeded`, `markDead`, `markCancelledIfRequested` and
    `cancelPending` each add `.set("terminalStateAt", now)` to their `asUpdate()` (D18: a bulk update
    does not honour `@WhenModified`); `cancelPending` gains the `now` parameter; the `claimNext`
    exhausted kill (`:104-112`) sets `model.terminalStateAt = now` before its `database.save`;
    `deleteTerminalBefore` filters `.terminalStateAt.lessThan(cutoff)` (`:229`).
  - `mappers/TaskModelMapper.kt`: round-trip `terminalStateAt`.
- `api-usecases`: `tasks/CancelTask.kt` injects `Clock` and calls `cancelPending(id, clock.now())`
  (its `cancel(id)` signature is unchanged, so the controller is unaffected); its test
  `tasks/CancelTaskTest.kt` updates the `cancelPending` mock to the two-argument form. The existing
  `cancelPending` calls in `EbeanTaskQueueTest` (`:231,242,377,378`) gain the `now` argument.
- Migration: `dbmigration/1.15.sql` and `model/1.15.model.xml` (add `tasks.terminal_state_at`; drop
  and recreate the tasks index). Read before commit; the thing to look for is that the index change
  is an in-place drop-and-recreate, not a table rebuild.

**Red, one commit** (repository-level: `ReapTerminalTasks` is unchanged since it already passes the
cutoff, and `CancelTask` is a mechanical follower of the port change rather than a new behaviour):

`EbeanTaskQueueTest`: each of the five terminal transitions writes `terminalStateAt` equal to the
`now` handed in, including `cancelPending` with its new parameter and the `claimNext` exhausted kill; a
live task has null `terminalStateAt`, so retry and reap do not write it; `deleteTerminalBefore` keeps
and drops tasks on the two sides of the cutoff. The `backDateWhenModified` helper becomes a back-date
of `terminal_state_at`. The comment at `EbeanTaskQueueTest.kt:364-368` that justifies the raw-SQL
back-date by citing `@WhenModified`'s overwrite-on-save is rewritten: `terminal_state_at` is a plain
column, no longer auto-stamped. Red is `compileTestKotlin` failing on the unresolved `terminalStateAt`,
pasted from `./gradlew :api-persistence-sqlite:compileTestKotlin`.

**Implementation**: as listed.

**Acceptance**: `./gradlew :api-persistence-sqlite:test --tests "EbeanTaskQueueTest"` green;
`./gradlew gate` green; `git grep -n "whenModified" -- api-persistence-sqlite/src/main` no longer
returns `EbeanTaskQueue.kt:229` (the KDoc and index in `TaskModel` remain until T2).

### T2: `createdAt`, the end of `AuditedBaseModel`, and the eight drops

Closes D6, D7 and D19, and the `createdAt` half of the block. Deletes `AuditedBaseModel` (all four
subclasses now extend `BaseModel`), drops `@WhenModified` from `UserModel` and `TagModel`, reuses
`when_created` as the domain-mapped `createdAt` for session tokens and password hashes, and drops the
eight dead columns.

**Files**

- `api-domain`: `entities/SessionToken.kt` (`val createdAt: Instant`); `entities/HashedPassword.kt`
  (`val createdAt: Instant`); `security/PasswordHasher.kt` (`hash(raw, createdAt)`; see the design note
  below).
- `api-system`: `BcryptPasswordHasher.kt` (`hash` gains `createdAt`, passes it through).
- `api-persistence-sqlite`:
  - **Delete** `models/bases/AuditedBaseModel.kt`.
  - `models/TaskModel.kt`: `: BaseModel(id)` (drops the `AuditedBaseModel` inheritance); it keeps the
    `terminalStateAt` from T1 and loses nothing else it still used, because `when_created` and
    `when_modified` were never read on tasks.
  - `models/SessionTokenModel.kt`, `models/UserPasswordHashModel.kt`: `: BaseModel(id)`, each declares
    `@Column(name = "when_created") var createdAt: Instant` (D19; the column keeps its name, the
    property is now mapper-written), drops `when_modified`.
  - `models/UserDataExportModel.kt`: `: BaseModel(id)`, drops `when_created` and `when_modified`
    (`requestedAt` is its creation instant).
  - `models/UserModel.kt`, `models/TagModel.kt`: drop `@WhenModified` and the `whenModified` property
    (and its import).
  - `mappers/SessionTokenModelMapper.kt`, `mappers/UserPasswordHashModelMapper.kt`: round-trip
    `createdAt`.
  - `repositories/UserPasswordHashRepository.kt`: `findCurrentPasswordHash` reads `.createdAt` where it
    reads `.whenCreated` (`:32`).
- `api-usecases`:
  - `SessionCreator.kt:30`, `SessionRenewer.kt:27`: `SessionToken(..., createdAt = clock.now())`.
  - `UserCreator.kt:31`: `passwordHasher.hash(password, clock.now())`.
  - `PasswordChanger.kt`: gains `Clock`, `passwordHasher.hash(newPassword, clock.now())`.
  - `UserAuthenticator.kt:24`: the constant-time `dummyHash` is never persisted, so it takes a
    placeholder instant, `passwordHasher.hash("constant-time-guard", Instant.EPOCH)`, with a comment.
- `agents/project.md`: the migration-history note that says `users`/`pins`/`boards`/`tags` keep
  `when_created` **and** `when_modified` is now half wrong (users and tags lose `when_modified` here);
  corrected in the same commit.
- Migration: the `pendingDrops` pair `dbmigration/1.16.*` and `1.17__dropsFor_1.16.*`, dropping
  `tasks.when_created`, `tasks.when_modified`, `session_tokens.when_modified`,
  `user_data_exports.when_created`, `user_data_exports.when_modified`,
  `user_password_hashes.when_modified`, `users.when_modified`, `tags.when_modified`. Read before commit;
  the thing to look for is whether the generator sequences the `tasks` index (already moved in T1) and
  the now-unindexed `when_modified` drop without a rebuild, and that none of the eight is a table
  rebuild. The no-backfill consequence (a terminal row pre-existing the pair would carry null
  `terminal_state_at` and never be swept) is written into the migration, as block 1 did.

**Design note: `HashedPassword.createdAt` and the hasher.** `createdAt` is a required domain field, so
every `HashedPassword` construction must supply it. The hasher is an adapter, and an adapter does not
own a business instant: `createdAt` arrives as a parameter to `hash(raw, createdAt)`, the same idiom as
`softDeletePin(pin, at)` and `markSucceeded(id, leaseId, now)`. The use cases pass `clock.now()`. The
constant-time `dummyHash` is the one construction that is never persisted and never read, so it takes a
sentinel rather than a `Clock` it would have no other use for; that is the single blemish and it carries
a comment. Resolved in T2 with the use-case tests below; if the sentinel reads worse than a `Clock`
injection on `UserAuthenticator`, the call is taken there and recorded, not deferred.

**Red, two commits**, in the project's testing order:

1. Use-case tests: `SessionCreator` and `SessionRenewer` hand `clock.now()` as `SessionToken.createdAt`;
   `UserCreator` and `PasswordChanger` hand `clock.now()` as `HashedPassword.createdAt` (the latter
   asserting `PasswordChanger` now uses the injected `Clock`). Asserted on the value passed, not on
   stubbing.
2. Repository test: `findCurrentPasswordHash` returns the hash whose `createdAt` is latest, ordering on
   `.createdAt` (the property rename from `.whenCreated`). Its fixture today relies on `Thread.sleep(2)`
   plus Ebean's auto-stamping to produce two distinct `when_created` values
   (`UserPasswordHashRepositoryTest.kt:30`); once the instant is use-case-supplied that timing trick is
   replaced by two explicit `createdAt` values on the fixtures, because the property is no longer
   auto-stamped.
3. **Mechanical sweep, not a separate red.** Making `createdAt` required and changing
   `PasswordHasher.hash` to `hash(raw, createdAt)` breaks every construction site at compile time. The
   ones the behaviour reds above do not already cover are fixture updates: seven test files building
   `SessionToken(...)` (`SessionTokenRepositoryTest`, `SessionControllerTest`, `SessionDtoMapperTest`,
   `BearerTokenIdentityProviderTest`, `SessionRenewerTest`, `SessionRevokerTest`,
   `SessionTokenAuthenticatorTest`) and six building `HashedPassword(...)` or mocking `hash(...)`
   (`UserPasswordHashRepositoryTest`, `PasswordChangerTest`, `ReauthenticatorTest`,
   `UserAuthenticatorTest`, `UserCreatorTest`, `BcryptPasswordHasherTest`). Each gains
   `createdAt = <instant>` (a fixed instant in fixtures, `clock.now()` where a `Clock` is in play) so the
   module compiles. The implementer finds them through the compile errors; the gate is what proves none
   was missed.

Both reds are `compileTestKotlin` failures (the `createdAt` members do not exist yet), pasted from the
run; the sweep is the compile fallout of the same members, fixed in the implementation.

**Implementation**: as listed.

**Acceptance**: `./gradlew gate` green; `models/bases/AuditedBaseModel.kt` gone;
`git grep -n "@When\(Created\|Modified\)" -- '*/src/main/*'` returns nothing;
`git grep -n "whenModified\|whenCreated" -- '*/src/main/*'` returns nothing (the `when_created` column
names remain on the four models that reuse or already mapped them, but no Kotlin property or read does).

### T3: ban `@WhenCreated` / `@WhenModified`

The structural guard for D6, in `ArchitectureKonsistTest` where the project's structural assertions
live. By the end of T2 there are no occurrences, so the assertion lands green: its red is the
**falsification** (re-add an import, run, watch it fail), pasted in the introducing commit, the way a
failing test carries its red (settled 2026-07-29).

**Files**: `api-application/src/test/.../ArchitectureKonsistTest.kt`.

**Assertion** (the prohibition, finishing on `assertEmpty()`):

```kotlin
@Test
fun `Given persistence sources, Then none imports the Ebean generated-timestamp annotations`()
```

covering `io.ebean.annotation.WhenCreated` and `io.ebean.annotation.WhenModified`, scoped to
`scopeFromProduction`. The import is the right level: it catches both the annotation use and a bare
import, and it cannot match a sentence in a KDoc the way file-text matching can.

**Red**: temporarily re-add `import io.ebean.annotation.WhenModified` to any production file, run the
test, paste the failure; revert. That output is what the commit message carries.

**Acceptance**: `./gradlew :api-application:test --tests "ArchitectureKonsistTest"` green;
`./gradlew gate` green; the falsification output in the `test(architecture):` commit body.

## Verify

`./gradlew gate` in full, then a holistic review by a fresh subagent over the whole branch diff with
mandate `agents/reviews/holistic.md`. Three zones the diff cannot show on its own:

- whether each of the five terminal paths really writes `terminalStateAt`, including the two a reader
  might not think of (`cancelPending`, the `claimNext` kill);
- whether the sweep now means terminal time rather than claim time, and whether any test still
  back-dates the old column out of habit;
- whether the eight drops and the `tasks` index change applied as in-place `alter table` rather than a
  rebuild that would have dropped the hand-written `ix_users_name_nocase`.

## Wrap

The handoff records what the gate does **not** validate: the drop pair is generated and read but never
applied to a database holding a row with `when_modified` set, since none exists (alpha, nothing
deployed); the five-path terminal-state semantics are proven on the in-memory test store, not against a
long-running queue; and the `dummyHash` sentinel is a deliberate blemish. The backlog loses block 2 from
the P0 entry and keeps block 3; the two corrections this block made to the frozen parent (count, and the
one-pair migration) are cross-linked from the parent's status line.
