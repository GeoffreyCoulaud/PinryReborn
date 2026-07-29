# Handoff: single-representation soft delete (block 1 of domain-owned timestamps)

Branch: `refactor/uniform-soft-delete` (cut from `main` on 2026-07-29; the branch name predates the
reversal recorded in ADR 0007 and was kept rather than renamed mid-branch).
Tier: Plan (four tasks). Spec: `docs/specs/2026-07-29-single-representation-soft-delete.md`,
ADR: `docs/adr/0007-single-representation-soft-delete.md`, plan:
`docs/plans/2026-07-29-single-representation-soft-delete.md`.

## Current state

Ebean's `@SoftDelete` is gone from the sources, its last mention being a comment in the migration
that drops the column it maintained. `softDeletedAt: Instant?` is the single
representation of recycling, in the domain and in the store, and no query is filtered implicitly any
more: every query rooted on a recyclable model says which state it means, through `active()`,
`recycled()` or `any()` on that model's `Queries` object. The five soft-delete transitions take their
instant as a parameter, stamped by the use case from the `Clock` port, and the four pin and board
transitions bump `updatedAt` as any modification does. Account retention now measures time since
tombstoning instead of reading a column Ebean rewrites on every write to the row.

The two residues that "Domain-owned timestamps" left open under **Design invariants** in
`agents/project.md` are both closed, and the sentence recording them is deleted. `./gradlew gate` is
green, 100% branch coverage per package holds, and the branch is ready to integrate through a rebased
pull request.

The block also gives the project its first custom static-analysis rules, in a new `detekt-rules`
Gradle module (twelve modules now, and `agents/project.md` declares it inside the gate perimeter like
any other).

## What was built

**One representation, and the queries that state it.**

- `models/SoftDeletableModel.kt`, a plain Kotlin interface declaring `var softDeletedAt: Instant?`.
  `PinModel`, `BoardModel` and `UserModel` implement it. It carries no mapping, no column and no
  annotation: implementing it is the declaration that this model's rows are recycled, and every
  structural rule derives its reach from it rather than from a list of type names.
- `queries/SoftDeletableQueries.kt`, one generic class bounded on the marker and on Ebean's
  `QueryBean<M, Q>`, taking the bean constructor and a column accessor. Its `active()`, `recycled()`
  and `any()` are the whole filtering logic, written once. `PinQueries`, `BoardQueries` and
  `UserQueries` are one line each; `UserQueries` adds `tombstonedBefore(cutoff)` for the retention
  sweep, because that predicate is on the same property and this package owns them all.
- `pagination/ModelPaginationHelper.kt` is now an object with one generic method, its types read off
  the call site's arguments, which is what lets `PinRepository` stop naming `QPinModel` at all and
  drops the same field from `UserDataExportRepository`.
- `queries/PinBoardQueries.kt`, the two association filters `withActiveBoard()` and `withActivePin()`
  as extensions on the join table's query bean. Their root is not a recyclable type, so a constructor
  cannot cover them; an extension can, and it keeps the predicate in the package that owns it.
- `repositories/ActiveUserModels.kt`: the account a new row is hung off, resolved in one place for
  the three repositories that need one. Rooted on `active()`, so it is what now refuses a session, an
  export or a credential to a tombstoned account, where the framework's automatic predicate used to.

**The adapter.** `PinRepository`, `BoardRepository` and `UserRepository` build every query through a
constructor. `markPendingDeletion` writes `softDeletedAt` instead of calling `database.delete`, and
stays on `active()` so a repeated deletion request cannot push the retention cutoff forward.
`permanentlyDeleteUser` is a plain delete again. `setIncludeSoftDeletes()` and `deletePermanent()`
have no remaining occurrence in the module. `SessionTokenRepository`, `UserDataExportRepository` and
`UserPasswordHashRepository` go through `ActiveUserModels`, and one repository test per site
tombstones a user and expects `UserModelDoesNotExistError`: those three tests are the only thing
standing between `active()` and `any()` at the sites that carry the authentication guarantee.

**The domain and the use cases.** `User.softDeleted: Boolean` becomes `softDeletedAt: Instant?`.
`markPendingDeletion(user, at)`, `softDeletePin/restorePin(pin, at)`,
`softDeleteBoard/restoreBoard(board, at)`. `findTombstonedUsersModifiedBefore` becomes
`findTombstonedUsersSoftDeletedBefore`: the old name described a column, the new one describes a
fact. `PinRecycleBin`, `BoardRecycleBin` and `AccountDeleter` take `Clock`.

**The rules.** `detekt-rules` declares a `RuleSetProvider` registered through the service loader and
three rules, none of which names a type and none of which needs type resolution:

- `QueryBeanConstructedByQualifiedName`: a query bean built through a fully qualified name, the one
  form the Konsist import assertion cannot see.
- `SoftDeleteStateFilteredOutsideQueries`: a navigation continuing past `softDeletedAt` into a call,
  outside the `queries` package. Its reach is set by path filters in `config/detekt/detekt.yml`,
  because the same property name is a legitimate domain value outside the persistence module.
  `asc()` and `desc()` are excluded by name: ordering is not filtering.
- `WallClockRead`: `Instant.now()`, `LocalDate.now()`, `LocalDateTime.now()` and
  `System.currentTimeMillis()` in every module but the one declaring the rules, except inside a class
  implementing the `Clock` port. It replaces the Konsist wall-clock assertion, which matched file
  text over two modules and would have flagged a sentence in `SystemClock`'s KDoc had `api-system`
  been in its scope.

Two Konsist assertions in `ArchitectureKonsistTest` complete them, both deriving their reach from the
marker interface: every persistence model carrying the recycling instant implements it, and no
production file outside `queries` and `pagination` imports a recyclable model's query bean.

**Migrations.** `1.13` adds `users.soft_deleted_at`; `1.14__dropsFor_1.13` drops `users.deleted`.
Both are plain `alter table` statements the store applies in place, neither is a table rebuild, and
there is no backfill. The consequence of not backfilling is written into
`1.14__dropsFor_1.13.sql` itself and is in the backlog.

## Pitfalls learned

- **Ebean never puts a destructive change in the apply output.** A dropped column or table goes to a
  `pendingDrops` change set in the model file the run produces, so a drop takes a **second** run of
  the generator and produces a second, separately numbered `__dropsFor_` pair. Worse, the property
  that asks for it has to reach the generator's own JVM: the Gradle task is a `JavaExec` and forwards
  nothing, so `-D` on the command line sets the property on the daemon and the run reports "no
  changes detected" whatever version is named. What works is
  `JAVA_TOOL_OPTIONS="-Dddl.migration.pendingDropsFor=<version>" ./gradlew :api-persistence-sqlite:generateDbMigration`.
  The command is now in `agents/project.md` under Commands, next to the ordinary generation one.
- **`dev.detekt:detekt-test:2.0.0-alpha.5` cannot resolve as published.** Its runtime variant
  requires `detekt-api` with a test-fixtures capability that version never published: Maven Central
  carries `detekt-api-2.0.0-alpha.5-test-fixtures-sources.jar` and no matching jar, so resolving
  `testRuntimeClasspath` fails outright. `detekt-rules/build.gradle.kts` excludes that edge and
  supplies `detekt-api` directly, which is enough because `lint()` only parses a snippet and visits
  it. The comment explaining it is in that file: expect the same wall on a detekt bump, and check the
  capability before blaming the build.
- **detekt does not validate the keys of a custom rule set.** A misspelt rule name, or a misspelt or
  forgotten `active` key, silently disables a rule while the build stays green. This was measured,
  not assumed: renaming a rule under `pinry-reborn` leaves the build successful, while the same typo
  under a built-in rule set fails it with "Run failed with 1 invalid config property". Two tests in
  `PinryRuleSetProviderTest` read the real `detekt.yml` and compare it to what the provider
  registers, one on the names and one on the activation, and they are the only thing standing between
  a typo and a rule that enforces nothing. The Gradle test task declares the configuration file as an
  input, without which editing it alone leaves the task up to date and the comparison unrun.
- **Konsist's production scope does not read `build/`.** The generated query beans import each other
  (`QPinBoardModel.kt` imports `QBoardModel` and `QPinModel`), which the import assertion would flag
  if it saw them. It does not, because `scopeFromProduction` reads source directories. Convenient
  here, and a limit to remember: a rule expressed in Konsist says nothing about generated output.
- **`detektMain` and `detektTest` are red, and are not in the gate.** They are detekt's
  type-resolution tasks; `check` depends on `detekt`, which is the one the gate runs.
  `./gradlew :api-usecases:detektMain` fails with five findings, all in files this branch never
  touched (`DownloadPinImage`, `PinCreator`, `SetPinImage`, `TokenHasher`, `TrigramSimilarity`), so
  the red predates the branch. Do not read it as damage from the new rule set, and do not switch
  those tasks on without budgeting the pre-existing findings first.
- **`core.hooksPath` is unset in this clone, so the pre-commit hook never ran on any commit of this
  branch.** The two things it would have done were done and checked by hand: `docs/openapi.json` was
  regenerated (`./scripts/generate-openapi.sh` exits 0 and leaves the tree clean, so the committed
  document is in sync and this block changed no wire contract), and the branch diff contains no em
  dash or en dash in any added line. Anyone continuing here should run
  `git config core.hooksPath .githooks` first rather than repeat the manual check.
- **`docs/specs/2026-07-29-domain-owned-timestamps.md` contradicts itself on a count that block 2
  needs.** It says "seven dead audit columns" twice, in its block summary and in section 6.5, while
  the model table in section 6.3 lists eight drops across six models. That document is frozen, so
  block 2 starts by re-deriving the count from the models rather than trusting either number. The
  backlog entry for block 2 says so too.
- **The specification and the plan were rewritten mid-branch**, after five researched facts about
  `@SoftDelete` turned out to make the mechanism indefensible rather than merely awkward. The reversal
  is ADR 0007 and it discarded three revisions of a plan. Nothing in production had been written,
  which is why it was cheap: the research phase is where that kind of finding is supposed to land.

## Not validated against real conditions

- **Neither migration has been applied to a database holding a row tombstoned under the old
  mechanism, and none exists.** Both were generated, read, and are exercised on every test run
  against an in-memory store built from the full history, but the interesting case (a row where
  `deleted = 1` before `1.13`) has no instance anywhere to try. The consequence is recorded in the
  migration file and in the backlog: such a row would come out of the pair active. The recovery
  statement is written down and has never been run.
- **The three detekt rules are proven against `2.0.0-alpha.5` and against this tree at one point in
  time.** Their unit tests pin what each one reports, and the two activation reds proved they are
  loaded by the gate rather than merely written, but custom-rule APIs are exactly what an alpha is
  allowed to change between builds. **A detekt bump is now a compile risk the project did not carry
  before**, and the `detekt-test` resolution defect above is a second reason to expect friction. The
  rules' own tests are what turns that risk into a build failure instead of a silent gap.
- **The local gate does not cover the multi-architecture container image build.** CI runs it behind
  the same `validate / gate` check and no local command covers it, which is unchanged from previous
  branches and is why integration goes through a pull request.
- **The guarantee is a build-time prohibition, not a compile-time impossibility.** A read that names
  no query bean and writes no predicate on the property is invisible to both tools: dereferencing a
  `UserModel` association is the live example, and the backlog holds three items about what that
  leaves open (the inherited Ebean finders, the one-insert session token window, and the export
  retention sweep aborting on a tombstoned owner).

## Suggested next step

- Integrate: push and open a pull request, merge with `gh pr merge --rebase` once the human review
  has come back. Squash is disabled on this repository.
- Then run Improve, from the input below.
- Then **block 2, the end of `AuditedBaseModel`**, specified in section 6 of
  `docs/specs/2026-07-29-domain-owned-timestamps.md`: `Task` receives `terminalStateAt`,
  `SessionToken` and `HashedPassword` receive `createdAt`, the dead audit columns are dropped, and a
  Konsist assertion bans `@WhenCreated` and `@WhenModified`. Two corrections to carry into its
  specification rather than assume from the frozen one: the column count (see above), and section
  6.5's expectation of a table rebuild for a column drop, which block 1 measured not to happen.
  The `pendingDrops` mechanics above apply in full, since block 2 is mostly drops.

## Improve input (failures the gate did not catch)

- **A design decision was taken on a measurement that was wrong.** ADR 0006's decision 4 credited
  the automatic predicate with covering 26 navigations it never touched; all 26 are `.id` navigations,
  which join nothing and filter nothing. The correction inverted the trade-off and cost three plan
  revisions. The gate cannot catch a wrong premise in a document. Candidate remedy: a judgement call
  about counted evidence, that a count offered in support of a decision names the command that
  produced it, in the document that uses it.
- **The gate is silent on a custom rule set that does not run.** detekt excludes custom rule sets
  from configuration validation, so the whole rule set could have been misconfigured with a green
  build. This branch closed it with two tests, which is the right home, but the general shape (a
  configuration key nothing validates) is worth stating once rather than rediscovering.
- **The pre-commit hook enforces nothing in a clone where `core.hooksPath` is unset**, and nothing
  says so until someone checks. The setting step is already documented under Install in
  `agents/project.md`, so writing it down again fixes nothing: what is missing is a check. Two of the
  hook's guarantees (OpenAPI in sync, no em dash in additions) had to be re-established by hand at
  wrap. Candidate remedy: move both into the gate, where a clone that skipped the step still gets
  them, and leave the hook as the early warning rather than the only one.
- **Type-resolution detekt tasks are red and nothing tracks it.** They are outside the gate for a
  reason, but the finding count only grows while nobody looks. Candidate remedy: a backlog item to
  clear the five findings and bring `detektMain` into the gate, or an explicit note in
  `agents/project.md` that those tasks are known red.
