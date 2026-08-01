# Plan: operational debt triage

Date: 2026-08-01
Spec: `docs/specs/2026-08-01-operational-debt-triage.md`
Tier: Plan
PR strategy: one PR (spec + plan + implementation, semantic commits). Branch:
`docs/operational-debt-triage` off `main` (9016e2a).

Execution: TDD strict (red committed alone before green, the red pasted in the test commit body),
subagent-driven, each task reviewed by a fresh subagent on completion, then a holistic review and the
full gate before the PR. Testing order per module: integration (`api-application`) then use-case unit
(`api-usecases`, MockK) then repository (`api-persistence-sqlite`, Ebean).

## Tasks (ordered)

### Task 0. Make `BaseModel` abstract

Prerequisite for Task 8 (accurate detekt type-res) and an honest-design fix in its own right.

- File: `api-persistence-sqlite/.../models/bases/BaseModel.kt` (`class` to `abstract class`).
- Why: a `@MappedSuperclass` base, never instantiated; detekt's plugin-less analyzer sees it as final
  and emits six spurious "cannot be extended" compiler errors.
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
- Test: use-case unit asserting a throwing `discard` in a rollback logs at WARN and the original error
  propagates.
- Acceptance: spec #3.

### Task 3. Narrow the export `catch (PersistenceException)` (spec #4)

- File: `api-persistence-sqlite/.../repositories/UserDataExportRepository.kt` (translate only
  `SQLITE_CONSTRAINT_UNIQUE` via `SQLiteException.resultCode`; rethrow the rest). Mirror
  `UserPasswordHashRepository.translateIfCollision`.
- Test: repository test asserting a non-unique-constraint persistence failure propagates (not 409).
- Acceptance: spec #4.

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
- Test: use-case unit asserting the failure and DEAD paths emit a log (settle the capture mechanism in
  implementation; the `api-usecases` logger precedent is `StorageCleanup` / `ReapTombstonedAccounts`).
- Acceptance: spec #6.

### Task 7. Export sweep: root cause + per-item guard (spec #2)

- Files: `UserDataExportRepository.save` (a state-change re-save must not re-resolve the user, so it
  does not throw for a tombstoned owner), `ReapExpiredUserDataExports.reap` (per-item guard so one
  failure does not abort the batch).
- Test: repository test (state-change save for a tombstoned owner does not throw) + use-case test
  (reap continues past a tombstoned owner's export).
- Acceptance: spec #2.

### Task 8. detekt type resolution in the gate (spec #8)

Sequenced after Task 0 (BaseModel) and Task 4 (clears four findings). Remaining findings: 35.

- (a) Wire `detektMain` / `detektTest` into the gate via each module's `check` (`build.gradle.kts`).
- (b) Configure `LongParameterList` with `ignoreAnnotated` on the CDI scope annotations (11).
- (c) Suppress `AbstractClassCanBeConcreteClass` with a reason on `PersistenceException`,
  `AuthoredBaseModel`, `SoftDeletableQueries`, `IntegrationTest`, `RepositoryTest` (5).
- (d) Fix mechanically (18): `ForbiddenVoid` (10), `ImplicitDefaultLocale` (2), `NoNameShadowing` (2),
  `UseCheckOrError` (2, tests), `UnusedVariable` (1, verify `ImageController.download` is not a bug),
  `MemberNameEqualsClassName` (1).
- (e) Examine `SpreadOperator` in `Application.kt` (1): keep with a reason or fix.
- Acceptance: `./gradlew detektMain detektTest` green; the gate runs both.

### Task 9. Deterministic tests via a fixed clock (spec #9)

Sequenced last (high-churn, no business logic). Depends on Task 8's gate wiring so `WallClockRead`
enforces on test sources.

- (a) Measure: activate `WallClockRead` against test sources for the real count.
- (b) Carry a fixed `Clock` via the shared test bases (`BaseTest`, `IntegrationTest`,
  `RepositoryTest`); replace `Instant.now()` and siblings.
- (c) Remove the test-source exclusion from `WallClockRead`.
- Acceptance: `WallClockRead` green on test sources; tests take the fixed clock from their base.

## Sequencing and dependencies

Task 0 first (prerequisite). Tasks 1 to 7 are independent and small; any order. Task 8 after Task 0
and Task 4. Task 9 last. Within each task, red before green.

## Out of scope

Everything in the spec's Out of scope section. Drift unrelated to a task is named, not fixed inline
(boy-scout rule: only trivial, obviously-correct, on-hunk fixes).
