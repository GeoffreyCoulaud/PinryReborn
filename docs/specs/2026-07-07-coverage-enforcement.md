# Spec: branch-coverage enforcement

Date: 2026-07-07
Status: in review
Slug: `coverage-enforcement`

## 1. Context and goal

`AGENTS.md` states a hard, non-negotiable rule:

> **100% branch coverage on unit tests, per package**, gated in CI and the pre-push
> hooks. Never lower the unit-test threshold; add the missing test (exercise *both*
> sides of every conditional).

Today **none of this exists**:

- No coverage tooling anywhere (no JaCoCo, no Kover in any `build.gradle.kts`, the
  version catalog, or CI).
- CI (`validate.yml`) runs `lint` (detekt) → `test` (`./gradlew test`) → `build-image`
  → `gate`. Nothing measures or enforces coverage.
- The "pre-push hook" the rule refers to **does not exist**. Local hooks go through the
  `pre-commit` framework (`.pre-commit-config.yaml`: `generate-openapi` + a
  `gradle-check` hook from `neuhalje/pre-commit-gradle`), which runs at the *commit*
  stage. There is no `pre-push` stage and `.git/hooks/pre-push` is absent.
- Several in-gate modules are far from 100%: `api-domain` (16 sources, **0 tests**),
  `api-presentation-quarkus` (39 sources, **0 unit tests**, exercised only through
  `api-application` integration tests), `api-utilities` (1 source, 0 tests).

Goal: make the AGENTS.md rule real and enforced, and bring every in-gate module to 100%
branch coverage.

## 2. Decisions taken

1. **Interpretation: Option A — per-module coverage measured from each module's own
   tests** (strict "on unit tests", aligned with Clean/Hexagonal). Each layer is
   unit-tested in isolation. Coverage is **not** aggregated across modules, so
   `api-application`'s end-to-end tests do **not** count toward presentation/domain
   coverage.
2. **Coverage unit: BRANCH only** (`CoverageUnit.BRANCH`, `minValue = 100`). This is the
   literal AGENTS.md wording ("branch coverage", "exercise both sides of every
   conditional") and keeps 100% achievable: a data class with no conditional has zero
   branches, so it needs no test to satisfy the gate. LINE coverage is explicitly out of
   scope (100% LINE in Kotlin forces exercising generated `equals`/`hashCode`/`copy`/
   `componentN`, which is painful and low-value).
3. **Tool: Kover `0.9.8`** (`org.jetbrains.kotlinx.kover`), applied **per in-gate
   module** (no root aggregation). Kover is Kotlin-aware where JaCoCo is not (no spurious
   branches on null-safety / `when`), which is what makes a 100% branch target realistic.
4. **`equals` policy (surgical, never blanket):**
   - A class with a **hand-written, branchy** member (`equals`, or anything else) is
     **never excluded** — it is tested.
   - A **pure data class** whose only gap would be the compiler-generated `equals` is
     covered by a normal equality assertion in a test (`assertEquals` / `assertNotEquals`)
     rather than excluded. Nominative exclusion of a file is a last resort, reserved for
     fully generated code. Kover filters are class/package/annotation granular (never
     per-method), so "exclude the generated `equals` but keep the hand-written one" is
     impossible by design — the policy above sidesteps that.
5. **Report-first calibration.** Kover is introduced in report-only mode first; the real
   gap (in particular whether Kover counts generated-`equals` branches) is *observed*
   from `koverHtmlReport`, the exclusion list is then frozen, tests are written, and the
   gate is flipped to fail-on-miss **only once every in-gate module is at 100%**.
6. **Replace the `pre-commit` framework** with two minimal versioned shell scripts under
   `.githooks/`, installed via `git config core.hooksPath .githooks`. The external
   `neuhalje/pre-commit-gradle` dependency and the Python `pre-commit` tool are dropped.

## 3. Scope

### In scope

- Kover `0.9.8` wired into the build, configured centrally in the root `subprojects {}`
  block (like detekt), scoped to in-gate modules only.
- A branch-coverage verify rule (`minValue = 100`, `groupBy = PACKAGE`) per in-gate
  module.
- New unit tests to bring every in-gate module to 100% branch coverage (bulk of the work:
  `api-presentation-quarkus` and `api-domain`).
- CI: `validate.yml` `test` job runs `./gradlew koverVerify` instead of `./gradlew test`.
- Remove `.pre-commit-config.yaml`; add `.githooks/pre-commit` and `.githooks/pre-push`.
- README: a short "Install git hooks" section.

### Out of scope

- LINE / INSTRUCTION coverage thresholds.
- Cross-module aggregated coverage reports.
- Coverage for `api-application` (composition root + e2e; Kover not applied there).
- Any behavioural change to production code beyond what is strictly needed to make a
  branch testable (if such a case appears, it is called out, not silently changed).

## 4. In-gate vs excluded

| Module                     | In gate? | Rationale                                                     |
|----------------------------|:--------:|--------------------------------------------------------------|
| `api-domain`               | ✅        | Pure; must be unit-testable in isolation.                    |
| `api-usecases`             | ✅        | Business logic; already MockK-tested, close to target.       |
| `api-persistence-sqlite`   | ✅        | Ebean repositories/mappers; partially tested.                |
| `api-presentation-quarkus` | ✅        | Controllers/mappers/security/serialization; **0 unit tests** today. |
| `api-utilities`            | ✅        | `StringUtils`; 0 tests today.                                |
| `api-application`          | ❌        | Composition root + `Application.kt` + e2e tests; no unit tests by design. |

**Report exclusions** (frozen during calibration): generated Ebean query beans (`*.Q*`
and kapt output), the `generated`/`testFixtures` source sets. Migration dev-tools in
`api-persistence-sqlite` live under `src/test` and are not counted as production code.

## 5. Tooling design

Central configuration in the root `build.gradle.kts`, applied only to in-gate modules
(the list above minus `api-application`):

```kotlin
// applied per in-gate subproject
apply(plugin = "org.jetbrains.kotlinx.kover")

extensions.configure<kotlinx.kover.gradle.plugin.dsl.KoverProjectExtension> {
    reports {
        filters {
            excludes {
                classes("*.Q*")            // Ebean generated query beans (calibrated)
            }
        }
        verify {
            rule {
                groupBy = GroupingEntityType.PACKAGE
                bound {
                    coverageUnits = CoverageUnit.BRANCH
                    minValue = 100
                }
            }
        }
    }
}
```

Notes:

- `groupBy = PACKAGE` makes the failure message name the offending package (clearer than
  a whole-module figure); at 100% the two are equivalent.
- No `kover(project(...))` dependencies at the root: aggregation is deliberately avoided
  (that would be Option B).
- `koverVerify` depends on the module `test` task, so running it triggers the tests.
- The exact `excludes` list is confirmed by calibration (§7), not assumed.

## 6. Enforcement design

### CI (`validate.yml`)

- `test` job: `./gradlew test` → `./gradlew koverVerify` (runs tests, then verifies
  100% branch per in-gate module).
- `lint` job (detekt) and `gate` job: **unchanged**. detekt stays in `lint`;
  `koverVerify` does not run detekt, and does not need to.

### Local git hooks

Remove `.pre-commit-config.yaml` entirely. Add two executable scripts under `.githooks/`:

- **`.githooks/pre-commit`** (fast): regenerate the OpenAPI spec by calling
  `scripts/generate-openapi.sh` (kept as-is: it `git add`s the spec and exits non-zero if
  it changed, forcing an up-to-date re-commit).
- **`.githooks/pre-push`** (the local gate, mirroring CI): `./gradlew check koverVerify`.
  `check` pulls in detekt + tests; `koverVerify` adds branch coverage. Gradle runs
  `test` once (dedup between `check` and `koverVerify`). So detekt **does** run at push,
  via `check`.

Installation is opt-in and documented in the README:

```bash
git config core.hooksPath .githooks
```

Once set, Git uses `.githooks/` and ignores `.git/hooks/`.

## 7. Delivery plan (single branch `chore/coverage-enforcement`, subagent-driven)

1. **Report-only setup.** Add Kover `0.9.8` to the version catalog and the root
   `subprojects {}` block for in-gate modules, with the verify rule present but the CI
   gate / hook not yet wired. Generate `koverHtmlReport` per module.
2. **Calibration.** Read the reports. Freeze the real exclusion list (notably the
   verdict on generated-`equals` branches). Confirm the module-by-module gap.
3. **Fill the gaps.** One subagent per in-gate module, strict TDD (red test first), up to
   100% branch. This is the bulk of the effort, concentrated in
   `api-presentation-quarkus` and `api-domain`.
4. **Close the gate.** Once all in-gate modules are at 100%: switch the CI `test` job to
   `koverVerify`, add the `.githooks/` scripts, remove `.pre-commit-config.yaml`, update
   the README. `koverVerify` is now blocking locally and in CI.

## 8. Verification

- `./gradlew koverVerify` is green (every in-gate module at 100% branch).
- `./gradlew koverHtmlReport` reports confirm no unexpected exclusions hide real gaps.
- The full gate (detekt + tests + coverage) is green: `./gradlew check koverVerify`.
- CI `validate / gate` passes on the PR.
- `.githooks/pre-commit` and `.githooks/pre-push` run as expected after
  `git config core.hooksPath .githooks`.

## 9. Risks / open points

- **Generated-`equals` branches**: if calibration shows Kover counts them and tests do
  not naturally cover them, the fallback is a normal equality assertion, not exclusion.
- **Ebean-generated code**: query beans and kapt output must be excluded cleanly; the
  `*.Q*` pattern is a starting point, confirmed by calibration.
- **`api-persistence-sqlite` branch reachability**: some repository branches (DB error
  paths) may be hard to trigger from unit tests; if a branch is genuinely unreachable,
  that is surfaced explicitly rather than worked around by lowering the threshold.
- **Not validated against real hardware**: this is a build/CI/test change; no runtime
  behaviour change is expected.
