# Project

Pinry Reborn's API server: the whole business logic of a self-hosted pin board (users, pins,
boards, tags, images and their renditions, exports), exposed as an HTTP API for the web UI and a
browser extension to call. It is a Kotlin and Quarkus service following Clean Architecture, with a
SQLite store, filesystem-backed image storage, and a task worker for the long operations
(downloading a remote image, deleting an account, building an export archive).

## Orientation

- `docs/handoffs/` : one continuation guide per milestone. **The newest is the entry point**:
  current state, what was just built, pitfalls learned, next step, what is not yet validated.
- `docs/specs/` : the authoritative design per subsystem, dated.
- `docs/adr/` : the architectural decisions, with their context and consequences.
- `docs/plans/` : the execution plans derived from specs.
- `docs/backlog.md` : what is queued, out of session.

## Where the code lives

Eleven Gradle modules, declared in `settings.gradle.kts`. The layering is enforced by the build
graph and by `ArchitectureKonsistTest`, not by this table.

| Subsystem | Location | Role |
|---|---|---|
| Domain | `api-domain/` | Pure: entities, enums, and the ports (`images`, `storage`, `tasks`, `time`, `security`, `exports`, `repositories`). Declares no project dependency. |
| Use cases | `api-usecases/` | Business logic: use cases and their exceptions, search, exports, task contracts. Depends on the domain and on `api-utilities`. |
| Persistence | `api-persistence-sqlite/` | Ebean and SQLite adapter: models (and `models/bases`), mappers, repositories, cursor pagination, and the migration history in `src/main/resources/dbmigration/`. |
| Presentation | `api-presentation-quarkus/` | Jakarta REST adapter: controllers, DTOs (`input`, `output`, `common`), mappers, security, serialization, OpenAPI, HTTP config. |
| File storage | `api-storage-filesystem/` | Image store, rendition cache, ZIP export archive store, data directory layout. |
| Imaging | `api-imaging-vips/` | libvips adapter through vips-ffm: probing and transforming images. |
| Fetching | `api-fetch-http/` | Fetches remote images over HTTP, behind an address policy. |
| System | `api-system/` | System adapters: `SystemClock`, bcrypt password hashing, secure token generation. |
| Worker | `api-worker-quarkus/` | Task worker runtime: dispatcher, bounded executor, task handlers (pin download, account deletion), export retention lifecycle. |
| Utilities | `api-utilities/` | Shared helpers (`createRandomString`) and the `BaseTest` fixture, published as a `testFixtures` source set. |
| Application | `api-application/` | Composition root: entry point, CDI wiring, and the end-to-end integration tests. Depends on every module. |

## Commands

- **Runner**: `./gradlew` (the committed wrapper; the JDK 25 Adoptium toolchain is provisioned by
  the foojay resolver, so no JDK has to be installed by hand).
- **Install**: nothing for the JVM side, two things once per clone. `git config core.hooksPath
  .githooks` enables the hooks, and native libvips must be present or the `api-imaging-vips` tests
  cannot load the library (`libvips42t64` on Ubuntu 24.04, which is what CI installs).
- **THE GATE**: `./gradlew check koverVerify` (detekt, all tests, and the 100% branch coverage
  bound). Measured green on 2026-07-23. **It is not everything CI runs**: `validate.yml` also
  builds the multi-arch container image behind the same `validate / gate` check, and no local
  command covers that. Building one would change the pre-push hook for every contributor, so it is
  its own task, not a side effect of another.
- **One test**: `./gradlew :api-usecases:test --tests "UserCreatorTest"`. The coverage bound lives
  in its own task, so running `test` alone never trips it: there is nothing to bypass.

## Gate perimeter

- **Inside (100% branch coverage)**: the ten modules other than `api-application`. Kover is applied
  per module and measures each module from its own tests, with no aggregation, so
  `api-application`'s end-to-end tests never inflate another module's figure.
- **Outside (not measured)**:
  - `api-application/`, because it is the composition root and its tests are end to end. It has no
    unit tests by design, so Kover is not applied to it at all.
  - `fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.models` and its `models.bases`
    subpackage, because Ebean's bytecode enhancement rewrites entity classes in place and its
    injected bookkeeping (`_ebean_intercept`, `_ebean_get_id`, the static initialiser building
    `_ebean_props`) carries no marker at class or method level and is frequently mis-attributed to
    the wrong source line. Operator decision B1, coverage calibration.
  - `...persistence.sqlite.models.query.Q*` and every class annotated
    `io.ebean.typequery.Generated`: kapt output. **The annotation filter is a per-category
    exemption, which the generic rule forbids, and it is kept here deliberately** (see
    `docs/adr/0001-adopt-agents-baseline.md`): the alternative is measuring generated bytecode that
    no test can reach and nobody wrote. This is the only place the generic file is overridden.

The perimeter is transcribed from `build.gradle.kts`, which is where it is enforced. Change it
there first.

**Inside never shrinks, and widening Outside requires the user's explicit agreement.** This
section is the one place where editing a document lowers the bar for the code, so it is the one
place where an agent must not decide alone.

## Documentation regime

| Document | Regime |
|---|---|
| `README.md` | living |
| `docs/backlog.md` | living |
| `docs/openapi.json` | generated: rewritten by the `pre-commit` hook, never edited by hand |
| `docs/specs/`, `docs/plans/`, `docs/adr/`, `docs/handoffs/` | dated, append-only |

## Design invariants

- **Alpha status**: breaking changes and data loss are acceptable, and nobody should be running
  Pinry Reborn yet. This is a decision input, not a disclaimer: when the only thing standing in the
  way of the clean fix is that a database somewhere already applied the old one, take the clean fix
  and record the consequence in the backlog.
- **`api-domain` is pure**: no I/O, no config, DB, network, clock or logging imports. All I/O lives
  in the adapters and the dependency graph is a DAG. `ArchitectureKonsistTest` enforces both and is
  the authority on the layering, ahead of any table in a document.
- **Never poke holes through layers**: presentation must not call persistence directly, and use
  cases must not depend on persistence implementations.
- **Domain data is stamped by use cases, never invented by adapters** (decided 2026-07-23):
  creation and update instants, ids and state transitions are business facts, so the use case sets
  them from a port such as `Clock` and the adapter stores what it is given. Two known residues are
  open in the backlog: `softDeletedAt` is still stamped inside the persistence adapter, and soft
  delete no longer bumps `updatedAt`.
- **Fix the design, do not work around it.** Three smells mean the design is wrong rather than the
  call site: the same explanatory comment repeated in several places for one workaround; a domain
  type widened to nullable or optional so existing call sites need not change (a migration-cost
  argument, not a design one); a workaround for a tool limitation nobody verified.
- **All code is English** (decided 2026-07-07). Documents written before that decision keep their
  original language: no retro-translation.
- **The migration history is append-only until beta**, when it will be flattened into a single
  generated baseline. Two known costs are accepted meanwhile: `1.2` is a hand-written
  case-insensitive unique index that `@Index(definition = ...)` would express today, and
  `users`/`pins`/`boards`/`tags` keep `when_created` and `when_modified` column names that no longer
  match the domain's `createdAt` and `updatedAt`.

Claims the old `AGENTS.md` made that the code disproved, recorded rather than deleted:

- It listed **six modules**; `settings.gradle.kts` declares **eleven**. The five it never mentioned
  (`api-storage-filesystem`, `api-imaging-vips`, `api-fetch-http`, `api-system`,
  `api-worker-quarkus`) all existed when it was replaced.
- It said `api-usecases` may depend on **`api-domain` only**; `api-usecases/build.gradle.kts` also
  declares `api-utilities`. The dependency table had drifted from the build graph, which is why the
  build graph and the Konsist test are the authority here.
- It said **JUnit 5**; `gradle/libs.versions.toml` pins `junit = "6.1.1"`.

## Conventions

- **The evidence for a library or tool claim goes in the commit message**, never as an unchecked
  claim in a comment.
- **Tags** are annotated and not pushed, one per subsystem, named `vX.Y.Z-` followed by the
  subsystem's name (the latest is `v0.9.0-user-data-export`).
- **The backlog holds open items only.** It has no shipped section: completed work is recorded by
  its handoff, git history and tag. On wrap, delete or narrow the item just finished, add the newly
  discovered ones, and update the `Last reviewed` line. After the merge, reconcile it on `main`: if
  a stale entry survived the pre-merge refresh, delete it with a doc-only commit.
- **"Leave as-is" stays available** as an integration option when the operator wants to handle the
  branch later.
- **Improve commits separately**: `docs(agents):` for a rule, `test(architecture):` for the test
  that enforces one. A rule lands in **this file**, since `AGENTS.md` is generic and the hook
  refuses to edit it; a lesson true of every project is proposed upstream in `agents-baseline`
  instead.
- **Structural remedies have two homes**: `ArchitectureKonsistTest` for an invariant over the
  source, and a plain test such as `DbMigrationModelCoverageTest` for an invariant about repository
  content.
- **Worktrees**: `EnterWorktree` creates one under `.claude/worktrees/` and moves the agent session
  there while the operator's editor stays on `main`; `worktree.baseRef` is `head`. It is one of the
  four branching options and **none of them is a suggested default**: the operator picks.
- **Testing order**, each level failing before implementation: integration tests in
  `api-application` (REST Assured, end to end), then use-case unit tests in `api-usecases` (MockK),
  then repository tests in `api-persistence-sqlite` (Ebean).
- **Test names** use backticks and the `Given..., Then...` form, with no "when" in the name:
  `` fun `Given duplicate username, Then throws UserCreationError`() ``.
- **Test bodies** follow Given-When-Then with explicit `// Given`, `// When` and `// Then` comments.
- **Test maintainability**: helper methods for repeated setup (`createAndSaveUser()`), named test
  variables rather than inline literals, `createRandomString()` from `api-utilities` for unique
  data, and extend the base class that fits: `IntegrationTest` (`api-application`),
  `RepositoryTest` (`api-persistence-sqlite`), or `BaseTest` (`api-utilities` test fixtures).
- **Module conventions**: entities in `api-domain/entities/` have matching interfaces in
  `api-domain/repositories/`; persistence repositories convert through `mappers/`; use cases throw
  domain-specific exceptions (`UserCreationError`, `PinCreationError`); controllers use the DTOs in
  `dtos/`.

## Gotchas

- **A local merge to `main` silently bypasses CI.** Branch protection requires the
  `validate / gate` check, but `enforce_admins` is false, so an admin merging locally gets no
  refusal and no CI run. Push and open a PR.
- **Editing an applied migration breaks startup.** The checksum changes and Ebean refuses the
  history. A correction is a new migration, never an edit.
- **The `api-application` integration tests share one real on-disk database.**
  `EbeanDatabaseProducer` builds its DataSource from `System.getenv("DB_PATH")` and falls back to
  `data.db` at the repository root, ignoring the `datasource.db.url` that
  `api-application/src/test/resources/application.properties` sets to `jdbc:sqlite::memory:`. The
  practical symptom: after editing an already-applied migration the whole suite fails on a checksum
  mismatch until that file is deleted by hand, and a leftover row can leak into a later run. Unlike
  `api-persistence-sqlite`, whose `RepositoryTest` truncates every table before each test. Open item
  in the backlog.
- **The `pre-commit` hook rewrites `docs/openapi.json`**, stages it, and exits non-zero when it
  changed, so the commit has to be re-run. That is the hook working, not a failure.
- **There is no auto-fix task.** detekt runs without formatting rules and ktlint is configured only
  as an IDE plugin (`.idea/ktlint-plugin.xml`), so a finding is fixed by hand.
- **detekt baselines are per module** (`config/detekt/baseline-api-usecases.xml` and its two
  siblings, one file per module name) because each module's
  `detektBaseline` task rewrites rather than merges the target file. The path degrades gracefully
  when the file is absent.

## Modules

@agents/kotlin.md
@agents/backend.md
