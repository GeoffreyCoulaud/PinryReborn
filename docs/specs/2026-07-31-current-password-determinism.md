# Current-password determinism (block 3 of domain-owned timestamps)

Date: 2026-07-31
Status: Draft 2026-07-31 (design approved in discussion; written spec pending review)
Branch: `feat/current-password-determinism`
Parent spec: `docs/specs/2026-07-29-domain-owned-timestamps.md`. Parent ADR:
`docs/adr/0006-domain-owned-timestamps.md` (decisions D8, D9, D10 carry this block; no new ADR, see
the end of section 3). Section 7 of the parent is in force; this specification narrows it to what one
session builds and records three realisation decisions (D21 to D23) the parent left open, each
grounded in the code rather than in the frozen document's prose:

1. The minimum interval reuses the current hash's `createdAt` the use case already reads. No new
   repository method, no new read (D21).
2. The interval is wired as a raw `Duration` constructor parameter on `PasswordChanger`, supplied by a
   producer in the composition root, the `UserDataExportRequester` precedent (D22).
3. 429 responses are centralised through one `ThrottledError` marker rendered by `BaseErrorMapper`;
   the dedicated `ExportTooSoonExceptionMapper` is deleted (D23).

The parent's decisions D8, D9, D10 are carried; D1 to D7 were blocks 1 and 2 and are done.

## 1. Goal

Close the third face of the defect the domain-owned timestamps work scoped: which password hash is
current is decided by an ordered read on `user_password_hashes.when_created` (now the domain-mapped
`createdAt`). Block 2 made that instant domain-stamped and ordered `findCurrentPasswordHash` on it,
but the ordering carries no uniqueness guarantee, so two hashes for one user can share an instant
(two concurrent changes the clock reports as the same tick) and the "current" is then nondeterministic.

Block 3 makes the ordering total per user with a `(user_id, created_at)` unique constraint, and keeps
that constraint out of reach in normal use with a configurable minimum interval between successful
changes. The interval is the soft guard the client sees (a 429); the constraint is the hard backstop
that catches the concurrent same-instant collision the interval cannot see (a 409).

## 2. Scope

**In scope:**

- A unique index on `user_password_hashes (user_id, when_created)`, so two hashes for one user cannot
  share an instant and `findCurrentPasswordHash` is deterministic.
- A configurable minimum interval between password changes, default `PT30S`, enforced in
  `PasswordChanger` against the current hash's `createdAt`. Counts successful changes only.
- Two new error codes: `PASSWORD_CHANGED_TOO_SOON` (429) and `PASSWORD_CHANGE_COLLISION` (409).
- A `ThrottledError` marker interface and centralised `Retry-After` rendering in `BaseErrorMapper`;
  deletion of `ExportTooSoonExceptionMapper`.
- One migration (`1.18`) adding the unique index. No backfill (parent D12).

**Out of scope:**

- Limiting authentication attempts (brute force on `changePassword` and on `POST /api/v1/sessions`).
  The interval counts successful changes only and is explicitly not a brute-force defence (D10). It
  stays its own backlog item.
- Making "current password" an explicit marker rather than an ordered read (considered and rejected,
  parent D8).
- Any backfill (parent D12): nothing is deployed.
- Flattening the migration history, a beta-time item.
- The remaining P2 backlog items.

## 3. Decisions (invariants)

- **D8 (kept, parent).** The current password stays an ordered read, made deterministic by a unique
  constraint plus a minimum interval, rather than by an explicit "current" marker.
- **D9 (kept, parent).** A residual unique-constraint violation is a 409 (`PASSWORD_CHANGE_COLLISION`),
  not a 500; the interval refusal is a 429 (`PASSWORD_CHANGED_TOO_SOON`).
- **D10 (kept, parent).** The rate limit counts successful changes, not attempts, and does not protect
  against brute force. It reads the current hash's `createdAt`, so a failed attempt writes nothing and
  costs the caller nothing.
- **D21.** The interval check reuses the current hash the use case already reads. `PasswordChanger`
  fetches `current` for the reauthentication check (`PasswordChanger.kt:21`); the interval compares
  `current.createdAt` to `now.minus(interval)` and throws `PasswordChangedTooSoonError` when the
  change falls inside the interval. No new repository method is added, mirroring the refusal shape of
  `UserDataExportRequester.kt:57-63`. The check sits outside the transaction, with the reauthentication
  and history checks; it is the soft guard, and the constraint is what closes the concurrent case.
- **D22.** The interval reaches `PasswordChanger` as a raw `Duration` constructor parameter, supplied
  by a CDI producer in the composition root that reads `AuthConfig.passwordChangeMinimumInterval()`.
  `PasswordChanger` loses `@ApplicationScoped` for this, the `UserDataExportRequester` precedent
  (`ExportProducers.kt:44-57`): a use case that needs a configured duration takes it as a plain
  constructor value, and the composition root is the single place that constructs it. `MeController`
  is unchanged (it injects `PasswordChanger` by type, which the producer satisfies). A typed wrapper
  scalar is not introduced: the `SessionExpiryPolicy` precedent is heavier than one scalar warrants,
  and an inline value class risks silent mishandling by CDI's reflection
  (`agents/modules/kotlin.md`, inline value classes). Configuration key
  `auth.password_change_minimum_interval`, default `PT30S`, beside the existing `auth.*` ISO-8601
  duration keys.
- **D23.** 429 retry rendering is centralised. A marker `interface ThrottledError { val
  retryAfterSeconds: Long }` in `api-usecases/exceptions` is implemented by `ExportTooSoonError` and
  the new `PasswordChangedTooSoonError`. `BaseErrorMapper.toResponse` adds the `Retry-After` header
  when `exception is ThrottledError`. `ExportTooSoonExceptionMapper` is deleted: the status already
  comes from `BaseErrorMapper.statusFor`, and the header now comes from the same mapper, so the
  dedicated mapper has nothing left to do. The invariant is that every 429 response carries
  `Retry-After`, because every 429-mapped code has its error class implement the marker. No
  `ExceptionMapper<interface>` is introduced: JAX-RS resolves mappers by the nearest match to the
  thrown exception's exact class, and a marker checked inside the existing `BaseErrorMapper` is
  reliable where an interface-typed mapper would not be.

**No new ADR.** This block executes the parent's D8, D9 and D10; it settles no architectural question
the parent did not already settle. D21 and D22 are realisation choices that follow the export
precedent; D23 is a presentation-layer rendering convention. ADR 0006 remains the authority for "the
current password is an ordered read, made deterministic by a constraint and an interval". If D23 is to
persist as a convention beyond this block, it is a candidate for `agents/project.md` in Improve, not
an ADR.

## 4. Design

### 4.1 The unique constraint

An index unique on `user_password_hashes (user_id, when_created)` at the database columns (the domain
property `createdAt` is mapped to the historical `when_created` column, D19; the `user` association
maps to `user_id`). Two hashes for one user can no longer share an instant, so the `createdAt.desc()`
ordering in `findCurrentPasswordHash` (`UserPasswordHashRepository.kt:27-36`) is total per user and
the read is deterministic.

The annotation form (a composite unique index) follows Ebean's convention and is verified against the
Ebean documentation at Act time, not recalled; the generated `1.18.sql` is read before it is
committed, and the constraint is pinned by `DbMigrationModelCoverageTest` and by the repository test
in section 5.

### 4.2 The minimum interval

`PasswordChanger.changePassword` gains the refusal after the reauthentication check, reusing the
`current` hash already fetched (`PasswordChanger.kt:21`):

```
val now = clock.now()
if (current.createdAt.isAfter(now.minus(minimumInterval))) {
    throw PasswordChangedTooSoonError(retryAfterSeconds = ...)
}
```

`retryAfterSeconds` is `Duration.between(now.minus(minimumInterval), current.createdAt).seconds`,
`coerceAtLeast(1)`, the same expression as `ExportTooSoonError` (`UserDataExportRequester.kt:62`, where
`earliest = now.minus(minimumInterval)` and `last` is the prior instant). The check
runs before the transaction, with the reauthentication (`PasswordChanger.kt:22`) and history
(`PasswordChanger.kt:23-24`) checks; the transaction still does the save and `revokeAll`
(`PasswordChanger.kt:25-28`).

Because the check reads the current hash's `createdAt`, it counts successful changes only: a failed
reauthentication writes no hash and costs nothing (D10).

**Wiring (D22).** `PasswordChanger` loses `@ApplicationScoped` and gains `private val minimumInterval:
Duration`. A producer in `api-application` (beside `ExportProducers`) reads
`AuthConfig.passwordChangeMinimumInterval()` and constructs the bean with its five existing
dependencies plus the interval. `MeController` is unchanged. `AuthConfig` gains
`passwordChangeMinimumInterval(): Duration` with `@WithDefault("PT30S")`, beside `persistentTtl` and
`ephemeralTtl` (`AuthConfig.kt`); `application.properties` carries the documented default.

### 4.3 Error codes and the 409 translation

Two entries are added to `ErrorCode` (`PASSWORD_CHANGED_TOO_SOON`, `PASSWORD_CHANGE_COLLISION`), and
therefore two arms in `BaseErrorMapper.statusFor` (`BaseErrorMapper.kt:29-60`): the `when` has no
`else`, so a missing entry fails compilation. `PASSWORD_CHANGED_TOO_SOON` maps to
`Response.Status.TOO_MANY_REQUESTS` beside `EXPORT_TOO_SOON` (`:55`); `PASSWORD_CHANGE_COLLISION` maps
to `Response.Status.CONFLICT`.

Two error classes, subclasses of `PasswordChangeError` (`PasswordChangeError.kt`):

- `PasswordChangedTooSoonError(val retryAfterSeconds: Long) : PasswordChangeError(...),
  ThrottledError`, code `PASSWORD_CHANGED_TOO_SOON`.
- `PasswordChangeCollisionError(cause: Throwable) : PasswordChangeError(...)`, code
  `PASSWORD_CHANGE_COLLISION`.

The 409 path mirrors the export (`UserDataExportRepository.kt:51-59`). The persistence adapter
translates its driver exception into a domain type, and the use case translates that into the error
the presentation layer maps:

- `UserPasswordHashRepository.saveUserPasswordHash` wraps the save in `try { ... } catch (error:
  PersistenceException) { throw PasswordChangeCollisionException(cause = error) }`. The domain
  exception `PasswordChangeCollisionException` lives in `api-domain/security`, beside the other
  password types, because `api-persistence-sqlite` must not depend on `api-usecases`.
- `PasswordChanger` catches `PasswordChangeCollisionException` around the save and rethrows
  `PasswordChangeCollisionError`.

The same caveat the export records applies: SQLite surfaces the violation as a plain
`PersistenceException` wrapping a `SQLiteException` with no reliable structured code, so the guard is
scoped by what is being written (every call inserts a hash row) rather than by inspecting the message.
The residual risk of reporting a different persistence failure as a 409 is accepted: the only writes
to this table are hash inserts by an authenticated user, so the unique constraint is the realistic
cause.

### 4.4 Centralised 429 rendering

`interface ThrottledError { val retryAfterSeconds: Long }` in `api-usecases/exceptions`.
`ExportTooSoonError` (`UserDataExportError.kt:12`) and `PasswordChangedTooSoonError` implement it.
`BaseErrorMapper.toResponse` builds the problem response as today, then adds `.header("Retry-After",
(exception as ThrottledError).retryAfterSeconds)` when `exception is ThrottledError`.

`ExportTooSoonExceptionMapper` (`ExportTooSoonExceptionMapper.kt`) is deleted. Its behaviour is
preserved: `statusFor` already maps `EXPORT_TOO_SOON` to 429, `Response.Status.fromStatusCode(429)`
returns `TOO_MANY_REQUESTS` (so the title matches), and the `Retry-After` header now comes from
`BaseErrorMapper` through the marker. Its test moves to `BaseErrorMapperTest` as a regression guard.

### 4.5 Migration

One migration (`1.18`) adding the unique index. Generated by
`./gradlew :api-persistence-sqlite:generateDbMigration` after the model annotation is added, read
before it is committed. No backfill (parent D12): the project is alpha, nothing is deployed, and no
database holds two same-instant hashes for one user. The consequence (none today) and the shape of the
index are written into the migration as block 1 and 2 did for their no-backfill cases.

### 4.6 Structural enforcement

No Konsist assertion is added by this block. The two genuine invariants, "every 429 carries
`Retry-After`" and "a new 429 code's error class implements `ThrottledError`", are hard to express in
Konsist: mapping an `ErrorCode` to its error class is not derivable from the declarations the way a
marker interface or an import is. They are held by the tests in section 5 instead. A Konsist rule is a
candidate for Improve if a third 429 code ever makes the mapping worth deriving.

## 5. Testing strategy

Strict TDD, red before green, the failing test committed alone with the command and its output in the
message body. Project order: integration tests in `api-application`, then use-case tests in
`api-usecases`, then repository tests in `api-persistence-sqlite`. 100% branch coverage per package on
the modules inside the perimeter.

1. **The constraint refuses a same-instant second hash.** A repository test inserts two hashes for one
   user at the same instant and asserts the second is refused and surfaces as
   `PasswordChangeCollisionException` (a domain error), not as an Ebean exception.
2. **The interval refuses inside and allows outside.** A `PasswordChanger` test with a controlled
   `Clock` asserts a change inside the interval throws `PasswordChangedTooSoonError` with the right
   `retryAfterSeconds`, a change at or beyond the interval succeeds, and the boundary
   (exactly `interval` elapsed) is pinned.
3. **The interval counts successful changes only.** A `PasswordChanger` test asserts a failed
   reauthentication writes no hash and leaves the caller free to retry immediately (D10).
4. **429 and 409 carry the RFC 7807 shape and the right `code`.** A controller (or `BaseErrorMapper`)
   test asserts both responses are `application/problem+json` with the right `code` member, that the
   429 carries `Retry-After` and the 409 does not.
5. **The `ThrottledError` marker renders the header.** A `BaseErrorMapperTest` case asserts an error
   implementing `ThrottledError` gets the `Retry-After` header and one that does not gets none. The
   export 429 is re-asserted end to end as the regression guard for the deleted
   `ExportTooSoonExceptionMapper`.

The red here is usually a compilation failure (a test naming a type or a `ThrottledError` implementer
its implementation has not introduced yet), pasted from the run.

## 6. Risks and accepted trade-offs

- **The guarantee is a unique index, not a type.** The constraint makes the ordering total at the
  database; nothing in the domain types forbids two same-instant hashes in memory. The interval keeps
  the constraint out of reach in normal use, and the residual concurrent case has a named 409 rather
  than being swallowed. Accepted under alpha status; the boundary test pins it.
- **The interval is a soft guard checked outside the transaction.** Two concurrent requests from the
  same user can both pass it and both insert; the unique constraint catches that and produces the 409.
  The interval is not asked to close the concurrent case.
- **Catching `PersistenceException` can mask a different failure as a 409.** Accepted for the same
  reason as the export: the only writes to `user_password_hashes` are hash inserts by an authenticated
  user, so the unique constraint is the realistic cause. The user lookup stays outside the try block
  to keep the "user absent" and "insert constraint" error paths distinct: `UserModelDoesNotExistError`
  extends the project's own `PersistenceException` (`...sqlite.exceptions.PersistenceException`, a
  `java.lang.Exception` subtype), not the `jakarta.persistence.PersistenceException` the catch is on,
  so it would not be caught either way; the separation is a defensive clarity choice, not a
  correctness requirement.
- **`PasswordChanger` leaves CDI auto-discovery.** It gains a producer in the composition root, the
  `UserDataExportRequester` precedent. `MeController` and the bean's other consumers are unchanged; the
  ripple is the producer and the test constructors that gain a `minimumInterval` argument.
- **`ExportTooSoonExceptionMapper` is deleted and its behaviour folded into `BaseErrorMapper`.** The
  export 429 response is byte-for-byte the same (status, title, body, header), verified by the moved
  test. The centralisation is the point of D23.
- **Two new public error codes are a contract change** on the password-change endpoint. Mandated
  escalation to Spec is satisfied by this document; the codes are additive, so no existing client
  response changes.
- **The interval counts the signup hash (D10/D21).** It reads the current hash's `createdAt`, and the
  seed hash written at signup is a successful write, so under the production default (`PT30S`) a user
  cannot change their password for 30 s after signing up. The test override `PT0S` keeps the existing
  signup-then-change integration tests green.
- **One migration on an append-only history**, already slated for flattening at beta.
