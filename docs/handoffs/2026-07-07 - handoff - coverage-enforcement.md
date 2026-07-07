# Handoff — branch-coverage enforcement (Kover)

Date: 2026-07-07
Branch: `chore/coverage-enforcement`
Spec: `docs/specs/2026-07-07-coverage-enforcement.md`
Plan: `docs/plans/2026-07-07-coverage-enforcement.md`
Calibration: `docs/handoffs/2026-07-07 - coverage calibration.md`

## Current state

The AGENTS.md hard rule ("100% branch coverage on unit tests, per package, gated in CI and
the pre-push hooks") is now real and enforced. `./gradlew koverVerify` is green: every in-gate
module is at 100% branch coverage. `./gradlew check koverVerify` (the full local gate) is green.

## What was built

- **Kover 0.9.8** (`org.jetbrains.kotlinx.kover`) applied per in-gate module in the root
  `subprojects` block, with a verify rule `BRANCH` / `minValue = 100` / `groupBy = PACKAGE`.
  No cross-module aggregation (Option A: each module measured from its own tests).
- **In-gate**: `api-domain`, `api-usecases`, `api-persistence-sqlite`, `api-presentation-quarkus`,
  `api-utilities`. **Out**: `api-application` (composition root + e2e; Kover not applied).
- **Exclusions** (generated/enhanced code only): the Ebean query-bean package
  (`...models.query.Q*`), `annotatedBy("io.ebean.typequery.Generated")`, and the whole
  `...persistence.sqlite.models` package (decision B1 — Ebean bytecode-enhancement injects
  untestable branches into entity classes).
- **Konsist guardrail** (`ModelsPackageArchTest`): because the `models` package leaves the
  coverage gate, this arch-test enforces it stays harmless (every class is `@Entity`/
  `@MappedSuperclass`, declares no functions, has no custom accessors). Verified non-vacuous.
- **Tests added** to reach 100%: `api-usecases` (PinGetter, TrigramSimilarity edge cases),
  `api-persistence-sqlite` (repositories, pagination, EbeanDatabaseProducer), the entire
  `api-presentation-quarkus` test source set (43 MockK unit tests — controllers/mappers/
  serialization/config), `api-utilities` (StringUtils).
- **Behaviour-preserving production changes** (decision A1 category — provably-dead branches,
  each reviewer-verified):
  - `TrigramSimilarity`: removed 2-3 unreachable defensive branches (safe because
    `generateTrigrams` pads inputs, so trigram sets are never empty).
  - `ModelPaginationHelper`: `cursor?.direction ?: FORWARD` → explicit `if`; the pivot-id
    `filterNot` rewritten (both dead because `ModelCursor.pivot`/`direction` are non-null).
  - `PinController` / `PinRecycleBinController`: `sortInput?.toDomain() ?: default` → explicit
    `if` (mapper `toDomain()` is a non-null exhaustive `when`).
  - `EbeanDatabaseProducer`: extracted `internal fun sqliteJdbcUrl(dbPath)` as a testability
    seam so the `?: "data.db"` fallback is unit-testable without a global `System` mock.
- **Enforcement**:
  - CI: `validate.yml` `test` job now runs `./gradlew test koverVerify` (`test` runs every
    module's tests incl. api-application e2e; `koverVerify` gates branch coverage).
  - Local hooks: the `pre-commit` framework (`.pre-commit-config.yaml`) is removed; replaced by
    `.githooks/pre-commit` (OpenAPI regen) and `.githooks/pre-push` (`./gradlew check
    koverVerify`), installed per clone via `git config core.hooksPath .githooks` (README).

## Learned pitfalls

- **`mockkStatic(System::class)` deadlocks the test JVM.** A persistence test used it to stub
  `System.getenv`; the test worker hung for hours (the JVM/agents call `System.*` constantly on
  other threads). Never mock `System` (or any JVM/stdlib class) globally — extract a pure seam
  and test that. This is why `EbeanDatabaseProducer.sqliteJdbcUrl` exists.
- **`koverVerify` alone does NOT run api-application's integration tests** (they aren't a Kover
  dependency). The CI job must run `test koverVerify`, not bare `koverVerify`, or e2e coverage
  of the boot path silently stops running.
- **Kover `classes("*.Q*")` wildcards cross package separators.** The initial pattern would have
  excluded any hand-written `Q*` class in any module. Scoped to `...models.query.Q*`.
- **Kover ignores compiler-generated members** (data-class `equals`/`hashCode`, `$default`
  bridges) but **NOT Ebean bytecode-enhancement** injected into entity classes — hence B1.
- **`koverVerify` and `koverXmlReport`/`koverHtmlReport` are different tasks.** The report XML is
  only refreshed by the report tasks; don't parse a stale `report.xml` after only running verify.

## Not validated

- No runtime behaviour change is expected (test + testability-refactor + build/CI only); not
  validated against real hardware or a running deployment. `produceDatabase()` (real DB build)
  is not executed by tests (0 branches after the seam extraction).
- The `.githooks/` hooks require a manual per-clone `git config core.hooksPath .githooks` (opt-in,
  documented). CI is the authoritative gate regardless.

## Suggested next step

Open the PR and let `validate / gate` confirm the gate on CI. Deferred minor follow-ups (none
blocking, see the final review): tighten a few thin test assertions (search-controller clamp,
logging filter), drop the unnecessary `RepositoryTest` base on `ModelSortStrategyTest`, optionally
hoist duplicated `createAndSaveUser` helpers into `RepositoryTest`, and broaden the Konsist
guardrail to also forbid `init{}`/secondary constructors/companion objects in the models package.
