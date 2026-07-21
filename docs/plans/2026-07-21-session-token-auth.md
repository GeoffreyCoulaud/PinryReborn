# Session-token authentication Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace HTTP Basic with opaque, server-stored, revocable session tokens (Bearer): body-login (= credential check), renewable with atomic rotation, absolute expiry with a persistent/ephemeral "keep me logged in" choice, logout current + all, `GET /me`, `GET /sessions/current`.

**Architecture:** A random 256-bit token is issued at login and stored as a SHA-256 hash. A custom Quarkus `HttpAuthenticationMechanism` reads `Authorization: Bearer <token>`, a custom `IdentityProvider<TokenAuthenticationRequest>` verifies it through a dedicated `SessionTokenAuthenticator` use case (hash → lookup → expiry check via the `Clock` port), and stashes `user`/`userId`/`sessionToken` on the `SecurityIdentity`. HTTP Basic (`quarkus.http.auth.basic`, `BasicAuthIdentityProvider`) is removed; `UserAuthenticator` stays password-only and is called by the login use case. Spec: `docs/specs/2026-07-21-session-token-auth.md`.

**Tech Stack:** Kotlin, Quarkus 3 (Jakarta REST + Quarkus Security, custom `HttpAuthenticationMechanism`), Ebean 19 (Kotlin query beans, SQLite), SmallRye Config `@ConfigMapping`, JUnit 5, MockK, REST Assured, JDK 25.

## Global Constraints

Every task's requirements implicitly include these. Values copied from AGENTS.md, the boards plan, and the spec.

- **100% branch coverage per package**, gated by Kover on **every module except `api-application`**. The persistence `...models` package (and generated `...models.query.Q*` beans) are **excluded**, so `SessionTokenModel` needs no test; mappers, repositories, use cases, and presentation code (incl. the auth mechanism, identity provider, exception mappers, controllers) **do**. Exercise **both** sides of every conditional. Kover verifies **branch** coverage: branchless glue (the config interface, the CDI producer) has no branch to cover and is exercised end-to-end by the Task 9 integration tests.
- **Strict TDD:** write the failing test first, run it, watch it fail, then the minimal implementation. Tests are the spec.
- **Clean / Hexagonal:** `api-domain` pure (no I/O, **no clock, no config, no `SecureRandom`**); `api-usecases` → domain only; `api-persistence-sqlite` → domain + utilities; `api-presentation-quarkus` → usecases + domain; `api-application` wires adapters. Never poke through layers. The `Clock` port lives in domain, `SystemClock` impl in presentation; `TokenGenerator` port lives in domain, `SecureTokenGenerator` impl in presentation.
- **No top-level functions** (Kotlin); extension functions are the only exception. `TokenHasher` is an `object`.
- **English everywhere** (identifiers + prose). **No em-dashes** in any user-facing string (JSON details, headers, log messages).
- **Conventional commits** (`feat(domain):`, `feat(usecase):`, `feat(persistence):`, `feat(presentation):`, `test:`, `chore:`).
- **Kover data-class trap:** a **non-null defaulted** data-class param (e.g. `= false`, `= emptyList()`) generates a synthetic constructor branch Kover's 100% gate flags; a **nullable `= null`** default is fine. Therefore `SessionToken.persistent: Boolean` and `ExistingSessionOutputDto.persistent: Boolean` have **no default**; `SessionCreationInputDto.rememberMe: Boolean?` is **nullable with no default** (absent JSON → `null`, coalesced to `false` in the controller).
- **detekt:** `ReturnCount` max 2, **no `@Suppress("ReturnCount")`** (restructure into helpers). Prefer **statement-form `when`**. A cohesive adapter tripping `TooManyFunctions` may be `@Suppress`ed with a rationale comment (precedent: `PinRepository`).
- **Per-task gate (JDK 25 toolchain):** `./gradlew :<module>:detekt :<module>:test :<module>:koverVerify`. Per-module `test`/`koverVerify` auto-use the JDK 25 toolchain. The full `build`, `generateDbMigration`, and `quarkusDev` need `JAVA_HOME=/home/geoffrey/.gradle/jdks/eclipse_adoptium-25-amd64-linux.2`.
- **Intermediate red build is expected.** Task 6 deletes `BasicAuthIdentityProvider` while `quarkus.http.auth.basic=true` is still set (removed in Task 8), so the **full** build / `api-application` integration tests are red from Task 6 until **Task 8** migrates every test to Bearer and drops the flag. Per-module gates (Tasks 1-7) stay green as each lands. This mirrors the boards sub-project's wiring window. Do not push until Task 8 is green.
- **Quarkus version sensitivity:** the exact `HttpAuthenticationMechanism` method set (notably `getCredentialTransport`, which is `getCredentialTransport(RoutingContext): Uni<HttpCredentialTransport>` in some 3.x versions and `getCredentialTransport(): Uni<HttpCredentialTransport>` in others) depends on the project's Quarkus BOM. The compiler flags any mismatch immediately; adjust the override signatures to match the compiled interface (confirm via context7 for the project's version). The Task 9 integration tests are the runtime proof.
- **Migration:** last shipped Ebean migration is **1.7** (boards); this adds **1.8** (purely additive: one new table). Generate via `JAVA_HOME=... ./gradlew :api-persistence-sqlite:generateDbMigration`.

---

## File Structure

**api-domain**
- Create `entities/SessionToken.kt`, `entities/IssuedSession.kt`
- Create `security/TokenGenerator.kt` (port), `security/SessionExpiryPolicy.kt` (pure class)
- Create `repositories/SessionTokenRepositoryInterface.kt`

**api-persistence-sqlite**
- Create `models/SessionTokenModel.kt`, `mappers/SessionTokenModelMapper.kt`, `repositories/SessionTokenRepository.kt`
- Create `resources/dbmigration/1.8.sql` (+ generated `model/1.8.model.xml`)

**api-usecases**
- Create `TokenHasher.kt` (object), `SessionTokenAuthenticator.kt`, `SessionCreator.kt`, `SessionRenewer.kt`, `SessionRevoker.kt`
- Create `exceptions/SessionAuthenticationError.kt`

**api-presentation-quarkus**
- Create `dtos/input/SessionCreationInputDto.kt`; `dtos/output/CreatedSessionOutputDto.kt`, `dtos/output/ExistingSessionOutputDto.kt`; `mappers/SessionDtoMapper.kt`
- Create `security/SecureTokenGenerator.kt`, `security/AuthRuntimeProducers.kt`, `security/BearerAuthenticationMechanism.kt`, `security/BearerTokenIdentityProvider.kt`, `security/SessionExpiredException.kt`; `config/AuthConfig.kt`; `mappers/SessionExpiredExceptionMapper.kt`
- Create `controllers/SessionController.kt`, `controllers/MeController.kt`
- Modify `security/SecurityIdentityExtensions.kt` (add `getSessionToken`), `mappers/ProblemResponses.kt` (Bearer challenge), `mappers/AuthenticationFailedExceptionMapper.kt`, `mappers/UnauthorizedExceptionMapper.kt`
- Delete `security/BasicAuthIdentityProvider.kt` (+ its test)

**api-application**
- Modify `src/main/resources/application.properties` (drop `quarkus.http.auth.basic`, add `auth.*`)
- Modify `src/test/.../IntegrationTest.kt` (unified auth helper); migrate the 15 Basic-auth integration tests to Bearer
- Create session-auth integration tests; regenerate `docs/openapi.json`

---

## Task 1: Domain foundations (SessionToken, IssuedSession, TokenGenerator, SessionExpiryPolicy, repo port)

**Files:**
- Create: `api-domain/src/main/kotlin/fr/geoffreyCoulaud/pinryReborn/api/domain/entities/SessionToken.kt`
- Create: `api-domain/.../domain/entities/IssuedSession.kt`
- Create: `api-domain/.../domain/security/TokenGenerator.kt`
- Create: `api-domain/.../domain/security/SessionExpiryPolicy.kt`
- Create: `api-domain/.../domain/repositories/SessionTokenRepositoryInterface.kt`
- Test: `api-domain/src/test/kotlin/.../domain/security/SessionExpiryPolicyTest.kt`

**Interfaces:**
- Produces: `SessionToken(id, user, expiresAt, persistent)`; `IssuedSession(token, expiresAt, renewAfter)`; `TokenGenerator { generateToken(): String }`; `SessionExpiryPolicy(persistentTtl, ephemeralTtl, renewThreshold)` with `expiryFrom(now, persistent): Instant` and `renewAfterFor(expiresAt, persistent): Instant`; `SessionTokenRepositoryInterface` (`saveSessionToken`, `findByTokenHash`, `deleteById`, `deleteAllForUser`).

- [ ] **Step 1: Create the entities and the port**

`entities/SessionToken.kt`:
```kotlin
package fr.geoffreyCoulaud.pinryReborn.api.domain.entities

import java.time.Instant
import java.util.UUID

data class SessionToken(
    override val id: UUID,
    val user: User,
    val expiresAt: Instant,
    val persistent: Boolean,
) : Identifiable
```
`entities/IssuedSession.kt`:
```kotlin
package fr.geoffreyCoulaud.pinryReborn.api.domain.entities

import java.time.Instant

data class IssuedSession(
    val token: String,
    val expiresAt: Instant,
    val renewAfter: Instant,
)
```
`security/TokenGenerator.kt`:
```kotlin
package fr.geoffreyCoulaud.pinryReborn.api.domain.security

interface TokenGenerator {
    /** Generate a fresh, high-entropy, URL-safe opaque token string. */
    fun generateToken(): String
}
```
`repositories/SessionTokenRepositoryInterface.kt`:
```kotlin
package fr.geoffreyCoulaud.pinryReborn.api.domain.repositories

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.SessionToken
import java.util.UUID

interface SessionTokenRepositoryInterface {
    /** Persist a new session token, storing [tokenHash] as its lookup key. Returns the saved token. */
    fun saveSessionToken(sessionToken: SessionToken, tokenHash: String): SessionToken

    /** Find a session token by the hash of its plaintext, or null. */
    fun findByTokenHash(tokenHash: String): SessionToken?

    /** Delete a session token by id. No-op if absent. */
    fun deleteById(id: UUID)

    /** Delete every session token belonging to the given user. */
    fun deleteAllForUser(userId: UUID)
}
```

- [ ] **Step 2: Write the failing `SessionExpiryPolicy` test**

`security/SessionExpiryPolicyTest.kt`:
```kotlin
package fr.geoffreyCoulaud.pinryReborn.api.domain.security

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

class SessionExpiryPolicyTest {
    private val now = Instant.parse("2026-07-21T00:00:00Z")
    private val policy = SessionExpiryPolicy(
        persistentTtl = Duration.ofDays(30),
        ephemeralTtl = Duration.ofHours(12),
        renewThreshold = 0.75,
    )

    @Test
    fun `Given a persistent session, Then expiryFrom adds the persistent TTL`() {
        assertEquals(now.plus(Duration.ofDays(30)), policy.expiryFrom(now, persistent = true))
    }

    @Test
    fun `Given an ephemeral session, Then expiryFrom adds the ephemeral TTL`() {
        assertEquals(now.plus(Duration.ofHours(12)), policy.expiryFrom(now, persistent = false))
    }

    @Test
    fun `Given a persistent expiry, Then renewAfterFor is expiry minus 25 percent of the persistent TTL`() {
        val expiresAt = now.plus(Duration.ofDays(30))
        // 30d * (1 - 0.75) = 7.5d before expiry
        assertEquals(expiresAt.minus(Duration.ofHours(180)), policy.renewAfterFor(expiresAt, persistent = true))
    }

    @Test
    fun `Given an ephemeral expiry, Then renewAfterFor is expiry minus 25 percent of the ephemeral TTL`() {
        val expiresAt = now.plus(Duration.ofHours(12))
        // 12h * (1 - 0.75) = 3h before expiry
        assertEquals(expiresAt.minus(Duration.ofHours(3)), policy.renewAfterFor(expiresAt, persistent = false))
    }
}
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `./gradlew :api-domain:test --tests "*SessionExpiryPolicyTest"`
Expected: FAIL (`SessionExpiryPolicy` not defined).

- [ ] **Step 4: Implement `SessionExpiryPolicy`**

`security/SessionExpiryPolicy.kt`:
```kotlin
package fr.geoffreyCoulaud.pinryReborn.api.domain.security

import java.time.Duration
import java.time.Instant

/**
 * Pure expiry policy. `expiryFrom` is `now + ttl`; `renewAfterFor` is the recommended soft-renewal
 * instant, `expiresAt - ttl * (1 - renewThreshold)` (renew once renewThreshold of the lifetime has
 * elapsed). Defining renewAfter from expiresAt lets it be recomputed for an already-stored token.
 */
class SessionExpiryPolicy(
    private val persistentTtl: Duration,
    private val ephemeralTtl: Duration,
    private val renewThreshold: Double,
) {
    private fun ttl(persistent: Boolean): Duration = if (persistent) persistentTtl else ephemeralTtl

    fun expiryFrom(now: Instant, persistent: Boolean): Instant = now.plus(ttl(persistent))

    fun renewAfterFor(expiresAt: Instant, persistent: Boolean): Instant {
        val ttlMillis = ttl(persistent).toMillis()
        val remainingBeforeRenewMillis = (ttlMillis * (1.0 - renewThreshold)).toLong()
        return expiresAt.minusMillis(remainingBeforeRenewMillis)
    }
}
```

- [ ] **Step 5: Run the gate**

Run: `./gradlew :api-domain:detekt :api-domain:test :api-domain:koverVerify`
Expected: PASS. (`SessionToken`/`IssuedSession` are branchless data classes; `TokenGenerator` and the repo interface have no bodies; the `if (persistent)` in `ttl` is covered by the four policy tests.)

- [ ] **Step 6: Commit**

```bash
git add api-domain/src
git commit -m "feat(domain): SessionToken, IssuedSession, TokenGenerator port, SessionExpiryPolicy, repo port"
```

---

## Task 2: Persistence (model, mapper, repository, migration 1.8)

**Files:**
- Create: `api-persistence-sqlite/.../models/SessionTokenModel.kt`, `.../mappers/SessionTokenModelMapper.kt`, `.../repositories/SessionTokenRepository.kt`
- Create: `api-persistence-sqlite/src/main/resources/dbmigration/1.8.sql` (+ generated model xml)
- Test: `api-persistence-sqlite/src/test/kotlin/.../repositories/SessionTokenRepositoryTest.kt`

**Interfaces:**
- Consumes: `SessionToken`, `SessionTokenRepositoryInterface` (Task 1); `UserModel`, `QUserModel`, `ModelRepository`, `UserModelDoesNotExistError`, `UserModelMapper` (existing).
- Produces: `SessionTokenModel` (`@Table("session_tokens")`); `SessionTokenModelMapper.toDomain`; `SessionTokenRepository : SessionTokenRepositoryInterface` (`@ApplicationScoped`, auto-wired by Arc); `QSessionTokenModel` (generated at build).

- [ ] **Step 1: Create the model**

`models/SessionTokenModel.kt`:
```kotlin
package fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.models

import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.models.bases.BaseModel
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "session_tokens")
class SessionTokenModel(
    id: UUID,
    @ManyToOne var user: UserModel,
    @Column(unique = true) var tokenHash: String,
    var expiresAt: Instant,
    var persistent: Boolean,
) : BaseModel(id = id)
```
Note: `SessionTokenModel` extends `BaseModel` (not `AuthoredBaseModel`) so the FK column is `user_id`, not `author_id` (a session token is scoped to a user, but "author" is the wrong noun).

- [ ] **Step 2: Create the mapper**

`mappers/SessionTokenModelMapper.kt`:
```kotlin
package fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.mappers

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.SessionToken
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.mappers.UserModelMapper.toDomain
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.models.SessionTokenModel

object SessionTokenModelMapper {
    fun SessionTokenModel.toDomain() =
        SessionToken(
            id = id,
            user = user.toDomain(),
            expiresAt = expiresAt,
            persistent = persistent,
        )
}
```
(No `toModel`: the repository builds the model, since it needs the resolved `UserModel` and the externally-computed `tokenHash`, neither of which live on the domain `SessionToken`.)

- [ ] **Step 3: Write the failing repository tests**

`repositories/SessionTokenRepositoryTest.kt` (extend `RepositoryTest`). Seed users with the local helper pattern (`QUserModel`/`saveAndReturn`, mirroring the other `*RepositoryTest`s):
```kotlin
package fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.repositories

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.SessionToken
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.User
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.RepositoryTest
import fr.geoffreyCoulaud.pinryReborn.api.utilities.createRandomString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID.randomUUID

class SessionTokenRepositoryTest : RepositoryTest() {
    private val repository = SessionTokenRepository(database = database)
    private val userRepository = UserRepository(database = database)

    private fun createUser(): User =
        userRepository.saveUser(User(id = randomUUID(), name = createRandomString()))

    private fun sessionToken(user: User, persistent: Boolean = false, expiresAt: Instant = Instant.now().plusSeconds(3600)) =
        SessionToken(id = randomUUID(), user = user, expiresAt = expiresAt, persistent = persistent)

    @Test
    fun `Given a saved token, Then findByTokenHash returns it with its user and fields`() {
        // Given
        val user = createUser()
        val expiresAt = Instant.now().plus(30, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MILLIS)
        val token = sessionToken(user, persistent = true, expiresAt = expiresAt)

        // When
        repository.saveSessionToken(token, tokenHash = "hash-a")
        val loaded = repository.findByTokenHash("hash-a")

        // Then
        assertEquals(token.id, loaded!!.id)
        assertEquals(user.id, loaded.user.id)
        assertEquals(expiresAt, loaded.expiresAt.truncatedTo(ChronoUnit.MILLIS))
        assertEquals(true, loaded.persistent)
    }

    @Test
    fun `Given no token for a hash, Then findByTokenHash returns null`() {
        assertNull(repository.findByTokenHash("absent"))
    }

    @Test
    fun `Given a saved token, Then deleteById removes it`() {
        val user = createUser()
        val token = sessionToken(user)
        repository.saveSessionToken(token, tokenHash = "hash-b")
        repository.deleteById(token.id)
        assertNull(repository.findByTokenHash("hash-b"))
    }

    @Test
    fun `Given several tokens for a user, Then deleteAllForUser removes them all`() {
        val user = createUser()
        repository.saveSessionToken(sessionToken(user), tokenHash = "h1")
        repository.saveSessionToken(sessionToken(user), tokenHash = "h2")
        repository.deleteAllForUser(user.id)
        assertNull(repository.findByTokenHash("h1"))
        assertNull(repository.findByTokenHash("h2"))
    }

    @Test
    fun `Given tokens for two users, Then deleteAllForUser only removes the target user's tokens`() {
        val userA = createUser()
        val userB = createUser()
        repository.saveSessionToken(sessionToken(userA), tokenHash = "ha")
        repository.saveSessionToken(sessionToken(userB), tokenHash = "hb")
        repository.deleteAllForUser(userA.id)
        assertNull(repository.findByTokenHash("ha"))
        assertEquals("hb", repository.findByTokenHash("hb")?.let { "hb" })
    }
}
```

- [ ] **Step 4: Run tests to verify they fail**

Run: `./gradlew :api-persistence-sqlite:test --tests "*SessionTokenRepositoryTest"`
Expected: FAIL (`SessionTokenRepository` / `QSessionTokenModel` not defined).

- [ ] **Step 5: Implement `SessionTokenRepository`**

`repositories/SessionTokenRepository.kt`:
```kotlin
package fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.repositories

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.SessionToken
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.SessionTokenRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.exceptions.UserModelDoesNotExistError
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.mappers.SessionTokenModelMapper.toDomain
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.models.SessionTokenModel
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.models.query.QSessionTokenModel
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.models.query.QUserModel
import io.ebean.Database
import jakarta.enterprise.context.ApplicationScoped
import java.util.UUID

@ApplicationScoped
class SessionTokenRepository(
    database: Database,
) : SessionTokenRepositoryInterface {
    private val sqlRepository = ModelRepository(entityClass = SessionTokenModel::class, database = database)

    override fun saveSessionToken(sessionToken: SessionToken, tokenHash: String): SessionToken {
        val userModel = QUserModel().id.equalTo(sessionToken.user.id).findOne() ?: throw UserModelDoesNotExistError()
        val model = SessionTokenModel(
            id = sessionToken.id,
            user = userModel,
            tokenHash = tokenHash,
            expiresAt = sessionToken.expiresAt,
            persistent = sessionToken.persistent,
        )
        return sqlRepository.saveAndReturn(model).toDomain()
    }

    override fun findByTokenHash(tokenHash: String): SessionToken? =
        QSessionTokenModel().tokenHash.equalTo(tokenHash).findOne()?.toDomain()

    override fun deleteById(id: UUID) {
        QSessionTokenModel().id.equalTo(id).delete()
    }

    override fun deleteAllForUser(userId: UUID) {
        QSessionTokenModel().user.id.equalTo(userId).delete()
    }
}
```

- [ ] **Step 6: Generate migration 1.8**

Run: `JAVA_HOME=/home/geoffrey/.gradle/jdks/eclipse_adoptium-25-amd64-linux.2 ./gradlew :api-persistence-sqlite:generateDbMigration`
Inspect the generated `1.8.sql`. Expected (additive; `Instant` → nullable-less `timestamp`, `Boolean` → `integer`/`boolean` per the SQLite dialect):
```sql
create table session_tokens (
  id                            uuid not null,
  user_id                       uuid not null,
  token_hash                    varchar(255) not null,
  expires_at                    timestamp not null,
  persistent                    boolean default false not null,
  when_created                  timestamp not null,
  when_modified                 timestamp not null,
  constraint uq_session_tokens_token_hash unique (token_hash),
  constraint pk_session_tokens primary key (id),
  foreign key (user_id) references users (id) on delete restrict on update restrict
);
```
If the generator emits anything destructive (drops/renames of existing tables) or unexpected, stop and reconcile the model before continuing.

- [ ] **Step 7: Run the gate**

Run: `./gradlew :api-persistence-sqlite:detekt :api-persistence-sqlite:test :api-persistence-sqlite:koverVerify`
Expected: PASS (a repository test at boot proves migration 1.8 applies).

- [ ] **Step 8: Commit**

```bash
git add api-persistence-sqlite/src
git commit -m "feat(persistence): SessionTokenModel, mapper, repository, and migration 1.8"
```

---

## Task 3: Usecases (TokenHasher, SessionTokenAuthenticator, exceptions)

**Files:**
- Create: `api-usecases/.../TokenHasher.kt`, `.../SessionTokenAuthenticator.kt`, `.../exceptions/SessionAuthenticationError.kt`
- Test: `api-usecases/src/test/kotlin/.../TokenHasherTest.kt`, `.../SessionTokenAuthenticatorTest.kt`

**Interfaces:**
- Consumes: `SessionTokenRepositoryInterface`, `SessionToken` (domain); `Clock` (domain).
- Produces:
  - `TokenHasher.sha256(token: String): String` (lowercase hex SHA-256).
  - `SessionTokenAuthenticator.authenticate(token: String): SessionToken` (throws `SessionTokenInvalidError` / `SessionTokenExpiredError`).
  - `SessionAuthenticationError` (sealed) with `SessionTokenInvalidError`, `SessionTokenExpiredError`.

- [ ] **Step 1: Write the failing `TokenHasher` test**

`TokenHasherTest.kt`:
```kotlin
package fr.geoffreyCoulaud.pinryReborn.api.usecases

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

class TokenHasherTest {
    @Test
    fun `Given a known input, Then sha256 returns its lowercase hex digest`() {
        // SHA-256 of "abc"
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            TokenHasher.sha256("abc"),
        )
    }

    @Test
    fun `Given two different inputs, Then their digests differ`() {
        assertNotEquals(TokenHasher.sha256("token-a"), TokenHasher.sha256("token-b"))
    }
}
```

- [ ] **Step 2: Run to verify it fails** — `./gradlew :api-usecases:test --tests "*TokenHasherTest"` → FAIL.

- [ ] **Step 3: Implement `TokenHasher`**

`TokenHasher.kt`:
```kotlin
package fr.geoffreyCoulaud.pinryReborn.api.usecases

import java.security.MessageDigest

/** Hashes an opaque session token for storage/lookup. SHA-256 is enough: the input is already
 *  256 bits of entropy, so no salted/slow KDF is needed on the per-request hot path. */
object TokenHasher {
    fun sha256(token: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(token.toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
}
```

- [ ] **Step 4: Create the exceptions**

`exceptions/SessionAuthenticationError.kt`:
```kotlin
package fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions

/**
 * Failures of session-token verification. These are caught by the Bearer identity provider and
 * translated into Quarkus auth failures; they deliberately do NOT extend [BaseError] (they never
 * reach [BaseErrorMapper] / need an [ErrorCode]).
 */
sealed class SessionAuthenticationError(message: String) : Exception(message)

class SessionTokenInvalidError : SessionAuthenticationError("Invalid session token")

class SessionTokenExpiredError : SessionAuthenticationError("Session token expired")
```

- [ ] **Step 5: Write the failing `SessionTokenAuthenticator` test**

`SessionTokenAuthenticatorTest.kt`:
```kotlin
package fr.geoffreyCoulaud.pinryReborn.api.usecases

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.SessionToken
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.User
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.SessionTokenRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.time.Clock
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.SessionTokenExpiredError
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.SessionTokenInvalidError
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID.randomUUID

class SessionTokenAuthenticatorTest {
    private val repository = mockk<SessionTokenRepositoryInterface>()
    private val clock = mockk<Clock>()
    private val authenticator = SessionTokenAuthenticator(repository, clock)

    private val now = Instant.parse("2026-07-21T00:00:00Z")
    private val user = User(id = randomUUID(), name = "alice")
    private val plaintext = "the-token"
    private val hash = TokenHasher.sha256(plaintext)

    @Test
    fun `Given a valid unexpired token, Then authenticate returns its session token`() {
        val token = SessionToken(randomUUID(), user, expiresAt = now.plusSeconds(60), persistent = false)
        every { clock.now() } returns now
        every { repository.findByTokenHash(hash) } returns token

        assertEquals(token, authenticator.authenticate(plaintext))
    }

    @Test
    fun `Given no token for the hash, Then authenticate throws SessionTokenInvalidError`() {
        every { repository.findByTokenHash(hash) } returns null
        assertThrows<SessionTokenInvalidError> { authenticator.authenticate(plaintext) }
    }

    @Test
    fun `Given an expired token, Then authenticate throws SessionTokenExpiredError`() {
        val token = SessionToken(randomUUID(), user, expiresAt = now.minusSeconds(1), persistent = false)
        every { clock.now() } returns now
        every { repository.findByTokenHash(hash) } returns token
        assertThrows<SessionTokenExpiredError> { authenticator.authenticate(plaintext) }
    }

    @Test
    fun `Given a token expiring exactly now, Then it is treated as expired`() {
        val token = SessionToken(randomUUID(), user, expiresAt = now, persistent = false)
        every { clock.now() } returns now
        every { repository.findByTokenHash(hash) } returns token
        assertThrows<SessionTokenExpiredError> { authenticator.authenticate(plaintext) }
    }
}
```

- [ ] **Step 6: Run to verify it fails** — `./gradlew :api-usecases:test --tests "*SessionTokenAuthenticatorTest"` → FAIL.

- [ ] **Step 7: Implement `SessionTokenAuthenticator`**

`SessionTokenAuthenticator.kt`:
```kotlin
package fr.geoffreyCoulaud.pinryReborn.api.usecases

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.SessionToken
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.SessionTokenRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.time.Clock
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.SessionTokenExpiredError
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.SessionTokenInvalidError
import jakarta.enterprise.context.ApplicationScoped

@ApplicationScoped
class SessionTokenAuthenticator(
    private val sessionTokenRepository: SessionTokenRepositoryInterface,
    private val clock: Clock,
) {
    fun authenticate(token: String): SessionToken {
        val sessionToken = sessionTokenRepository.findByTokenHash(TokenHasher.sha256(token))
            ?: throw SessionTokenInvalidError()
        if (!sessionToken.expiresAt.isAfter(clock.now())) throw SessionTokenExpiredError()
        return sessionToken
    }
}
```
Note: `!expiresAt.isAfter(now)` treats `expiresAt == now` as expired (covered by the "exactly now" test).

- [ ] **Step 8: Run the gate** — `./gradlew :api-usecases:detekt :api-usecases:test :api-usecases:koverVerify` → PASS.

- [ ] **Step 9: Commit**

```bash
git add api-usecases/src
git commit -m "feat(usecase): TokenHasher and SessionTokenAuthenticator (bearer verification)"
```

---

## Task 4: Usecases (SessionCreator, SessionRenewer, SessionRevoker)

**Files:**
- Create: `api-usecases/.../SessionCreator.kt`, `.../SessionRenewer.kt`, `.../SessionRevoker.kt`
- Test: `api-usecases/src/test/kotlin/.../SessionCreatorTest.kt`, `.../SessionRenewerTest.kt`, `.../SessionRevokerTest.kt`

**Interfaces:**
- Consumes: `UserAuthenticator` (existing, password path), `SessionTokenRepositoryInterface`, `TokenGenerator`, `Clock`, `SessionExpiryPolicy`, `TokenHasher` (Task 3), `BasicAuthLogin`.
- Produces:
  - `SessionCreator.create(name: String, password: String, persistent: Boolean): IssuedSession` (`@Transactional`).
  - `SessionRenewer.renew(current: SessionToken): IssuedSession` (`@Transactional`, atomic save-new + delete-old).
  - `SessionRevoker.revokeCurrent(current: SessionToken)`, `SessionRevoker.revokeAll(user: User)`.

- [ ] **Step 1: Write the failing tests**

`SessionCreatorTest.kt`:
```kotlin
package fr.geoffreyCoulaud.pinryReborn.api.usecases

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.IssuedSession
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Login.BasicAuthLogin
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.SessionToken
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.User
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.SessionTokenRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.security.SessionExpiryPolicy
import fr.geoffreyCoulaud.pinryReborn.api.domain.security.TokenGenerator
import fr.geoffreyCoulaud.pinryReborn.api.domain.time.Clock
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.UserAuthenticationInvalidPasswordError
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.UUID.randomUUID

class SessionCreatorTest {
    private val userAuthenticator = mockk<UserAuthenticator>()
    private val repository = mockk<SessionTokenRepositoryInterface>(relaxed = true)
    private val tokenGenerator = mockk<TokenGenerator>()
    private val clock = mockk<Clock>()
    private val policy = SessionExpiryPolicy(Duration.ofDays(30), Duration.ofHours(12), 0.75)
    private val creator = SessionCreator(userAuthenticator, repository, tokenGenerator, clock, policy)

    private val now = Instant.parse("2026-07-21T00:00:00Z")
    private val user = User(id = randomUUID(), name = "alice")

    @Test
    fun `Given valid credentials, Then create issues, stores the hash, and returns the token with expiry metadata`() {
        every { userAuthenticator.authenticate(any()) } returns user
        every { tokenGenerator.generateToken() } returns "plain-token"
        every { clock.now() } returns now

        val issued = creator.create(name = "alice", password = "pw", persistent = true)

        val expectedExpiry = now.plus(Duration.ofDays(30))
        assertEquals(IssuedSession("plain-token", expectedExpiry, policy.renewAfterFor(expectedExpiry, true)), issued)

        val saved = slot<SessionToken>()
        verify { repository.saveSessionToken(capture(saved), tokenHash = TokenHasher.sha256("plain-token")) }
        assertEquals(user, saved.captured.user)
        assertEquals(expectedExpiry, saved.captured.expiresAt)
        assertEquals(true, saved.captured.persistent)
    }

    @Test
    fun `Given the login is a BasicAuthLogin with the supplied credentials`() {
        every { userAuthenticator.authenticate(any()) } returns user
        every { tokenGenerator.generateToken() } returns "t"
        every { clock.now() } returns now
        val login = slot<BasicAuthLogin>()
        every { userAuthenticator.authenticate(capture(login)) } returns user

        creator.create(name = "alice", password = "pw", persistent = false)

        assertEquals("alice", login.captured.userName)
        assertEquals("pw", login.captured.password)
    }

    @Test
    fun `Given invalid credentials, Then create propagates the authentication error and stores nothing`() {
        every { userAuthenticator.authenticate(any()) } throws UserAuthenticationInvalidPasswordError()
        assertThrows<UserAuthenticationInvalidPasswordError> { creator.create("alice", "bad", persistent = false) }
        verify(exactly = 0) { repository.saveSessionToken(any(), any()) }
    }
}
```
`SessionRenewerTest.kt`:
```kotlin
package fr.geoffreyCoulaud.pinryReborn.api.usecases

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.SessionToken
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.User
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.SessionTokenRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.security.SessionExpiryPolicy
import fr.geoffreyCoulaud.pinryReborn.api.domain.security.TokenGenerator
import fr.geoffreyCoulaud.pinryReborn.api.domain.time.Clock
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.mockk.verifyOrder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.UUID.randomUUID

class SessionRenewerTest {
    private val repository = mockk<SessionTokenRepositoryInterface>(relaxed = true)
    private val tokenGenerator = mockk<TokenGenerator>()
    private val clock = mockk<Clock>()
    private val policy = SessionExpiryPolicy(Duration.ofDays(30), Duration.ofHours(12), 0.75)
    private val renewer = SessionRenewer(repository, tokenGenerator, clock, policy)

    private val now = Instant.parse("2026-07-21T00:00:00Z")
    private val user = User(id = randomUUID(), name = "alice")
    private val current = SessionToken(randomUUID(), user, expiresAt = now.plusSeconds(10), persistent = true)

    @Test
    fun `Given a current token, Then renew issues a new token preserving persistent and deletes the old`() {
        every { tokenGenerator.generateToken() } returns "new-token"
        every { clock.now() } returns now

        val issued = renewer.renew(current)

        val expectedExpiry = now.plus(Duration.ofDays(30))
        assertEquals("new-token", issued.token)
        assertEquals(expectedExpiry, issued.expiresAt)
        val saved = slot<SessionToken>()
        verify { repository.saveSessionToken(capture(saved), TokenHasher.sha256("new-token")) }
        assertEquals(user, saved.captured.user)
        assertEquals(true, saved.captured.persistent)
        verify { repository.deleteById(current.id) }
    }

    @Test
    fun `Given renew, Then the new token is saved before the old is deleted`() {
        every { tokenGenerator.generateToken() } returns "new-token"
        every { clock.now() } returns now
        renewer.renew(current)
        verifyOrder {
            repository.saveSessionToken(any(), any())
            repository.deleteById(current.id)
        }
    }

    @Test
    fun `Given the new token save fails, Then the old token is not deleted (no half-rotation)`() {
        every { tokenGenerator.generateToken() } returns "new-token"
        every { clock.now() } returns now
        every { repository.saveSessionToken(any(), any()) } throws IllegalStateException("db down")

        assertThrows<IllegalStateException> { renewer.renew(current) }
        verify(exactly = 0) { repository.deleteById(any()) }
    }
}
```
`SessionRevokerTest.kt`:
```kotlin
package fr.geoffreyCoulaud.pinryReborn.api.usecases

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.SessionToken
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.User
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.SessionTokenRepositoryInterface
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID.randomUUID

class SessionRevokerTest {
    private val repository = mockk<SessionTokenRepositoryInterface>(relaxed = true)
    private val revoker = SessionRevoker(repository)
    private val user = User(id = randomUUID(), name = "alice")

    @Test
    fun `Given a current token, Then revokeCurrent deletes it by id`() {
        val current = SessionToken(randomUUID(), user, Instant.now(), persistent = false)
        revoker.revokeCurrent(current)
        verify { repository.deleteById(current.id) }
    }

    @Test
    fun `Given a user, Then revokeAll deletes all their tokens`() {
        revoker.revokeAll(user)
        verify { repository.deleteAllForUser(user.id) }
    }
}
```

- [ ] **Step 2: Run to verify they fail** — `./gradlew :api-usecases:test --tests "*SessionCreatorTest" --tests "*SessionRenewerTest" --tests "*SessionRevokerTest"` → FAIL.

- [ ] **Step 3: Implement the three use cases**

`SessionCreator.kt`:
```kotlin
package fr.geoffreyCoulaud.pinryReborn.api.usecases

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.IssuedSession
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Login.BasicAuthLogin
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.SessionToken
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.SessionTokenRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.security.SessionExpiryPolicy
import fr.geoffreyCoulaud.pinryReborn.api.domain.security.TokenGenerator
import fr.geoffreyCoulaud.pinryReborn.api.domain.time.Clock
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import java.util.UUID.randomUUID

@ApplicationScoped
class SessionCreator(
    private val userAuthenticator: UserAuthenticator,
    private val sessionTokenRepository: SessionTokenRepositoryInterface,
    private val tokenGenerator: TokenGenerator,
    private val clock: Clock,
    private val expiryPolicy: SessionExpiryPolicy,
) {
    @Transactional
    fun create(name: String, password: String, persistent: Boolean): IssuedSession {
        val user = userAuthenticator.authenticate(BasicAuthLogin(userName = name, password = password))
        val token = tokenGenerator.generateToken()
        val expiresAt = expiryPolicy.expiryFrom(clock.now(), persistent)
        sessionTokenRepository.saveSessionToken(
            sessionToken = SessionToken(id = randomUUID(), user = user, expiresAt = expiresAt, persistent = persistent),
            tokenHash = TokenHasher.sha256(token),
        )
        return IssuedSession(token, expiresAt, expiryPolicy.renewAfterFor(expiresAt, persistent))
    }
}
```
`SessionRenewer.kt`:
```kotlin
package fr.geoffreyCoulaud.pinryReborn.api.usecases

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.IssuedSession
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.SessionToken
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.SessionTokenRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.security.SessionExpiryPolicy
import fr.geoffreyCoulaud.pinryReborn.api.domain.security.TokenGenerator
import fr.geoffreyCoulaud.pinryReborn.api.domain.time.Clock
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import java.util.UUID.randomUUID

@ApplicationScoped
class SessionRenewer(
    private val sessionTokenRepository: SessionTokenRepositoryInterface,
    private val tokenGenerator: TokenGenerator,
    private val clock: Clock,
    private val expiryPolicy: SessionExpiryPolicy,
) {
    /** Atomic rotation: save the new token, then delete the old, in one transaction. */
    @Transactional
    fun renew(current: SessionToken): IssuedSession {
        val token = tokenGenerator.generateToken()
        val expiresAt = expiryPolicy.expiryFrom(clock.now(), current.persistent)
        sessionTokenRepository.saveSessionToken(
            sessionToken = SessionToken(
                id = randomUUID(),
                user = current.user,
                expiresAt = expiresAt,
                persistent = current.persistent,
            ),
            tokenHash = TokenHasher.sha256(token),
        )
        sessionTokenRepository.deleteById(current.id)
        return IssuedSession(token, expiresAt, expiryPolicy.renewAfterFor(expiresAt, current.persistent))
    }
}
```
`SessionRevoker.kt`:
```kotlin
package fr.geoffreyCoulaud.pinryReborn.api.usecases

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.SessionToken
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.User
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.SessionTokenRepositoryInterface
import jakarta.enterprise.context.ApplicationScoped

@ApplicationScoped
class SessionRevoker(
    private val sessionTokenRepository: SessionTokenRepositoryInterface,
) {
    fun revokeCurrent(current: SessionToken) = sessionTokenRepository.deleteById(current.id)

    fun revokeAll(user: User) = sessionTokenRepository.deleteAllForUser(user.id)
}
```

- [ ] **Step 4: Run the gate** — `./gradlew :api-usecases:detekt :api-usecases:test :api-usecases:koverVerify` → PASS.

- [ ] **Step 5: Commit**

```bash
git add api-usecases/src
git commit -m "feat(usecase): SessionCreator, SessionRenewer (atomic rotation), SessionRevoker"
```

---

## Task 5: Presentation (DTOs, mapper, SecureTokenGenerator, AuthConfig, policy producer)

**Files:**
- Create: `api-presentation-quarkus/.../dtos/input/SessionCreationInputDto.kt`; `.../dtos/output/CreatedSessionOutputDto.kt`, `.../dtos/output/ExistingSessionOutputDto.kt`; `.../mappers/SessionDtoMapper.kt`
- Create: `.../security/SecureTokenGenerator.kt`; `.../config/AuthConfig.kt`; `.../security/AuthRuntimeProducers.kt`
- Test: `.../mappers/SessionDtoMapperTest.kt`, `.../security/SecureTokenGeneratorTest.kt`

**Interfaces:**
- Consumes: `IssuedSession`, `SessionToken` (domain), `TokenGenerator`, `SessionExpiryPolicy` (domain).
- Produces:
  - `SessionCreationInputDto(name, password, rememberMe: Boolean?)`.
  - `CreatedSessionOutputDto(token, expiresAt, renewAfter)`, `ExistingSessionOutputDto(expiresAt, renewAfter, persistent)`.
  - `SessionDtoMapper.toCreatedDto()` (on `IssuedSession`), `SessionDtoMapper.toExistingDto(renewAfter)` (on `SessionToken`).
  - `SecureTokenGenerator : TokenGenerator` (`@ApplicationScoped`); `AuthConfig`; `AuthRuntimeProducers.sessionExpiryPolicy`.

- [ ] **Step 1: Create the DTOs**

`dtos/input/SessionCreationInputDto.kt`:
```kotlin
package fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.dtos.input

import jakarta.validation.constraints.NotBlank

/**
 * Login body. Only [name]/[password] are `@NotBlank`; registration's size/pattern constraints are
 * deliberately NOT re-applied, so a badly-shaped credential fails as 401 (auth), never 400
 * (validation). [rememberMe] is nullable (absent JSON -> null, coalesced to false in the controller);
 * a non-null default would trip Kover's synthetic-constructor-branch check.
 */
data class SessionCreationInputDto(
    @field:NotBlank
    val name: String,
    @field:NotBlank
    val password: String,
    val rememberMe: Boolean?,
)
```
`dtos/output/CreatedSessionOutputDto.kt`:
```kotlin
package fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.dtos.output

import java.time.Instant

data class CreatedSessionOutputDto(
    val token: String,
    val expiresAt: Instant,
    val renewAfter: Instant,
)
```
`dtos/output/ExistingSessionOutputDto.kt`:
```kotlin
package fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.dtos.output

import java.time.Instant

data class ExistingSessionOutputDto(
    val expiresAt: Instant,
    val renewAfter: Instant,
    val persistent: Boolean,
)
```
Note: Quarkus/Jackson serializes `Instant` as an ISO-8601 UTC string (e.g. `2026-08-19T12:34:56Z`) by default (`quarkus.jackson.write-dates-as-timestamps` defaults to false); the Task 9 integration tests assert that format. Do not add a custom serializer.

- [ ] **Step 2: Write the failing mapper test**

`mappers/SessionDtoMapperTest.kt`:
```kotlin
package fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.mappers

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.IssuedSession
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.SessionToken
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.User
import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.mappers.SessionDtoMapper.toCreatedDto
import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.mappers.SessionDtoMapper.toExistingDto
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID.randomUUID

class SessionDtoMapperTest {
    private val expiresAt = Instant.parse("2026-08-19T12:34:56Z")
    private val renewAfter = Instant.parse("2026-08-12T12:34:56Z")

    @Test
    fun `Given an IssuedSession, Then toCreatedDto copies token and timestamps`() {
        val dto = IssuedSession("tok", expiresAt, renewAfter).toCreatedDto()
        assertEquals("tok", dto.token)
        assertEquals(expiresAt, dto.expiresAt)
        assertEquals(renewAfter, dto.renewAfter)
    }

    @Test
    fun `Given a SessionToken, Then toExistingDto exposes expiry, renewAfter and persistent but no token`() {
        val token = SessionToken(randomUUID(), User(randomUUID(), "alice"), expiresAt, persistent = true)
        val dto = token.toExistingDto(renewAfter)
        assertEquals(expiresAt, dto.expiresAt)
        assertEquals(renewAfter, dto.renewAfter)
        assertEquals(true, dto.persistent)
    }
}
```

- [ ] **Step 3: Run to verify it fails** — `./gradlew :api-presentation-quarkus:test --tests "*SessionDtoMapperTest"` → FAIL.

- [ ] **Step 4: Implement the mapper**

`mappers/SessionDtoMapper.kt`:
```kotlin
package fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.mappers

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.IssuedSession
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.SessionToken
import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.dtos.output.CreatedSessionOutputDto
import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.dtos.output.ExistingSessionOutputDto
import java.time.Instant

object SessionDtoMapper {
    fun IssuedSession.toCreatedDto() =
        CreatedSessionOutputDto(token = token, expiresAt = expiresAt, renewAfter = renewAfter)

    fun SessionToken.toExistingDto(renewAfter: Instant) =
        ExistingSessionOutputDto(expiresAt = expiresAt, renewAfter = renewAfter, persistent = persistent)
}
```

- [ ] **Step 5: Write the failing `SecureTokenGenerator` test**

`security/SecureTokenGeneratorTest.kt`:
```kotlin
package fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.security

import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SecureTokenGeneratorTest {
    private val generator = SecureTokenGenerator()

    @Test
    fun `Given generateToken, Then it returns a non-blank URL-safe token`() {
        val token = generator.generateToken()
        assertTrue(token.isNotBlank())
        assertTrue(token.all { it.isLetterOrDigit() || it == '-' || it == '_' }, "URL-safe base64url charset")
    }

    @Test
    fun `Given two calls, Then the tokens differ`() {
        assertNotEquals(generator.generateToken(), generator.generateToken())
    }
}
```

- [ ] **Step 6: Run to verify it fails** — `./gradlew :api-presentation-quarkus:test --tests "*SecureTokenGeneratorTest"` → FAIL.

- [ ] **Step 7: Implement generator, config, and producer**

`security/SecureTokenGenerator.kt`:
```kotlin
package fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.security

import fr.geoffreyCoulaud.pinryReborn.api.domain.security.TokenGenerator
import jakarta.enterprise.context.ApplicationScoped
import java.security.SecureRandom
import java.util.Base64

@ApplicationScoped
class SecureTokenGenerator : TokenGenerator {
    private val random = SecureRandom()
    private val encoder = Base64.getUrlEncoder().withoutPadding()

    override fun generateToken(): String {
        val bytes = ByteArray(TOKEN_BYTES)
        random.nextBytes(bytes)
        return encoder.encodeToString(bytes)
    }

    private companion object {
        const val TOKEN_BYTES = 32 // 256 bits
    }
}
```
`config/AuthConfig.kt`:
```kotlin
package fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.config

import io.smallrye.config.ConfigMapping
import io.smallrye.config.WithDefault
import java.time.Duration

@ConfigMapping(prefix = "auth", namingStrategy = ConfigMapping.NamingStrategy.SNAKE_CASE)
interface AuthConfig {
    @WithDefault("P30D")
    fun persistentTtl(): Duration

    @WithDefault("PT12H")
    fun ephemeralTtl(): Duration

    @WithDefault("0.75")
    fun renewThreshold(): Double
}
```
`security/AuthRuntimeProducers.kt`:
```kotlin
package fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.security

import fr.geoffreyCoulaud.pinryReborn.api.domain.security.SessionExpiryPolicy
import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.config.AuthConfig
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Produces

@ApplicationScoped
class AuthRuntimeProducers {
    @Produces
    @ApplicationScoped
    fun sessionExpiryPolicy(config: AuthConfig): SessionExpiryPolicy =
        SessionExpiryPolicy(
            persistentTtl = config.persistentTtl(),
            ephemeralTtl = config.ephemeralTtl(),
            renewThreshold = config.renewThreshold(),
        )
}
```

- [ ] **Step 8: Run the gate** — `./gradlew :api-presentation-quarkus:detekt :api-presentation-quarkus:test :api-presentation-quarkus:koverVerify` → PASS. (`AuthConfig` is an interface; `AuthRuntimeProducers.sessionExpiryPolicy` is branchless: no branch to cover, exercised end-to-end in Task 9.)

- [ ] **Step 9: Commit**

```bash
git add api-presentation-quarkus/src
git commit -m "feat(presentation): session DTOs, mapper, SecureTokenGenerator, AuthConfig, expiry-policy producer"
```

---

## Task 6: Presentation security cutover (Bearer mechanism + identity provider, remove Basic)

> After this task the **full build is red** until Task 8 (Basic config still set, no Basic provider). The `api-presentation-quarkus` module gate stays green.

**Files:**
- Create: `.../security/BearerAuthenticationMechanism.kt`, `.../security/BearerTokenIdentityProvider.kt`, `.../security/SessionExpiredException.kt`; `.../mappers/SessionExpiredExceptionMapper.kt`
- Modify: `.../security/SecurityIdentityExtensions.kt` (add `getSessionToken`); `.../mappers/ProblemResponses.kt` (add `WWW_AUTHENTICATE_BEARER`); `.../mappers/AuthenticationFailedExceptionMapper.kt`, `.../mappers/UnauthorizedExceptionMapper.kt` (Bearer challenge + neutral detail)
- Delete: `.../security/BasicAuthIdentityProvider.kt` and its test file
- Test: `.../security/BearerAuthenticationMechanismTest.kt`, `.../security/BearerTokenIdentityProviderTest.kt`, `.../mappers/SessionExpiredExceptionMapperTest.kt`

**Interfaces:**
- Consumes: `SessionTokenAuthenticator`, `SessionTokenInvalidError`, `SessionTokenExpiredError` (usecases); `SessionToken` (domain).
- Produces: a registered `HttpAuthenticationMechanism` for `Authorization: Bearer`; `IdentityProvider<TokenAuthenticationRequest>` stashing `user`/`userId`/`sessionToken`; `SecurityIdentity.getSessionToken(): SessionToken`; `WWW_AUTHENTICATE_BEARER`.

- [ ] **Step 1: Add the `WWW_AUTHENTICATE_BEARER` challenge and switch both auth mappers**

In `mappers/ProblemResponses.kt`, add next to the existing constant (keep `WWW_AUTHENTICATE_BASIC` for now; it is removed with its last user in this task):
```kotlin
/** RFC 7807 challenge value: opaque bearer token, no realm. */
const val WWW_AUTHENTICATE_BEARER = "Bearer"
```
In `mappers/AuthenticationFailedExceptionMapper.kt`, change the detail to a neutral message (it now covers bad login creds AND a bad bearer token) and the challenge to Bearer:
```kotlin
    override fun toResponse(exception: AuthenticationFailedException): Response =
        problemResponse(
            status = Response.Status.UNAUTHORIZED,
            detail = "Authentication failed",
            code = "AUTHENTICATION_FAILED",
            uriInfo = uriInfo,
        ).header("WWW-Authenticate", WWW_AUTHENTICATE_BEARER).build()
```
In `mappers/UnauthorizedExceptionMapper.kt`, swap only the challenge header:
```kotlin
        ).header("WWW-Authenticate", WWW_AUTHENTICATE_BEARER).build()
```
Then delete the now-unused `const val WWW_AUTHENTICATE_BASIC = ...` from `ProblemResponses.kt`.

- [ ] **Step 2: Add `getSessionToken` to the identity extensions**

Append to `security/SecurityIdentityExtensions.kt`:
```kotlin
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.SessionToken
// ...
fun SecurityIdentity.getSessionToken(): SessionToken =
    getAttribute("sessionToken") as SessionToken
```

- [ ] **Step 3: Create the expired-session exception and its mapper**

`security/SessionExpiredException.kt`:
```kotlin
package fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.security

import io.quarkus.security.AuthenticationFailedException

/**
 * A structurally valid but expired bearer token. Extends [AuthenticationFailedException] so Quarkus
 * security handles it as a 401 challenge; a dedicated mapper renders the distinct SESSION_EXPIRED code.
 */
class SessionExpiredException(message: String, cause: Throwable? = null) :
    AuthenticationFailedException(message, cause)
```
`mappers/SessionExpiredExceptionMapper.kt`:
```kotlin
package fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.mappers

import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.security.SessionExpiredException
import jakarta.annotation.Priority
import jakarta.ws.rs.Priorities
import jakarta.ws.rs.core.Context
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.core.UriInfo
import jakarta.ws.rs.ext.ExceptionMapper
import jakarta.ws.rs.ext.Provider

// Higher priority than AuthenticationFailedExceptionMapper so the SESSION_EXPIRED subtype wins.
@Provider
@Priority(Priorities.AUTHENTICATION - 1)
class SessionExpiredExceptionMapper : ExceptionMapper<SessionExpiredException> {
    @Context
    lateinit var uriInfo: UriInfo

    override fun toResponse(exception: SessionExpiredException): Response =
        problemResponse(
            status = Response.Status.UNAUTHORIZED,
            detail = "Session expired",
            code = "SESSION_EXPIRED",
            uriInfo = uriInfo,
        ).header("WWW-Authenticate", WWW_AUTHENTICATE_BEARER).build()
}
```
Note: JAX-RS resolves the most specific mapper, so a `SessionExpiredException` is mapped here, not by `AuthenticationFailedExceptionMapper`. The `@Priority` is defensive. The Task 9 integration test (age a token, then call an endpoint) is the runtime proof; if the security-integrated path does not honor the subtype mapper on the project's Quarkus version, fall back to a single `AUTHENTICATION_FAILED` and record it in the handoff (spec §12).

- [ ] **Step 4: Write the failing `BearerTokenIdentityProvider` test**

`security/BearerTokenIdentityProviderTest.kt`:
```kotlin
package fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.security

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.SessionToken
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.User
import fr.geoffreyCoulaud.pinryReborn.api.usecases.SessionTokenAuthenticator
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.SessionTokenExpiredError
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.SessionTokenInvalidError
import io.quarkus.security.AuthenticationFailedException
import io.quarkus.security.credential.TokenCredential
import io.quarkus.security.identity.AuthenticationRequestContext
import io.quarkus.security.identity.SecurityIdentity
import io.quarkus.security.identity.request.TokenAuthenticationRequest
import io.mockk.every
import io.mockk.mockk
import io.smallrye.mutiny.Uni
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID.randomUUID
import java.util.function.Supplier

class BearerTokenIdentityProviderTest {
    private val authenticator = mockk<SessionTokenAuthenticator>()
    private val provider = BearerTokenIdentityProvider(authenticator)
    private val user = User(randomUUID(), "alice")

    // Execute the runBlocking supplier synchronously.
    private val context = mockk<AuthenticationRequestContext> {
        every { runBlocking(any<Supplier<SecurityIdentity>>()) } answers {
            Uni.createFrom().item(firstArg<Supplier<SecurityIdentity>>().get())
        }
    }

    private fun request(token: String) = TokenAuthenticationRequest(TokenCredential(token, "bearer"))

    @Test
    fun `Given a valid token, Then the identity carries the user, userId and sessionToken`() {
        val session = SessionToken(randomUUID(), user, Instant.now().plusSeconds(60), persistent = true)
        every { authenticator.authenticate("good") } returns session

        val identity = provider.authenticate(request("good"), context).await().indefinitely()

        assertEquals("alice", identity.principal.name)
        assertEquals(user.id, identity.getAttribute("userId"))
        assertEquals(user, identity.getAttribute<User>("user"))
        assertEquals(session, identity.getAttribute<SessionToken>("sessionToken"))
    }

    @Test
    fun `Given an invalid token, Then it throws AuthenticationFailedException`() {
        every { authenticator.authenticate("bad") } throws SessionTokenInvalidError()
        assertThrows<AuthenticationFailedException> {
            provider.authenticate(request("bad"), context).await().indefinitely()
        }
    }

    @Test
    fun `Given an expired token, Then it throws SessionExpiredException`() {
        every { authenticator.authenticate("old") } throws SessionTokenExpiredError()
        assertThrows<SessionExpiredException> {
            provider.authenticate(request("old"), context).await().indefinitely()
        }
    }
}
```

- [ ] **Step 5: Run to verify it fails** — `./gradlew :api-presentation-quarkus:test --tests "*BearerTokenIdentityProviderTest"` → FAIL.

- [ ] **Step 6: Implement `BearerTokenIdentityProvider`**

`security/BearerTokenIdentityProvider.kt`:
```kotlin
package fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.security

import fr.geoffreyCoulaud.pinryReborn.api.usecases.SessionTokenAuthenticator
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.SessionTokenExpiredError
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.SessionTokenInvalidError
import io.quarkus.security.AuthenticationFailedException
import io.quarkus.security.identity.AuthenticationRequestContext
import io.quarkus.security.identity.IdentityProvider
import io.quarkus.security.identity.SecurityIdentity
import io.quarkus.security.identity.request.TokenAuthenticationRequest
import io.quarkus.security.runtime.QuarkusSecurityIdentity
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped

@ApplicationScoped
class BearerTokenIdentityProvider(
    private val sessionTokenAuthenticator: SessionTokenAuthenticator,
) : IdentityProvider<TokenAuthenticationRequest> {
    override fun getRequestType(): Class<TokenAuthenticationRequest> = TokenAuthenticationRequest::class.java

    override fun authenticate(
        request: TokenAuthenticationRequest,
        context: AuthenticationRequestContext,
    ): Uni<SecurityIdentity> =
        context.runBlocking {
            try {
                val session = sessionTokenAuthenticator.authenticate(request.token.token)
                QuarkusSecurityIdentity
                    .builder()
                    .setPrincipal { session.user.name }
                    .addAttribute("userId", session.user.id)
                    .addAttribute("user", session.user)
                    .addAttribute("sessionToken", session)
                    .build()
            } catch (e: SessionTokenExpiredError) {
                throw SessionExpiredException("Session token expired", e)
            } catch (e: SessionTokenInvalidError) {
                throw AuthenticationFailedException("Invalid session token", e)
            }
        }
}
```

- [ ] **Step 7: Write the failing `BearerAuthenticationMechanism` test**

`security/BearerAuthenticationMechanismTest.kt`:
```kotlin
package fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.security

import io.quarkus.security.identity.IdentityProviderManager
import io.quarkus.security.identity.SecurityIdentity
import io.quarkus.security.identity.request.TokenAuthenticationRequest
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.smallrye.mutiny.Uni
import io.vertx.core.http.HttpServerRequest
import io.vertx.ext.web.RoutingContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BearerAuthenticationMechanismTest {
    private val mechanism = BearerAuthenticationMechanism()
    private val idpManager = mockk<IdentityProviderManager>()

    private fun contextWithHeader(value: String?): RoutingContext {
        val request = mockk<HttpServerRequest> { every { getHeader("Authorization") } returns value }
        return mockk { every { request() } returns request }
    }

    @Test
    fun `Given a Bearer header, Then it authenticates the extracted token`() {
        val identity = mockk<SecurityIdentity>()
        val captured = slot<TokenAuthenticationRequest>()
        every { idpManager.authenticate(capture(captured)) } returns Uni.createFrom().item(identity)

        val result = mechanism.authenticate(contextWithHeader("Bearer abc.def"), idpManager).await().indefinitely()

        assertEquals(identity, result)
        assertEquals("abc.def", captured.captured.token.token)
    }

    @Test
    fun `Given no Authorization header, Then it returns a null identity (anonymous)`() {
        assertNull(mechanism.authenticate(contextWithHeader(null), idpManager).await().indefinitely())
    }

    @Test
    fun `Given a non-Bearer header, Then it returns a null identity (anonymous)`() {
        assertNull(mechanism.authenticate(contextWithHeader("Basic dXNlcjpwdw=="), idpManager).await().indefinitely())
    }

    @Test
    fun `Given getChallenge, Then it is a 401 Bearer challenge`() {
        val challenge = mechanism.getChallenge(contextWithHeader(null)).await().indefinitely()
        assertEquals(401, challenge.status)
    }

    @Test
    fun `Given getCredentialTypes, Then it is TokenAuthenticationRequest`() {
        assertTrue(mechanism.getCredentialTypes().contains(TokenAuthenticationRequest::class.java))
    }
}
```

- [ ] **Step 8: Run to verify it fails** — `./gradlew :api-presentation-quarkus:test --tests "*BearerAuthenticationMechanismTest"` → FAIL.

- [ ] **Step 9: Implement `BearerAuthenticationMechanism`**

`security/BearerAuthenticationMechanism.kt` (verify the override signatures against the project's Quarkus version; the compiler flags mismatches, notably `getCredentialTransport`):
```kotlin
package fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.security

import io.quarkus.security.credential.TokenCredential
import io.quarkus.security.identity.IdentityProviderManager
import io.quarkus.security.identity.SecurityIdentity
import io.quarkus.security.identity.request.AuthenticationRequest
import io.quarkus.security.identity.request.TokenAuthenticationRequest
import io.quarkus.vertx.http.runtime.security.ChallengeData
import io.quarkus.vertx.http.runtime.security.HttpAuthenticationMechanism
import io.quarkus.vertx.http.runtime.security.HttpCredentialTransport
import io.smallrye.mutiny.Uni
import io.vertx.ext.web.RoutingContext
import jakarta.enterprise.context.ApplicationScoped

@ApplicationScoped
class BearerAuthenticationMechanism : HttpAuthenticationMechanism {
    override fun authenticate(
        context: RoutingContext,
        identityProviderManager: IdentityProviderManager,
    ): Uni<SecurityIdentity> {
        val header = context.request().getHeader("Authorization")
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            return Uni.createFrom().nullItem()
        }
        val token = header.substring(BEARER_PREFIX.length)
        return identityProviderManager.authenticate(TokenAuthenticationRequest(TokenCredential(token, "bearer")))
    }

    override fun getChallenge(context: RoutingContext): Uni<ChallengeData> =
        Uni.createFrom().item(ChallengeData(401, "WWW-Authenticate", "Bearer"))

    override fun getCredentialTypes(): Set<Class<out AuthenticationRequest>> =
        setOf(TokenAuthenticationRequest::class.java)

    override fun getCredentialTransport(context: RoutingContext): Uni<HttpCredentialTransport> =
        Uni.createFrom().item(HttpCredentialTransport(HttpCredentialTransport.Type.AUTHORIZATION, "bearer"))

    private companion object {
        const val BEARER_PREFIX = "Bearer "
    }
}
```

- [ ] **Step 10: Write the failing `SessionExpiredExceptionMapper` test**

`mappers/SessionExpiredExceptionMapperTest.kt`:
```kotlin
package fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.mappers

import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.dtos.output.ProblemDetail
import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.security.SessionExpiredException
import jakarta.ws.rs.core.UriInfo
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SessionExpiredExceptionMapperTest {
    @Test
    fun `Given an expired session, Then it maps to 401 SESSION_EXPIRED with a Bearer challenge`() {
        val mapper = SessionExpiredExceptionMapper()
        mapper.uriInfo = mockk<UriInfo> { every { path } returns "/api/v1/me" }

        val response = mapper.toResponse(SessionExpiredException("Session token expired"))

        assertEquals(401, response.status)
        assertEquals("Bearer", response.getHeaderString("WWW-Authenticate"))
        assertEquals("SESSION_EXPIRED", (response.entity as ProblemDetail).code)
    }
}
```
(Adjust the `ProblemDetail` field access to match its actual shape from `dtos/output/ProblemDetail.kt`.)

- [ ] **Step 11: Run to verify it fails** — `./gradlew :api-presentation-quarkus:test --tests "*SessionExpiredExceptionMapperTest"` → FAIL, then it passes once the mapper (Step 3) is in place.

- [ ] **Step 12: Delete the Basic identity provider and its test**

```bash
git rm api-presentation-quarkus/src/main/kotlin/fr/geoffreyCoulaud/pinryReborn/api/presentation/quarkus/security/BasicAuthIdentityProvider.kt
# Remove its test if one exists:
git rm api-presentation-quarkus/src/test/kotlin/fr/geoffreyCoulaud/pinryReborn/api/presentation/quarkus/security/BasicAuthIdentityProviderTest.kt 2>/dev/null || true
```
Grep to confirm nothing in the presentation module still imports `BasicAuthIdentityProvider` or `UsernamePasswordAuthenticationRequest`:
```bash
grep -rn "BasicAuthIdentityProvider\|UsernamePasswordAuthenticationRequest\|WWW_AUTHENTICATE_BASIC" api-presentation-quarkus/src
```
Expected: no matches.

- [ ] **Step 13: Run the module gate** — `./gradlew :api-presentation-quarkus:detekt :api-presentation-quarkus:test :api-presentation-quarkus:koverVerify` → PASS. (The full build is now red until Task 8; that is expected.)

- [ ] **Step 14: Commit**

```bash
git add api-presentation-quarkus/src
git commit -m "feat(presentation): Bearer auth mechanism + identity provider, SESSION_EXPIRED, remove Basic"
```

---

## Task 7: Presentation controllers (SessionController, MeController)

**Files:**
- Create: `.../controllers/SessionController.kt`, `.../controllers/MeController.kt`
- Test: `.../controllers/SessionControllerTest.kt`, `.../controllers/MeControllerTest.kt`

**Interfaces:**
- Consumes: `SessionCreator`, `SessionRenewer`, `SessionRevoker`, `SessionExpiryPolicy`, `SecurityIdentity` + `getSessionToken`/`getUser`; `SessionDtoMapper`; `UserAuthenticationError`; `UserDtoMapper.toDto`.
- Produces the REST surface of spec §5.

- [ ] **Step 1: Write the failing `SessionController` unit test (branch coverage: try/catch + rememberMe coalesce)**

`controllers/SessionControllerTest.kt`:
```kotlin
package fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.controllers

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.IssuedSession
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.SessionToken
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.User
import fr.geoffreyCoulaud.pinryReborn.api.domain.security.SessionExpiryPolicy
import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.dtos.input.SessionCreationInputDto
import fr.geoffreyCoulaud.pinryReborn.api.usecases.SessionCreator
import fr.geoffreyCoulaud.pinryReborn.api.usecases.SessionRenewer
import fr.geoffreyCoulaud.pinryReborn.api.usecases.SessionRevoker
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.UserAuthenticationInvalidPasswordError
import io.quarkus.security.AuthenticationFailedException
import io.quarkus.security.identity.SecurityIdentity
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.UUID.randomUUID

class SessionControllerTest {
    private val creator = mockk<SessionCreator>()
    private val renewer = mockk<SessionRenewer>(relaxed = true)
    private val revoker = mockk<SessionRevoker>(relaxed = true)
    private val policy = SessionExpiryPolicy(Duration.ofDays(30), Duration.ofHours(12), 0.75)
    private val identity = mockk<SecurityIdentity>()
    private val controller = SessionController(creator, renewer, revoker, policy, identity)

    private val user = User(randomUUID(), "alice")
    private val issued = IssuedSession("tok", Instant.parse("2026-08-01T00:00:00Z"), Instant.parse("2026-07-25T00:00:00Z"))

    @Test
    fun `Given rememberMe true, Then createSession passes persistent=true and returns the created dto`() {
        every { creator.create("alice", "pw", persistent = true) } returns issued
        val response = controller.createSession(SessionCreationInputDto("alice", "pw", rememberMe = true))
        assertEquals("tok", response.token)
        verify { creator.create("alice", "pw", persistent = true) }
    }

    @Test
    fun `Given rememberMe absent (null), Then createSession defaults persistent to false`() {
        every { creator.create("alice", "pw", persistent = false) } returns issued
        controller.createSession(SessionCreationInputDto("alice", "pw", rememberMe = null))
        verify { creator.create("alice", "pw", persistent = false) }
    }

    @Test
    fun `Given invalid credentials, Then createSession raises AuthenticationFailedException`() {
        every { creator.create(any(), any(), any()) } throws UserAuthenticationInvalidPasswordError()
        assertThrows<AuthenticationFailedException> {
            controller.createSession(SessionCreationInputDto("alice", "bad", rememberMe = null))
        }
    }

    @Test
    fun `Given a current session, Then getCurrentSession returns its metadata without a token`() {
        val current = SessionToken(randomUUID(), user, Instant.parse("2026-08-01T00:00:00Z"), persistent = true)
        every { identity.getAttribute<SessionToken>("sessionToken") } returns current
        val dto = controller.getCurrentSession()
        assertEquals(current.expiresAt, dto.expiresAt)
        assertEquals(policy.renewAfterFor(current.expiresAt, true), dto.renewAfter)
        assertEquals(true, dto.persistent)
    }

    @Test
    fun `Given a current session, Then renewSession delegates to the renewer and returns the new token`() {
        val current = SessionToken(randomUUID(), user, Instant.now(), persistent = false)
        every { identity.getAttribute<SessionToken>("sessionToken") } returns current
        every { renewer.renew(current) } returns issued
        assertEquals("tok", controller.renewSession().token)
    }

    @Test
    fun `Given a current session, Then revokeCurrentSession deletes the current token`() {
        val current = SessionToken(randomUUID(), user, Instant.now(), persistent = false)
        every { identity.getAttribute<SessionToken>("sessionToken") } returns current
        controller.revokeCurrentSession()
        verify { revoker.revokeCurrent(current) }
    }

    @Test
    fun `Given the caller, Then revokeAllSessions deletes all their tokens`() {
        every { identity.getAttribute<User>("user") } returns user
        controller.revokeAllSessions()
        verify { revoker.revokeAll(user) }
    }
}
```

- [ ] **Step 2: Run to verify it fails** — `./gradlew :api-presentation-quarkus:test --tests "*SessionControllerTest"` → FAIL.

- [ ] **Step 3: Implement `SessionController`**

`controllers/SessionController.kt`:
```kotlin
package fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.controllers

import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.dtos.input.SessionCreationInputDto
import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.dtos.output.CreatedSessionOutputDto
import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.dtos.output.ExistingSessionOutputDto
import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.mappers.SessionDtoMapper.toCreatedDto
import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.mappers.SessionDtoMapper.toExistingDto
import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.security.getSessionToken
import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.security.getUser
import fr.geoffreyCoulaud.pinryReborn.api.usecases.SessionCreator
import fr.geoffreyCoulaud.pinryReborn.api.usecases.SessionRenewer
import fr.geoffreyCoulaud.pinryReborn.api.usecases.SessionRevoker
import fr.geoffreyCoulaud.pinryReborn.api.domain.security.SessionExpiryPolicy
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.UserAuthenticationError
import io.quarkus.security.AuthenticationFailedException
import io.quarkus.security.Authenticated
import io.quarkus.security.identity.SecurityIdentity
import jakarta.annotation.security.PermitAll
import jakarta.validation.Valid
import jakarta.ws.rs.DELETE
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import org.jboss.resteasy.reactive.ResponseStatus

@Path("/api/v1/sessions")
class SessionController(
    private val sessionCreator: SessionCreator,
    private val sessionRenewer: SessionRenewer,
    private val sessionRevoker: SessionRevoker,
    private val expiryPolicy: SessionExpiryPolicy,
    private val securityIdentity: SecurityIdentity,
) {
    @POST
    @PermitAll
    @ResponseStatus(HTTP_CREATED)
    fun createSession(@Valid dto: SessionCreationInputDto): CreatedSessionOutputDto {
        val issued = try {
            sessionCreator.create(name = dto.name, password = dto.password, persistent = dto.rememberMe ?: false)
        } catch (e: UserAuthenticationError) {
            throw AuthenticationFailedException("Authentication failed", e)
        }
        return issued.toCreatedDto()
    }

    @GET
    @Path("/current")
    @Authenticated
    fun getCurrentSession(): ExistingSessionOutputDto {
        val current = securityIdentity.getSessionToken()
        return current.toExistingDto(expiryPolicy.renewAfterFor(current.expiresAt, current.persistent))
    }

    @POST
    @Path("/current/renew")
    @Authenticated
    fun renewSession(): CreatedSessionOutputDto = sessionRenewer.renew(securityIdentity.getSessionToken()).toCreatedDto()

    @DELETE
    @Path("/current")
    @Authenticated
    fun revokeCurrentSession() = sessionRevoker.revokeCurrent(securityIdentity.getSessionToken())

    @DELETE
    @Authenticated
    fun revokeAllSessions() = sessionRevoker.revokeAll(securityIdentity.getUser())

    private companion object {
        const val HTTP_CREATED = 201
    }
}
```
Note: `revokeCurrentSession`/`revokeAllSessions` return `Unit` (a `void` resource method) so resteasy-reactive replies **204 No Content**. `@ResponseStatus(201)` sets Created on the login. `getUser()`/`getSessionToken()` are the `SecurityIdentityExtensions`.

- [ ] **Step 4: Write the failing `MeController` test**

`controllers/MeControllerTest.kt`:
```kotlin
package fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.controllers

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.User
import io.quarkus.security.identity.SecurityIdentity
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.UUID.randomUUID

class MeControllerTest {
    @Test
    fun `Given an authenticated caller, Then getCurrentUser returns their id and name`() {
        val user = User(randomUUID(), "alice")
        val identity = mockk<SecurityIdentity> { every { getAttribute<User>("user") } returns user }
        val dto = MeController(identity).getCurrentUser()
        assertEquals(user.id, dto.id)
        assertEquals("alice", dto.name)
    }
}
```

- [ ] **Step 5: Run to verify it fails** — `./gradlew :api-presentation-quarkus:test --tests "*MeControllerTest"` → FAIL.

- [ ] **Step 6: Implement `MeController`**

`controllers/MeController.kt`:
```kotlin
package fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.controllers

import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.dtos.output.UserOutputDto
import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.mappers.UserDtoMapper.toDto
import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.security.getUser
import io.quarkus.security.Authenticated
import io.quarkus.security.identity.SecurityIdentity
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path

@Path("/api/v1/me")
class MeController(
    private val securityIdentity: SecurityIdentity,
) {
    @GET
    @Authenticated
    fun getCurrentUser(): UserOutputDto = securityIdentity.getUser().toDto()
}
```

- [ ] **Step 7: Run the module gate** — `./gradlew :api-presentation-quarkus:detekt :api-presentation-quarkus:test :api-presentation-quarkus:koverVerify` → PASS. (Full build still red until Task 8.)

- [ ] **Step 8: Commit**

```bash
git add api-presentation-quarkus/src
git commit -m "feat(presentation): SessionController and MeController"
```

---

## Task 8: Application cutover (config, unified test-auth helper, migrate existing tests)

> This task makes the **full build green** again. Do this as one deliverable: the app authenticates end-to-end via Bearer, and every pre-existing integration test is migrated.

**Files:**
- Modify: `api-application/src/main/resources/application.properties`
- Modify: `api-application/src/test/kotlin/.../IntegrationTest.kt`
- Modify: every integration test using `.auth().preemptive().basic(...)` (find with grep; ~15 files)

**Interfaces:**
- Consumes: `POST /api/v1/sessions` (Task 7), `UserCreator.createUserWithPassword` (existing).
- Produces: `IntegrationTest.createAuthenticatedUser(...)` and `RequestSpecification.authenticatedAs(auth)` — the single seam every integration test uses to obtain and send a Bearer token.

- [ ] **Step 1: Remove Basic and add `auth.*` config**

In `api-application/src/main/resources/application.properties`, delete the line:
```
quarkus.http.auth.basic=true
```
and add (near the auth-related config):
```
# Session-token auth (AuthConfig uses the SNAKE_CASE naming strategy: underscores, not dashes)
auth.persistent_ttl=P30D
auth.ephemeral_ttl=PT12H
auth.renew_threshold=0.75
```
The test `application.properties` needs no `auth.*` entries: `AuthConfig`'s `@WithDefault`s (P30D / PT12H / 0.75) apply in tests.

- [ ] **Step 2: Add the unified auth helper to `IntegrationTest`**

Replace `api-application/src/test/kotlin/.../IntegrationTest.kt` with (keeps the existing truncate logic, adds the helper):
```kotlin
package fr.geoffreyCoulaud.pinryReborn.api.application

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.User
import fr.geoffreyCoulaud.pinryReborn.api.usecases.UserCreator
import fr.geoffreyCoulaud.pinryReborn.api.utilities.createRandomString
import io.ebean.DB
import io.ebean.Database
import io.restassured.RestAssured
import io.restassured.http.ContentType
import io.restassured.specification.RequestSpecification
import jakarta.inject.Inject
import org.junit.jupiter.api.BeforeEach

abstract class IntegrationTest {
    @Inject
    lateinit var userCreator: UserCreator

    private val database: Database get() = DB.getDefault()

    /** A created user together with a live bearer token for it. */
    data class AuthenticatedUser(val user: User, val token: String)

    @BeforeEach
    fun truncateAllTables() {
        database
            .sqlQuery("SELECT name FROM sqlite_master WHERE type='table'")
            .findList()
            .map { it.getString("name") }
            .filterNot { it.startsWith("sqlite_") or it.equals("db_migration") }
            .forEach { database.truncate(it) }
    }

    /** Create a user and log it in, returning the user and a bearer token. */
    protected fun createAuthenticatedUser(
        name: String = createRandomString(),
        password: String = DEFAULT_PASSWORD,
        rememberMe: Boolean = false,
    ): AuthenticatedUser {
        val user = userCreator.createUserWithPassword(name = name, password = password)
        val token = RestAssured
            .given()
            .contentType(ContentType.JSON)
            .body(mapOf("name" to name, "password" to password, "rememberMe" to rememberMe))
            .post("/api/v1/sessions")
            .then()
            .statusCode(HTTP_CREATED)
            .extract()
            .path<String>("token")
        return AuthenticatedUser(user, token)
    }

    /** Attach `Authorization: Bearer <token>` to a REST-Assured request. */
    protected fun RequestSpecification.authenticatedAs(auth: AuthenticatedUser): RequestSpecification =
        header("Authorization", "Bearer ${auth.token}")

    companion object {
        const val DEFAULT_PASSWORD = "password123"
        private const val HTTP_CREATED = 201
    }
}
```

- [ ] **Step 3: Migrate every existing integration test from Basic to Bearer**

List the files to change:
```bash
grep -rln "preemptive().basic" api-application/src/test
```
For **each** file, apply this mechanical transformation:
- Delete any local `authenticatedAs` helper and per-test `defaultPassword` constant (now on the base class).
- Delete the per-test `@Inject lateinit var userCreator: UserCreator` **only if** it is used solely to seed the authenticating user; keep it if the test still uses `userCreator` for other setup.
- Where a test created a user then authed with Basic:
  ```kotlin
  // BEFORE
  val username = createRandomString()
  userCreator.createUserWithPassword(username, defaultPassword)
  given().auth().preemptive().basic(username, defaultPassword) ...
  // AFTER
  val auth = createAuthenticatedUser()
  given().authenticatedAs(auth) ...            // use auth.user where the User/username is needed
  ```
- Where a test needs the caller's `User` (e.g. to assert ownership), use `auth.user`; where it needs the username, use `auth.user.name`.
- Where a test authenticated as a **second** user (non-owner / 403 cases), call `createAuthenticatedUser()` again for a distinct user and `authenticatedAs` that one.
- Tests that assert a **401 without credentials** keep sending no `Authorization` header (now challenged with `WWW-Authenticate: Bearer`); if any asserts the header value `Basic ...`, update it to `Bearer`. If any asserts the `AUTHENTICATION_FAILED` detail string `"Invalid username or password"`, update it to `"Authentication failed"` (Task 6 neutralised it).

Work through the grep list one file at a time; after each, run that file's test class to keep the diff small:
```bash
JAVA_HOME=/home/geoffrey/.gradle/jdks/eclipse_adoptium-25-amd64-linux.2 \
  ./gradlew :api-application:test --tests "*<ClassName>"
```

- [ ] **Step 4: Run the full build (first green since Task 5)**

Run: `JAVA_HOME=/home/geoffrey/.gradle/jdks/eclipse_adoptium-25-amd64-linux.2 ./gradlew build`
Expected: BUILD SUCCESSFUL. If a test still uses Basic or asserts an old challenge/detail, fix it. Confirm no stragglers:
```bash
grep -rn "preemptive().basic\|quarkus.http.auth.basic" api-application
```
Expected: no matches.

- [ ] **Step 5: Commit**

```bash
git add api-application/src/main/resources/application.properties api-application/src/test
git commit -m "chore(app): switch auth to Bearer, add auth.* config, unify integration test auth helper"
```

---

## Task 9: Session-auth integration tests + OpenAPI

**Files:**
- Create: `api-application/src/test/kotlin/.../SessionAuthIntegrationTest.kt`
- Modify: `docs/openapi.json` (regenerated)

**Interfaces:**
- Consumes: the full stack (Tasks 1-8), `IntegrationTest.createAuthenticatedUser`/`authenticatedAs`, and `SessionTokenModel` (to age a token for the expiry test).

- [ ] **Step 1: Write the failing session-auth integration tests**

`SessionAuthIntegrationTest.kt`:
```kotlin
package fr.geoffreyCoulaud.pinryReborn.api.application

import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.models.SessionTokenModel
import io.ebean.DB
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import org.hamcrest.Matchers.matchesPattern
import org.hamcrest.Matchers.notNullValue
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import java.time.Instant

@QuarkusTest
class SessionAuthIntegrationTest : IntegrationTest() {
    private val iso8601Utc = "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?Z"

    private fun login(name: String, password: String = DEFAULT_PASSWORD, rememberMe: Boolean? = null) =
        given().contentType(ContentType.JSON)
            .body(buildMap<String, Any> {
                put("name", name); put("password", password)
                if (rememberMe != null) put("rememberMe", rememberMe)
            })
            .post("/api/v1/sessions")

    @Test
    fun `Given valid credentials, Then POST sessions returns 201 with a token and ISO-8601 UTC metadata`() {
        val name = createRandomString()
        userCreator.createUserWithPassword(name, DEFAULT_PASSWORD)
        login(name)
            .then().statusCode(201)
            .body("token", notNullValue())
            .body("expiresAt", matchesPattern(iso8601Utc))
            .body("renewAfter", matchesPattern(iso8601Utc))
    }

    @Test
    fun `Given a bad password, Then POST sessions returns 401 AUTHENTICATION_FAILED`() {
        val name = createRandomString()
        userCreator.createUserWithPassword(name, DEFAULT_PASSWORD)
        login(name, password = "wrong-password")
            .then().statusCode(401).body("code", org.hamcrest.Matchers.equalTo("AUTHENTICATION_FAILED"))
    }

    @Test
    fun `Given an unknown user, Then POST sessions returns 401 AUTHENTICATION_FAILED`() {
        login(createRandomString())
            .then().statusCode(401).body("code", org.hamcrest.Matchers.equalTo("AUTHENTICATION_FAILED"))
    }

    @Test
    fun `Given a valid token, Then GET me returns the caller`() {
        val auth = createAuthenticatedUser()
        given().authenticatedAs(auth).get("/api/v1/me")
            .then().statusCode(200).body("name", org.hamcrest.Matchers.equalTo(auth.user.name))
    }

    @Test
    fun `Given no token, Then GET me returns 401 with a Bearer challenge`() {
        given().get("/api/v1/me")
            .then().statusCode(401).header("WWW-Authenticate", "Bearer")
    }

    @Test
    fun `Given a garbage token, Then GET me returns 401 AUTHENTICATION_FAILED`() {
        given().header("Authorization", "Bearer not-a-real-token").get("/api/v1/me")
            .then().statusCode(401).body("code", org.hamcrest.Matchers.equalTo("AUTHENTICATION_FAILED"))
    }

    @Test
    fun `Given a token, Then GET sessions current returns expiry metadata without a token`() {
        val auth = createAuthenticatedUser(rememberMe = true)
        given().authenticatedAs(auth).get("/api/v1/sessions/current")
            .then().statusCode(200)
            .body("expiresAt", matchesPattern(iso8601Utc))
            .body("renewAfter", matchesPattern(iso8601Utc))
            .body("persistent", org.hamcrest.Matchers.equalTo(true))
            .body("$", org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasKey("token")))
    }

    @Test
    fun `Given a token, Then renew returns a new token and the old one is rejected`() {
        val auth = createAuthenticatedUser()
        val newToken = given().authenticatedAs(auth).post("/api/v1/sessions/current/renew")
            .then().statusCode(200).extract().path<String>("token")
        assertNotNull(newToken)
        // Old token now rejected:
        given().authenticatedAs(auth).get("/api/v1/me").then().statusCode(401)
        // New token works:
        given().header("Authorization", "Bearer $newToken").get("/api/v1/me").then().statusCode(200)
    }

    @Test
    fun `Given a token, Then DELETE sessions current logs it out`() {
        val auth = createAuthenticatedUser()
        given().authenticatedAs(auth).delete("/api/v1/sessions/current").then().statusCode(204)
        given().authenticatedAs(auth).get("/api/v1/me").then().statusCode(401)
    }

    @Test
    fun `Given two sessions for one user, Then DELETE sessions revokes them all`() {
        val name = createRandomString()
        userCreator.createUserWithPassword(name, DEFAULT_PASSWORD)
        val first = login(name).then().statusCode(201).extract().path<String>("token")
        val second = login(name).then().statusCode(201).extract().path<String>("token")

        given().header("Authorization", "Bearer $first").delete("/api/v1/sessions").then().statusCode(204)

        given().header("Authorization", "Bearer $first").get("/api/v1/me").then().statusCode(401)
        given().header("Authorization", "Bearer $second").get("/api/v1/me").then().statusCode(401)
    }

    @Test
    fun `Given an expired token, Then it is rejected with 401 SESSION_EXPIRED`() {
        val auth = createAuthenticatedUser()
        // Age the single session-token row directly via Ebean (deterministic, no clock mocking).
        val model = DB.getDefault().find(SessionTokenModel::class.java).findList().single()
        model.expiresAt = Instant.now().minusSeconds(60)
        DB.getDefault().save(model)

        given().authenticatedAs(auth).get("/api/v1/me")
            .then().statusCode(401).body("code", org.hamcrest.Matchers.equalTo("SESSION_EXPIRED"))
    }
}
```
Note on the expiry test: it proves the full plumbing (mechanism → provider → `SessionExpiredException` → `SessionExpiredExceptionMapper` → `SESSION_EXPIRED`) deterministically by aging the row, no clock mocking (which would perturb the task-queue timing tests). If this assertion fails because the project's Quarkus version does not route the subtype through `SessionExpiredExceptionMapper`, relax the code to `AUTHENTICATION_FAILED`, adjust the mapper strategy, and record the limitation in the handoff (spec §12).

- [ ] **Step 2: Run to verify they fail, then pass**

Run: `JAVA_HOME=/home/geoffrey/.gradle/jdks/eclipse_adoptium-25-amd64-linux.2 ./gradlew :api-application:test --tests "*SessionAuthIntegrationTest"`
Expected: all green (the stack is already implemented; this task is the end-to-end proof). Fix any wiring gap surfaced here (this is where a wrong `HttpAuthenticationMechanism` signature or an unrouted `SESSION_EXPIRED` shows up).

- [ ] **Step 3: Regenerate the OpenAPI schema**

Run: `JAVA_HOME=/home/geoffrey/.gradle/jdks/eclipse_adoptium-25-amd64-linux.2 ./gradlew build`
This regenerates `docs/openapi.json` (via `quarkus.smallrye-openapi.store-schema-directory=../docs`). Confirm it now documents `/api/v1/sessions`, `/api/v1/sessions/current`, `/api/v1/sessions/current/renew`, `/api/v1/me`, and no longer advertises Basic security.

- [ ] **Step 4: Run the full gate**

Run: `JAVA_HOME=/home/geoffrey/.gradle/jdks/eclipse_adoptium-25-amd64-linux.2 ./gradlew build`
Expected: BUILD SUCCESSFUL, 100% branch coverage held across all gated modules.

- [ ] **Step 5: Commit**

```bash
git add api-application/src/test docs/openapi.json
git commit -m "test(auth): end-to-end session-token integration tests; regenerate OpenAPI"
```

---

## Done

After Task 9: the full build is green, HTTP Basic is gone, and clients authenticate with renewable session tokens. Proceed to the **Verify** phase (holistic review) and **Wrap** (handoff in `docs/handoffs/`, backlog refresh, PR). The handoff should flag: the `SESSION_EXPIRED` subtype-mapper plumbing (verified in Task 9, note the Quarkus version), the deferred expired-row GC (P2), and that CORS (the next P1 item) is now unblocked.
