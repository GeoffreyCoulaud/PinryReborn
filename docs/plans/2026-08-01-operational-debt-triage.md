# Plan: operational debt triage

Date: 2026-08-01 (revised same day to apply the plan review's six findings)
Spec: `docs/specs/2026-08-01-operational-debt-triage.md`
Tier: Plan
PR strategy: one PR (spec + plan + implementation, semantic commits). Branch
`docs/operational-debt-triage` off `main` (9016e2a).

Execution: TDD strict (red committed alone before green, the red pasted in the test commit body),
subagent-driven, each task reviewed by a fresh subagent, then a holistic review and the full gate.
Testing order per module: integration (`api-application`) then use-case unit (`api-usecases`, MockK)
then repository (`api-persistence-sqlite`, Ebean).

Logging convention (applies to Tasks 2 and 6): `api-usecases` binds `slf4j-nop` and its tests assert
outcomes, not log output (`StorageCleanupTest` is the precedent). Tests assert the behaviour; the WARN
log line is added but not unit-asserted.

## Tasks (ordered)

### Task 0. Make `BaseModel` abstract

Prerequisite for Task 8 (accurate detekt type-res) and an honest-design fix in its own right.

- File: `api-persistence-sqlite/.../models/bases/BaseModel.kt` (`class` to `abstract class`).
- Why: a `@MappedSuperclass` base, never instantiated; detekt's plugin-less analyzer sees it as final
  and emits six spurious "cannot be extended" compiler errors on its subclasses.
- Acceptance: `./gradlew :api-persistence-sqlite:detektMain` reports zero analysis compiler errors;
  gate green (Ebean enhancement unaffected).

### Task 1. `Retry-After` exposed cross-origin (spec #7)

- File: `api-application/src/main/resources/application.properties` (add `Retry-After` to
  `quarkus.http.cors.exposed-headers`).
- Test: integration (`api-application`) asserting a 429 exposes `Retry-After` to a cross-origin
  client.
- Acceptance: spec #7.

### Task 2. `discardQuietly` (spec #3)

- Files: `api-usecases/.../StorageCleanup.kt` (add `discardQuietly`), `SetPinImage.kt`,
  `DownloadPinImage.kt` (use it in the rollback catch blocks).
- Test: use-case unit asserting a throwing `discard` in a rollback does not mask the original error,
  which still propagates (the WARN is not asserted; see the logging convention).
- Acceptance: spec #3.

### Task 3. Harden `UserDataExportRepository.save` (spec #2 root cause + spec #4 catch narrow)

Merged: both edits are the same method and interact (the resolve removal reshapes the catch site Task 4
of the spec narrows), so one task, not two.

- Root cause: a state-change re-save (READY to EXPIRED) must not re-resolve the user (the association
  already exists on the row), so `save` does not throw `UserModelDoesNotExistError` for a tombstoned
  owner (load and mutate the existing model, or resolve outside the throwing path).
- Catch narrow: translate only `SQLITE_CONSTRAINT_UNIQUE` (via `SQLiteException.resultCode`), rethrow
  everything else. Mirror `UserPasswordHashRepository.translateIfCollision`.
- Boy-scout: the existing `save` comment claims the `PersistenceException` "carries no usable
  structured code", contradicted by the password-hash sibling; rewrite it on this hunk.
- Test: repository test (state-change save for a tombstoned owner does not throw) + (a non-unique-
  constraint persistence failure propagates as 500, not 409).
- Acceptance: spec #2 (save half) + #4.

### Task 4. `checkNotNull` for the four `!!` (spec #5)

- Files: `PinRepository.kt` (197, 205), `BoardRepository.kt` (50, 58).
- Test: repository test asserting a soft-delete/restore on an absent row throws
  `IllegalStateException` with a clear message (today: `NullPointerException`).
- Acceptance: spec #5. Also clears the four `UnsafeCallOnNullableType` detekt findings.

### Task 5. Session token read-path filter (spec #1)

- Files: a `withActiveUser()` extension on `QSessionTokenModel` declared beside the query constructors
  (the `withActivePin` / `withActiveBoard` shape); `SessionTokenRepository.findByTokenHash` uses it.
- Test: repository test asserting `findByTokenHash` returns null for a tombstoned owner and resolves a
  live owner's token.
- Acceptance: spec #1 (the filter is a query extension, visible to the soft-delete Konsist assertions
  and the detekt rule).

### Task 6. Worker DEAD / failed-task logging (spec #6)

- File: `api-usecases/.../tasks/TaskProcessor.kt` (WARN on handler failure and on the DEAD
  transition).
- Test: use-case unit asserting the failure-to-DEAD behaviour (the WARN is not asserted; see the
  logging convention). The failure path is already behaviour-tested; this task adds the operational
  log line and the test confirms the path still routes to DEAD.
- Acceptance: spec #6.

### Task 7. Per-item guard in `ReapExpiredUserDataExports.reap` (spec #2 reap half)

Sequenced after Task 3 (the known throw is fixed first; this guards the rest).

- File: `api-usecases/.../exports/ReapExpiredUserDataExports.kt` (wrap each item so one failure does
  not abort the batch).
- Test: use-case unit asserting the sweep continues past an item whose save throws.
- Acceptance: spec #2 (reap half).

### Task 8. detekt type resolution in the gate (spec #8)

Sequenced after Task 0 (BaseModel) and Task 4 (clears four findings). Remaining findings: 36 (Task
0 surfaced `BaseModel`'s own `AbstractClassCanBeConcreteClass`, so the accurate total is 40, not 39).

- (a) Wire `detektMain` / `detektTest` into the gate via each module's `check` (`build.gradle.kts`).
- (b) Suppress with reason: `LongParameterList` on each of its 11 sites (9 DI/framework constructors +
  2 functions: a CDI producer and a test helper) with a "dependency injection by design" reason
  (detekt 2.0.0-alpha.5 has no class-level `ignoreAnnotated` for this rule); and
  `AbstractClassCanBeConcreteClass` on `PersistenceException`, `AuthoredBaseModel`,
  `SoftDeletableQueries`, `BaseModel`, `IntegrationTest`, `RepositoryTest` (6; `BaseModel` was added
  by Task 0, which made it abstract).
- (c) Fix mechanically (18): `ForbiddenVoid` (10), `ImplicitDefaultLocale` (2), `NoNameShadowing` (2),
  `UseCheckOrError` (2, tests), `UnusedVariable` (1, verify `ImageController.download` is not a bug),
  `MemberNameEqualsClassName` (1).
- (d) Examine `SpreadOperator` in `Application.kt` (1): keep with a reason or fix.
- (e) Baselines: regenerate or consciously re-confirm `config/detekt/baseline-*.xml` after wiring,
  because type resolution can shift finding signatures (the nine baselined IDs were generated for the
  non-type-resolution `detekt` task). The 39-finding figure is post-baseline.
- Acceptance: `./gradlew detektMain detektTest` green; the gate runs both.

### Task 9. Deterministic tests via a fixed clock (spec #9)

Sequenced last (high-churn, no business logic). Depends on Task 8's gate wiring so `WallClockRead`
can enforce on test sources.

- (a) Measure: activate `WallClockRead` against test sources for the real count.
- (b) Carry a fixed `Clock` via the shared test bases. `BaseTest` and the seam live in the
  `testFixtures` source set, which stays excluded from `WallClockRead` (it hosts the `FixedClock` seam
  that reads `Instant.now()` once); `test/` sources take the injected clock and must not read the wall
  clock.
- (c) Remove only the `**/test/**` exclusion from `WallClockRead` (keep `**/testFixtures/**`).
- Acceptance: `WallClockRead` green on `test/` sources; tests take the fixed clock from their base.

## Sequencing and dependencies

Task 0 first (prerequisite). Tasks 1, 2, 4, 5, 6 independent and small. Task 3 before Task 7 (same
subsystem: fix the known throw, then guard the batch). Task 8 after Task 0 and Task 4. Task 9 last
(after Task 8). Within each task, red before green.

## Out of scope

Everything in the spec's Out of scope section. Drift unrelated to a task is named, not fixed inline
(boy-scout rule: only trivial, obviously-correct, on-hunk fixes).
