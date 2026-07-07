# Branch-coverage enforcement Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Introduce Kover-based 100% branch-coverage enforcement (per in-gate module, from that module's own tests), fill every coverage gap, and gate it in CI and a new `.githooks/pre-push` hook.

**Architecture:** Kover `0.9.8` is applied per in-gate module (no cross-module aggregation) with a `BRANCH`/`minValue = 100` verify rule grouped by package. The tool is introduced report-only first; a calibration pass freezes the exclusion list and the real gap inventory; subagents fill gaps module-by-module with strict TDD; the gate (CI `test` job → `koverVerify`, plus `.githooks/pre-push`) is wired last. The `pre-commit` framework is replaced by two minimal `.githooks/` scripts.

**Tech Stack:** Kotlin 2.4, Gradle multi-module, Kover `0.9.8`, JUnit 5, MockK, Ebean (persistence tests), GitHub Actions.

## Global Constraints

- **Coverage unit: BRANCH only**, `minValue = 100`, per in-gate module, grouped by package. Never lower the threshold (`AGENTS.md` hard rule).
- **In-gate modules:** `api-domain`, `api-usecases`, `api-persistence-sqlite`, `api-presentation-quarkus`, `api-utilities`. **Out:** `api-application` (Kover not applied).
- **Strict TDD:** red test first, watch it fail, then minimal implementation/coverage. Tests are the spec.
- **`equals` policy:** never blanket-exclude; a hand-written branchy member is always tested; a pure data class whose only gap is the generated `equals` is covered by an equality assertion (`assertEquals`/`assertNotEquals`), not excluded. Nominative file exclusion is a last resort reserved for fully-generated code.
- **Clean/Hexagonal:** `api-domain` stays pure (no I/O). Presentation depends on `api-usecases`/`api-domain` only; never on persistence.
- **Language: English** for all code and for new docs under `docs/`.
- **Conventional commits** (`chore(...)`, `test(...)`, `feat(...)`, `docs(...)`).
- **Test conventions:** backtick `Given..., Then...` names; Given-When-Then body with comments; MockK `mockk<Interface>()`; `createRandomString()` from `api-utilities` test fixtures; extend `RepositoryTest`/`IntegrationTest` where relevant.

---

## Task 1: Kover report-only setup

Add Kover to the build, applied to in-gate modules, with the verify rule present but **not** wired into CI, hooks, or `check` yet. After this task `./gradlew koverHtmlReport` works per module and `./gradlew :<module>:koverVerify` is the pass/fail oracle for later tasks; the CI `test` job still runs `./gradlew test` (unchanged), so nothing is prematurely broken.

**Files:**
- Modify: `gradle/libs.versions.toml` (add Kover version + plugin)
- Modify: `build.gradle.kts` (root: plugin classpath + `subprojects` config)

**Interfaces:**
- Produces: per-module Gradle tasks `koverHtmlReport`, `koverXmlReport`, `koverVerify`, applied to every subproject except `api-application`. Verify rule: `BRANCH`, `minValue = 100`, `groupBy = PACKAGE`.

- [ ] **Step 1: Add Kover to the version catalog**

In `gradle/libs.versions.toml`, under `[versions]` add:

```toml
kover = "0.9.8"
```

Under `[plugins]` add:

```toml
kover = { id = "org.jetbrains.kotlinx.kover", version.ref = "kover" }
```

- [ ] **Step 2: Put Kover on the root plugin classpath**

In `build.gradle.kts`, in the top-level `plugins { }` block, add alongside the existing `apply false` aliases:

```kotlin
    alias(libs.plugins.kover) apply false
```

- [ ] **Step 3: Apply and configure Kover in `subprojects` for in-gate modules**

In `build.gradle.kts`, inside the existing `subprojects { ... }` block (after the detekt configuration), add:

```kotlin
    // Branch-coverage gate (Kover). Applied to every module EXCEPT api-application,
    // which is the composition root + end-to-end tests and has no unit tests by design.
    // Coverage is measured per-module from that module's own tests (no aggregation):
    // integration tests in api-application must NOT count toward other modules.
    if (project.name != "api-application") {
        apply(plugin = "org.jetbrains.kotlinx.kover")

        extensions.configure<kotlinx.kover.gradle.plugin.dsl.KoverProjectExtension> {
            reports {
                filters {
                    excludes {
                        // Ebean generated Kotlin query beans (kapt output). Confirmed/adjusted
                        // during calibration (Task 2).
                        classes("*.Q*")
                    }
                }
                verify {
                    rule("100% branch coverage per package") {
                        groupBy = kotlinx.kover.gradle.plugin.dsl.GroupingEntityType.PACKAGE
                        bound {
                            coverageUnits = kotlinx.kover.gradle.plugin.dsl.CoverageUnit.BRANCH
                            minValue = 100
                        }
                    }
                }
            }
        }
    }
```

- [ ] **Step 4: Verify the build configures cleanly**

Run: `./gradlew help -q`
Expected: configures without error (Kover plugin resolves).

Run: `./gradlew tasks --all -q | grep -i kover | head`
Expected: `koverHtmlReport`, `koverVerify`, etc. listed for in-gate modules but **not** for `api-application`.

- [ ] **Step 5: Confirm CI is still unchanged**

Confirm `.github/workflows/validate.yml` `test` job still runs `./gradlew test` (not `koverVerify`). It must NOT be edited in this task.

- [ ] **Step 6: Commit**

```bash
git add gradle/libs.versions.toml build.gradle.kts
git commit -m "chore(coverage): add Kover 0.9.8 report-only, per in-gate module"
```

---

## Task 2: Calibration — freeze exclusions and build the gap inventory

Run the reports and observe reality. Resolve the two open questions: does Kover count branches in (a) compiler-generated data-class `equals`/`hashCode` and (b) synthetic default-argument `$default` bridges? Freeze the exclusion filter and produce a per-module gap list the later tasks work from.

**Files:**
- Modify (if calibration requires): `build.gradle.kts` (`excludes { classes(...) }`)
- Create: `docs/handoffs/2026-07-07 - coverage calibration.md` (short inventory — committed so module tasks are grounded)

**Interfaces:**
- Consumes: Task 1 Kover setup.
- Produces: frozen `excludes` filter; a per-module list of uncovered branches (file → branch) that Tasks 3–7 consume.

- [ ] **Step 1: Generate per-module HTML + XML reports**

Run: `./gradlew koverHtmlReport koverXmlReport`
Expected: BUILD SUCCESSFUL. Reports at `<module>/build/reports/kover/html/index.html` and `.../report.xml`.

- [ ] **Step 2: Read each in-gate module's report and record missed branches**

For each of `api-domain`, `api-usecases`, `api-persistence-sqlite`, `api-presentation-quarkus`, `api-utilities`, open the HTML report (or grep the XML for `missedb` / branch counters) and list every class with missed branches. Record file + method + which side of the conditional is missed.

Grep helper for XML (branch = `type="BRANCH"`):

```bash
find . -path '*/build/reports/kover/report.xml' | while read x; do
  echo "== $x =="; grep -oE '<class[^>]*name="[^"]*"' "$x" | head -50
done
```

- [ ] **Step 3: Decide the generated-code verdict**

- If `api-domain` (pure data classes) reports **0 branches** → Kover ignores generated `equals`; domain needs no test (Task 3 is a no-op verification).
- If it reports missed branches inside generated `equals`/`hashCode` → per the `equals` policy, cover them with equality assertions in Task 3 (do **not** exclude).
- Same logic for `api-utilities` `createRandomString` default-argument `$default` branches (Task 7): if present, cover by calling with defaults AND with explicit args; do not exclude.

- [ ] **Step 4: Freeze the exclusion filter**

Confirm `*.Q*` matches the real generated query-bean class names (check `api-persistence-sqlite/build/generated` / kapt output). Adjust the `classes(...)` pattern in `build.gradle.kts` if the real FQN differs (e.g. `*.query.Q*` or `**.Q*Model`). Only generated code may be added here — never production classes.

- [ ] **Step 5: Write the calibration inventory**

Create `docs/handoffs/2026-07-07 - coverage calibration.md` with, per module: current branch %, the list of missed branches (file → method → missing side), and the generated-code verdict. Keep it terse — it is a worklist, not prose.

- [ ] **Step 6: Commit**

```bash
git add build.gradle.kts "docs/handoffs/2026-07-07 - coverage calibration.md"
git commit -m "chore(coverage): calibrate Kover exclusions and record gap inventory"
```

---

## Tasks 3–8: Fill gaps per module (strict TDD to 100% branch) + B1 guardrail

Task 6 (Konsist guardrail) is the exception to "test-only": it adds an architecture test, not coverage. Task 4 also includes the A1 dead-code removal. All other tasks are test-only.

Each task below is dispatched to a fresh subagent. The oracle for every task is `./gradlew :<module>:koverVerify` turning **green**. The subagent must: read its module's `koverHtmlReport`, enumerate every missed branch, and for each write a failing test first (watch it fail), then the minimal test change to cover it. Follow the test conventions in Global Constraints. Do **not** touch production code except where a branch is genuinely unreachable (see per-task notes) — surface that explicitly instead of deleting/altering logic.

Order: do `api-usecases` and `api-persistence-sqlite` (closest to done) and `api-presentation-quarkus` (largest) in parallel where possible; `api-domain` and `api-utilities` are likely near-no-ops pending calibration.

### Task 3: `api-domain` to 100% branch

**Files:**
- Test: `api-domain/src/test/kotlin/fr/geoffreyCoulaud/pinryReborn/api/domain/**` (create as needed)
- Modify (only if domain gains a test source set need): `api-domain/build.gradle.kts` (add test deps)

**Interfaces:**
- Consumes: calibration verdict (Task 2 Step 3).

- [ ] **Step 1: Check the report**

Run: `./gradlew :api-domain:koverHtmlReport` and open the report.
Expected: near-100% already (entities/enums/interfaces are branch-free).

- [ ] **Step 2: If already 100%, verify and stop**

Run: `./gradlew :api-domain:koverVerify`
Expected: PASS. If PASS, skip to Step 6 (no tests needed — a branch-free module is trivially 100%).

- [ ] **Step 3: If generated `equals` branches are missed — add the test deps**

`api-domain/build.gradle.kts` currently has no test dependencies. Add:

```kotlin
dependencies {
    testImplementation(testFixtures(project(":api-utilities")))
    testImplementation(libs.bundles.testing)
    testRuntimeOnly(libs.bundles.testing.runtime)
}
```

(This requires `api-domain` → `api-utilities` test-only dependency, allowed by the dependency table: "may use api-utilities if absolutely necessary".)

- [ ] **Step 4: Write the failing equality test(s)**

For each data class with missed generated-`equals` branches (e.g. `Pin`, `User`), write a test that exercises equality both ways:

```kotlin
package fr.geoffreyCoulaud.pinryReborn.api.domain.entities

import fr.geoffreyCoulaud.pinryReborn.api.utilities.createRandomString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import java.util.UUID

class UserTest {
    @Test
    fun `Given two users, Then equality is by value`() {
        // Given
        val id = UUID.randomUUID()
        val name = createRandomString()
        val user = User(id = id, name = name)
        val same = User(id = id, name = name)
        val different = User(id = UUID.randomUUID(), name = name)

        // When, Then
        assertEquals(user, same)
        assertNotEquals(user, different)
    }
}
```

- [ ] **Step 5: Run to fail, implement (none — test-only), run to pass**

Run: `./gradlew :api-domain:koverVerify`
Expected: PASS once every missed branch is covered.

- [ ] **Step 6: Commit**

```bash
git add api-domain/
git commit -m "test(domain): 100% branch coverage"
```

### Task 4: `api-usecases` to 100% branch (10 testable branches + A1 dead-code removal)

**Files:**
- Test: `api-usecases/src/test/kotlin/fr/geoffreyCoulaud/pinryReborn/api/usecases/**` (new `PinGetterTest.kt`; extend `TrigramSimilarityTest.kt`)
- Modify (A1): `api-usecases/src/main/kotlin/fr/geoffreyCoulaud/pinryReborn/api/usecases/search/TrigramSimilarity.kt`

The exact per-branch worklist is in `docs/handoffs/2026-07-07 - coverage calibration.md` → "api-usecases" section (12 missed branches). 10 are testable; 2 are provably dead (A1, decided by the operator).

**Interfaces:**
- Consumes: existing use-case test style (`mockk<...>()`, construct use case, `every { } returns`, `assertThrows<>`).

- [ ] **Step 1: Read the worklist**

Read the "api-usecases" section of the calibration doc. It names each missed branch (file → method → missing side). Optionally re-run `./gradlew :api-usecases:koverHtmlReport` to cross-check.

- [ ] **Step 2: Cover the 10 testable branches, failing test first**

Mirror `UserAuthenticatorTest` style (Given-When-Then, backtick names). Concretely:
- `TrigramSimilarity.jaroWinklerSimilarity` line 50 and `combinedSimilarity` line 63: add empty-query and empty-target cases.
- `combinedSimilarity` line 73: whitespace-only target (`"   "`) so `targetWords` is empty.
- `combinedSimilarity` line 76: a target with ≥2 words where the max is not the first word.
- `PinGetter.getPinForUser` line 23 false side: a reader reading their OWN pin succeeds (new `PinGetterTest.kt`).
- `PinGetter.listPinsPaginatedForUser` line 33: both `cursor == null` and `cursor != null` paths (new `PinGetterTest.kt`).

Example (new null-path branch):

```kotlin
@Test
fun `Given repository returns null, Then throws`() {
    // Given
    every { someRepository.find(any()) } returns null

    // When, Then
    assertThrows<SomeError> { useCase.doThing(input) }
}
```

- [ ] **Step 3: A1 — remove the two dead defensive branches**

In `search/TrigramSimilarity.kt`, remove the provably-unreachable code (operator-approved, no behaviour change because unreachable):
- line 35 `if (queryTrigrams.isEmpty() || targetTrigrams.isEmpty()) return 0.0` — dead: the line-30 guard already rejects empty query/target, and `generateTrigrams` on a non-empty string always yields ≥1 trigram.
- line 40 `return if (union > 0) ... else 0.0` — dead: the union of two non-empty sets is never empty; simplify to the non-else expression.

First confirm unreachability by reading the method top-to-bottom (the line-30 guard, `generateTrigrams` behaviour). If you find either branch is actually reachable, STOP and report — do not remove reachable code; test it instead. Keep the existing `TrigramSimilarityTest` green throughout.

- [ ] **Step 4: Run focused tests to verify fail→pass**

Run: `./gradlew :api-usecases:test --tests "PinGetterTest" --tests "TrigramSimilarityTest"`

- [ ] **Step 5: Verify 100%**

Run: `./gradlew :api-usecases:koverVerify`
Expected: PASS (the 2 dead branches are gone, the 10 real ones covered).

- [ ] **Step 6: Commit**

Commit the A1 refactor separately from the tests:

```bash
git add api-usecases/src/main/kotlin/fr/geoffreyCoulaud/pinryReborn/api/usecases/search/TrigramSimilarity.kt
git commit -m "refactor(usecases): remove two provably-unreachable defensive branches in TrigramSimilarity"
git add api-usecases/
git commit -m "test(usecases): 100% branch coverage"
```

### Task 5: `api-persistence-sqlite` to 100% branch (real logic) + B1 models exclusion

**Files:**
- Test: `api-persistence-sqlite/src/test/kotlin/fr/geoffreyCoulaud/pinryReborn/api/persistence/sqlite/**`
- Modify (B1): `build.gradle.kts` (add the `models` package to the shared Kover `excludes`)

Cover the **42 real-logic branches** (repositories + pagination + `EbeanDatabaseProducer`); the ~105 branches in the `models/*` package are Ebean bytecode-enhancement noise (untestable) and are excluded per operator decision B1. The exact worklist is in `docs/handoffs/2026-07-07 - coverage calibration.md` → "api-persistence-sqlite" section (with the "KNOWN RISK" explanation of why `models` is excluded). **Do not** write tests for the `models` package or try to cover `_ebean_*` members.

**Interfaces:**
- Consumes: `RepositoryTest` base (`database`, `truncateAllTables()` before each) for DB-backed tests; `ebean-test` in-memory DB.
- Produces: the `models` package exclusion that the Konsist guardrail (Task 6) then protects.

- [ ] **Step 1: Read the worklist**

Read the "api-persistence-sqlite" section of the calibration doc. Real gaps (42): `repositories/PinRepository` (cursor `?.let` chains + `softDeletedPinIds.isEmpty()` guard), `repositories/{UserPasswordHash,Tag,User}Repository` (`?.toDomain()` / `?: throw`), `pagination/ModelPaginationHelper` (the densest — cursor direction / threshold / has-more), `pagination/ModelSortStrategy` + `PinModelSortStrategy` (`when` over `CursorDirection`/strategy), `EbeanDatabaseProducer` (`System.getenv(...) ?: ...`).

- [ ] **Step 2: B1 — exclude the Ebean-enhanced models package**

In `build.gradle.kts`, add to the shared Kover `excludes { }` block (harmless for other modules, which have no such package):

```kotlin
packages("fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.models")
```

(`packages(...)` excludes the package and its subpackages, so `models` and `models.bases` are both dropped.) Then `./gradlew :api-persistence-sqlite:koverHtmlReport` and confirm the `models` classes no longer appear in the report.

- [ ] **Step 3: Pagination — pure unit tests where possible**

`ModelPaginationHelper`/`ModelSortStrategy`/`PinModelSortStrategy` are pure logic (cursor direction, threshold, has-more, sort strategy). Prefer plain unit tests over DB tests. One test per branch side (e.g. `CursorDirection.AFTER` vs `BEFORE`, first page vs subsequent, has-more true/false).

- [ ] **Step 4: Repository branches — DB-backed tests**

Extend `RepositoryTest`. For `PinRepository`/`{User,Tag,UserPasswordHash}Repository` null/empty paths, arrange DB state (or its absence) to hit each side (e.g. `findOne()` returns null → `?: throw UserModelDoesNotExistError()`). Follow the existing `PinRepositoryTest`/`UserRepositoryTest`.

- [ ] **Step 5: Verify 100% (excluding models)**

Run: `./gradlew :api-persistence-sqlite:koverVerify`
Expected: PASS. If a NON-`models` branch is genuinely unreachable, STOP and report to the controller (do not exclude or alter production code unilaterally).

- [ ] **Step 6: Commit**

```bash
git add build.gradle.kts api-persistence-sqlite/
git commit -m "test(persistence): 100% branch coverage; exclude Ebean-enhanced models package"
```

### Task 6: Konsist guardrail on the excluded `models` package

Because Task 5 removes the `models` package from the coverage gate, a Konsist architecture test enforces that the package stays harmless (only field-storage entity classes — no hand-written branchy logic can hide there). This is the operator-mandated guardrail for decision B1.

**Files:**
- Modify: `gradle/libs.versions.toml` (add Konsist version + library)
- Modify: `api-persistence-sqlite/build.gradle.kts` (add `testImplementation` Konsist)
- Create: `api-persistence-sqlite/src/test/kotlin/fr/geoffreyCoulaud/pinryReborn/api/persistence/sqlite/models/ModelsPackageArchTest.kt`

**Interfaces:**
- Consumes: the `models` package (`fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.models` + `.bases`) excluded from Kover in Task 5.

The invariant: every class residing in `..persistence.sqlite.models..` (a) is annotated `@Entity` or `@MappedSuperclass`, (b) declares no functions, (c) has no property with a custom getter/setter.

- [ ] **Step 1: Confirm the current Konsist API via context7**

This project mandates context7 for library questions. Fetch the current Konsist Gradle dependency coordinates/version and confirm the exact API for: scoping to a module/package, `classes()`, `resideInPackage("..pattern..")`, `hasAnnotationOf(KClass)`, `functions()`, and property custom-accessor predicates (e.g. `hasCustomGetter`/`hasCustomSetter` — verify the real names). Adjust the code below to the confirmed API.

- [ ] **Step 2: Add Konsist to the version catalog**

In `gradle/libs.versions.toml`: add `konsist = "<latest stable, confirmed via context7>"` under `[versions]` and `konsist = { module = "com.lemonappdev:konsist", version.ref = "konsist" }` under `[libraries]`.

- [ ] **Step 3: Add the test dependency**

In `api-persistence-sqlite/build.gradle.kts`, add `testImplementation(libs.konsist)` alongside the existing test deps.

- [ ] **Step 4: Write the arch-test (failing-first mindset: it must actually exercise the invariant)**

Create `ModelsPackageArchTest.kt` (adjust to the confirmed API). Konsist fails on an empty scope by default — good; if the scope resolves empty (wrong module/package pattern), fix the scope, do not relax the assertion.

```kotlin
package fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.models

import com.lemonappdev.konsist.api.Konsist
import jakarta.persistence.Entity
import jakarta.persistence.MappedSuperclass
import org.junit.jupiter.api.Test

class ModelsPackageArchTest {
    private val modelClasses = Konsist
        .scopeFromModule("api-persistence-sqlite")
        .classes()
        .filter { it.resideInPackage("..persistence.sqlite.models..") }

    @Test
    fun `Given the coverage-excluded models package, Then every class is a persistence entity`() {
        modelClasses.assertTrue {
            it.hasAnnotationOf(Entity::class) || it.hasAnnotationOf(MappedSuperclass::class)
        }
    }

    @Test
    fun `Given the coverage-excluded models package, Then no class declares functions`() {
        modelClasses.assertTrue { it.functions().isEmpty() }
    }

    @Test
    fun `Given the coverage-excluded models package, Then no property has a custom accessor`() {
        modelClasses.assertTrue {
            it.properties().all { property -> !property.hasCustomGetter && !property.hasCustomSetter }
        }
    }
}
```

- [ ] **Step 5: Run the test — it must PASS on the current (harmless) models, and the scope must be non-empty**

Run: `./gradlew :api-persistence-sqlite:test --tests "ModelsPackageArchTest"`
Expected: PASS. Sanity-check the scope is non-empty (Konsist errors on empty scope; if it does, the module/package scoping is wrong — fix it). If any assertion fails on the CURRENT code, that is a real finding (a model already has a function / custom accessor) — report it, do not weaken the rule.

- [ ] **Step 6: Confirm this test does not affect coverage**

`ModelsPackageArchTest` lives in `src/test`; it is not measured by Kover (which measures `main`). Run `./gradlew :api-persistence-sqlite:koverVerify` and confirm still PASS.

- [ ] **Step 7: Commit**

```bash
git add gradle/libs.versions.toml api-persistence-sqlite/build.gradle.kts api-persistence-sqlite/src/test/
git commit -m "test(persistence): Konsist guardrail keeps the coverage-excluded models package harmless"
```

### Task 7: `api-presentation-quarkus` to 100% branch (largest)

**Files:**
- Create: `api-presentation-quarkus/src/test/kotlin/fr/geoffreyCoulaud/pinryReborn/api/presentation/quarkus/**` (no test files exist yet)

Controllers are **constructor-injected** (use cases + `SecurityIdentity` + `ApiConfig`) → plain MockK unit tests, **no `QuarkusTest`**. The module's `build.gradle.kts` already has the needed test deps (`testFixtures(api-utilities)`, jakarta.ws.rs, resteasy, testing bundle). Branchy targets (Task 2): controllers `PinController`(6)/`PinRecycleBinController`(5)/`PinSearchController`(3)/`TagSearchController`(3); mappers `CursorMapper`(2)/`PinMapper`(2)/`PinSortStrategyMapper`/`PinRecycleBinSortStrategyMapper`/`BaseErrorMapper`; `serialization/Base64ParamConverter`(7)/`Base64JsonSerializer`(2); `config/LoggingRequestResponseFilter`(2). DTOs/output are branch-free (no tests needed).

**Interfaces:**
- Consumes: controller constructors, e.g. `PinController(pinCreator, pinGetter, pinTagger, pinRecycleBin, securityIdentity, apiConfig)`; `SecurityIdentity` mocked to return a `User` via the `getUser()` extension.

- [ ] **Step 1: Report → enumerate missed branches**

Run: `./gradlew :api-presentation-quarkus:koverHtmlReport`.

- [ ] **Step 2: Controllers — write failing MockK tests, one per branch**

Example covering `PinController.listPins` null/non-null branches (`pageSize`, `sort`, `cursor` each defaulted vs provided):

```kotlin
package fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.controllers

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Page
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.User
import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.config.ApiConfig
import fr.geoffreyCoulaud.pinryReborn.api.usecases.PinGetter
import io.mockk.every
import io.mockk.mockk
import io.quarkus.security.identity.SecurityIdentity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.UUID

class PinControllerTest {
    private val pinGetter = mockk<PinGetter>()
    private val securityIdentity = mockk<SecurityIdentity>()
    private val apiConfig = mockk<ApiConfig>()
    // ... other use-case mocks
    private val controller = PinController(
        pinCreator = mockk(),
        pinGetter = pinGetter,
        pinTagger = mockk(),
        pinRecycleBin = mockk(),
        securityIdentity = securityIdentity,
        apiConfig = apiConfig,
    )

    @Test
    fun `Given no cursor and no page size, Then defaults are used`() {
        // Given
        val user = User(id = UUID.randomUUID(), name = "u")
        every { securityIdentity.principal.name } returns user.name // adapt to getUser() impl
        every { pinGetter.listPinsPaginatedForUser(any(), any(), any(), any()) } returns Page(/* ... */)

        // When
        val response = controller.listPins(cursorInput = null, pageSizeInput = null, sortInput = null)

        // Then
        assertEquals(200, response.status)
    }
}
```

Note: inspect `security/SecurityIdentityExtensions.kt` (`getUser()`) to mock `SecurityIdentity` correctly. Add a second test with `cursorInput`/`pageSizeInput`/`sortInput` non-null to cover the other branch sides.

- [ ] **Step 3: Mappers & serialization — pure unit tests**

`Base64JsonParamConverter.fromString`/`toString`: test `value == null` and non-null. Provider `getConverter`: annotations null, annotations without `@Base64Json`, annotations with `@Base64Json`. Enum mappers: one test per enum value + the else/exhaustive path.

- [ ] **Step 4: Run each new test to fail first, then confirm coverage**

Run per class: `./gradlew :api-presentation-quarkus:test --tests "<Class>"`

- [ ] **Step 5: Verify 100%**

Run: `./gradlew :api-presentation-quarkus:koverVerify`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add api-presentation-quarkus/
git commit -m "test(presentation): 100% branch coverage"
```

### Task 8: `api-utilities` to 100% branch

**Files:**
- Create: `api-utilities/src/test/kotlin/fr/geoffreyCoulaud/pinryReborn/api/utilities/StringUtilsTest.kt`

**Interfaces:**
- Consumes: `createRandomString(length, alphabet)`.

- [ ] **Step 1: Report**

Run: `./gradlew :api-utilities:koverHtmlReport`.
Expected: gap is only the default-argument `$default` branches (if Kover counts them) and/or none.

- [ ] **Step 2: If 100% already, verify and stop**

Run: `./gradlew :api-utilities:koverVerify` → if PASS, go to Step 5.

- [ ] **Step 3: Failing tests covering default vs explicit args**

```kotlin
package fr.geoffreyCoulaud.pinryReborn.api.utilities

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StringUtilsTest {
    @Test
    fun `Given no arguments, Then uses default length`() {
        // When
        val result = createRandomString()
        // Then
        assertEquals(32, result.length)
    }

    @Test
    fun `Given explicit length and alphabet, Then honours them`() {
        // When
        val result = createRandomString(length = 8, alphabet = "ab")
        // Then
        assertEquals(8, result.length)
        assertTrue(result.all { it == 'a' || it == 'b' })
    }
}
```

- [ ] **Step 4: Verify 100%**

Run: `./gradlew :api-utilities:koverVerify`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add api-utilities/
git commit -m "test(utilities): 100% branch coverage"
```

---

## Task 9: Wire the gate (CI + `.githooks/`, remove `pre-commit`)

Only after **every** in-gate module's `koverVerify` is green. Flip the gate on and replace the `pre-commit` framework.

**Files:**
- Modify: `.github/workflows/validate.yml` (`test` job command)
- Delete: `.pre-commit-config.yaml`
- Create: `.githooks/pre-commit`, `.githooks/pre-push` (executable)
- Modify: `README.md` (install-hooks section)

**Interfaces:**
- Consumes: `./gradlew koverVerify` green across all in-gate modules.

- [ ] **Step 1: Pre-condition — full gate green locally**

Run: `./gradlew check koverVerify`
Expected: BUILD SUCCESSFUL (detekt + all tests + 100% branch on every in-gate module).

- [ ] **Step 2: CI — `test` job runs `koverVerify`**

In `.github/workflows/validate.yml`, in the `test` job, change the step:

```yaml
      - name: Test
        run: ./gradlew koverVerify
```

(`lint` and `gate` jobs unchanged.)

- [ ] **Step 3: Create `.githooks/pre-commit`**

```bash
#!/bin/sh
# Keep the committed OpenAPI spec in sync. generate-openapi.sh regenerates it,
# stages it, and exits non-zero if it changed so the commit is re-run up to date.
set -e
exec ./scripts/generate-openapi.sh
```

- [ ] **Step 4: Create `.githooks/pre-push`**

```bash
#!/bin/sh
# Local gate, mirroring CI: detekt + tests + 100% branch coverage.
# `check` pulls in detekt and tests; koverVerify adds the branch-coverage gate.
set -e
exec ./gradlew check koverVerify
```

- [ ] **Step 5: Make hooks executable**

Run: `chmod +x .githooks/pre-commit .githooks/pre-push`

- [ ] **Step 6: Remove the pre-commit framework config**

Run: `git rm .pre-commit-config.yaml`

- [ ] **Step 7: README — install-hooks section**

Add to `README.md`:

```markdown
## Git hooks

This repo ships its hooks in `.githooks/`. Enable them once per clone:

```bash
git config core.hooksPath .githooks
```

- `pre-commit`: regenerates the OpenAPI spec.
- `pre-push`: runs `./gradlew check koverVerify` (detekt, tests, 100% branch coverage).
```

- [ ] **Step 8: Smoke-test the hooks**

Run: `git config core.hooksPath .githooks` then `./.githooks/pre-push`
Expected: full gate runs and passes.

- [ ] **Step 9: Commit**

```bash
git add .github/workflows/validate.yml .githooks/pre-commit .githooks/pre-push README.md
git rm .pre-commit-config.yaml
git commit -m "chore(coverage): gate koverVerify in CI and pre-push, drop pre-commit framework"
```

---

## Task 10: Holistic verify + handoff

**Files:**
- Create: `docs/handoffs/2026-07-07 - handoff - coverage-enforcement.md`

- [ ] **Step 1: Full local gate**

Run: `./gradlew check koverVerify`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: Holistic review**

Review the whole diff: no production logic altered to game coverage; no unjustified exclusions; tests follow conventions and assert behaviour (not just execute lines); `api-application` untouched; dependency rules respected.

- [ ] **Step 3: Write the handoff**

`docs/handoffs/2026-07-07 - handoff - coverage-enforcement.md`: current state, what was built (Kover gate, tests, hooks), pitfalls (generated-code verdict, any unreachable-branch decisions), what is NOT validated (runtime behaviour unchanged; hooks require `git config core.hooksPath .githooks`), suggested next step.

- [ ] **Step 4: Commit, push, open PR**

```bash
git add "docs/handoffs/2026-07-07 - handoff - coverage-enforcement.md"
git commit -m "docs(handoff): branch-coverage enforcement"
git push -u origin chore/coverage-enforcement
gh pr create --fill
```

Wait for `validate / gate` green before merge (squash or rebase; linear history).
