# Handoff: operational-debt wave delivered

Date: 2026-08-02. Branch: `docs/operational-debt-triage` (off `main` at `9016e2a`), 34 commits.
Spec: `docs/specs/2026-08-01-operational-debt-triage.md`. Plan:
`docs/plans/2026-08-01-operational-debt-triage.md`. This consumes and supersedes the T8/T9
continuation handoff (`docs/handoffs/2026-08-01 - handoff - operational-debt-t8-t9.md`), which was the
entry point for the session that finished the wave.

## Current state

The wave is complete. All nine in-scope items (T0-T9) are implemented, `./gradlew gate` is green with
detekt type resolution wired in and `WallClockRead` enforced on test sources, and a fresh-subagent
holistic review passed. The one MAJOR it found, a gap in T6's logging, was fixed before the review
closed. The branch is pushed and a PR is open for human review; nothing is integrated yet.

## What was built

T0-T7 (prior session, summarised): `BaseModel` abstract; `Retry-After` cross-origin;
`ImageStore.discardQuietly`; `UserDataExportRepository.save` hardened (Ebean reference for the FK,
unique-constraint-only catch); four `!!` -> `checkNotNull`; `withActiveUser()` session-token filter;
`TaskProcessor` WARN logging; `ReapExpiredUserDataExports` per-item guard.

T8 (detekt type resolution in the gate), this session:
- Wired `detektMain` / `detektTest` into each module's `check` (root `build.gradle.kts`).
- `ForbiddenVoid.ignoreUsageInGenerics = true`: the ten `RestResponse<Void>` returns are the JAX-RS
  no-body idiom and `Void` -> `Unit` does not compile (`RestResponse.noContent()` is `<Void>`).
- Nine code fixes (an `UnreachableCode` regression from T3, two `Locale.ROOT`, two `NoNameShadowing`,
  two `UseCheckOrError`, one `UnusedVariable`, one `MemberNameEqualsClassName` rename).
- Eighteen reasoned `@Suppress` (eleven `LongParameterList`, six `AbstractClassCanBeConcreteClass`, one
  `SpreadOperator`).
- Per-module baselines re-confirmed (not regenerated); they apply to the type-res tasks.

T9 (deterministic tests via a fixed clock), this session:
- `object TestTime` in testFixtures (a fixed millisecond-coarse instant) replaces 261 `Instant.now()`
  fixture reads across the unit-test modules.
- `WallClockRead` enforced on test sources (`**/test/**` exclusion removed; `**/testFixtures/**`
  kept, hosting the seam). Four legitimate reads suppressed: `SystemClockTest` and three
  api-application integration tests whose app runs the real `SystemClock`.
- api-persistence-sqlite wired to the api-utilities testFixtures dependency (it was the one module
  without it).

Post-review fix: T6's WARN logging was on one of three DEAD paths; the holistic review found the
other two (a `PermanentTaskException` and a no-handler task) reached DEAD silently. Both now log.

## Pitfalls learned (this session)

- **The spec's `Void` -> `Unit` for `ForbiddenVoid` was refuted by compilation.** `RestResponse.noContent()`
  is typed `RestResponse<Void>`; `Unit` is not assignable. Resolved by `ignoreUsageInGenerics = true`
  (detekt's own option for the idiom). The spec and plan were reconciled to the measured outcome.
- **The spec's "fixed Clock via the test bases" did not fit the tests.** The 268 wall-clock reads were
  fixture timestamps in standalone test classes, not Clock consumers, so a fixed-instant singleton
  (`TestTime`) in testFixtures was simpler and uniform. Spec/plan reconciled.
- **A completed task can be incomplete.** T6 passed its per-task review in the prior session but
  logged only one of three DEAD transitions; the gap was invisible to the gate because the logging
  convention does not assert WARN output. The holistic review (cross-task, fresh eyes) caught it.
- **api-persistence-sqlite lacked the testFixtures dependency.** The T9 sweep there blocked until
  `testImplementation(testFixtures(project(":api-utilities")))` was added (the other modules had it).
- **detekt `@Suppress` reasons can exceed `MaxLineLength` (120) as trailing comments.** The integration
  and clock-adapter suppressions put the reason on its own line above the annotation. Comment lines are
  not exempt from `MaxLineLength`.
- **Type-resolution detekt re-uses the plain-task baselines.** The extension's `baseline` property is
  task-agnostic, so the baselined findings did not reappear; no regeneration needed.

## What is not validated

- **No CI yet.** The branch is pushed and the PR is open but not merged. CI's `validate / gate` also
  builds the multi-arch image and checks `docs/openapi.json` sync, neither covered locally.
- **Integration tests still run the real `SystemClock`.** They are deterministic in behaviour (they
  assert expiry/reaping, not exact timestamps) but not in timestamp values. A Clock-bean override
  would make the values reproducible; deferred as over-engineering for behaviour-asserting tests.
- **The type-resolution gate wiring has no structural guard.** Removing the one `dependsOn` line in
  `build.gradle.kts` leaves the gate green while type-res silently stops (backlog item).
- **`TestTime.now` is a single fixed instant.** No test relied on two `Instant.now()` reads differing
  (the green gate confirms), but a future test that needs ordered instants must derive them
  explicitly (`TestTime.now.plusSeconds(...)`) or use `Instant.parse`.

## Suggested next step

Human review of the PR (rebase-only merge once approved). After integration, the Improve phase: the
two judgement calls the reviews surfaced are (a) the `check`-level vs root-`gate` wiring convention
(T8 wired type-res into each module's `check`, matching the detekt plugin's own pattern, where
`project.md` says "grow the gate by adding dependsOn [to the root gate]"); and (b) logging additions
being unverifiable by the gate (the convention that tests assert outcomes, not log output, left the T6
gap invisible). The four backlog items the wave added are independent follow-ups.
