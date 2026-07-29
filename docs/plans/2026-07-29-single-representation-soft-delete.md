# Plan: single-representation soft delete (block 1 of domain-owned timestamps)

Date: 2026-07-29
Spec: `docs/specs/2026-07-29-single-representation-soft-delete.md`
ADR: `docs/adr/0007-single-representation-soft-delete.md` (reverses decision 4 of `0006`)
Branch: `refactor/uniform-soft-delete`
Supersedes: `docs/plans/2026-07-29-uniform-soft-delete.md`, deleted in the commit that adds this file.
That plan generalised `@SoftDelete`; this one removes it, so nothing in it survives the reversal.

## Conventions for every task

- **TDD, red first.** Each task commits its failing artefacts alone before any implementation, as
  `test(scope): <behaviour>`, the message body carrying the command and the failure pasted from the
  run. A task with several kinds of red (unit tests, an activated detekt rule, a Konsist assertion)
  commits one red per kind rather than merging them, so each red is readable on its own.
- **A detekt rule activated against real sources is a red like any other**, and it is the only red
  that proves the rule is wired into the gate rather than merely written. Its unit tests prove what
  it reports; the activation proves it runs.
- **Every task ends on a green gate.** The red windows are inside tasks, never between them. No task
  may take a green `:api-application:test` as its acceptance while one of its own reds is open.
- **Scope.** Touch only the files a task lists. Adjacent defects go to `docs/backlog.md`, not to this
  branch. Blocks 2 and 3 of `docs/specs/2026-07-29-domain-owned-timestamps.md` are not started here:
  no `AuditedBaseModel` change, no `user_password_hashes` work.
- **Coverage.** Everything new is inside the gate perimeter, `detekt-rules` included, at 100% branch
  per package. The `models` package stays excluded (operator decision B1).
- **Living documents move with the code.** `agents/project.md` is updated in the same commit as the
  change it describes, never in a follow-up `docs:` commit.
- **Baseline.** `./gradlew gate` was measured green on this branch before any production change
  (BUILD SUCCESSFUL, exit 0), which is what every task compares against.
- **One mapped-column change for the block**, in T4, the only task that touches one. It yields **two**
  migrations rather than one: Ebean records a drop as a pending drop instead of putting it in the
  apply output, so the drop is asked for by a second run of the generator (T4).

## What the block rests on

The five Ebean facts researched for the previous plan are recorded in the ADR's Context and are
**no longer load-bearing**: with `@SoftDelete` gone, no query filters implicitly, so predicate
placement, foreign-key navigation, the `delete()` ambiguity, the `merge` collision and the fate of a
derived setter under enhancement all stop deciding anything here. They are kept in the ADR because
they were expensive to establish and because they bear on any future use of Ebean's soft delete.

What this plan rests on instead is verifiable with `git grep`, and each task carries the command:

| Fact | Command | Output today |
|---|---|---|
| The three recyclable query beans are constructed at 38 sites in 6 production files | `git grep -c "QPinModel()\|QBoardModel()\|QUserModel()" -- api-persistence-sqlite/src/main` | 38 across `PinRepository`, `BoardRepository`, `UserRepository`, `SessionTokenRepository`, `UserDataExportRepository`, `UserPasswordHashRepository` |
| Navigations on `softDeletedAt` in the module | `git grep -n "softDeletedAt\s*$\|softDeletedAt\." -- api-persistence-sqlite/src/main` | **15**, of which 2 are `PinModelSortStrategy.kt:133,141` (`asc()` / `desc()`, ordering rather than filtering, which detekt rule 2 excludes by name). The 13 that remain are 8 `.isNull` and 5 `.isNotNull`, the whole population of state predicates |
| Wall-clock occurrences in production sources | `git grep -n "Instant.now(" -- '*/src/main/*'` | **4**: `PinRepository:195`, `BoardRepository:50`, `SystemClock:20`, and `SystemClock:14`, which is a **KDoc sentence**. Three calls, one comment. That fourth line is the false positive spec lines 277-279 cite as the reason to move the assertion off file text and onto the syntax tree, and it is what T2 uses to prove the new rule does not repeat it |
| `User.softDeleted` is read in 2 production files | `git grep -n "softDeleted\b" -- '*/src/main/*'` | `User.kt:10` (declaration), `UserModelMapper.kt` |

## One correction to the specification, made while planning

Section 4.6's assertion 2 read "no production file outside `queries/` imports the query bean of a
`SoftDeletableModel`". That is false against the target state: `PinModelSortStrategy` is
`ModelSortStrategy<PinModel, QPinModel>` and names `QPinModel` in its supertype and in fourteen
signatures, without ever constructing one (`PinModelSortStrategy.kt:19-138`). Satisfying the
assertion literally would mean moving that file into `queries/`, which is moving something to escape
a constraint. The specification now exempts the `pagination` package and says why, and it moves the
one remaining name-only use in `PinRepository` (`ModelPaginationHelper<PinModel, QPinModel>()`,
`PinRepository.kt:46`) into `PinQueries`, which is an addition rather than a move. Corrected in the
same commit as this plan, the specification not being frozen until the branch integrates.

## Tasks

### T1: the `detekt-rules` module and its three rules

The project's first custom static-analysis rules. No production behaviour changes, so this task is
pure infrastructure and lands first: T2 and T3 each activate one of the rules as their own red.

**Files**

- `settings.gradle.kts`: `include(":detekt-rules")`.
- `gradle/libs.versions.toml`: `detekt-api` and `detekt-test` on the existing `detekt` version ref
  (`2.0.0-alpha.5`). No hard-coded version in a module build file.
- `detekt-rules/build.gradle.kts`: Kotlin JVM, `compileOnly(libs.detekt.api)`,
  `testImplementation(libs.detekt.test)`, JUnit. Depends on no project module, and no project module
  is on its compile classpath, so the layering is untouched.
- `detekt-rules/src/main/kotlin/fr/geoffreyCoulaud/pinryReborn/detekt/`: `PinryRuleSetProvider.kt`
  plus one file per rule.
- `detekt-rules/src/main/resources/META-INF/services/dev.detekt.api.RuleSetProvider`.
- `detekt-rules/src/test/kotlin/fr/geoffreyCoulaud/pinryReborn/detekt/`: one test class per rule.
- `build.gradle.kts` (root): wire `detektPlugins(project(":detekt-rules"))` into every subproject
  **except `detekt-rules` itself**, which would be a dependency cycle.
- `config/detekt/detekt.yml`: the rule set block. `QueryBeanConstructedByQualifiedName` active;
  `SoftDeleteStateFilteredOutsideQueries` and `WallClockRead` `active: false`, each with a one-line
  comment naming the task that activates it. Test and test-fixture paths excluded for the rule set
  (tests legitimately read the wall clock through `RepositoryTest.storableNow()` and construct query
  beans).
- `agents/project.md`: the module row in "Where the code lives", the module count (eleven becomes
  twelve), the gate perimeter (inside, 100%), and "Structural remedies have two homes" becomes three.
  Same commit as the module.

**Rules** (spec 4.6; none carries a type name, and none needs type resolution)

1. `QueryBeanConstructedByQualifiedName`: a construction of a `Q…Model` written as a fully-qualified
   name, in any module. The shape is the whole condition.
2. `SoftDeleteStateFilteredOutsideQueries`: a navigation that continues past `softDeletedAt` into a
   call, in a file outside `queries/`, excluding `asc()` and `desc()`, which are ordering rather than
   filtering. Scoped to `api-persistence-sqlite` in `detekt.yml`: outside it, `softDeletedAt` is a
   domain value that may legitimately be compared (`pin.softDeletedAt.isBefore(x)` would be a false
   positive), and query beans do not exist there.
3. `WallClockRead`: `Instant.now()`, `LocalDate.now()`, `LocalDateTime.now()` and
   `System.currentTimeMillis()` in eleven of the twelve modules, except inside a class implementing
   the `Clock` port. The exemption is structural, not a list. The twelfth is `detekt-rules`, which
   `build.gradle.kts:102-104` leaves off its own plugin list: a rule set is not on its own analysis
   classpath, so the module that declares the rule is the one whose production sources it never
   reads. The module stays inside the gate perimeter, so this is a limit of the tool and not a
   narrowing of that perimeter.

**Red**: each rule's unit test written before its rule class, so `./gradlew :detekt-rules:test` fails
to compile on an unresolved reference. That output is the red pasted into the `test(detekt):` commit.

**Acceptance**

- `./gradlew :detekt-rules:test` green, each rule covered on both sides: a violating snippet reports
  exactly one finding, at the expected location and with the expected message, and a compliant one
  reports none. The `queries/` exemption is not among them and no unit test can reach it:
  `detekt-test`'s `lint()` parses a snippet and calls `Rule.visitFile` on it, while path filtering
  belongs to the engine (`dev.detekt.utils.PathFilters`, which `dev.detekt.api.Rule` never touches).
  What covers the exemption is the rule's own entry in `config/detekt/detekt.yml`: `includes` naming
  the persistence module and `excludes` naming the `queries` package, with excludes winning over
  includes, measured on the real sources by activating the rule and counting its findings (13 under
  the module, 0 once the analysed package is excluded, 0 once `includes` names no existing module).
- Two further tests on the provider itself. The first: its rule set carries **exactly the three
  expected rule ids**. Rules 2 and 3 each get an activation red of their own later (T2, T3); rule 1
  never does, because no production source constructs a query bean by qualified name, so without
  this test its registration is asserted by nothing.
- The second compares the `pinry-reborn` keys of `config/detekt/detekt.yml` to the names the provider
  registers, because the first test cannot see the configuration and the build will not complain
  about it. Measured on this project rather than assumed: renaming `WallClockRead` to
  `WallClockReads` under `pinry-reborn` leaves `./gradlew :api-usecases:detekt
  :api-persistence-sqlite:detekt` **successful**, while renaming `AbstractClassCanBeConcreteClass`
  to `AbstractClassCanBeConcreteClasses` under `style` fails it with "Run failed with 1 invalid
  config property". Custom rule sets are excluded from configuration validation
  (detekt.dev/docs/introduction/configurations, "Config validation"; consulted 2026-07-29), and this
  project excludes the path a second time so the rule module can be analysed at all. Silence is
  therefore the danger, not the safeguard: a typo disables a rule and nothing says so, which is
  what this test converts into a failure. The Gradle test task declares `detekt.yml` as an input,
  without which editing it alone leaves the task up to date and the comparison unrun.
- `./gradlew :detekt-rules:koverVerify` green: 100% branch per package.
- `./gradlew gate` green. Rule 1 being active and reporting nothing is expected and is **not**
  evidence that it runs: `git grep -n "models\.query\.Q[A-Za-z]*Model("` returns no production match,
  so there is nothing for it to find. What proves the rule set is loaded by the gate at all is T2's
  red, where `WallClockRead` reports real occurrences in real sources.

### T2: the four pin and board transitions take their instant

Closes D1, D2 and D3 for pins and boards, and activates `WallClockRead`.

**Red, three commits**, in the project's testing order (integration, then use case, then repository):

1. Integration tests: recycling a pin moves its `updatedAt`, restoring it moves it again; same for a
   board (`PinSoftDeleteIntegrationTest`, `BoardRecycleBinIntegrationTest`). This is D2's new
   behaviour and the only user-visible change in the task.
2. Use-case tests: `PinRecycleBin.softDelete` and `restore` hand `clock.now()` to the repository, and
   the same for `BoardRecycleBin`. Asserted on the value passed, not on the stubbing.
3. `config/detekt/detekt.yml`: `WallClockRead` activated. Expected red: `PinRepository.kt:195` and
   `BoardRepository.kt:50`. Two lines that must **not** be reported, and they are the point of the
   rule: `SystemClock.kt:20`, exempt because the class implements the `Clock` port, and
   `SystemClock.kt:14`, a KDoc sentence the Konsist test being deleted here would have flagged had
   `api-system` been in its scope.

**Implementation**

- `api-domain`: `PinRepositoryInterface.softDeletePin(pin, at)` / `restorePin(pin, at)`,
  `BoardRepositoryInterface.softDeleteBoard(board, at)` / `restoreBoard(board, at)`, KDocs updated.
- `api-usecases`: `PinRecycleBin` and `BoardRecycleBin` take `Clock` (constructor injection;
  `SystemClock` is `@ApplicationScoped` and implements the port, so no producer is needed).
- `api-persistence-sqlite`: the four transitions write `softDeletedAt` **and** `updatedAt` from the
  parameter. No clock left in the module. The four `findOne()!!` on those lines
  (`PinRepository.kt:194,201`, `BoardRepository.kt:49,56`) are rewritten but keep their `!!`, which
  `agents/modules/kotlin.md` forbids: a pre-existing violation on a line this task happens to touch
  is inventoried, not swept, so it goes to `docs/backlog.md` at wrap rather than growing this task an
  absent-row path nobody specified.
- `api-application`: delete `ArchitectureKonsistTest`'s wall-clock test (lines 82-97), replaced by
  rule 3, which covers more modules and cannot confuse a KDoc sentence with a call.
- `agents/project.md`: the two residues named under "Design invariants" are both closed here; the
  sentence goes. Same commit.

**Acceptance**: `./gradlew gate` green; `git grep -n "Instant.now(" -- '*/src/main/*'` returns
`SystemClock` only.

### T3: the `queries` package, for pins and boards

Introduces the marker interface, the query constructors and the two association extensions, and
routes `PinRepository` and `BoardRepository` through them. Behaviour-preserving: the existing
repository and integration tests are the safety net, which is the refactor exemption on TDD order.
The new code is not exempt and is tested first.

**Red, three commits**

1. `SoftDeletableQueriesTest` (`api-persistence-sqlite`, extends `RepositoryTest`): `active()`
   excludes a recycled row, `recycled()` returns only recycled rows, `any()` returns both, against
   one concrete type. Then one test per type asserting each object's accessor reaches its own
   `softDeletedAt`, which is the only thing a per-type declaration can get wrong. Then
   `withActiveBoard()` and `withActivePin()` against a recycled association.
2. `config/detekt/detekt.yml`: `SoftDeleteStateFilteredOutsideQueries` activated. Expected red: the
   sites listed in spec 4.4 plus the root-level filters in both repositories.
3. `ArchitectureKonsistTest`: the two declaration assertions. Assertion 1 is scoped to
   `api-persistence-sqlite`'s `..models..` classes, since the domain entities `Pin` and `Board`
   declare `softDeletedAt` too and must not be dragged in. Assertion 2 exempts `queries/` and
   `pagination/` (see the correction above). Both red on the current sources.

**Implementation**

- `models/SoftDeletableModel.kt`; `PinModel` and `BoardModel` implement it (`override var`).
- `queries/SoftDeletableQueries.kt`, `queries/PinQueries.kt`, `queries/BoardQueries.kt`,
  `queries/PinBoardQueries.kt` (the two extensions).
- `PinQueries` holds the pin pagination helper, so `PinRepository` stops naming `QPinModel`.
- `PinRepository` and `BoardRepository`: every construction becomes `active()`, `recycled()` or
  `any()`; the three join-rooted filters become `withActiveBoard()` / `withActivePin()`. Each `any()`
  is the faithful translation of a query that filters nothing today, notably the three cursor pivot
  lookups, which must keep finding a pivot whatever its state.
- `agents/project.md`: a new design invariant, "a query rooted on a recyclable model is built by its
  `Queries` object". Same commit.

**Acceptance**: `./gradlew gate` green, the two new Konsist assertions being the criterion that can
actually fail. A grep for `QPinModel()` is **not** one: the constructors take a constructor reference
(`::QPinModel`) and `PinQueries`' pagination helper names the type as an argument, so the literal
disappears from the module whatever the implementation did. Expected output: empty.

### T4: users, from `@SoftDelete` to `softDeletedAt`

The security-bearing task: it changes what every user-rooted query returns, the seven inside
`UserRepository` and the three outside it, and it replaces an automatic guard on the authentication
path with an explicit one.

**Red, three commits**

1. Repository and use-case tests: `markPendingDeletion(user, at)` stamps the instant it was given;
   `findTombstonedUsersSoftDeletedBefore` returns stale tombstones and neither active users nor fresh
   ones; `findUserById` and `findUserByName` exclude a tombstoned user while their
   `IncludingDeleted` siblings return it; `AccountDeleter` hands the clock's instant to the
   repository; `ReapTombstonedAccounts` calls the renamed method. The retention test loses its
   `backDateWhenModified` raw-SQL helper: the instant is now a parameter, so back-dating is passing
   an older value.
2. **One repository test per converted site**, and this is the task's load-bearing red. The three
   conversions the specification calls the security-bearing part of the change
   (`SessionTokenRepository.kt:22`, `UserDataExportRepository.kt:38`,
   `UserPasswordHashRepository.kt:25`) are covered by nothing today: each looks a user up and throws
   `UserModelDoesNotExistError` when the lookup comes back empty, and an implementer who wrote
   `UserQueries.any()` at all three would leave the whole gate green. So: tombstone a user, then call
   `saveSessionToken`, `save` (export) and `saveUserPasswordHash`, and expect
   `UserModelDoesNotExistError` from each. These fail before the conversion for the right reason, the
   method not existing yet in its new shape, and they are the only thing standing between `active()`
   and `any()` afterwards.
3. `MeDeleteIntegrationTest:15-25` is **not** modified and must stay green throughout: after
   deletion, the token is rejected and `POST /api/v1/sessions` returns 401. It is run and shown green
   before and after, but it is **not** a red and it is **not** evidence for the three conversions
   above: its 401 on `/api/v1/me` comes from `sessionRevoker.revokeAll` and its 401 on `/sessions`
   from `findUserByName`, neither of which touches them.

**Implementation**

- `api-domain`: `User.softDeleted: Boolean` becomes `softDeletedAt: Instant?`;
  `markPendingDeletion(user, at)`; `findTombstonedUsersModifiedBefore` becomes
  `findTombstonedUsersSoftDeletedBefore`, KDoc rewritten to describe the fact rather than the column.
- `api-usecases`: `AccountDeleter` takes `Clock`; `ReapTombstonedAccounts` follows the rename.
- `api-persistence-sqlite`:
  - `UserModel` drops `@SoftDelete deleted`, gains `override var softDeletedAt`, implements the
    marker. Its `whenModified` KDoc claims the column "records when the row was tombstoned", which
    stops being true here: corrected to say the column is now dead and is dropped by block 2.
  - `UserModelMapper` maps `softDeletedAt` **both ways**. Today `toModel` deliberately omits
    `deleted` because only `database.delete` could set it; with one representation, omitting it would
    make `saveUser` silently resurrect a tombstoned account.
  - `queries/UserQueries.kt`, with `tombstonedBefore(cutoff)`.
  - `UserRepository` rewritten, one constructor per method rather than left to judgement:
    `findUserById` and `findUserByName` become `active()`; their two `IncludingDeleted` siblings and
    `permanentlyDeleteUser` become `any()`; `markPendingDeletion` becomes **`active()`**, which keeps
    today's behaviour that a second deletion request on an already tombstoned account finds nothing
    and returns. `any()` there would re-stamp `softDeletedAt` and push the retention cutoff forward
    on every repeat, which is the failure mode this whole block exists to remove.
    `markPendingDeletion` writes the model instead of calling `database.delete`;
    `permanentlyDeleteUser` uses a plain `delete`.
  - `SessionTokenRepository.kt:22`, `UserDataExportRepository.kt:38`,
    `UserPasswordHashRepository.kt:25`: `UserQueries.active()`. These three are the sites that were
    filtered by the automatic predicate and now say so, and red 2 above is what holds them there.
- Migration, **two pairs and two runs of the generator**.
  `./gradlew :api-persistence-sqlite:generateDbMigration` produces `1.13.sql` and
  `model/1.13.model.xml`, adding `users.soft_deleted_at`. The drop of `users.deleted` is **not** in
  that pair: Ebean puts a destructive change in a `pendingDrops` change set rather than in the apply
  output (ebean.io/docs/setup/dbmigration, "Pending Drops"; consulted 2026-07-29), so `1.13.model.xml`
  records it as pending and the drop is asked for by a second run with the system property
  `ddl.migration.pendingDropsFor=1.13`, which writes `1.14__dropsFor_1.13.sql` and its model. Both
  pairs are committed together. **Reading the generated SQL is a task step with a named thing to look
  for, not a formality**, and the thing to look for is a table rebuild: a rebuild recreates `users`
  from the **model**, and `ix_users_name_nocase` is not in the model, `1.2.sql` being hand-written
  with no `1.2.model.xml`, so it would silently drop case-insensitive username uniqueness. Neither
  file is one: both are plain `alter table` statements the store applies in place, so nothing is
  completed by hand and the precedent at `dbmigration/1.4.sql:17-21` (a "not supported"
  placeholder for a constraint change, completed by a hand-written rebuild) does not repeat. The check
  that would prove it wrong exists and runs against a migrated database: `UserRepositoryTest`, "saving
  two users whose names differ only by case is rejected". No backfill: nothing is deployed.

**One behaviour change no guard can see.** Ebean roots a query on `users` whenever a `UserModel`
association is dereferenced, and those sites import no query bean and write no predicate, so neither
the Konsist assertion nor detekt rule 2 covers them: `SessionTokenModelMapper.kt:11`
(`user.toDomain()`), and `AuthoredBaseModel.author` through `PinModelMapper.kt:31`,
`BoardModelMapper.kt:23` and `TagModelMapper.kt:20`. The authentication one is the one that matters:
`SessionTokenAuthenticator.kt:16` resolves a token to a session whose `user` becomes the security
identity (`BearerTokenIdentityProvider.kt:30-32`). Today the automatic predicate makes that load fail
for a tombstoned account; afterwards it succeeds. What keeps the path closed is that no token
survives a tombstone: `AccountDeleter.kt:21-22` marks the user and revokes every session in the same
transaction, asserted at `AccountDeleterTest.kt:37-38`. That is the guard, it is revocation and not
filtering, and this paragraph is where it is written down instead of being assumed.

**Acceptance**: `./gradlew gate` green;
`git grep -n "@SoftDelete\|setIncludeSoftDeletes\|deletePermanent" -- api-persistence-sqlite/src/main`
returns nothing (the unanchored `SoftDelete` of an earlier draft also matched the domain method names
`findAllSoftDeletedPinsForUser` and its two siblings, which this task does not touch, so it would
have failed against a correct implementation); the Konsist import assertion now covers
`QUserModel` by construction, because `UserModel` declared itself recyclable.

## Verify

`./gradlew gate` in full, then a holistic review by a fresh subagent over the whole branch diff with
mandate `agents/reviews/holistic.md`. Two zones the diff cannot show on its own: what a query
constructor returns for a state nobody asked about, and whether the three rules would still fire if
someone reverted a single call site.

## Wrap

The handoff records what is **not** validated by the gate: both migrations have been generated and
read but never applied to a database holding tombstoned rows, since none exists; and the detekt rules are
proven against this repository's sources at one point in time, not against `2.0.0-alpha.6`. The
backlog loses block 1 from its P0 entry and keeps blocks 2 and 3.
