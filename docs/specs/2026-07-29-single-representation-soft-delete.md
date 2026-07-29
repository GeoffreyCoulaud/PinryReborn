# Single-representation soft delete (block 1, replacing the generalisation of `@SoftDelete`)

Date: 2026-07-29
Status: Approved 2026-07-29
Branch: `refactor/uniform-soft-delete`
ADR: `docs/adr/0007-single-representation-soft-delete.md`
Replaces: section 5 of `docs/specs/2026-07-29-domain-owned-timestamps.md`. That document's sections 6
and 7 (blocks 2 and 3) stay in force, as do its decisions D1, D2, D3, D5, D6, D7, D12. Its D4 is
reversed here.

## 1. Goal

Deliver block 1 of the domain-owned timestamps work, with one representation of recycling instead of
two and no implicit filtering. The goal of block 1 is unchanged: business instants are stamped by use
cases, `softDeletedAt` reaches the domain for users, and account retention measures time since
tombstoning instead of reading a column Ebean rewrites. What changes is the mechanism, for the reasons
in the ADR: Ebean's automatic predicate applies under conditions that are not readable at the call
site, and it required two excursions into the ORM's sources to establish, one of which contradicts the
official documentation.

## 2. Scope

**In scope:**

- `@SoftDelete` is removed from `UserModel`, and the `users.deleted` column is dropped. `PinModel` and
  `BoardModel` never receive it.
- `User.softDeleted: Boolean` becomes `User.softDeletedAt: Instant?`.
- A `queries` package with `PinQueries`, `BoardQueries` and `UserQueries`, each exposing `active()`,
  `recycled()` and `any()`. Every query rooted on one of those three types goes through them.
- The five soft-delete transitions take their instant as a parameter, and `updatedAt` is bumped on the
  four pin and board transitions (D2 of the previous specification).
- Account and task retention: only the account half here, reading `softDeletedAt`.
- The `SoftDeletableModel` marker interface, a `detekt-rules` Gradle module with three custom rules,
  and two Konsist assertions that derive their reach from the marker (section 4.6). The existing
  wall-clock assertion
  (`ArchitectureKonsistTest:82-97`) is replaced by one of them and deleted: this block has to touch it
  regardless, since D1 extends the prohibition to the persistence adapter, and leaving a text-matching
  twin beside an AST rule for the same invariant is the dual mechanism this whole specification exists
  to remove.
- Two migrations: the one that adds the column, and the one that carries the drop, which Ebean does
  not put in the same file (section 4.5).

**Out of scope:**

- Blocks 2 and 3 of the previous specification (`AuditedBaseModel`, current-password determinism).
- The `merge` collision recorded as fact 4 in the ADR: without `@SoftDelete` no query hides a row from
  `merge`, so the failure mode disappears for pins, boards and users. Nothing to do, nothing to file.
- Query constructors for types that are not recyclable (tags, tasks, session tokens, exports, images).
- Any backfill: nothing is deployed.
- Konsist's layering and import assertions, which are not affected: architecture over declarations is
  what Konsist is genuinely good at. Only the wall-clock assertion moves, and it moves because this
  block rewrites it anyway (section 4.6).

## 3. Decisions (invariants)

- **D1 (kept).** No clock is read in `api-persistence-sqlite`. Business instants arrive as parameters.
- **D2 (kept).** `updatedAt` means any modification, including recycling and restoration.
- **D3 (kept).** Soft-delete transitions take the instant and do not route through `savePin` /
  `saveBoard`.
- **D4 (replaced).** One representation: `softDeletedAt`. No boolean column, no derivation, nothing to
  keep in step.
- **D5 (kept).** `User.softDeleted: Boolean` becomes `User.softDeletedAt: Instant?`.
- **D13.** Filtering is explicit and expressed once per intention. A query rooted on a recyclable type
  is built by `active()`, `recycled()` or `any()`, never by constructing the query bean directly.
- **D14.** The invariant is enforced by a test, and the test reasons about code rather than text. The
  prohibition concerns statements, which is not what Konsist sees: it reasons about declarations and
  file content, so it can match a spelling but not a shape. The rules are therefore detekt rules
  visiting the syntax tree, in a new `detekt-rules` module. This is infrastructure the project does not
  have yet, and it is paid for here because the alternative is an assertion narrower than the rule it
  claims to enforce.
- **D15.** A query rooted on a join table that filters on a recyclable **association** goes through an
  extension function declared beside the query constructors, `withActiveBoard()` / `withActivePin()`.
  Query constructors cannot cover those sites, since their root is not a recyclable type, but an
  extension can, and it puts the predicate in the same file as the rest. Extension functions are the
  project's one exemption from the no-top-level-functions convention.
- **D16.** No state predicate on `softDeletedAt` exists outside `queries/`, and the rule recognises a
  predicate by its shape, not by its spelling: a navigation that continues past `softDeletedAt` into a
  call. Writing the property, passing it as an argument and ordering by it (`PinModelSortStrategy`) are
  different acts and stay where they are; `asc()` and `desc()` are ordering, not filtering, and the
  rule says so explicitly rather than by omission. With D13 and D15 there is nothing left to enumerate:
  every filtering decision is in one package, and the rule holds whatever predicate someone reaches
  for.
- **D17. Being recyclable is declared in the code, not in a rule.** A model whose rows are recycled
  implements the `SoftDeletableModel` marker interface, and every structural rule derives its reach
  from that interface instead of naming types. Without it, the rules would carry a hard-coded list of
  three names, and a fourth recyclable type would arrive with nothing to remind anyone that it needs
  protecting: the guard and the code could drift apart silently, which is the failure this whole
  specification is about. **No list of type names appears anywhere**, neither in a rule nor in
  `detekt.yml`.

## 4. Design

### 4.1 Domain

- `User`: `softDeleted: Boolean = false` becomes `softDeletedAt: Instant? = null`.
- `UserRepositoryInterface.markPendingDeletion(user, at: Instant)`.
- `UserRepositoryInterface.findTombstonedUsersModifiedBefore(cutoff)` is renamed
  `findTombstonedUsersSoftDeletedBefore(cutoff)`: the old name describes a column, the new one a fact.
- `PinRepositoryInterface`: `softDeletePin(pin, at: Instant)`, `restorePin(pin, at: Instant)`.
- `BoardRepositoryInterface`: `softDeleteBoard(board, at: Instant)`, `restoreBoard(board, at: Instant)`.

The interface already names its intentions (`findActiveBoardById` beside `findBoardById`,
`findAllSoftDeletedPinsForUser`), so the domain contract does not change shape. What changes is that
the adapter now has one obvious way to honour each name.

### 4.2 Declaring what is recyclable

```kotlin
// models/SoftDeletableModel.kt
/**
 * A model whose rows are recycled rather than removed.
 *
 * Implementing this is the declaration that queries on the type go through its `Queries` object:
 * the structural rules read this interface to know what they guard, so a new recyclable model is
 * covered by declaring itself, with no rule to update.
 */
interface SoftDeletableModel {
    var softDeletedAt: Instant?
}
```

`PinModel`, `BoardModel` and `UserModel` implement it; they already declare the property, so the change
is an `override`. It is a plain Kotlin interface, not a `@MappedSuperclass`, so it carries no column and
no mapping.

### 4.3 Query constructors

The three questions are the same three questions for every recyclable type, so they are written once.
Ebean's generated query beans share a supertype, `io.ebean.typequery.QueryBean<M, R>`, which the
project already exploits for pagination (`ModelPaginationHelper.getPage<M : BaseModel, Q : QueryBean<M, Q>>`),
and a column is reached generically by passing an accessor, exactly as
`PinModelSortStrategy.filterDownFrom(query, column: (QPinModel) -> PInstant<QPinModel>, …)` already
does. Nothing here needs a property name as a string.

```kotlin
// queries/SoftDeletableQueries.kt
abstract class SoftDeletableQueries<M : SoftDeletableModel, Q : QueryBean<M, Q>>(
    private val newQuery: () -> Q,
    private val softDeletedAt: (Q) -> PInstant<Q>,
) {
    /** Rows that are not in the recycle bin. */
    fun active(): Q = softDeletedAt(newQuery()).isNull

    /** Rows in the recycle bin. */
    fun recycled(): Q = softDeletedAt(newQuery()).isNotNull

    /** Every row, whatever its state. The caller states that it means it. */
    fun any(): Q = newQuery()
}
```

```kotlin
// queries/PinQueries.kt and its two siblings
object PinQueries : SoftDeletableQueries<PinModel, QPinModel>(::QPinModel, { it.softDeletedAt })

object UserQueries : SoftDeletableQueries<UserModel, QUserModel>(::QUserModel, { it.softDeletedAt }) {
    /** Accounts tombstoned before [cutoff], for the retention sweep. */
    fun tombstonedBefore(cutoff: Instant): QUserModel = recycled().softDeletedAt.lessThan(cutoff)
}
```

The `M : SoftDeletableModel` bound is what ties the two halves together: a type that has not declared
itself recyclable cannot be given these constructors, and one that has gets all three in a single line.
The filtering logic exists once, is tested once, and the per-type declarations carry no logic to get
wrong. `tombstonedBefore` stays on `UserQueries` because retention asks a question of its own, and it
belongs in this package for the same reason as the rest: the package owns every predicate on that
property, so `UserRepository` asks instead of building.

One generic class and three declarations replace 38 query-bean constructions spread across six files.

The pin pagination helper, today the private field `ModelPaginationHelper<PinModel, QPinModel>()` of
`PinRepository` (`PinRepository.kt:46`), is the one remaining place outside the pagination machinery
where the repository has to **name** `QPinModel` rather than build a query with it. It is stateless
and both its types are derivable from its arguments, so it becomes an object with one generic
method: nobody names the type, `PinRepository` drops the import, which is what assertion 2 below
asserts, and `UserDataExportRepository` loses the same field for its own query bean.

Beside them, the association filters of D15, as extensions on the join query bean:

```kotlin
// queries/PinBoardQueries.kt
fun QPinBoardModel.withActiveBoard(): QPinBoardModel = board.softDeletedAt.isNull

fun QPinBoardModel.withActivePin(): QPinBoardModel = pin.softDeletedAt.isNull
```

The three call sites then read `QPinBoardModel().pin.id.equalTo(pinId).withActiveBoard()`, where the
intention is in the name and the predicate is in one place. These two are not folded into the generic
class: they navigate a different association each, on a type that is not itself recyclable, so a
generic version would take the accessor as a parameter and read worse than the two lines it replaced.
`QPinBoardModel` stays freely constructible, since it is not a recyclable type and D14's import
assertion does not cover it; D16 is what covers these sites.

### 4.4 Adapters

Every site of `PinRepository`, `BoardRepository` and `UserRepository` moves to a constructor. The three
sites whose root is a join table move to an extension (D15):

| Site | Today | After |
|---|---|---|
| `PinRepository.getBoardsForPin:62` | `.board.softDeletedAt.isNull` | `.withActiveBoard()` |
| `PinRepository.savePinBoards:126` | `.board.softDeletedAt.isNull` | `.withActiveBoard()` |
| `BoardRepository.countActivePinsInBoard:83` | `.pin.softDeletedAt.isNull` | `.withActivePin()` |

Four sites outside `UserRepository` read users and are filtered automatically today; they become
explicit, and this is the security-bearing part of the change:

| Site | Today | After |
|---|---|---|
| `SessionTokenRepository.kt:22` | `QUserModel()`, filtered by the automatic predicate | `UserQueries.active()`. This is what stops a tombstoned account obtaining a session |
| `UserDataExportRepository.kt:38` | same | `UserQueries.active()` |
| `UserPasswordHashRepository.kt:25` | same | `UserQueries.active()` |
| `UserRepository.findUserByNameIncludingDeleted:41`, `findUserByIdIncludingDeleted:49` | `setIncludeSoftDeletes()` | `UserQueries.any()` |

`markPendingDeletion` stops calling `database.delete(model)` and writes `softDeletedAt` from the
instant it receives. `permanentlyDeleteUser` stops needing `deletePermanent`. Every
`setIncludeSoftDeletes()` in the module disappears.

### 4.5 Migration

**Two migrations, because Ebean splits the change.** A destructive change does not go in the apply
output: the generator puts it in a `pendingDrops` change set instead, and pending drops have to be
selected explicitly (ebean.io/docs/setup/dbmigration, "Pending Drops"; consulted 2026-07-29). So one
run of the generator produces the add of `users.soft_deleted_at` and, in the same model file, a
`pendingDrops` change set recording the drop of `users.deleted`. The drop itself is asked for by a
second run, with the system property `ddl.migration.pendingDropsFor` naming the version that recorded
it, which writes a `<next>__dropsFor_<version>` pair of its own. Both pairs are committed together, so
the history stays fully modelled.

**Neither is a table rebuild**, and that is what the generated SQL is read for. The generator emits a
plain `alter table … drop column`, which the SQLite this project runs on applies in place, so there is
no hand completion here and the precedent at `dbmigration/1.4.sql:17-21` (the generator leaving a
"not supported" placeholder for a constraint change, completed by a hand-written rebuild) does not
repeat. It stays the thing to look for on the next drop: a rebuild recreates the table from the
**model**, and `ix_users_name_nocase` is not in the model, `1.2.sql` being hand-written with no
`1.2.model.xml`, so a rebuild of `users` would drop case-insensitive username uniqueness with nothing
saying so. The check that would catch it exists and runs against a migrated database:
`UserRepositoryTest`, "saving two users whose names differ only by case is rejected".

No backfill (D12 of the previous specification): `soft_deleted_at` is written by the use case from now
on, and nothing is deployed.

### 4.6 Structural enforcement

A new Gradle module, `detekt-rules`, declared in `settings.gradle.kts`. It depends on
`dev.detekt:detekt-api` as `compileOnly` and on `dev.detekt:detekt-test` for its own tests, declares a
`RuleSetProvider` registered through `resources/META-INF/services/dev.detekt.api.RuleSetProvider`, and
is consumed by the modules it applies to through `detektPlugins(project(":detekt-rules"))`. The rule
set is activated in `config/detekt/detekt.yml`. It depends on no project module and no module depends
on it, so the layering is untouched.

**Two tools, split by what each one can see.** A detekt rule reads one file's syntax tree, which is what
a prohibition about statements needs; Konsist sees every declaration in the project at once, which is
what deriving a set of types from a marker interface needs. Neither can do the other's half, and
between them no rule names a type.

**Konsist, over declarations** (in `ArchitectureKonsistTest`, where the project's other structural
assertions live), both deriving their reach from `SoftDeletableModel` (D17):

1. Every model declaring a `softDeletedAt` property implements `SoftDeletableModel`. Without this one
   the marker is optional, and opting out would be the way around everything below.
2. No production file outside `queries/` and `pagination/` imports the query bean of a
   `SoftDeletableModel`. The names are computed from the implementations (`PinModel` gives
   `QPinModel`, the generator's own convention), so a newly declared recyclable model is covered the
   moment it declares itself.

   The `pagination` exemption is a fact about the code, not a concession: `PinModelSortStrategy` is
   `ModelSortStrategy<PinModel, QPinModel>`, so it names the type in its supertype and in every
   signature, and it **never constructs one**. It receives a query someone else rooted and refines
   its keyset predicate and its ordering. Making it satisfy the assertion would mean moving it into
   `queries/`, which is moving something to escape a constraint, and `AGENTS.md` forbids exactly
   that. The exemption is a package, not a type name, so D17 stands; and it leaves no gap on the
   state predicate, since detekt rule 2 below covers `pagination` like everywhere else. What it does
   leave is a place where a query bean could be constructed with no state predicate at all: one
   generic file, whose whole reason to exist is that it does not decide what a query returns.

There is deliberately **no assertion that a `Queries` object exists**. It would be a test enforcing the
presence of a file, which is a weaker thing than a prohibition. What assertion 2 buys is narrower than
"a recyclable model without its constructors cannot be queried": it bars the import outside `queries/`
and `pagination/`, which makes writing the constructors the ordinary way to root such a query, not the
only one, and nothing fails to compile without them. Three routes stay open, none of them named by a
guard. `ModelRepository` extends `io.ebean.BeanRepository`, which extends `BeanFinder`, whose public
`findAll()`, `findById(id)`, `findByIdOrEmpty(id)` and `db()` read the entity class or hand out the
`Database` without naming a query bean or writing a state predicate
(`ebean-api/src/main/java/io/ebean/BeanFinder.java:87-116` at tag `v19.0.0`, the nearest tag to the
pinned 19.2.0). `raw("soft_deleted_at is null")` on `any()` writes the predicate in SQL
(`ebean-querybean/src/main/java/io/ebean/typequery/QueryBean.java:507` at the same tag), where the
detekt rule reads Kotlin. And reading the instant in memory after `any()` filters without a predicate
either tool can see. The first route is the one this codebase actually exposes, and it is recorded in
`docs/backlog.md`.

**detekt, over statements**, three rules, each reporting a forbidden shape and none carrying data:

1. **`QueryBeanConstructedByQualifiedName`** (D14). Reports any construction of a `Q…Model` written as
   a fully-qualified name, in any module. This is the one form the import assertion above cannot see,
   and it is an oddity for every type, recyclable or not, so the rule needs no list to be right.
2. **`SoftDeleteStateFilteredOutsideQueries`** (D15, D16). Reports a navigation that continues past
   `softDeletedAt` into a call, in a file outside `queries/`, excluding `asc()` and `desc()`, which are
   ordering. It keys on the property name, which is the marker interface's single member, so it covers
   a new recyclable type the day it declares itself.
3. **`WallClockRead`** (D1). Reports `Instant.now()`, `LocalDate.now()`, `LocalDateTime.now()` and
   `System.currentTimeMillis()` **in eleven of the twelve modules**, except inside a class that
   implements the `Clock` port. The twelfth is `detekt-rules` itself, which cannot carry itself as a
   detekt plugin (`build.gradle.kts:102-104`) because a rule set is not on its own analysis
   classpath: the module that declares the rule is the one whose production sources it never sees,
   and that is a fact about the tool, not a perimeter decision, since the module is inside the gate
   perimeter like any other. The exception is structural, not a list: the port's implementation is
   the one place a wall clock may legitimately be read, and there is exactly one
   (`api-system/.../SystemClock.kt:20`, the only production occurrence besides the two this block
   deletes). This replaces `ArchitectureKonsistTest:82-97`, which covered two modules by matching file
   text, and which would have flagged `SystemClock.kt:14` had `api-system` been in its scope, since a
   sentence in a KDoc contains the same characters as a call. An AST rule does not confuse a comment
   with an expression.

**Type resolution is not needed**, verified rule by rule rather than assumed:

- Rule 1 matches a call whose callee is a dotted name ending in an identifier of the generated query
  bean shape. The shape is the whole condition, so there is nothing to resolve.
- Rule 2 matches a navigation ending in `softDeletedAt` followed by a call, scoped to
  `api-persistence-sqlite`. The other uses of the property in that module take different shapes, none
  of which is a call on the navigation: assignment (8), named constructor argument (6), declaration
  (2), and `asc()` / `desc()` (2), which the rule excludes by name because ordering is not filtering.
- Rule 3 matches a call on the `Instant`, `LocalDate`, `LocalDateTime` and `System` names. No project
  type shadows any of them.

The build declares no `classpath` for detekt today (`build.gradle.kts:88-104` configures the extension
and `jvmTarget` only), so enabling type resolution would mean analysing against compiled output on
eleven modules, for precision none of the three rules needs. The tests use `lint()` rather than
`compileAndLintWithContext`. If a future rule needs types, the switch is a build change and a test-base
change, not a rewrite of these rules.

**Scope of analysis.** The rules target production sources. detekt analyses test sources too, and tests
legitimately read the wall clock (`RepositoryTest.storableNow()`) and construct query beans, so
`config/detekt/detekt.yml` excludes test and test-fixture paths for this rule set. That exclusion is
part of the rule set's configuration and is stated there, not discovered later from a red build. The
Konsist assertions use `scopeFromProduction`, which never sees test sources.

**What it takes to add a fourth recyclable model, afterwards**: implement `SoftDeletableModel`. The
build then refuses it until a `Queries` object exists, and its query bean is barred from every other
file. No rule, no configuration and no assertion is edited, which is the property this section exists
to provide.

**Gate perimeter.** `detekt-rules` is a module like any other: inside the perimeter, 100% branch
coverage per package, no "just tooling" exemption. Detekt rules are unusually easy to cover, since
`detekt-test`'s `lint()` takes a code snippet and returns the findings. `agents/project.md` declares
the module and its perimeter in the same commit that creates it.

## 5. Testing strategy

Strict TDD, red before green, the failing test committed alone with the command and its output in the
message body. Project order: integration tests in `api-application`, then use-case tests in
`api-usecases`, then repository tests in `api-persistence-sqlite`.

1. **The filtering logic is tested once, where it lives**: `active()` excludes a recycled row,
   `recycled()` returns only recycled rows, `any()` returns both. Those three tests run against one
   concrete type, since the other two differ only by the accessor they pass. A fourth test then asserts
   that each of the three objects passes an accessor that reaches its own `softDeletedAt`, which is the
   only thing a per-type declaration can get wrong. Plus `tombstonedBefore()` on each side of its
   cutoff, and `withActiveBoard()` / `withActivePin()` against a recycled association.
2. **The authentication guard is asserted end to end**: a tombstoned account cannot obtain a session.
   An integration test covers this today and must stay green through the change, since the mechanism
   behind it is being replaced.
3. Recycle-bin integration and repository tests are the safety net for the adapter rewrite: green
   before and after, with no assertion edited.
4. Use-case tests assert the instant handed to the repository comes from the injected `Clock`, and that
   `updatedAt` moves on recycling and restoration (D2, a new assertion).
5. Retention: a repository test back-dates `soft_deleted_at` and asserts the cutoff behaviour through
   `findTombstonedUsersSoftDeletedBefore`, replacing the `backDateWhenModified` helper in
   `UserRepositoryTest`.
6. **Each detekt rule is unit-tested on code snippets** with `detekt-test`: a violating snippet reports
   one finding and names the node it reports, and a compliant one reports none. That is the rule's own
   red-green cycle, and it is what the 100% bound is measured on. The `queries/` exemption is **not**
   among those tests and cannot be: `lint()` visits a snippet, and path filtering belongs to the
   engine, so the exemption is configuration and is measured by running detekt over the real tree.
7. The three rules then fail red against the real sources before the adapter satisfies them, which is
   the second red and the one that proves they are wired into the gate rather than merely written.
   Only two of them can: no production source constructs a query bean by qualified name, so
   `QueryBeanConstructedByQualifiedName` has no red to earn, and detekt fails the build for neither a
   missing registration nor a misspelled key in a custom rule set. What holds it is a test comparing
   the configured names to the registered ones.

## 6. Risks and accepted trade-offs

- **The authentication guard changes mechanism.** It moves from an automatic predicate to an explicit
  `UserQueries.active()` at three named sites. Mitigated by the end-to-end test above, which is the
  reason it is listed second in the testing order rather than last.
- **detekt is pinned at `2.0.0-alpha.5`.** Custom-rule APIs are exactly what an alpha is allowed to
  change between builds, and this block makes the project depend on that surface for the first time. The
  exposure is small (three rules, one provider) and the alternative was an assertion narrower than its
  rule, but a detekt bump now carries a compile risk it did not carry before. The rules' own unit tests
  are what turns that risk into a build failure rather than a silent gap.
- **A rule that reports a shape can still be evaded**, by reaching the predicate through an
  intermediate variable or a raw SQL fragment. The AST form closes the spelling variants, not
  determination. What it guarantees is that the ordinary way of writing the query is caught.
- **The import assertion has two blind spots**: a file placed inside the `models.query` package would
  need no import (that package holds generated artefacts and is outside the gate perimeter, so a
  hand-written file there is already an anomaly), and the `pagination` package is exempt for the
  reason given in section 4.6. Both are places where a query bean could be constructed without a
  state predicate; neither can carry one, since detekt rule 2 covers the whole module.
- **Removing `@SoftDelete` from `UserModel` is a behaviour change on every query rooted on users.**
  Four sites outside `UserRepository` are affected and are listed in section 4.4; the risk is a site
  missed, which the import assertion catches by construction.
- **Two more migrations on an append-only history** (section 4.5), already slated for flattening at
  beta.
- **Work already committed on this branch is discarded**: three revisions of a plan built on the
  previous mechanism. Nothing in production was written, which is precisely why the reversal is taken
  now rather than after block 1 ships.
