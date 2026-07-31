# Handoff: current-password determinism (block 3 of domain-owned timestamps)

Branch: `feat/current-password-determinism` (cut from `main` on 2026-07-31).
Tier: Plan (five TDD tasks). Spec: `docs/specs/2026-07-31-current-password-determinism.md`,
plan: `docs/plans/2026-07-31-current-password-determinism.md`. No new ADR: the parent
`docs/adr/0006-domain-owned-timestamps.md` carries decisions D8, D9, D10; the spec adds D21
to D23 (realisation choices), none of which settles a question the parent did not.

## Current state

`findCurrentPasswordHash` is deterministic. A `(user_id, created_at)` unique index on
`user_password_hashes` makes the `createdAt.desc()` ordering total per user, and a configurable
minimum interval between successful password changes (default `PT30S`, key
`auth.password_change_minimum_interval`) keeps that constraint out of reach in normal use.
`PasswordChanger` refuses a change inside the interval with `PASSWORD_CHANGED_TOO_SOON` (429,
`Retry-After`), and the residual concurrent same-instant collision surfaces as
`PASSWORD_CHANGE_COLLISION` (409), translated from Ebean's `PersistenceException` through the domain
`PasswordChangeCollisionException`. 429 rendering is centralised: a `ThrottledError` marker rendered
by `BaseErrorMapper`, which deleted `ExportTooSoonExceptionMapper`. `./gradlew gate` is green (100%
branch coverage per package, detekt clean, `checkNoLongDashes`), and the branch is ready to integrate
through a rebased pull request after human review.

## What was built

**Errors (D23).** `interface ThrottledError { val retryAfterSeconds: Long }` in `api-usecases`;
`ExportTooSoonError` and the new `PasswordChangedTooSoonError` implement it. `BaseErrorMapper` adds
the `Retry-After` header when `exception is ThrottledError`. `ExportTooSoonExceptionMapper` and its
test are deleted; the assertion moved into `BaseErrorMapperTest`. Invariant: every 429 carries
`Retry-After`.

**Codes and the domain exception.** `ErrorCode.PASSWORD_CHANGED_TOO_SOON` (429) and
`PASSWORD_CHANGE_COLLISION` (409), both with mandatory `BaseErrorMapper.statusFor` arms (the `when`
has no `else`). `PasswordChangedTooSoonError` and `PasswordChangeCollisionError` subclass a widened
`PasswordChangeError` (now carries `cause`). `PasswordChangeCollisionException` lives in
`api-domain/security`: the persistence adapter throws it, `PasswordChanger` catches it and rethrows
the error.

**Use case (D21, D22).** `PasswordChanger` gained `minimumInterval: Duration`, an interval check that
reuses the current hash's `createdAt` (`Duration.between(now.minus(interval), current.createdAt)
.seconds.coerceAtLeast(1)`), and a collision catch around the save. It lost `@ApplicationScoped` and
is constructed by `PasswordChangerProducer` in `api-application` (the `UserDataExportRequester`
precedent: a raw `Duration` from the composition root, keeping `api-usecases` config-free).
`AuthConfig.passwordChangeMinimumInterval()` defaults to `PT30S`; the test `application.properties`
overrides to `PT0S` so the existing signup-then-change integration tests stay green.

**Persistence and migration.** `UserPasswordHashModel` carries `@Index(name =
"ix_user_password_hashes_user_created", columnNames = ["user_id", "when_created"], unique = true)`.
`UserPasswordHashRepository.saveUserPasswordHash` wraps the insert in `catch
(jakarta.persistence.PersistenceException)` and throws the domain exception; the user lookup
(`ActiveUserModels.resolve`) stays outside the try. Migration `1.18.sql` is hand-written as
`create unique index ...` (the `1.2.sql` precedent), see the pitfall below.

**Tests.** `UserPasswordHashRepositoryTest` (same-instant second hash refused as a collision);
`PasswordChangerTest` (inside refusal with `retryAfterSeconds`, boundary success, D10 failed-attempt
costs nothing, collision rethrow); `BaseErrorMapperTest` (two new statuses, `ThrottledError` header,
export regression); `MePasswordRateLimitIntegrationTest` (end-to-end 429 under a `PT1H` profile).

## Pitfalls learned

- **Ebean's SQLite dialect cannot emit a unique constraint.** `@Index(... unique = true)` made the
  generator try `alter table ... add constraint ... unique (...)`, which SQLite does not support, so
  the generated `1.18.sql` was a `-- not supported:` comment that enforces nothing. The red
  repository test caught it. The fix is the project's existing `1.2.sql` precedent: hand-write the
  migration as `create unique index ...` and keep the generated `1.18.model.xml`. Worth a Gotchas
  entry in `agents/project.md` (see Improve input).
- **Two unrelated `PersistenceException` types.** `UserModelDoesNotExistError` extends the project's
  own `PersistenceException` (`api-persistence-sqlite/.../exceptions/PersistenceException.kt`, a
  `java.lang.Exception` subtype), not the `jakarta.persistence.PersistenceException` the repository
  `catch` is on. A first-draft comment and the plan/spec docs asserted a subtype relation that does
  not hold (the catch would not catch `UserModelDoesNotExistError` either way). The resolve-outside-
  try placement is still right (it keeps the "user absent" and "insert constraint" paths distinct),
  but for that reason, not the subtype one. The holistic review caught it; the comment and docs are
  corrected. The pre-existing export comment (`UserDataExportRepository.kt`) carries the same loose
  wording and was left for a separate sweep.
- **The interval counts the signup hash.** The seed hash from account creation is a successful write,
  so under `PT30S` a user cannot change their password for 30 s after signup. This follows from D10
  (the interval reads the current hash's `createdAt`); the `PT0S` test override keeps
  `MePasswordIntegrationTest` green. Recorded as a risk in the spec.
- **`changePassword` reads `clock.now()` before the history check.** Adding the interval check moved
  the clock read earlier, so the existing "previously-used password" test needed a `clock.now()` stub
  or it threw `MockKException`. The plan review caught this before dispatch.
- **`changePassword` throws four domain exceptions**, tripping detekt's `ThrowsCount`. Suppressed
  inline with a reason (the `HttpImageFetcher` precedent): the four throws are four distinct HTTP
  outcomes the controller must distinguish.

## Not validated against real conditions

- **The `1.18` index has not been applied to a database holding real rows**, and none exists. It is
  correct SQL run against the in-memory test store (migration-driven), but no real instance has
  applied it.
- **The 409 collision path is not observed under genuine concurrency.** It is exercised by a unit
  test (mock throws) and a repository test (a real same-instant insert), never by two real concurrent
  HTTP changes in the same millisecond. The interval makes that near-impossible in normal use; the
  constraint is the backstop.
- **`Retry-After` is not readable cross-origin.** `quarkus.http.cors.exposed-headers=Location` only,
  so a browser SPA or extension cannot read the header the 429 sets. Pre-existing for the export 429;
  recorded as a P2 backlog item.
- **Brute-force limiting is explicitly out of scope (D10).** The interval counts successful changes
  only; a failed authentication attempt writes nothing and costs nothing. Attempt limiting stays its
  own backlog item.
- **The local gate does not cover the multi-architecture container image build** or the
  `docs/openapi.json` sync, both behind CI's `validate / gate`. This branch added two error codes but
  no `@APIResponse` annotations (the endpoint declares none today), so `openapi.json` is unchanged;
  integration still goes through a pull request for that reason.

## Suggested next step

- Integrate: push and open a pull request, merge with `gh pr merge --rebase` once the human review
  has come back. Squash is disabled on this repository.
- Then run Improve, from the input below.
- Then the next backlog item. With the P0 domain-owned timestamps work complete, the candidates are
  the P1 user-data import (the other half of portability), or P2 operational debt (the worker DEAD-task
  observability, detekt type-resolution, or the inverse-associations refactor).

## Improve input (failures the gate did not catch)

- **Ebean's SQLite dialect comments out `ALTER TABLE ADD CONSTRAINT UNIQUE`.** The plan assumed the
  generator would emit the migration; it emitted a no-op. The red test caught it, but a future unique
  constraint will hit the same wall. Candidate remedy: a Gotchas line in `agents/project.md` (and
  possibly the shared baseline, since it is true of Ebean-on-SQLite anywhere) recording that a unique
  constraint must be hand-written as `create unique index`, the `1.2.sql` precedent.
- **A comment and two docs asserted a `PersistenceException` subtype relation that does not hold.**
  The project has its own `PersistenceException` alongside the JPA one, and a reviewer (not the gate)
  had to catch the conflation. Candidate remedy: none structural (it is comment accuracy), but the
  pre-existing export comment repeats it and is worth a one-line sweep.
- **The signup-hash rate-limit consequence was caught by the plan self-review**, not the gate, and
  only added to the spec's risks during the holistic fix wave. Candidate remedy: none; the plan
  self-review is the right place and it held.
- **Retaining nothing else is the likely outcome.** The two real defects (the no-op migration, the
  type conflation) were both caught before merge: the first by the red test, the second by the
  holistic review. The gate enforced everything it was asked to.
