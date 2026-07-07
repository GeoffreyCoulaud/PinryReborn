# Coverage calibration (Task 2)

Measurement only. No production tests written here. Verify rule untouched (100% BRANCH per package,
report-only — not gated in CI yet). One `build.gradle.kts` change: added an `annotatedBy` exclude
(see "Exclusion filter" below).

Reproduce: `./gradlew koverHtmlReport koverXmlReport`, then open `<module>/build/reports/kover/html/index.html`
or `<module>/build/reports/kover/report.xml`.

## Generated-code verdict (the two calibration questions)

**Q1 — data class `equals`/`hashCode`/`toString`/`copy`/`componentN`: NOT counted.**
`api-domain` report: BRANCH missed=0 covered=0 (0 total). Confirmed at the method level: for every
domain data class (`Pin`, `User`, `Tag`, ...) the XML lists only the `<init>` method — `equals`,
`hashCode`, `toString`, `copy`, `componentN` are entirely **absent** from the report (not "0%
covered", just not present as `<method>` elements). Verified these are real, non-bridge, non-synthetic
JVM methods via `javap -v` (`ACC_PUBLIC` only, no `ACC_SYNTHETIC`) — Kover recognizes the Kotlin
data-class pattern specifically and skips it. **api-domain needs no tests for equals/hashCode; Task 3
is a no-op verification** (confirm `koverVerify` is already green, nothing to write).

**Q2 — synthetic default-argument `$default` bridges: NOT counted, but the 2 "missed branches"
reported for `api-utilities` are unrelated to defaults.** `api-utilities` report: BRANCH missed=2
covered=0. Decompiled `StringUtilsKt.class`: `createRandomString$default(int, CharSequence, int,
Object)` is `ACC_SYNTHETIC` and contains 2 real conditional branches (the default-value bitmask
checks) — but it is **absent** from the Kover report entirely, same pattern as Q1. The 2 branches
Kover *does* report belong to the non-synthetic `createRandomString(int, CharSequence)` method, line 6,
and come from the **inlined stdlib `repeat` loop** inside `List(length) { alphabet.random() }` (loop
continuation test `i < length`, inlined at the call site). `api-utilities` currently has **zero test
source** (`src/test` is empty; `createRandomString` is only exercised via `testFixtures` from other
modules' tests, which don't count for this module's own coverage). One test calling
`createRandomString(length = n)` with `n >= 1` hits both branch outcomes in a single execution (loop
runs `n` times: enters the body `n` times, exits once). Task 7: add a real unit test file to
`api-utilities/src/test`, e.g. call with a positive length and assert the result length/alphabet
membership. No exclude needed; no equality-style workaround needed — just write the missing test file.

## Exclusion filter (frozen)

```kotlin
excludes {
    classes("*.Q*")
    annotatedBy("io.ebean.typequery.Generated")
}
```

- `classes("*.Q*")` — confirmed correct against real kapt output
  (`api-persistence-sqlite/build/generated/source/kaptKotlin/main/.../models/query/Q{Pin,PinTag,Tag,User,UserPasswordHash}Model.kt`,
  5 entities × nested `Assoc`/`AssocOne`/`AssocMany`/`Companion`). None of these appear anywhere in
  the `api-persistence-sqlite` report — pattern matches the full FQN including nested `$`-classes. No
  change needed.
- `annotatedBy("io.ebean.typequery.Generated")` — **added** during calibration. Found one kapt-generated
  class that does *not* match `Q*`: `EbeanEntityRegister`
  (`api-persistence-sqlite/build/generated/source/kapt/main/.../models/EbeanEntityRegister.java`, lives
  only under `build/generated`, header says "DO NOT MODIFY THIS CLASS"). It carries
  `@io.ebean.typequery.Generated(...)` (retention `CLASS`, i.e. Kover-visible via ASM) same as the `Q*`
  beans. Before the fix it contributed 1 spurious missed branch (`if (defaultServer)` in
  `classesFor(String, boolean)`, never called with `defaultServer=false` in tests). After adding the
  filter and re-running `koverXmlReport`, the class no longer appears in the report and
  `api-persistence-sqlite` BRANCH total dropped from missed=148/covered=71/total=219 to
  missed=147/covered=70/total=217 (−2, matching the class's 2 total branches). Verified: no production
  class name matches `*.Q*` or carries `@io.ebean.typequery.Generated` (only kapt-generated files under
  `build/generated/` do) — no production code was excluded.

## KNOWN RISK — Ebean bytecode enhancement pollutes entity-model branch counts (api-persistence-sqlite)

Not resolved here; flagged for whoever picks up `api-persistence-sqlite` (Task 5). `ebeanEnhance`/
`ebeanEnhanceTest` rewrite compiled entity classes **in place in the same class file** as the
hand-written model (`build/ebean-enhanced/main/...`, distinct from — and structurally different
from — the pristine `build/classes/kotlin/main/...` output; the enhanced version is what actually
runs and what Kover measures). This injects `EntityBean`-interface bookkeeping
(`_ebean_intercept`, `_ebean_get_id`, `_ebean_getIntercept`, a `<clinit>` that builds the
`_ebean_props` name array, etc.) that:

- Cannot be excluded by class name or annotation — same FQN as the production entity, and the
  injected members carry no distinguishing marker (`javap -v` shows only `ACC_SYNTHETIC`, no
  Ebean-specific annotation at class *or* method level; class-level `AlreadyEnhancedMarker` seen on
  `Q*` query beans is **not** present on entity classes).
- Is frequently **mis-attributed to the wrong source line** by Kover's report — e.g. `BaseModel.kt`
  shows `mb=20` on line 1 (the `package` statement) and `mb=2` each on line 3/line 5 (plain `import`
  statements). These numbers are the sum of several injected methods' branches; there is no real
  source line to "go fix". The usual read-the-report-fix-the-line workflow does not apply here.
- In one case is internally inconsistent, suggesting a **reporting artifact rather than a real gap**:
  `BaseModel._ebean_get_id()` reports `INSTRUCTION missed=0/covered=18` (fully executed) but
  `BRANCH missed=2/covered=0` (fully unhit) — impossible under normal branch semantics if the method
  actually executed, and `javap -c` confirms this exact method has **zero** conditional bytecode.
  Don't spend Task 5 budget trying to "cover" `_ebean_get_id`; if `koverVerify` can't reach 100% on
  the `models` package because of this, escalate to the controller rather than mutating production
  code to route around it.

Split of `api-persistence-sqlite`'s missed branches (147 total) by cause, from the current report:

| Class | Missed | Cause |
|---|---|---|
| `bases/BaseModel` | 28 | 4 real (lateinit getter null-checks, lines 16/19) + 2 real (`id: UUID = randomUUID()` default-arg ctor bridge) + ~22 Ebean noise/artifacts (`<clinit>`, `_ebean_intercept`, `_ebean_get_id`) |
| `bases/AuthoredBaseModel` | 16 | 100% Ebean noise — no lateinit/defaults of its own, pure passthrough constructor |
| `PinModel` | 15 | 1 real (`softDeletedAt: Instant? = null` default-arg ctor bridge) + rest noise |
| `TagModel` | 9 | 100% noise (no defaults/lateinit) |
| `UserModel` | 7 | 100% noise |
| `UserPasswordHashModel` | 24 | 100% noise |
| `PinTagModel` | 6 | 100% noise |
| **models subtotal** | **105 / 147 (71%)** | mostly untestable Ebean enhancement bookkeeping |
| repositories + pagination + `EbeanDatabaseProducer` | 42 / 147 | real production logic, normal TDD gaps (see worklist below) |

**Recommendation for Task 5**: write tests for the clearly-testable pieces first (lateinit getters,
default-arg constructors, all of the repositories/pagination worklist below), rerun
`koverXmlReport`, and only then assess what's left in the `models` package. If a genuine
untestable residual remains, that's a controller-level policy decision (e.g. documented partial
exception for `models`, or revisit whether Ebean's static-enhancement mode can emit cleaner debug
info), not something to solve by weakening the verify rule unilaterally.

## Per-module worklist

### api-domain — 100% (0 branches total)

No entities have hand-written conditionals; everything is data classes/enums/a sealed interface
(`Cursor`, `HashedPassword`, `Login.BasicAuthLogin`, `Page`, `Pin`, `SearchResult`, `Tag`, `User`,
`CursorDirection`, `PasswordHashAlgorithm`, `PinSortStrategy`). Task 3: confirm
`./gradlew :api-domain:koverVerify` is green (it already is) — no test writing needed.

### api-usecases — 84.21% (missed=12, covered=64, total=76)

- `search/TrigramSimilarity.kt`
  - `jaroWinklerSimilarity(query, target)` — line 50 `if (query.isEmpty() || target.isEmpty())
    return 0.0`: **both "true" sides untested** (never called with an empty query or empty target
    anywhere in the current test suite — every existing call uses non-empty strings). Add
    "Given empty query" / "Given empty target" tests mirroring the ones that already exist for
    `trigramSimilarity`.
  - `combinedSimilarity(query, target)` — line 63, same pattern as above (`query.isEmpty() ||
    target.isEmpty()`), both "true" sides untested.
  - `combinedSimilarity` — line 73 `val bestWordScore = if (targetWords.isEmpty()) { 0.0 } else
    {...}`: "true" side (target has content but splits to zero non-blank words, e.g.
    `target = "   "`) untested. Reachable — add a whitespace-only-target test.
  - `combinedSimilarity` — line 76 `targetWords.maxOf { ... }`: 1 of 4 branch outcomes on the
    inlined running-max comparison uncovered; needs a target with ≥2 words where the max isn't the
    first word, to exercise the "new max found" vs "not" outcomes both ways.
  - `trigramSimilarity(query, target)` — line 35 `if (queryTrigrams.isEmpty() ||
    targetTrigrams.isEmpty()) return 0.0`: **likely unreachable** given the line-30 guard already
    rejects empty query/target, and `generateTrigrams` on any non-empty string always yields ≥1
    trigram (padded length ≥ 5, so `padded.length - 2 ≥ 3 > 0`). Flag for review rather than
    force-testing with contrived input; do not alter production code without discussion.
  - `trigramSimilarity` — line 40 `return if (union > 0) ... else 0.0`: the `else` side is
    **likely unreachable** for the same reason (union of two non-empty sets can't be empty). Flag,
    don't force.
- `PinGetter.kt`
  - `getPinForUser(reader, pinId)` — line 23 `if (pin.author != reader) throw
    PinRetrievalPermissionError()`: the **`false` side is untested** (no test exercises a reader
    successfully reading their own pin via `getPinForUser` directly — the only coverage comes
    indirectly through `PinRecycleBinGetterTest`, which only exercises the `true`/permission-error
    side). No dedicated `PinGetterTest.kt` file currently exists.
  - `listPinsPaginatedForUser(...)` — line 33 `if (cursor != null) {...}`: **entirely untested**
    (missed=2, covered=0, instructions 0% covered too) — this method itself has zero direct test
    coverage; only `PinRecycleBinGetter.listSoftDeletedPinsPaginatedForUser` (a different method)
    is exercised. Needs a new `PinGetterTest.kt` covering both `cursor == null` and
    `cursor != null` (pointing to reader's own pin) paths.

### api-persistence-sqlite — 32.26% (missed=147, covered=70, total=217)

See "KNOWN RISK" above for the `models` package (105 of 147 missed). Real production-logic gaps
(42 missed, file → line → snippet — read the current `koverHtmlReport` for exact branch sides,
these were true 0%/partial and straightforward at time of writing):

- `repositories/PinRepository.kt` — lines 99, 100, 110, 111, 148, 161, 162, 172, 173 (cursor
  `?.let{}` null-propagation chains in the two paginated-listing methods, plus the
  `softDeletedPinIds.isEmpty()` early-return guard at line 148).
- `repositories/UserPasswordHashRepository.kt` — line 26 (`?.toDomain()`), line 32 (`findOne() ?:
  throw UserModelDoesNotExistError()`).
- `repositories/TagRepository.kt` — line 31 (`?.toDomain()`).
- `repositories/UserRepository.kt` — line 37 (`?.toDomain()`).
- `pagination/ModelPaginationHelper.kt` — lines 15, 19, 31, 32, 33, 36, 38, 41, 43, 47, 50 (cursor
  direction / threshold / has-more logic — the densest real gap in the module, all hand-written).
- `pagination/ModelSortStrategy.kt` — lines 17, 46 (`when` over `CursorDirection`).
- `pagination/PinModelSortStrategy.kt` — line 91 (`when (strategy)`).
- `EbeanDatabaseProducer.kt` — line 14 (`System.getenv("DB_PATH") ?: "data.db"`).

### api-presentation-quarkus — 0% (missed=67, covered=0, total=67)

**Module has zero test source** (`api-presentation-quarkus/src/test` doesn't exist). Every branch
below is untested on both sides — no per-side nuance needed, just write tests.

- `controllers/PinRecycleBinController.kt` — lines 39, 40, 41 (page size / sort / cursor default
  handling).
- `controllers/PinSearchController.kt` — lines 28, 31 (limit clamping, `requireNotNull(query)`).
- `controllers/TagSearchController.kt` — lines 28, 31 (same pattern).
- `controllers/PinController.kt` — lines 79, 80, 81 (page size / sort / cursor default handling).
- `mappers/BaseErrorMapper.kt` — line 20, `when (code)` over all 10 `ErrorCode` enum values (one
  test per code → expected `Response.Status`, or one parameterized test).
- `mappers/CursorMapper.kt` — lines 16, 22 (two `when (this)` over `CursorDirection`, both
  directions of both mappings).
- `mappers/PinRecycleBinSortStrategyMapper.kt` — line 7 (`when (this)`, 3 enum cases).
- `mappers/PinSortStrategyMapper.kt` — line 7 (`when (this)`).
- `mappers/PinMapper.kt` — lines 25, 26 (`?.toDto()` null-propagation for cursors).
- `config/LoggingRequestResponseFilter.kt` — lines 38, 47 (`if (ctx.hasEntity())`, twice —
  request/response filter methods).
- `serialization/Base64JsonSerializer.kt` — lines 20 (`if (value == null)`), 25 (`gen.codec as?
  ObjectMapper ?: ObjectMapper()`).
- `serialization/Base64ParamConverter.kt` — lines 29 (`annotations?.any { it is Base64Json } ==
  true`), 47, 55 (`if (value == null) return null`, twice).

### api-utilities — 0% (missed=2, covered=0, total=2)

**Module has zero test source.** See Q2 above: the 2 missed branches are the inlined `repeat` loop
inside `createRandomString`, not the `$default` bridge (which Kover doesn't count at all). One test
calling `createRandomString(length = n)` with `n >= 1` covers both. `BaseTest.kt` (test-fixture
base class) shows 0 branches — no action needed there.
