# Spec: operational debt triage

Date: 2026-08-01
Tier: Plan

## Goal

Make the system's operational behaviour honest and observable, in one resolution wave: failures
reported by their real cause, errors not masked by cleanup, maintenance batches not silently
aborted, dead background work visible, the gate running the analysis it claims, and tests
deterministic.

The triage principle: a wave resolves debt whose remedy is already determined (a mechanical fix or a
small local choice); debt whose remedy needs a design decision is deferred to its own spec. This spec
is the wave's realization: nine in-scope items, each with the arbitration decided in triage and its
acceptance criteria, the detekt sub-process, and the out-of-scope list.

## In scope

### 1. Session token outliving a tombstoned owner

`SessionTokenRepository.findByTokenHash` roots on `session_tokens` alone, so a token whose owner was
tombstoned still resolves. Close it at the read path with a `withActiveUser()` extension on
`QSessionTokenModel` that filters `user.softDeletedAt.isNull`, declared beside the existing query
constructors and matching the `withActivePin` / `withActiveBoard` shape, so `findByTokenHash` returns
null for a tombstoned owner.

- Acceptance: a tombstoned owner's token does not authenticate (401); a live owner's token does. The
  filter is a query extension, so it is visible to the soft-delete Konsist assertions and the
  `SoftDeleteStateFilteredOutsideQueries` detekt rule.

### 2. One tombstoned owner stops the export retention sweep

Both, per triage:

- **Root cause.** `UserDataExportRepository.save` re-resolves the active user on every save
  (`ActiveUserModels.resolve(export.userId)`), which throws `UserModelDoesNotExistError` for a
  tombstoned owner. A state-change re-save (READY to EXPIRED) must not require resolving the user: the
  association already exists on the row. Fix `save` so a state-change does not throw for a tombstoned
  owner (load and mutate the existing model, or resolve outside the throwing path).
- **Per-item guard.** `ReapExpiredUserDataExports.reap` wraps each item so one failure does not abort
  the batch.

- Acceptance: a tombstoned owner's expired export does not abort the sweep; exports after it are
  still swept in the same run.

### 3. `imageStore.discard` masks the original error

Add a `discardQuietly` to `StorageCleanup` (mirroring `deleteQuietly`), and use it in the rollback
catch blocks of `SetPinImage` and `DownloadPinImage`.

- Acceptance: a throwing `discard` in a rollback logs at WARN and does not mask the original error,
  which still propagates.

### 4. Broad `catch (PersistenceException)` misreports failures

Narrow `UserDataExportRepository.save`'s catch to translate only a unique-constraint violation
(`SQLiteException.resultCode == SQLITE_CONSTRAINT_UNIQUE`), rethrowing everything else. Mirror
`UserPasswordHashRepository.translateIfCollision`. The export site is the only remaining wholesale
catch in the persistence module (audited in triage: the password-hash site is already narrowed).

- Acceptance: a NOT NULL, foreign-key or connection failure on the export save propagates as 500;
  only "export already in progress" is 409.

### 5. Four `!!` in the soft-delete transitions

Replace `findOne()!!` with `checkNotNull(findOne()) { "<entity> <id> vanished between read and
transition" }` at `PinRepository.softDeletePin` / `restorePin` (197, 205) and
`BoardRepository.softDeleteBoard` / `restoreBoard` (50, 58). The entity was just read and validated
by the use case (`PinRecycleBin`, `BoardRecycleBin`); absence is a concurrent hard delete, an illegal
state. `checkNotNull` asserts the invariant and smart-casts to non-null for the rest of the method.
This is the type-checker guarantee preferred by `agents/modules/kotlin.md`; `requireNotNull` does not
fit (the id argument was valid), and `!!` is the last resort.

- Acceptance: no `UnsafeCallOnNullableType` in the soft-delete transitions; a concurrent hard delete
  surfaces an `IllegalStateException` with a clear message.

### 6. Task worker observability: DEAD and failed tasks invisible

`TaskProcessor` swallows a throwing handler into a retryable outcome with no logging. Add WARN
logging on handler failure and on the DEAD transition. `api-usecases` already carries `StorageCleanup`
and `ReapTombstonedAccounts` loggers, so the "use cases are logger-free" convention is already widened
for justified cases; this is another. A metric is out of scope (no metrics infrastructure).

- Acceptance: a handler that throws is logged; a task that exhausts retries and is marked DEAD is
  logged with enough context to find it (task id, outcome message).

### 7. `Retry-After` not exposed to cross-origin clients

Add `Retry-After` to `quarkus.http.cors.exposed-headers` (`application.properties`), alongside
`Location`.

- Acceptance: a cross-origin browser client can read the `Retry-After` header on a 429 (export too
  soon, password changed too soon).

### 8. detekt runs without type resolution (and the tasks that have it are red)

Sub-process, with the violation triage already done in the discussion that produced this spec.

1. **Make `BaseModel` abstract.** It is a `@MappedSuperclass`, never instantiated, and detekt's
   type-resolution analyzer does not apply the `allopen` compiler plugin, so it sees `BaseModel` as
   final and emits six spurious "this type is final, cannot be extended" compiler errors on its direct
   subclasses. Declaring it `abstract` aligns detekt with the build (JPA convention for a mapped
   superclass base; one-word change). This is the prerequisite for accurate type-res on the
   persistence module.
2. **Wire `detektMain` and `detektTest` into the gate** through each module's `check`, so the gate
   runs what it claims.
3. **Resolve the findings measured at implementation: 37 total (32 main, 5 test)**, after Task 0
   cleared the six BaseModel compiler errors and Task 4 cleared the four `!!`. The pre-implementation
   estimate (40) shifted on measurement; the buckets hold:
   - **Resolved by config (10).** `ForbiddenVoid` on the ten `RestResponse<Void>` controller return
     types: `ForbiddenVoid.ignoreUsageInGenerics = true`. The first proposal (`Void` to `Unit`) does
     not compile, because `RestResponse.noContent()` is typed `RestResponse<Void>` and `Unit` is not
     assignable to it. `Void` as a generic type argument is the JAX-RS no-body idiom; a direct
     `fun f(): Void` return stays flagged.
   - **Fix in the code (8).** `ImplicitDefaultLocale` (2, `Locale.ROOT`; hex output, no behaviour
     change), `NoNameShadowing` (2, rename the shadowed local), `UseCheckOrError` (2, tests, to
     `error()`), `UnusedVariable` (1, `ImageController` `download`: the 202 returns a synthetic PENDING
     state and the call is a side effect, not a bug), `MemberNameEqualsClassName` (1, rename
     `TrigramSimilarity.trigramSimilarity` to `jaccard`).
   - **Fix in the code (1), surfaced at implementation.** `UnreachableCode` in
     `UserDataExportRepository.save`: a redundant `throw` before the `Nothing`-returning
     `translateIfCollision`, a regression from item 4's catch-narrowing (invisible to plain `detekt`;
     `UnreachableCode` is a type-resolution rule).
   - **Suppress with reason (18).** `LongParameterList` on its 11 sites (5 CDI-injected constructors,
     4 Ebean entity models, 1 CDI producer, 1 test fixture builder); `AbstractClassCanBeConcreteClass`
     on `PersistenceException`, `AuthoredBaseModel`, `BaseModel`, `SoftDeletableQueries`,
     `IntegrationTest`, `RepositoryTest` (abstract bases by intent); and `SpreadOperator` in
     `Application.kt` (`Quarkus.run(*args)`: the `@QuarkusMain` bootstrap, forwarding JVM args once).

- Acceptance: `./gradlew detektMain detektTest` is green; the gate runs both; the persistence module
  reports no analysis compiler errors.

### 9. Test sources read the wall clock freely

1. **Measure.** Activate the `WallClockRead` rule against test sources: 268 sites across 53 files
   (the 259 grep was an upper bound).
2. **Fixed instant, not a fixed clock.** The reads are fixture timestamps (entity `createdAt` and the
   like), not `Clock` consumers, and the unit-test classes are standalone, so a fixed instant in
   testFixtures fits better than a `Clock` threaded through the test bases. `object TestTime` in
   testFixtures exposes one millisecond-coarse instant; tests replace `Instant.now()` with
   `TestTime.now` (261 sites). testFixtures stays excluded, since it hosts the seam.
3. **Four legitimate reads stay.** `SystemClockTest` tests the clock adapter against the real wall
   clock; three api-application integration tests run the real `SystemClock` and read the wall clock
   to keep fixture instants consistent with the app's now. Suppressed, not converted.
4. **Remove the test-source exclusion** from `WallClockRead` (keep `**/testFixtures/**`).

- Acceptance: no wall-clock reads in test sources except the four suppressed classes; `WallClockRead`
  green on tests; the gate enforces it.

## Out of scope (deferred, each its own spec)

- Inverse associations on the persistence models (needs Ebean behaviour proofs; see the backlog item).
- `ModelRepository` inherited finders (the unused open door).
- Authentication attempt limiting / brute force (security; needs a per-user failure counter and its
  state, its own spec before beta).
- Periodic maintenance via the task queue (architecture trade-off; needs a recurrence mechanism the
  queue does not have).
- Flatten the migration history at beta (timing gate).
- User data import (P1 feature, the other half of portability).
- Browser-extension CORS origin (blocked: the extension does not exist yet).

## Assumptions and risks

- **Brute-force limiting is deferred** although it is a security gap. Accepted: the project is alpha
  and nobody is running it (`agents/project.md`); the deferral is recorded, not overlooked.
- **The detekt count (39)** may shift slightly before implementation; the triage buckets (fix /
  configure / suppress / examine) hold regardless.
- **The wall-clock sweep is large** (upper bound 259 sites, 55 files). Mechanical but high-churn; it
  is sequenced last so the rest of the wave is not blocked on it.
- **The `BaseModel` abstract change** is verified safe (no direct instantiation, only generic bounds
  and super calls) but the gate confirms it.
- **The session-token read filter adds a join** to every token resolution. Negligible at alpha scale;
  flagged because it is the one in-scope item with a per-request cost.
