# Handoff: operational debt wave, Tasks 8 and 9

Branch: `docs/operational-debt-triage` (off `main` at `9016e2a`). Spec:
`docs/specs/2026-08-01-operational-debt-triage.md`. Plan: `docs/plans/2026-08-01-operational-debt-triage.md`.
PR strategy: one PR (spec + plan + every task commit). The branch is not pushed and no PR is open.

## Current state

Tasks 0 to 7 of the plan are done and committed. The small cluster is gate-green (the full `./gradlew
gate` passed after the one fix below; see the last commit). **Tasks 8 (detekt type resolution in the
gate) and 9 (deterministic tests via a fixed clock) remain.** This handoff is the entry point for
finishing the wave in a fresh session.

The triage artefacts (spec, plan) were reviewed: a fresh-subagent plan review found six issues, all
applied (commit `61081d3`). The ADR was dropped by operator decision; the spec carries the arbitration
and the one-line "no ADR" justification lives in the spec commit (`724134f`).

## What was built (T0-T7)

| Task | Spec | Commits | Summary |
|------|------|---------|---------|
| 0 | prereq | `2f093e5` | `BaseModel` declared `abstract` so detekt's plugin-less analyzer agrees with the `allopen`-opened build (the six "cannot be extended" compiler errors gone). |
| 1 | #7 | `9dd6442` `683925f` | `Retry-After` added to `quarkus.http.cors.exposed-headers`; cross-origin integration test. |
| 2 | #3 | `078aff3` `a98bead` | `ImageStore.discardQuietly` in `StorageCleanup`, used at every cleanup-on-failure site in `SetPinImage` / `DownloadPinImage` (the two rollbacks plus three probe-failure catches, a boy-scout extension). |
| 3 | #2 + #4 | `fbbeaee` `accc563` | `UserDataExportRepository.save`: state-change re-saves no longer re-resolve the user (Ebean `database.reference` for the FK); the `catch` translates only `SQLITE_CONSTRAINT_UNIQUE`; the stale comment rewritten. |
| 4 | #5 | `3a51861` `b5c6f79` | The four `findOne()!!` in the soft-delete transitions replaced with `checkNotNull` (clears four `UnsafeCallOnNullableType`). |
| 5 | #1 | `f167f6c` `7d18364` | `withActiveUser()` extension on `QSessionTokenModel`; `findByTokenHash` returns null for a tombstoned owner. |
| 6 | #6 | `6c7a8b8` | `TaskProcessor` logs WARN on handler failure and on the DEAD transition (`runHandler` now takes the task id). |
| 7 | #2 | `8e56586` `25941ca` | `ReapExpiredUserDataExports.reap` isolates each item (per-item try/catch + WARN) so one failure does not abort the sweep. |

Plus `chore(persistence): suppress LargeClass on the comprehensive PinRepositoryTest` (see pitfall 1).

## Pitfalls learned (feed straight into T8)

1. **Targeted-test-only dispatch misses detekt.** T4 added two tests to `PinRepositoryTest` and tipped
   it over `LargeClass`; the implementer ran the targeted tests but not detekt, so the gate caught it.
   The fix was a reasoned `@Suppress("LargeClass")` (the class is the comprehensive main suite; feature
   slices are siblings). Lesson for T8 dispatches: run the module's `detekt` task too, not only the
   tests. T8 should arbitrate split-vs-keep for `PinRepositoryTest`.
2. **`BaseModel` abstract surfaced its own `AbstractClassCanBeConcreteClass`.** Making it abstract (T0)
   completed the persistence analysis: findings went 11 to 12, total 39 to 40. T8's
   `AbstractClassCanBeConcreteClass` suppress list is **six**, not five: add `BaseModel` to
   `PersistenceException`, `AuthoredBaseModel`, `SoftDeletableQueries`, `IntegrationTest`,
   `RepositoryTest`. The spec and plan are already corrected.
3. **`LongParameterList` has no `ignoreAnnotated` in detekt `2.0.0-alpha.5`.** `config.validation` is
   `true`, so a bad key fails the whole run. The operator chose per-site `@Suppress("LongParameterList")`
   with a DI reason (11 sites: 9 DI/framework constructors, 2 functions). Valid keys are
   `allowedConstructorParameters` / `allowedFunctionParameters` / `ignoreAnnotatedParameter` (per-param,
   does not help, the CDI annotations are on the class).
4. **Logging is outcome-tested, not log-tested.** `api-usecases` binds `slf4j-nop` and its tests assert
   outcomes (`StorageCleanupTest` is the precedent). T6's WARN is added but not unit-asserted; do not
   introduce a log-capture fixture without an operator decision.
5. **The export `save` root-cause fix uses `database.reference`.** `database.reference(UserModel, id)`
   holds only the id and never loads the row, so a tombstoned owner's export moves state without
   re-resolving. The PENDING path still resolves the active user (the deleted-account guard). Verified
   against `ebean-api-19.2.0.jar`.

## What is not validated

- The branch is not integrated: no PR, no CI. CI's `validate / gate` also builds the multi-arch image
  and checks `docs/openapi.json` sync, neither covered locally.
- T8 and T9 are not started.
- The session-token read filter (T5) is not observed under genuine concurrency (the one-insert race);
  it is a repository test on a tombstoned owner.
- The `BaseModel` abstract change is not applied to a database holding real rows (none exists).

## Suggested next step

**Task 8 (detekt type resolution), then Task 9 (fixed clock).** Both are large sweeps; they are why
this is a fresh session.

### Task 8

The finding universe is now **40** (35 main + 5 test). Task 0 cleared the six compiler errors and Task
4 cleared four `UnsafeCallOnNullableType`, so **36 remain**:

- Wire `detektMain` and `detektTest` into the gate via each module's `check` (`build.gradle.kts`).
- Fix mechanically (18): `ForbiddenVoid` (10, `Void` to `Unit`), `ImplicitDefaultLocale` (2,
  `Locale.ROOT`), `NoNameShadowing` (2), `UseCheckOrError` (2, tests), `UnusedVariable` (1,
  `ImageController.download`, verify it is not a bug), `MemberNameEqualsClassName` (1).
- `@Suppress` with reason (17): `LongParameterList` on its 11 sites (DI reason); and
  `AbstractClassCanBeConcreteClass` on the six classes (pitfall 2).
- Examine (1): `SpreadOperator` in `Application.kt`.
- Regenerate or re-confirm `config/detekt/baseline-*.xml` after wiring (type resolution can shift
  signatures; the baselined `CyclomaticComplexMethod` on `ModelPaginationHelper` and the others were
  generated for the non-type-resolution `detekt` task).
- Arbitrate `PinRepositoryTest` `LargeClass` (pitfall 1): split vs keep the `@Suppress`.

Acceptance: `./gradlew detektMain detektTest` green; the gate runs both.

### Task 9

- Measure first: activate `WallClockRead` against test sources for the real count (the 259 grep is an
  upper bound, inflated by string literals in the rule's own tests).
- Carry a fixed `Clock` via the shared test bases (`BaseTest`, `IntegrationTest`, `RepositoryTest`).
  `BaseTest` and the seam live in the `testFixtures` source set, which **stays excluded** from
  `WallClockRead` (it hosts the `FixedClock` that reads `Instant.now()` once). `test/` sources take the
  injected clock.
- Remove only the `**/test/**` exclusion from `WallClockRead` (keep `**/testFixtures/**`).

Acceptance: `WallClockRead` green on `test/` sources; tests take the fixed clock.

## After T8 and T9

Run the full gate, do the holistic review over the whole branch diff, then open the PR (one PR,
rebase-only merge once the human review is back). Backlog items to add at wrap: a shared
`SqliteConstraintViolations` helper (T3 left `isUniqueConstraint` / `translateIfCollision` duplicated
across two repositories), and the `PinRepositoryTest` split decision if T8 keeps the suppress.
