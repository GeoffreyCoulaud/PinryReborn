# Current-password determinism Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `findCurrentPasswordHash` deterministic with a `(user_id, created_at)` unique constraint, add a configurable minimum interval between successful password changes, and surface two new error codes (429, 409) through a centralised 429 renderer.

**Architecture:** The constraint is the hard backstop (a 409 on a concurrent same-instant collision); the interval is the soft guard the client sees (a 429 with `Retry-After`). Both reuse the current hash's `createdAt` the use case already reads. 429 rendering is centralised through a `ThrottledError` marker rendered by `BaseErrorMapper`, which deletes `ExportTooSoonExceptionMapper`.

**Tech Stack:** Kotlin, Quarkus (CDI/ARC, RESTEasy Reactive, SmallRye Config `@ConfigMapping`), Ebean 19.2 on SQLite, JUnit, MockK, REST Assured.

**Spec:** `docs/specs/2026-07-31-current-password-determinism.md`. Parent: `docs/specs/2026-07-29-domain-owned-timestamps.md` section 7; ADR `docs/adr/0006-domain-owned-timestamps.md` (D8, D9, D10).

## Global Constraints

Copied verbatim from the project baseline and the spec; every task's requirements include these.

- **No `!!`.** A non-null value is modelled non-nullable; a nullable one is handled.
- **No em dash or en dash anywhere** humans read (code, comments, commits, docs). The gate's `checkNoLongDashes` fails on any em dash or en dash character in a tracked file. Use a colon, period, parentheses, or hyphen.
- **Everything in the repository is English**: identifiers, comments, KDoc, commits, docs.
- **Conventional commits**: `feat(scope):`, `refactor(scope):`, `test(scope):`, etc.
- **Strict TDD, red before green.** The failing test is committed ALONE first as `test(scope): <behaviour>`, its message body carrying the red (the command run and its pasted failure, never retyped). Then the implementation commits as `feat(scope):` / `refactor(scope):`. The red here is usually a compile failure (an unresolved reference to a type not yet introduced).
- **Test names** are backticked `Given ..., Then ...` with no "when"; bodies use `// Given`, `// When`, `// Then` comments.
- **100% branch coverage per package** is enforced by the gate; do not lower it, add tests.
- **The gate**: `./gradlew gate`. One test class: `./gradlew :<module>:test --tests "ClassName"`. Running `test` alone never trips the coverage bound.
- **Testing order**: integration in `api-application`, use-case unit in `api-usecases` (MockK), repository in `api-persistence-sqlite` (Ebean). Extend the base that fits: `IntegrationTest`, `RepositoryTest`, `BaseTest`.
- **Migrations are append-only.** Generate with `./gradlew :api-persistence-sqlite:generateDbMigration`; read the produced `.sql` before committing; never edit an applied migration. The generator is a `JavaExec`, so `-D` flags must go through `JAVA_TOOL_OPTIONS`.
- **`problemResponse(...)` returns a `Response.ResponseBuilder`** (chain `.header(...).build()`), confirmed in `ProblemResponses.kt`.
- **`UserModelDoesNotExistError` extends `PersistenceException`** (`api-persistence-sqlite/.../exceptions/UserModelDoesNotExistError.kt`). Any `catch (PersistenceException)` in the password repository must keep the user lookup (`ActiveUserModels.resolve`) OUTSIDE the try, or an absent user is misreported as a collision.
- **The password endpoint declares no `@APIResponse` today.** The new 409/429 are documented in the spec, not added as OpenAPI annotations, matching the existing endpoint. If the `pre-commit` hook regenerates `docs/openapi.json` and it changed, commit the regenerated file (the hook stages it and exits non-zero).
- If the gate fails to start integration tests with an unsatisfied `PasswordChanger` bean, `core.hooksPath` may be unrelated; the cause is a missing producer (Task 3).

## File Structure

| File | Action | Responsibility |
|---|---|---|
| `api-usecases/.../exceptions/ThrottledError.kt` | Create | Marker interface: a `BaseError` that carries a `Retry-After` hint. |
| `api-usecases/.../exceptions/UserDataExportError.kt` | Modify | `ExportTooSoonError` implements `ThrottledError`. |
| `api-presentation-quarkus/.../mappers/BaseErrorMapper.kt` | Modify | Adds the `Retry-After` header for any `ThrottledError`; gains two `statusFor` arms. |
| `api-presentation-quarkus/.../mappers/ExportTooSoonExceptionMapper.kt` | Delete | Superseded by the centralised header in `BaseErrorMapper`. |
| `api-presentation-quarkus/.../mappers/ExportTooSoonExceptionMapperTest.kt` | Delete | Its assertion moves to `BaseErrorMapperTest`. |
| `api-usecases/.../exceptions/ErrorCode.kt` | Modify | Add `PASSWORD_CHANGED_TOO_SOON`, `PASSWORD_CHANGE_COLLISION`. |
| `api-usecases/.../exceptions/PasswordChangeError.kt` | Modify | Widen base to take `cause`; add the two new error classes. |
| `api-domain/.../security/PasswordChangeCollisionException.kt` | Create | Domain exception thrown by the adapter, caught by the use case. |
| `api-usecases/.../PasswordChanger.kt` | Modify | Interval check + collision catch; loses `@ApplicationScoped`; gains `minimumInterval`. |
| `api-persistence-sqlite/.../repositories/UserPasswordHashRepository.kt` | Modify | Catch `PersistenceException` on the insert, throw the domain exception. |
| `api-persistence-sqlite/.../models/UserPasswordHashModel.kt` | Modify | Composite unique `@Index`. |
| `api-persistence-sqlite/.../dbmigration/1.18*` (+ model) | Create | The unique index. Generated, then read. Ebean may name it `1.18.sql` or `1.18__<desc>.sql`. |
| `api-presentation-quarkus/.../config/AuthConfig.kt` | Modify | `passwordChangeMinimumInterval()` default `PT30S`. |
| `api-application/.../wiring/PasswordChangerProducer.kt` | Create | CDI producer that constructs `PasswordChanger` with the interval from `AuthConfig`. |
| `api-application/.../resources/application.properties` (main + test) | Modify | Document the default `PT30S`; override to `PT0S` in tests. |
| `api-application/.../MePasswordRateLimitIntegrationTest.kt` (+ profile) | Create | End-to-end 429 contract test on a positive interval. |

---

## Task 1: Centralise 429 rendering behind a `ThrottledError` marker

Deletes `ExportTooSoonExceptionMapper` by moving its `Retry-After` logic into `BaseErrorMapper` behind a marker interface. Behaviour-preserving for the export 429.

**Files:**
- Create: `api-usecases/src/main/kotlin/fr/geoffreyCoulaud/pinryReborn/api/usecases/exceptions/ThrottledError.kt`
- Modify: `api-usecases/src/main/kotlin/fr/geoffreyCoulaud/pinryReborn/api/usecases/exceptions/UserDataExportError.kt`
- Modify: `api-presentation-quarkus/src/main/kotlin/fr/geoffreyCoulaud/pinryReborn/api/presentation/quarkus/mappers/BaseErrorMapper.kt`
- Delete: `api-presentation-quarkus/src/main/kotlin/fr/geoffreyCoulaud/pinryReborn/api/presentation/quarkus/mappers/ExportTooSoonExceptionMapper.kt`
- Test: `api-presentation-quarkus/src/test/kotlin/fr/geoffreyCoulaud/pinryReborn/api/presentation/quarkus/mappers/BaseErrorMapperTest.kt`
- Delete test: `api-presentation-quarkus/src/test/kotlin/fr/geoffreyCoulaud/pinryReborn/api/presentation/quarkus/mappers/ExportTooSoonExceptionMapperTest.kt`

**Interfaces:**
- Produces: `interface ThrottledError { val retryAfterSeconds: Long }` (used by Task 2's `PasswordChangedTooSoonError`).

- [ ] **Step 1: Write the failing tests**

Add to `BaseErrorMapperTest` (imports: `ExportTooSoonError`, `assertNull`):

```kotlin
@Test
fun `Given a ThrottledError, Then the response carries a numeric Retry-After header`() {
    // Given
    val exception = ExportTooSoonError(retryAfterSeconds = 42)
    // When
    val response = mapper.toResponse(exception)
    // Then
    assertEquals(429, response.status)
    assertEquals("42", response.getHeaderString("Retry-After"))
    assertEquals("EXPORT_TOO_SOON", (response.entity as ProblemDetail).code)
}

@Test
fun `Given a plain BaseError, Then no Retry-After header is present`() {
    val exception = BaseError(message = "boom", code = ErrorCode.USERNAME_ALREADY_EXISTS)
    val response = mapper.toResponse(exception)
    assertNull(response.getHeaderString("Retry-After"))
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :api-presentation-quarkus:test --tests "BaseErrorMapperTest"`
Expected: FAIL. `BaseErrorMapper.toResponse` does not add `Retry-After` today (the header lives in the dedicated mapper this task deletes), so the first assertion fails on the missing header. Paste the failure into the next commit.

- [ ] **Step 3: Commit the failing test alone**

```bash
git add api-presentation-quarkus/src/test/.../BaseErrorMapperTest.kt
git commit -m "test(presentation): ThrottledError carries Retry-After through BaseErrorMapper

<paste the command and the failure output from step 2>"
```

- [ ] **Step 4: Implement the marker and centralise the header**

Create `ThrottledError.kt`:

```kotlin
package fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions

/**
 * A [BaseError] that tells the client how long to wait before retrying. Rendered as a `Retry-After`
 * header by [fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.mappers.BaseErrorMapper], so
 * every 429 response carries the header without a dedicated mapper per code.
 */
interface ThrottledError {
    val retryAfterSeconds: Long
}
```

Make `ExportTooSoonError` implement it (`val` becomes `override val`):

```kotlin
class ExportTooSoonError(override val retryAfterSeconds: Long) :
    UserDataExportError("Another export was requested too recently", ErrorCode.EXPORT_TOO_SOON),
    ThrottledError
```

In `BaseErrorMapper.toResponse`, build on the builder and add the header conditionally:

```kotlin
override fun toResponse(exception: BaseError): Response {
    val status = statusFor(exception.code)
    val resolvedStatus = Response.Status.fromStatusCode(status)
    val title = if (resolvedStatus == null) UNPROCESSABLE_ENTITY_TITLE else resolvedStatus.reasonPhrase
    val builder = problemResponse(status, title, exception.message, exception.code.name, uriInfo)
    if (exception is ThrottledError) {
        builder.header("Retry-After", exception.retryAfterSeconds)
    }
    return builder.build()
}
```

Delete `ExportTooSoonExceptionMapper.kt` and `ExportTooSoonExceptionMapperTest.kt` (the assertion above replaces the deleted test).

- [ ] **Step 5: Run to verify it passes**

Run: `./gradlew :api-presentation-quarkus:test --tests "BaseErrorMapperTest"`
Expected: PASS.

- [ ] **Step 6: Commit the implementation**

```bash
git add -A
git commit -m "refactor(presentation): centralise Retry-After behind a ThrottledError marker"
```

---

## Task 2: Add the two new error codes, classes, and the domain collision exception

Adds the vocabulary Task 3 and Task 4 rely on. No behaviour is wired yet.

**Files:**
- Modify: `api-usecases/src/main/kotlin/fr/geoffreyCoulaud/pinryReborn/api/usecases/exceptions/ErrorCode.kt`
- Modify: `api-usecases/src/main/kotlin/fr/geoffreyCoulaud/pinryReborn/api/usecases/exceptions/PasswordChangeError.kt`
- Create: `api-domain/src/main/kotlin/fr/geoffreyCoulaud/pinryReborn/api/domain/security/PasswordChangeCollisionException.kt`
- Test: `api-presentation-quarkus/src/test/kotlin/fr/geoffreyCoulaud/pinryReborn/api/presentation/quarkus/mappers/BaseErrorMapperTest.kt`

**Interfaces:**
- Produces: `ErrorCode.PASSWORD_CHANGED_TOO_SOON`, `ErrorCode.PASSWORD_CHANGE_COLLISION`, `PasswordChangedTooSoonError`, `PasswordChangeCollisionError`, `PasswordChangeCollisionException`.

- [ ] **Step 1: Write the failing tests**

Add to `BaseErrorMapperTest`:

```kotlin
@Test
fun `Given PASSWORD_CHANGED_TOO_SOON, Then status is TOO_MANY_REQUESTS`() {
    assertEquals(Response.Status.TOO_MANY_REQUESTS, statusFor(ErrorCode.PASSWORD_CHANGED_TOO_SOON))
}

@Test
fun `Given PASSWORD_CHANGE_COLLISION, Then status is CONFLICT`() {
    assertEquals(Response.Status.CONFLICT, statusFor(ErrorCode.PASSWORD_CHANGE_COLLISION))
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :api-presentation-quarkus:test --tests "BaseErrorMapperTest"`
Expected: FAIL to compile: `PASSWORD_CHANGED_TOO_SOON` and `PASSWORD_CHANGE_COLLISION` are unresolved references on `ErrorCode`. Paste the compile error.

- [ ] **Step 3: Commit the failing test alone**

```bash
git add api-presentation-quarkus/src/test/.../BaseErrorMapperTest.kt
git commit -m "test(presentation): password-change 429 and 409 status mapping

<paste the command and the compile failure from step 2>"
```

- [ ] **Step 4: Implement the codes, classes, and the domain exception**

In `ErrorCode.kt`, add the two entries (after `PASSWORD_PREVIOUSLY_USED`):

```kotlin
    PASSWORD_CHANGED_TOO_SOON,
    PASSWORD_CHANGE_COLLISION,
```

In `BaseErrorMapper.statusFor`, the `when` has no `else`, so add the two arms (beside the export and conflict entries):

```kotlin
            ErrorCode.PASSWORD_CHANGED_TOO_SOON -> Response.Status.TOO_MANY_REQUESTS.statusCode
            ErrorCode.PASSWORD_CHANGE_COLLISION -> Response.Status.CONFLICT.statusCode
```

Widen `PasswordChangeError` to carry a cause (mirrors `UserDataExportError`) and add the two subclasses. Replace the file body:

```kotlin
package fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions

open class PasswordChangeError(message: String, code: ErrorCode, cause: Throwable? = null) :
    BaseError(message, code, cause)

class PasswordPreviouslyUsedError :
    PasswordChangeError(message = "Password was previously used", code = ErrorCode.PASSWORD_PREVIOUSLY_USED)

class PasswordChangedTooSoonError(override val retryAfterSeconds: Long) :
    PasswordChangeError(
        message = "Password was changed too recently",
        code = ErrorCode.PASSWORD_CHANGED_TOO_SOON,
    ),
    ThrottledError

class PasswordChangeCollisionError(cause: Throwable? = null) :
    PasswordChangeError(
        message = "A password change is already in progress",
        code = ErrorCode.PASSWORD_CHANGE_COLLISION,
        cause = cause,
    )
```

Create `PasswordChangeCollisionException.kt`:

```kotlin
package fr.geoffreyCoulaud.pinryReborn.api.domain.security

/**
 * Raised by
 * [fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.UserPasswordHashRepositoryInterface.saveUserPasswordHash]
 * when a second password hash for one user would share another's creation instant, enforced by the
 * persistence layer's `(user_id, created_at)` unique index.
 *
 * A domain exception, not a use-case [fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.BaseError]:
 * `api-persistence-sqlite` must not depend on `api-usecases`. The adapter throws this;
 * `PasswordChanger` catches it and rethrows `PasswordChangeCollisionError`, the same layering as
 * `ExportAlreadyInProgressException`.
 */
class PasswordChangeCollisionException(cause: Throwable? = null) :
    Exception("A password hash for this user already exists at that instant", cause)
```

- [ ] **Step 5: Run to verify it passes**

Run: `./gradlew :api-presentation-quarkus:test --tests "BaseErrorMapperTest"`
Expected: PASS.

- [ ] **Step 6: Commit the implementation**

```bash
git add -A
git commit -m "feat(errors): add password-change 429/409 codes and the collision domain exception"
```

---

## Task 3: Rate-limit `PasswordChanger` and wire the interval

Adds the interval check (D21) and the collision rethrow, and wires the interval as a raw `Duration` through a composition-root producer (D22). Because `PasswordChanger` gains a constructor parameter it can no longer be `@ApplicationScoped`, so the producer, the `AuthConfig` entry, and the `application.properties` entries land in this task to keep the application startable.

**Files:**
- Modify: `api-usecases/src/main/kotlin/fr/geoffreyCoulaud/pinryReborn/api/usecases/PasswordChanger.kt`
- Modify: `api-usecases/src/test/kotlin/fr/geoffreyCoulaud/pinryReborn/api/usecases/PasswordChangerTest.kt`
- Modify: `api-presentation-quarkus/src/main/kotlin/fr/geoffreyCoulaud/pinryReborn/api/presentation/quarkus/config/AuthConfig.kt`
- Create: `api-application/src/main/kotlin/fr/geoffreyCoulaud/pinryReborn/api/application/wiring/PasswordChangerProducer.kt`
- Modify: `api-application/src/main/resources/application.properties`
- Modify: `api-application/src/test/resources/application.properties`

**Interfaces:**
- Consumes: `ThrottledError` (Task 1), `PasswordChangedTooSoonError`, `PasswordChangeCollisionError`, `PasswordChangeCollisionException` (Task 2).
- Produces: `PasswordChanger` constructed with `minimumInterval: Duration` (consumed by `MeController` via the producer, and by Task 5's integration test).

**Note on the existing tests:** `PasswordChangerTest` today sets `current.createdAt = Instant.now()` while `clock.now()` returns a fixed past instant. Once the interval check reads `current.createdAt`, that mismatch refuses every change. Step 1 replaces `current` with a controlled instant, so the pre-existing tests stay green. The new `changePassword` also reads `clock.now()` before the history check, so the existing `Given a previously-used new password` test (which passes reauthentication but never stubs the clock) must gain a clock stub or it throws `MockKException` before it reaches the history check. The other pre-existing tests are unaffected (the valid-change test already stubs the clock; the two reauth-failure tests throw before the clock is read).

- [ ] **Step 1: Refactor the test fixture, then write the failing tests**

In `PasswordChangerTest`, change the constructor call and the `current` fixture (imports: `java.time.Duration`, the new error and exception types):

```kotlin
    private val minimumInterval = Duration.ofSeconds(30)
    private val changer = PasswordChanger(passwords, hasher, sessionRevoker, tx, clock, minimumInterval)

    private val now = Instant.parse("2026-07-29T00:00:00Z")
    private val user = User(id = randomUUID(), name = "u", createdAt = Instant.now())
    // One minute before `now`, so the pre-existing tests sit outside the 30 s interval.
    private val current =
        HashedPassword("current-hash", PasswordHashAlgorithm.BCRYPT, createdAt = now.minus(Duration.ofMinutes(1)))
```

In the existing `Given a previously-used new password` test, add the clock stub the interval check now reads (before the history check runs):

```kotlin
    @Test
    fun `Given a previously-used new password, Then it throws PasswordPreviouslyUsedError`() {
        every { passwords.findCurrentPasswordHash(user) } returns current
        every { hasher.matches("old", current) } returns true
        every { clock.now() } returns now
        val older = HashedPassword("older", PasswordHashAlgorithm.BCRYPT, createdAt = Instant.now())
        every { passwords.findAllPasswordHashesForUser(user) } returns listOf(current, older)
        every { hasher.matches("reused", current) } returns false
        every { hasher.matches("reused", older) } returns true
        assertThrows<PasswordPreviouslyUsedError> { changer.changePassword(user, "old", "reused") }
    }
```

Add the new tests:

```kotlin
    @Test
    fun `Given a change inside the minimum interval, Then it throws PasswordChangedTooSoonError with the remaining seconds`() {
        // Given
        val recent = HashedPassword("h", PasswordHashAlgorithm.BCRYPT, createdAt = now.minusSeconds(10))
        every { passwords.findCurrentPasswordHash(user) } returns recent
        every { hasher.matches("old", recent) } returns true
        every { clock.now() } returns now
        // When / Then: 30 s interval, 10 s elapsed -> 20 s remaining
        val error = assertThrows<PasswordChangedTooSoonError> { changer.changePassword(user, "old", "new") }
        assertEquals(20, error.retryAfterSeconds)
        verify(exactly = 0) { passwords.saveUserPasswordHash(any(), any()) }
    }

    @Test
    fun `Given a change at the interval boundary, Then it succeeds`() {
        // Given: createdAt exactly `interval` ago is allowed (the refusal is strictly inside)
        every { tx.inTransaction(any<() -> Any?>()) } answers { (firstArg<() -> Any?>())() }
        val boundary = HashedPassword("h", PasswordHashAlgorithm.BCRYPT, createdAt = now.minusSeconds(30))
        every { passwords.findCurrentPasswordHash(user) } returns boundary
        every { hasher.matches("old", boundary) } returns true
        every { passwords.findAllPasswordHashesForUser(user) } returns listOf(boundary)
        every { hasher.matches("new", boundary) } returns false
        every { clock.now() } returns now
        every { hasher.hash("new", now) } returns HashedPassword("new-hash", PasswordHashAlgorithm.BCRYPT, createdAt = now)
        every { passwords.saveUserPasswordHash(any(), any()) } returns
            HashedPassword("new-hash", PasswordHashAlgorithm.BCRYPT, createdAt = now)
        // When / Then
        changer.changePassword(user, "old", "new")
        verify { passwords.saveUserPasswordHash(any(), any()) }
    }

    @Test
    fun `Given a failed reauthentication, Then no hash is written so a later change is still allowed`() {
        // Given: a wrong current password fails and writes nothing
        every { passwords.findCurrentPasswordHash(user) } returns current
        every { hasher.matches("bad", current) } returns false
        assertThrows<ReauthenticationError> { changer.changePassword(user, "bad", "new") }
        verify(exactly = 0) { passwords.saveUserPasswordHash(any(), any()) }
        // Then: the interval is still measured from `current`'s createdAt (outside the interval),
        // so a correct change afterwards still succeeds. This is D10: the limit counts successful
        // changes, not attempts.
        every { tx.inTransaction(any<() -> Any?>()) } answers { (firstArg<() -> Any?>())() }
        every { hasher.matches("old", current) } returns true
        every { passwords.findAllPasswordHashesForUser(user) } returns listOf(current)
        every { hasher.matches("new", current) } returns false
        every { clock.now() } returns now
        every { hasher.hash("new", now) } returns HashedPassword("new-hash", PasswordHashAlgorithm.BCRYPT, createdAt = now)
        every { passwords.saveUserPasswordHash(any(), any()) } returns
            HashedPassword("new-hash", PasswordHashAlgorithm.BCRYPT, createdAt = now)
        changer.changePassword(user, "old", "new")
    }

    @Test
    fun `Given the repository signals a collision, Then PasswordChanger rethrows PasswordChangeCollisionError`() {
        // Given
        every { tx.inTransaction(any<() -> Any?>()) } answers { (firstArg<() -> Any?>())() }
        every { passwords.findCurrentPasswordHash(user) } returns current
        every { hasher.matches("old", current) } returns true
        every { passwords.findAllPasswordHashesForUser(user) } returns listOf(current)
        every { hasher.matches("new", current) } returns false
        every { clock.now() } returns now
        every { hasher.hash("new", now) } returns HashedPassword("new-hash", PasswordHashAlgorithm.BCRYPT, createdAt = now)
        every { passwords.saveUserPasswordHash(any(), any()) } throws PasswordChangeCollisionException()
        // When / Then
        assertThrows<PasswordChangeCollisionError> { changer.changePassword(user, "old", "new") }
    }
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :api-usecases:test --tests "PasswordChangerTest"`
Expected: FAIL to compile: `PasswordChanger` has no `minimumInterval` parameter, and `PasswordChangedTooSoonError` / `PasswordChangeCollisionError` / `PasswordChangeCollisionException` are used before this task wires them into `PasswordChanger`. Paste the compile error.

- [ ] **Step 3: Commit the failing tests alone**

```bash
git add api-usecases/src/test/.../PasswordChangerTest.kt
git commit -m "test(auth): password-change interval refusal and collision rethrow

<paste the command and the compile failure from step 2>"
```

- [ ] **Step 4: Implement the interval, the catch, the config entry, and the producer**

Replace `PasswordChanger.kt` (drop `@ApplicationScoped` and its import; add `Duration`, the two errors, and the domain exception):

```kotlin
package fr.geoffreyCoulaud.pinryReborn.api.usecases

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.User
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.TransactionRunner
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.UserPasswordHashRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.security.PasswordChangeCollisionException
import fr.geoffreyCoulaud.pinryReborn.api.domain.security.PasswordHasher
import fr.geoffreyCoulaud.pinryReborn.api.domain.time.Clock
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.PasswordChangedTooSoonError
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.PasswordChangeCollisionError
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.PasswordPreviouslyUsedError
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.ReauthenticationError
import java.time.Duration

@Suppress("LongParameterList")
class PasswordChanger(
    private val userPasswordRepository: UserPasswordHashRepositoryInterface,
    private val passwordHasher: PasswordHasher,
    private val sessionRevoker: SessionRevoker,
    private val transactionRunner: TransactionRunner,
    private val clock: Clock,
    private val minimumInterval: Duration,
) {
    fun changePassword(user: User, currentPassword: String, newPassword: String) {
        val current = userPasswordRepository.findCurrentPasswordHash(user)
        if (current == null || !passwordHasher.matches(currentPassword, current)) throw ReauthenticationError()
        val now = clock.now()
        if (current.createdAt.isAfter(now.minus(minimumInterval))) {
            val retryAfterSeconds =
                Duration.between(now.minus(minimumInterval), current.createdAt).seconds.coerceAtLeast(1)
            throw PasswordChangedTooSoonError(retryAfterSeconds)
        }
        val history = userPasswordRepository.findAllPasswordHashesForUser(user)
        if (history.any { passwordHasher.matches(newPassword, it) }) throw PasswordPreviouslyUsedError()
        transactionRunner.inTransaction {
            try {
                userPasswordRepository.saveUserPasswordHash(user, passwordHasher.hash(newPassword, now))
            } catch (error: PasswordChangeCollisionException) {
                throw PasswordChangeCollisionError(error)
            }
            sessionRevoker.revokeAll(user)
        }
    }
}
```

In `AuthConfig.kt`, add the entry (default `PT30S`, beside `ephemeralTtl`):

```kotlin
    @WithDefault("PT30S")
    fun passwordChangeMinimumInterval(): Duration
```

Create `PasswordChangerProducer.kt`:

```kotlin
package fr.geoffreyCoulaud.pinryReborn.api.application.wiring

import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.TransactionRunner
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.UserPasswordHashRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.security.PasswordHasher
import fr.geoffreyCoulaud.pinryReborn.api.domain.time.Clock
import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.config.AuthConfig
import fr.geoffreyCoulaud.pinryReborn.api.usecases.PasswordChanger
import fr.geoffreyCoulaud.pinryReborn.api.usecases.SessionRevoker
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Produces

/**
 * Constructs [PasswordChanger] with its configured minimum interval. The use case takes a raw
 * `Duration` (the `UserDataExportRequester` precedent) rather than the `AuthConfig` interface, so
 * `api-usecases` stays free of configuration; the composition root is the single place that reads it.
 */
@ApplicationScoped
class PasswordChangerProducer {
    @Produces
    @ApplicationScoped
    fun passwordChanger(
        userPasswordRepository: UserPasswordHashRepositoryInterface,
        passwordHasher: PasswordHasher,
        sessionRevoker: SessionRevoker,
        transactionRunner: TransactionRunner,
        clock: Clock,
        config: AuthConfig,
    ): PasswordChanger =
        PasswordChanger(
            userPasswordRepository,
            passwordHasher,
            sessionRevoker,
            transactionRunner,
            clock,
            minimumInterval = config.passwordChangeMinimumInterval(),
        )
}
```

In `api-application/src/main/resources/application.properties`, add beside the other `auth.*` keys:

```properties
auth.password_change_minimum_interval=PT30S
```

In `api-application/src/test/resources/application.properties`, disable the interval so the existing `MePasswordIntegrationTest` success paths stay green (the seed hash is fresh at signup):

```properties
auth.password_change_minimum_interval=PT0S
```

- [ ] **Step 5: Run the use-case tests, then the gate**

Run: `./gradlew :api-usecases:test --tests "PasswordChangerTest"` then `./gradlew gate`
Expected: PASS. The gate also confirms the application still starts (the producer satisfies `MeController`'s `PasswordChanger` injection) and the existing `MePasswordIntegrationTest` passes under `PT0S`.

- [ ] **Step 6: Commit the implementation**

```bash
git add -A
git commit -m "feat(auth): rate-limit password change and wire the interval through a producer"
```

---

## Task 4: Enforce the unique constraint and translate its violation

Adds the composite unique index and the adapter translation. The constraint is what makes the collision in Task 3's catch actually reachable.

**Files:**
- Modify: `api-persistence-sqlite/src/main/kotlin/fr/geoffreyCoulaud/pinryReborn/api/persistence/sqlite/models/UserPasswordHashModel.kt`
- Modify: `api-persistence-sqlite/src/main/kotlin/fr/geoffreyCoulaud/pinryReborn/api/persistence/sqlite/repositories/UserPasswordHashRepository.kt`
- Create: `api-persistence-sqlite/src/main/resources/dbmigration/1.18.sql` (and its model)
- Test: `api-persistence-sqlite/src/test/kotlin/fr/geoffreyCoulaud/pinryReborn/api/persistence/sqlite/UserPasswordHashRepositoryTest.kt`

**Interfaces:**
- Consumes: `PasswordChangeCollisionException` (Task 2).

- [ ] **Step 1: Write the failing test**

Add to `UserPasswordHashRepositoryTest` (import `PasswordChangeCollisionException`):

```kotlin
    @Test
    fun `Given two hashes at the same instant, Then the second is refused as a PasswordChangeCollisionException`() {
        // Given
        val user = user()
        repository.saveUserPasswordHash(user, hash("first", anInstant))
        // When / Then
        assertThrows<PasswordChangeCollisionException> {
            repository.saveUserPasswordHash(user, hash("second", anInstant))
        }
    }
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :api-persistence-sqlite:test --tests "UserPasswordHashRepositoryTest"`
Expected: FAIL. Today there is no unique index, so the second insert succeeds and `assertThrows` sees no exception (it fails with "Expected ... to be thrown, but nothing was thrown"). Paste the failure.

- [ ] **Step 3: Commit the failing test alone**

```bash
git add api-persistence-sqlite/src/test/.../UserPasswordHashRepositoryTest.kt
git commit -m "test(persistence): same-instant second hash refused as a collision

<paste the command and the failure from step 2>"
```

- [ ] **Step 4: Add the index, generate the migration, translate the violation**

On `UserPasswordHashModel`, add the composite unique index (import `io.ebean.annotation.Index`). Place the annotation on the class:

```kotlin
@Entity
@Table(name = "user_password_hashes")
@Index(name = "ix_user_password_hashes_user_created", columnNames = ["user_id", "when_created"], unique = true)
class UserPasswordHashModel(
    @ManyToOne var user: UserModel,
    var hash: String,
    @Enumerated(EnumType.STRING)
    var algorithm: PasswordHashAlgorithm,
    @Column(name = "when_created") var createdAt: Instant,
) : BaseModel()
```

Generate the migration (the generator is a `JavaExec`; no pending drops here, so a plain run):

```bash
./gradlew :api-persistence-sqlite:generateDbMigration
```

Read the generated migration file before committing (Ebean names it `1.18.sql` or `1.18__<desc>.sql`, as it did for `1.14__dropsFor_1.13`). Confirm it contains a `create unique index ix_user_password_hashes_user_created on user_password_hashes (user_id, when_created)` and nothing else. If Ebean emits a different column list or a table rebuild, stop and reconcile against the Ebean documentation (`ebean.io/docs/mapping` and `/setup/dbmigration`) before proceeding; do not commit a migration you have not read.

In `UserPasswordHashRepository.saveUserPasswordHash`, keep the user lookup OUTSIDE the try (it throws `UserModelDoesNotExistError`, which is a `PersistenceException`) and translate only the insert failure (import `jakarta.persistence.PersistenceException` and `PasswordChangeCollisionException`):

```kotlin
    override fun saveUserPasswordHash(
        user: User,
        hashedPassword: HashedPassword,
    ): HashedPassword {
        // resolve() throws UserModelDoesNotExistError (a PersistenceException) for a tombstoned or
        // absent user; it stays outside the try so that is never reported as a collision.
        val hashedPasswordModel = hashedPassword.toModel(ActiveUserModels.resolve(user.id))
        return try {
            sqlRepository.saveAndReturn(hashedPasswordModel).toDomain()
        } catch (error: PersistenceException) {
            throw PasswordChangeCollisionException(cause = error)
        }
    }
```

- [ ] **Step 5: Run to verify it passes**

Run: `./gradlew :api-persistence-sqlite:test --tests "UserPasswordHashRepositoryTest"`
Expected: PASS. The existing tombstoned-user and nonexistent-user tests still pass (their `UserModelDoesNotExistError` is thrown by `resolve`, outside the try).

- [ ] **Step 6: Commit the implementation**

```bash
git add -A
git commit -m "feat(persistence): unique (user_id, created_at) index and collision translation"
```

---

## Task 5: End-to-end 429 contract test on a positive interval

The unit tests prove the interval logic with a controlled clock; this test proves the wiring (producer, `AuthConfig`, the interval) and the 429 wire format end to end. It uses a `QuarkusTestProfile` (an established pattern in this module) to set a positive interval for one class while the module default stays `PT0S`.

**Files:**
- Create: `api-application/src/test/kotlin/fr/geoffreyCoulaud/pinryReborn/api/application/MePasswordRateLimitIntegrationTest.kt`

**Interfaces:**
- Consumes: the wired `PasswordChanger` (Task 3), `PASSWORD_CHANGED_TOO_SOON` (Task 2).

- [ ] **Step 1: Write the failing test**

Create `MePasswordRateLimitIntegrationTest.kt`:

```kotlin
package fr.geoffreyCoulaud.pinryReborn.api.application

import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured.given
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.notNullValue
import org.junit.jupiter.api.Test

class MePasswordRateLimitTestProfile : QuarkusTestProfile {
    override fun getConfigOverrides(): Map<String, String> =
        mapOf("auth.password_change_minimum_interval" to "PT1H")
}

@QuarkusTest
@TestProfile(MePasswordRateLimitTestProfile::class)
class MePasswordRateLimitIntegrationTest : IntegrationTest() {
    private fun changeBody(current: String, next: String) =
        """{"currentPassword":"$current","newPassword":"$next"}"""

    @Test
    fun `Given a change inside the minimum interval, Then 429 with Retry-After and PASSWORD_CHANGED_TOO_SOON`() {
        // Given: the seed hash from signup is fresh, and the profile pins a 1 h interval
        val auth = createAuthenticatedUser(password = "password123")
        // When / Then
        given().authenticatedAs(auth).contentType("application/json")
            .body(changeBody("password123", "newpassword1"))
            .put("/api/v1/me/password")
            .then()
            .statusCode(429)
            .header("Retry-After", notNullValue())
            .body("code", equalTo("PASSWORD_CHANGED_TOO_SOON"))
    }
}
```

- [ ] **Step 2: Run to verify the behaviour**

Run: `./gradlew :api-application:test --tests "MePasswordRateLimitIntegrationTest"`
Expected: PASS once Tasks 1 to 4 are in. (If run before Task 3 it fails to start: `PasswordChanger` is unsatisfied. If the header is missing it fails on the `Retry-After` assertion, which is the regression guard for Task 1's centralisation.) This test is a contract guard; it is committed together with its assertion rather than red-then-green in isolation, because the red is "the feature is not built yet", already covered by Tasks 1 to 4. This is an accepted exception to the Global Constraint's red-first rule, recorded here so the implementer does not force a red-then-green cycle on a test that cannot compile in isolation.

- [ ] **Step 3: Commit the test**

```bash
git add api-application/src/test/.../MePasswordRateLimitIntegrationTest.kt
git commit -m "test(auth): end-to-end 429 on a password change inside the interval"
```

---

## Verify (whole branch)

After Task 5, before opening a pull request:

- [ ] Run the full gate: `./gradlew gate`. Expected: green, 100% branch coverage per package.
- [ ] Confirm `git status --porcelain` is clean (the migration files and the regenerated `openapi.json`, if any, are committed).
- [ ] Skim the branch diff for em dashes; the gate's `checkNoLongDashes` is the authority.

## Self-review notes (spec coverage)

- Constraint (spec 4.1): Task 4.
- Interval (spec 4.2): Task 3 (logic) + Task 3 (wiring) + Task 5 (end to end).
- Error codes and 409 translation (spec 4.3): Task 2 (codes/classes/exception) + Task 4 (translation).
- Centralised 429 rendering (spec 4.4): Task 1.
- Migration (spec 4.5): Task 4.
- Tests (spec section 5): Task 4 (1), Task 3 (2, 3), Task 1 + Task 2 (4), Task 1 (5).
- No new ADR: none of the tasks adds one; D8 to D10 are the parent ADR's, D21 to D23 are in the spec.

A known consequence to record in the spec's risks at wrap (not a code change): the interval counts the signup hash, so under the production default a user cannot change their password for 30 s after signing up. This follows from D10 and D21 (the interval reads the current hash's `createdAt`, and the seed hash is a successful write); the test override `PT0S` keeps the existing signup-then-change integration tests green.
