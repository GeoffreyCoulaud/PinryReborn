# Profile management Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let an authenticated user change their password and delete their account, both behind a re-authentication, following `docs/specs/2026-07-21-profile-management.md`.

**Architecture:** Hexagonal. Password hashing is inverted behind a new `PasswordHasher` domain port (BCrypt adapter in the `api-application` composition root). Transactions use the existing `TransactionRunner` port, never `@Transactional`. The account tombstone is Ebean `@SoftDelete` mirrored by a domain `User.softDeleted` flag. Delete-account is async: the request tombstones + revokes + enqueues an `AccountDeletion` task; a worker cleaner erases rows (FK order) and on-disk bytes, then hard-deletes the user.

**Tech Stack:** Kotlin, Quarkus 3 (Jakarta REST), Ebean 19 + SQLite, jBCrypt, JUnit 5, MockK, REST Assured, Kover (100% branch coverage).

## Global Constraints

- **100% branch coverage per package**, gated by `koverVerify`. Exercise both sides of every conditional.
- **Strict TDD**: write the failing test first, watch it fail, then the minimal implementation.
- **Clean/Hexagonal**: `api-domain` pure; `api-usecases` depends only on `api-domain`. **No `@Transactional` in use cases** (use the `TransactionRunner` port). **No concrete crypto in use cases** (use the `PasswordHasher` port).
- **Language: English everywhere** — identifiers, prose, commit messages.
- **Conventional commits** (`feat(...)`, `refactor(...)`, `test(...)`).
- **No top-level functions** — helpers live in a class/companion/object; extension functions are the only exception.
- **Test naming**: backticked `` `Given ..., Then ...` `` (no "when" in the name); body uses `// Given` / `// When` / `// Then`.
- **Test bases**: integration → `IntegrationTest` (api-application, add `@QuarkusTest`); use-case → `BaseTest` (MockK, `checkUnnecessaryStub` runs in `@AfterEach` so every `every {}` must be exercised); repository → `RepositoryTest`.
- **Run the gate** with `./gradlew check koverVerify` (needs JDK 25 + libvips for image tests).

## File Structure

**New files**
- `api-domain/.../domain/security/PasswordHasher.kt` — hashing port.
- `api-usecases/.../usecases/Reauthenticator.kt` — generic step-up verifier.
- `api-usecases/.../usecases/PasswordChanger.kt` — change-password use case.
- `api-usecases/.../usecases/AccountDeleter.kt` — delete-account request use case.
- `api-usecases/.../usecases/AccountDeletionCleaner.kt` — worker erasure use case.
- `api-usecases/.../usecases/tasks/AccountDeletionTask.kt` — task identity constants.
- `api-usecases/.../usecases/exceptions/ReauthenticationError.kt`, `PasswordChangeError.kt` (`PasswordPreviouslyUsedError`), `ReauthenticationHeaderError.kt` (`MalformedReauthenticationError`).
- `api-application/.../BcryptPasswordHasher.kt` — BCrypt adapter (pragmatic home; see spec §4).
- `api-presentation-quarkus/.../dtos/input/PasswordChangeInputDto.kt`.
- `api-presentation-quarkus/.../security/ReauthenticationHeader.kt` — parse `X-Reauthentication`.
- `api-presentation-quarkus/.../tasks/AccountDeletionTaskHandler.kt` — thin task adapter (temporary placement).

**Modified files**
- `api-domain/.../entities/User.kt` — add `softDeleted: Boolean = false`.
- `api-domain/.../repositories/UserRepositoryInterface.kt`, `UserPasswordHashRepositoryInterface.kt`, `PinRepositoryInterface.kt`, `BoardRepositoryInterface.kt`, `TagRepositoryInterface.kt` — new methods.
- `api-usecases/.../usecases/UserCreator.kt`, `UserAuthenticator.kt` — use `PasswordHasher`; `UserCreator` uses `TransactionRunner` + include-deleted uniqueness; `UserAuthenticator` reads current hash.
- `api-usecases/.../usecases/exceptions/ErrorCode.kt` — new codes.
- `api-persistence-sqlite/.../models/UserModel.kt` — Ebean `@SoftDelete`.
- `api-persistence-sqlite/.../mappers/UserModelMapper.kt` — map `softDeleted`.
- `api-persistence-sqlite/.../repositories/UserRepository.kt`, `UserPasswordHashRepository.kt`, `PinRepository.kt`, `BoardRepository.kt`, `TagRepository.kt` — new methods.
- `api-persistence-sqlite/src/main/resources/dbmigration/1.9.sql` + `model/1.9.model.xml` — generated.
- `api-presentation-quarkus/.../controllers/MeController.kt` — `PUT /me/password`, `DELETE /me`.
- `api-presentation-quarkus/.../mappers/BaseErrorMapper.kt` — new status arms.
- `api-application/build.gradle.kts` (+ `api-usecases/build.gradle.kts`) — move the jBCrypt dependency.
- `docs/openapi.json` — regenerated.

---

### Task 1: `PasswordHasher` port + BCrypt adapter (refactor, no behaviour change)

Invert BCrypt behind a domain port; move the concrete library out of the use cases into the composition root. No user-facing change — existing tests must stay green.

**Files:**
- Create: `api-domain/src/main/kotlin/fr/geoffreyCoulaud/pinryReborn/api/domain/security/PasswordHasher.kt`
- Create: `api-application/src/main/kotlin/fr/geoffreyCoulaud/pinryReborn/api/application/BcryptPasswordHasher.kt`
- Create test: `api-application/src/test/kotlin/fr/geoffreyCoulaud/pinryReborn/api/application/BcryptPasswordHasherTest.kt`
- Modify: `api-usecases/.../usecases/UserCreator.kt`, `UserAuthenticator.kt`
- Modify: `api-application/build.gradle.kts`, `api-usecases/build.gradle.kts` (move jBCrypt)

**Interfaces:**
- Produces: `interface PasswordHasher { fun hash(raw: String): HashedPassword; fun matches(raw: String, stored: HashedPassword): Boolean }`.
- Consumes: `HashedPassword(hash, algorithm)`, `PasswordHashAlgorithm.BCRYPT` (in `...domain.enums`).

- [ ] **Step 1: Create the port**

```kotlin
package fr.geoffreyCoulaud.pinryReborn.api.domain.security

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.HashedPassword

interface PasswordHasher {
    /** Hash [raw] with a fresh random salt. */
    fun hash(raw: String): HashedPassword

    /** True if [raw] matches [stored] under [stored]'s algorithm. */
    fun matches(raw: String, stored: HashedPassword): Boolean
}
```

- [ ] **Step 2: Write the failing adapter test**

```kotlin
package fr.geoffreyCoulaud.pinryReborn.api.application

import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.PasswordHashAlgorithm
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BcryptPasswordHasherTest {
    private val hasher = BcryptPasswordHasher()

    @Test
    fun `Given a raw password, Then hash then matches round-trips`() {
        // Given
        val raw = "correct horse battery staple"
        // When
        val hashed = hasher.hash(raw)
        // Then
        assertEquals(PasswordHashAlgorithm.BCRYPT, hashed.algorithm)
        assertTrue(hasher.matches(raw, hashed))
    }

    @Test
    fun `Given a wrong password, Then matches is false`() {
        // Given
        val hashed = hasher.hash("right")
        // When / Then
        assertFalse(hasher.matches("wrong", hashed))
    }
}
```

- [ ] **Step 3: Run it — expect FAIL** (`BcryptPasswordHasher` unresolved)

Run: `./gradlew :api-application:test --tests "*BcryptPasswordHasherTest"`
Expected: FAIL (compilation: unresolved reference).

- [ ] **Step 4: Implement the adapter**

```kotlin
package fr.geoffreyCoulaud.pinryReborn.api.application

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.HashedPassword
import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.PasswordHashAlgorithm
import fr.geoffreyCoulaud.pinryReborn.api.domain.security.PasswordHasher
import jakarta.enterprise.context.ApplicationScoped
import org.mindrot.jbcrypt.BCrypt

@ApplicationScoped
class BcryptPasswordHasher : PasswordHasher {
    override fun hash(raw: String): HashedPassword =
        HashedPassword(hash = BCrypt.hashpw(raw, BCrypt.gensalt()), algorithm = PasswordHashAlgorithm.BCRYPT)

    override fun matches(raw: String, stored: HashedPassword): Boolean =
        when (stored.algorithm) {
            PasswordHashAlgorithm.BCRYPT -> BCrypt.checkpw(raw, stored.hash)
        }
}
```

Add jBCrypt to `api-application/build.gradle.kts` dependencies (copy the coordinate from `api-usecases/build.gradle.kts`, e.g. `implementation("org.mindrot:jbcrypt:0.4")`).

- [ ] **Step 5: Run it — expect PASS**

Run: `./gradlew :api-application:test --tests "*BcryptPasswordHasherTest"`
Expected: PASS.

- [ ] **Step 6: Migrate `UserCreator` to the port** (constructor injects `PasswordHasher`, drop `BCrypt` import)

```kotlin
// constructor: add `private val passwordHasher: PasswordHasher,`
// in createUserWithPassword, replace the BCrypt.hashpw block with:
userPasswordRepository.saveUserPasswordHash(user = user, hashedPassword = passwordHasher.hash(password))
```

Update `UserCreatorTest` to pass a `mockk<PasswordHasher>()` and stub `every { passwordHasher.hash(any()) } returns HashedPassword("h", PasswordHashAlgorithm.BCRYPT)` in the with-password test only.

- [ ] **Step 7: Migrate `UserAuthenticator` to the port** (inject `PasswordHasher`, replace `dummyHash`/`checkPassword`)

```kotlin
// constructor: `private val passwordHasher: PasswordHasher,` (keep the two repositories)
// replace the BCrypt fields/methods with:
private val dummyHash: HashedPassword by lazy { passwordHasher.hash("constant-time-guard") }
// in checkLogin, the missing-user/hash branch:
if (user == null || hash == null) {
    passwordHasher.matches(login.password, dummyHash) // constant-time guard, result ignored
    throw if (user == null) UserAuthenticationUserDoesNotExistError() else UserAuthenticationInvalidPasswordError()
}
return user.takeIf { passwordHasher.matches(login.password, hash) } ?: throw UserAuthenticationInvalidPasswordError()
```

Update `UserAuthenticatorTest`: inject `mockk<PasswordHasher>()`; stub `matches`/`hash` per branch (only stub what each test exercises — `checkUnnecessaryStub` is strict).

- [ ] **Step 8: Remove jBCrypt from `api-usecases`** if no other file imports `org.mindrot.jbcrypt` (grep first: `grep -rn "org.mindrot" api-usecases/src`). If clean, drop the dependency from `api-usecases/build.gradle.kts`.

- [ ] **Step 9: Run the affected modules — expect PASS**

Run: `./gradlew :api-usecases:test :api-application:test`
Expected: PASS (behaviour unchanged).

- [ ] **Step 10: Commit**

```bash
git add -A
git commit -m "refactor(usecases): invert password hashing behind a PasswordHasher port"
```

---

### Task 2: `User.softDeleted` + Ebean `@SoftDelete` + migration 1.9 + user lifecycle repo methods

Add the domain-visible tombstone flag, the first Ebean `@SoftDelete`, and the lookup/lifecycle methods. This is the first `@SoftDelete` in the codebase — verify behaviour with a repository test.

**Files:**
- Modify: `api-domain/.../entities/User.kt`, `.../repositories/UserRepositoryInterface.kt`
- Modify: `api-persistence-sqlite/.../models/UserModel.kt`, `.../mappers/UserModelMapper.kt`, `.../repositories/UserRepository.kt`
- Create: `api-persistence-sqlite/src/main/resources/dbmigration/1.9.sql` + `model/1.9.model.xml` (generated)
- Test: `api-persistence-sqlite/.../repositories/UserRepositoryTest.kt`

**Interfaces:**
- Produces on `UserRepositoryInterface`: `findUserByNameIncludingDeleted(name): User?`, `findUserByIdIncludingDeleted(id): User?`, `markPendingDeletion(user)`, `permanentlyDeleteUser(user)`. `findUserById`/`findUserByName` now auto-exclude tombstoned rows. `deleteUser` is removed.
- Produces on `User`: `softDeleted: Boolean`.

- [ ] **Step 1: Add the domain field**

```kotlin
data class User(
    override val id: UUID,
    val name: String,
    val softDeleted: Boolean = false,
) : Identifiable
```

- [ ] **Step 2: Extend the interface** (`UserRepositoryInterface.kt`)

```kotlin
fun findUserById(id: UUID): User?                       // now excludes tombstoned rows
fun findUserByName(name: String): User?                 // now excludes tombstoned rows
fun findUserByNameIncludingDeleted(name: String): User? // uniqueness check
fun findUserByIdIncludingDeleted(id: UUID): User?       // deletion task loader
fun saveUser(user: User): User
fun markPendingDeletion(user: User)                     // one-way soft delete
fun permanentlyDeleteUser(user: User)                   // physical delete
// remove: fun deleteUser(user: User)
```

- [ ] **Step 3: Write the failing repository test** (`UserRepositoryTest.kt`)

```kotlin
package fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.repositories

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.User
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.RepositoryTest
import fr.geoffreyCoulaud.pinryReborn.api.utilities.createRandomString
import org.junit.jupiter.api.Test
import java.util.UUID.randomUUID
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UserRepositoryTest : RepositoryTest() {
    private val repository = UserRepository(database = database)

    private fun saveUser(name: String = createRandomString()) =
        repository.saveUser(User(id = randomUUID(), name = name))

    @Test
    fun `Given a tombstoned user, Then normal lookups hide it but including-deleted finds it`() {
        // Given
        val user = saveUser()
        repository.markPendingDeletion(user)
        // When / Then
        assertNull(repository.findUserById(user.id))
        assertNull(repository.findUserByName(user.name))
        val found = repository.findUserByIdIncludingDeleted(user.id)
        assertEquals(user.id, found?.id)
        assertTrue(found!!.softDeleted)
        assertEquals(user.id, repository.findUserByNameIncludingDeleted(user.name)?.id)
    }

    @Test
    fun `Given a tombstoned user, Then permanentlyDeleteUser removes it entirely`() {
        // Given
        val user = saveUser()
        repository.markPendingDeletion(user)
        // When
        repository.permanentlyDeleteUser(user)
        // Then
        assertNull(repository.findUserByIdIncludingDeleted(user.id))
    }

    @Test
    fun `Given an active user, Then findUserById returns it with softDeleted false`() {
        // Given
        val user = saveUser()
        // When / Then
        assertEquals(false, repository.findUserById(user.id)?.softDeleted)
    }
}
```

- [ ] **Step 4: Run it — expect FAIL** (missing methods + no `deleted` column)

Run: `./gradlew :api-persistence-sqlite:test --tests "*UserRepositoryTest"`
Expected: FAIL (unresolved methods / column).

- [ ] **Step 5: Add `@SoftDelete` to `UserModel`**

```kotlin
import io.ebean.annotation.SoftDelete
// ...
@Entity
@Table(name = "users")
class UserModel(
    id: UUID,
    var name: String,
) : BaseModel(id = id) {
    @SoftDelete
    var deleted: Boolean = false
}
```

- [ ] **Step 6: Map the flag** (`UserModelMapper.kt`)

```kotlin
object UserModelMapper {
    // toModel never sets `deleted`: new users are active; transitions go through delete()/deletePermanent()
    fun User.toModel() = UserModel(id = id, name = name)
    fun UserModel.toDomain() = User(id = id, name = name, softDeleted = deleted)
}
```

- [ ] **Step 7: Generate migration 1.9**

Run: `./gradlew :api-persistence-sqlite:generateDbMigration`
Expected: creates `dbmigration/1.9.sql` (`alter table users add column deleted ...`) and `dbmigration/model/1.9.model.xml`. Open `1.9.sql` and confirm it only adds the `users.deleted` column (a boolean/int, default 0/false, not null). If it contains anything else, stop and reconcile.

- [ ] **Step 8: Implement the repository methods** (`UserRepository.kt`)

```kotlin
override fun findUserByNameIncludingDeleted(name: String): User? =
    QUserModel().name.equalTo(name).setIncludeSoftDeletes().findOne()?.toDomain()

override fun findUserByIdIncludingDeleted(id: UUID): User? =
    QUserModel().id.equalTo(id).setIncludeSoftDeletes().findOne()?.toDomain()

override fun markPendingDeletion(user: User) {
    val model = QUserModel().id.equalTo(user.id).findOne() ?: return
    database.delete(model) // soft delete via @SoftDelete
}

override fun permanentlyDeleteUser(user: User) {
    val model = QUserModel().id.equalTo(user.id).setIncludeSoftDeletes().findOne() ?: return
    database.deletePermanent(model)
}
```

`findUserById`/`findUserByName` are unchanged (Ebean now auto-excludes `deleted = true`). Delete the old `deleteUser` override. Grep for `deleteUser(` callers and remove/replace (expected: none).

- [ ] **Step 9: Run it — expect PASS**

Run: `./gradlew :api-persistence-sqlite:test --tests "*UserRepositoryTest"`
Expected: PASS.

- [ ] **Step 10: Regression + commit**

```bash
./gradlew :api-persistence-sqlite:test
git add -A
git commit -m "feat(persistence): tombstone users via Ebean @SoftDelete, mirrored on the domain"
```

---

### Task 3: Password history repository methods

Switch password storage reads to "latest = current" and expose the full history + a per-user delete. Append stays as-is.

**Files:**
- Modify: `api-domain/.../repositories/UserPasswordHashRepositoryInterface.kt`
- Modify: `api-persistence-sqlite/.../repositories/UserPasswordHashRepository.kt`
- Modify: `api-usecases/.../usecases/UserAuthenticator.kt` (caller rename)
- Test: `api-persistence-sqlite/.../repositories/UserPasswordHashRepositoryTest.kt`

**Interfaces:**
- Produces: `findCurrentPasswordHash(user): HashedPassword?` (replaces `findUserPasswordHash`), `findAllPasswordHashesForUser(user): List<HashedPassword>`, `deleteForUser(user)`. `saveUserPasswordHash` unchanged (append).

- [ ] **Step 1: Update the interface**

```kotlin
fun saveUserPasswordHash(user: User, hashedPassword: HashedPassword): HashedPassword // appends
fun findCurrentPasswordHash(user: User): HashedPassword?   // latest by when_created
fun findAllPasswordHashesForUser(user: User): List<HashedPassword>
fun deleteForUser(user: User)
// remove: fun findUserPasswordHash(user: User): HashedPassword?
```

- [ ] **Step 2: Write the failing repository test**

```kotlin
class UserPasswordHashRepositoryTest : RepositoryTest() {
    private val users = UserRepository(database = database)
    private val repository = UserPasswordHashRepository(database = database)

    private fun user() = users.saveUser(User(id = randomUUID(), name = createRandomString()))
    private fun hash(h: String) = HashedPassword(hash = h, algorithm = PasswordHashAlgorithm.BCRYPT)

    @Test
    fun `Given two saved hashes, Then current is the latest and all returns both`() {
        // Given
        val user = user()
        repository.saveUserPasswordHash(user, hash("old"))
        Thread.sleep(2) // ensure a distinct when_created for deterministic ordering
        repository.saveUserPasswordHash(user, hash("new"))
        // When / Then
        assertEquals("new", repository.findCurrentPasswordHash(user)?.hash)
        assertEquals(setOf("old", "new"), repository.findAllPasswordHashesForUser(user).map { it.hash }.toSet())
    }

    @Test
    fun `Given saved hashes, Then deleteForUser removes them all`() {
        // Given
        val user = user()
        repository.saveUserPasswordHash(user, hash("a"))
        // When
        repository.deleteForUser(user)
        // Then
        assertNull(repository.findCurrentPasswordHash(user))
        assertEquals(emptyList(), repository.findAllPasswordHashesForUser(user))
    }
}
```

- [ ] **Step 3: Run it — expect FAIL**

Run: `./gradlew :api-persistence-sqlite:test --tests "*UserPasswordHashRepositoryTest"`
Expected: FAIL (unresolved methods).

- [ ] **Step 4: Implement the repository methods**

```kotlin
override fun findCurrentPasswordHash(user: User): HashedPassword? =
    QUserPasswordHashModel().user.id.equalTo(user.id)
        .orderBy().whenCreated.desc()
        .findList().firstOrNull()?.toDomain()

override fun findAllPasswordHashesForUser(user: User): List<HashedPassword> =
    QUserPasswordHashModel().user.id.equalTo(user.id).findList().map { it.toDomain() }

override fun deleteForUser(user: User) {
    QUserPasswordHashModel().user.id.equalTo(user.id).delete()
}
```

Keep `saveUserPasswordHash` as-is (it already inserts a fresh row). Remove `findUserPasswordHash`.

- [ ] **Step 5: Update `UserAuthenticator`** — replace `userPasswordRepository.findUserPasswordHash(it)` with `userPasswordRepository.findCurrentPasswordHash(it)`. Update `UserAuthenticatorTest` stubs accordingly.

- [ ] **Step 6: Run it — expect PASS**

Run: `./gradlew :api-persistence-sqlite:test --tests "*UserPasswordHashRepositoryTest" && ./gradlew :api-usecases:test --tests "*UserAuthenticatorTest"`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "feat(persistence): append-only password history (current = latest)"
```

---

### Task 4: Registration uniqueness includes tombstoned users

A username held by a pending-deletion account stays reserved.

**Files:**
- Modify: `api-usecases/.../usecases/UserCreator.kt`
- Test: `api-usecases/.../usecases/UserCreatorTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
@Test
fun `Given a name held by a tombstoned user, Then creation is rejected`() {
    // Given
    val name = createRandomString()
    every { userRepository.findUserByNameIncludingDeleted(name) } returns
        User(id = randomUUID(), name = name, softDeleted = true)
    // When / Then
    assertThrows<UsernameAlreadyTakenError> { userCreator.createUser(name) }
}
```

- [ ] **Step 2: Run it — expect FAIL** (still calls `findUserByName`)

Run: `./gradlew :api-usecases:test --tests "*UserCreatorTest"`
Expected: FAIL.

- [ ] **Step 3: Switch the lookup** in `UserCreator.createUser`: `userRepository.findUserByNameIncludingDeleted(normalizedName)` instead of `findUserByName(...)`. Update the existing "already taken" test stub to `findUserByNameIncludingDeleted`.

- [ ] **Step 4: Run it — expect PASS**; then `./gradlew :api-usecases:test`.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat(usecases): reserve usernames held by tombstoned accounts"
```

---

### Task 5: `PasswordChanger` use case + its errors

Change-password logic: verify current password, reject reuse (against full history), append, revoke all sessions — inside one `TransactionRunner`.

**Files:**
- Create: `api-usecases/.../usecases/PasswordChanger.kt`
- Create: `api-usecases/.../usecases/exceptions/ReauthenticationError.kt`, `PasswordChangeError.kt`
- Modify: `api-usecases/.../usecases/exceptions/ErrorCode.kt`
- Modify: `api-presentation-quarkus/.../mappers/BaseErrorMapper.kt`
- Test: `api-usecases/.../usecases/PasswordChangerTest.kt`

**Interfaces:**
- Consumes: `PasswordHasher`, `UserPasswordHashRepositoryInterface` (`findCurrentPasswordHash`, `findAllPasswordHashesForUser`, `saveUserPasswordHash`), `SessionRevoker.revokeAll(user)`, `TransactionRunner.inTransaction`.
- Produces: `PasswordChanger.changePassword(user: User, currentPassword: String, newPassword: String)`; `ReauthenticationError` (`REAUTHENTICATION_FAILED`), `PasswordPreviouslyUsedError` (`PASSWORD_PREVIOUSLY_USED`).

- [ ] **Step 1: Add error codes** (`ErrorCode.kt`): add `REAUTHENTICATION_FAILED`, `PASSWORD_PREVIOUSLY_USED` to the enum.

- [ ] **Step 2: Add the error classes**

```kotlin
// ReauthenticationError.kt
class ReauthenticationError :
    BaseError(message = "Re-authentication failed", code = ErrorCode.REAUTHENTICATION_FAILED)

// PasswordChangeError.kt
open class PasswordChangeError(message: String, code: ErrorCode) : BaseError(message, code)
class PasswordPreviouslyUsedError :
    PasswordChangeError(message = "Password was previously used", code = ErrorCode.PASSWORD_PREVIOUSLY_USED)
```

- [ ] **Step 3: Map the statuses** (`BaseErrorMapper.statusFor`): add
```kotlin
ErrorCode.REAUTHENTICATION_FAILED -> Response.Status.FORBIDDEN.statusCode
ErrorCode.PASSWORD_PREVIOUSLY_USED -> UNPROCESSABLE_ENTITY_STATUS_CODE // the existing private const 422
```

- [ ] **Step 4: Write the failing use-case test**

```kotlin
class PasswordChangerTest : BaseTest() {
    private val passwords = mockk<UserPasswordHashRepositoryInterface>()
    private val hasher = mockk<PasswordHasher>()
    private val sessionRevoker = mockk<SessionRevoker>(relaxed = true)
    private val tx = mockk<TransactionRunner>()
    private val changer = PasswordChanger(passwords, hasher, sessionRevoker, tx)

    private val user = User(id = randomUUID(), name = "u")
    private val current = HashedPassword("current-hash", PasswordHashAlgorithm.BCRYPT)

    @BeforeEach
    fun passThroughTransaction() {
        every { tx.inTransaction(any<() -> Any?>()) } answers { (firstArg<() -> Any?>())() }
    }

    @Test
    fun `Given valid inputs, Then the new hash is appended and all sessions revoked`() {
        // Given
        every { passwords.findCurrentPasswordHash(user) } returns current
        every { hasher.matches("old", current) } returns true
        every { passwords.findAllPasswordHashesForUser(user) } returns listOf(current)
        every { hasher.matches("new", current) } returns false
        every { hasher.hash("new") } returns HashedPassword("new-hash", PasswordHashAlgorithm.BCRYPT)
        every { passwords.saveUserPasswordHash(user, any()) } answers { secondArg() }
        // When
        changer.changePassword(user, "old", "new")
        // Then
        verify { passwords.saveUserPasswordHash(user, HashedPassword("new-hash", PasswordHashAlgorithm.BCRYPT)) }
        verify { sessionRevoker.revokeAll(user) }
    }

    @Test
    fun `Given a wrong current password, Then it throws and nothing is written`() {
        every { passwords.findCurrentPasswordHash(user) } returns current
        every { hasher.matches("bad", current) } returns false
        assertThrows<ReauthenticationError> { changer.changePassword(user, "bad", "new") }
        verify(exactly = 0) { passwords.saveUserPasswordHash(any(), any()) }
    }

    @Test
    fun `Given no stored hash, Then re-authentication fails`() {
        every { passwords.findCurrentPasswordHash(user) } returns null
        assertThrows<ReauthenticationError> { changer.changePassword(user, "x", "new") }
    }

    @Test
    fun `Given a previously-used new password, Then it throws PasswordPreviouslyUsedError`() {
        every { passwords.findCurrentPasswordHash(user) } returns current
        every { hasher.matches("old", current) } returns true
        val older = HashedPassword("older", PasswordHashAlgorithm.BCRYPT)
        every { passwords.findAllPasswordHashesForUser(user) } returns listOf(current, older)
        every { hasher.matches("reused", current) } returns false
        every { hasher.matches("reused", older) } returns true
        assertThrows<PasswordPreviouslyUsedError> { changer.changePassword(user, "old", "reused") }
    }
}
```

- [ ] **Step 5: Run it — expect FAIL** (`PasswordChanger` unresolved).

- [ ] **Step 6: Implement `PasswordChanger`**

```kotlin
@ApplicationScoped
class PasswordChanger(
    private val userPasswordRepository: UserPasswordHashRepositoryInterface,
    private val passwordHasher: PasswordHasher,
    private val sessionRevoker: SessionRevoker,
    private val transactionRunner: TransactionRunner,
) {
    fun changePassword(user: User, currentPassword: String, newPassword: String) {
        val current = userPasswordRepository.findCurrentPasswordHash(user)
        if (current == null || !passwordHasher.matches(currentPassword, current)) throw ReauthenticationError()
        val history = userPasswordRepository.findAllPasswordHashesForUser(user)
        if (history.any { passwordHasher.matches(newPassword, it) }) throw PasswordPreviouslyUsedError()
        transactionRunner.inTransaction {
            userPasswordRepository.saveUserPasswordHash(user, passwordHasher.hash(newPassword))
            sessionRevoker.revokeAll(user)
        }
    }
}
```

- [ ] **Step 7: Run — expect PASS**; then `./gradlew :api-usecases:test :api-presentation-quarkus:test`.

- [ ] **Step 8: Commit**

```bash
git add -A
git commit -m "feat(usecases): PasswordChanger with re-auth, history check, revoke-all"
```

---

### Task 6: `PUT /me/password` endpoint + DTO (integration)

**Files:**
- Create: `api-presentation-quarkus/.../dtos/input/PasswordChangeInputDto.kt`
- Modify: `api-presentation-quarkus/.../controllers/MeController.kt`
- Test: `api-application/.../MePasswordIntegrationTest.kt`

**Interfaces:**
- Consumes: `PasswordChanger.changePassword`, `securityIdentity.getUser()`.

- [ ] **Step 1: Write the failing integration test**

```kotlin
@QuarkusTest
class MePasswordIntegrationTest : IntegrationTest() {
    private fun changeBody(current: String, next: String) =
        """{"currentPassword":"$current","newPassword":"$next"}"""

    @Test
    fun `Given valid change, Then 204 and old sessions die and new password works`() {
        // Given
        val auth = createAuthenticatedUser(password = "password123")
        // When
        given().authenticatedAs(auth).contentType("application/json")
            .body(changeBody("password123", "newpassword1")).put("/api/v1/me/password")
            .then().statusCode(204)
        // Then — old token rejected
        given().authenticatedAs(auth).get("/api/v1/me").then().statusCode(401)
        // Then — new password logs in, old does not
        login(auth.user.name, "newpassword1").then().statusCode(201)
        login(auth.user.name, "password123").then().statusCode(401)
    }

    @Test
    fun `Given wrong current password, Then 403 and password unchanged`() {
        val auth = createAuthenticatedUser(password = "password123")
        given().authenticatedAs(auth).contentType("application/json")
            .body(changeBody("wrongpass", "newpassword1")).put("/api/v1/me/password")
            .then().statusCode(403).body("code", equalTo("REAUTHENTICATION_FAILED"))
        login(auth.user.name, "password123").then().statusCode(201)
    }

    @Test
    fun `Given reusing the current password, Then 422`() {
        val auth = createAuthenticatedUser(password = "password123")
        given().authenticatedAs(auth).contentType("application/json")
            .body(changeBody("password123", "password123")).put("/api/v1/me/password")
            .then().statusCode(422).body("code", equalTo("PASSWORD_PREVIOUSLY_USED"))
    }

    @Test
    fun `Given a too-short new password, Then 400`() {
        val auth = createAuthenticatedUser(password = "password123")
        given().authenticatedAs(auth).contentType("application/json")
            .body(changeBody("password123", "short")).put("/api/v1/me/password")
            .then().statusCode(400)
    }

    @Test
    fun `Given no token, Then 401`() {
        given().contentType("application/json").body(changeBody("a", "newpassword1"))
            .put("/api/v1/me/password").then().statusCode(401)
    }

    private fun login(name: String, password: String) =
        given().contentType("application/json").body("""{"name":"$name","password":"$password"}""")
            .post("/api/v1/sessions")
}
```

- [ ] **Step 2: Run it — expect FAIL** (404/no route).

- [ ] **Step 3: Add the DTO**

```kotlin
data class PasswordChangeInputDto(
    @field:NotBlank val currentPassword: String,
    @field:NotBlank @field:Size(min = 8, max = 72) val newPassword: String,
)
```

- [ ] **Step 4: Add the endpoint** to `MeController` (inject `PasswordChanger`)

```kotlin
@PUT
@Path("/password")
@Authenticated
fun changePassword(@Valid dto: PasswordChangeInputDto): RestResponse<Unit> {
    passwordChanger.changePassword(securityIdentity.getUser(), dto.currentPassword, dto.newPassword)
    return RestResponse.ResponseBuilder.create<Unit>(RestResponse.Status.NO_CONTENT).build()
}
```

- [ ] **Step 5: Run it — expect PASS**.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat(presentation): PUT /me/password (change password)"
```

---

### Task 7: Bulk per-user deletes (pins / boards / tags)

Needed by the deletion cleaner. All-states (not only soft-deleted/recycled).

**Files:**
- Modify: `api-domain/.../repositories/{PinRepositoryInterface,BoardRepositoryInterface,TagRepositoryInterface}.kt`
- Modify: `api-persistence-sqlite/.../repositories/{PinRepository,BoardRepository,TagRepository}.kt`
- Test: extend the existing repository tests (or add focused ones).

**Interfaces:**
- Produces: `PinRepositoryInterface.permanentlyDeleteAllPinsForUser(user)`, `BoardRepositoryInterface.permanentlyDeleteAllBoardsForUser(user)`, `TagRepositoryInterface.deleteAllTagsForUser(user)`.

- [ ] **Step 1: Write failing repository tests** (one per method), e.g. for pins:

```kotlin
@Test
fun `Given active and soft-deleted pins, Then permanentlyDeleteAllPinsForUser removes all`() {
    // Given: create a user with one active + one soft-deleted pin (reuse existing test helpers)
    // When
    repository.permanentlyDeleteAllPinsForUser(user)
    // Then
    assertEquals(emptyList(), repository.findAllPinsForUser(user))
    assertEquals(emptyList(), repository.findAllSoftDeletedPinsForUser(user))
}
```
Mirror for boards (active + recycled) and tags (assert the user's tags are gone).

- [ ] **Step 2: Run — expect FAIL**.

- [ ] **Step 3: Implement** (query-bean style, mirroring `permanentlyDeleteAllSoftDeletedPinsForUser`):

```kotlin
// PinRepository
override fun permanentlyDeleteAllPinsForUser(user: User) {
    val pinIds = QPinModel().author.id.equalTo(user.id).findList().map { it.id }
    if (pinIds.isEmpty()) return
    QPinTagModel().pin.id.isIn(pinIds).delete()
    QPinBoardModel().pin.id.isIn(pinIds).delete()
    QPinModel().id.isIn(pinIds).delete()
}

// BoardRepository
override fun permanentlyDeleteAllBoardsForUser(user: User) {
    val boardIds = QBoardModel().author.id.equalTo(user.id).findList().map { it.id }
    if (boardIds.isEmpty()) return
    QPinBoardModel().board.id.isIn(boardIds).delete()
    QBoardModel().id.isIn(boardIds).delete()
}

// TagRepository
override fun deleteAllTagsForUser(user: User) {
    QTagModel().author.id.equalTo(user.id).delete()
}
```

- [ ] **Step 4: Run — expect PASS**; then `./gradlew :api-persistence-sqlite:test`.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat(persistence): all-states per-user bulk deletes for pins/boards/tags"
```

---

### Task 8: `Reauthenticator` (generic step-up)

**Files:**
- Create: `api-usecases/.../usecases/Reauthenticator.kt`
- Test: `api-usecases/.../usecases/ReauthenticatorTest.kt`

**Interfaces:**
- Consumes: `UserPasswordHashRepositoryInterface.findCurrentPasswordHash`, `PasswordHasher.matches`, `ReauthenticationError` (from Task 5).
- Produces: `Reauthenticator.reauthenticate(user: User, factor: String)` (throws `ReauthenticationError` on failure).

- [ ] **Step 1: Write the failing test**

```kotlin
class ReauthenticatorTest : BaseTest() {
    private val passwords = mockk<UserPasswordHashRepositoryInterface>()
    private val hasher = mockk<PasswordHasher>()
    private val reauth = Reauthenticator(passwords, hasher)
    private val user = User(id = randomUUID(), name = "u")
    private val hash = HashedPassword("h", PasswordHashAlgorithm.BCRYPT)

    @Test
    fun `Given the correct factor, Then it passes`() {
        every { passwords.findCurrentPasswordHash(user) } returns hash
        every { hasher.matches("secret", hash) } returns true
        assertDoesNotThrow { reauth.reauthenticate(user, "secret") }
    }

    @Test
    fun `Given a wrong factor, Then it throws`() {
        every { passwords.findCurrentPasswordHash(user) } returns hash
        every { hasher.matches("bad", hash) } returns false
        assertThrows<ReauthenticationError> { reauth.reauthenticate(user, "bad") }
    }

    @Test
    fun `Given no stored hash, Then it throws`() {
        every { passwords.findCurrentPasswordHash(user) } returns null
        assertThrows<ReauthenticationError> { reauth.reauthenticate(user, "x") }
    }
}
```

- [ ] **Step 2: Run — expect FAIL**.

- [ ] **Step 3: Implement**

```kotlin
@ApplicationScoped
class Reauthenticator(
    private val userPasswordRepository: UserPasswordHashRepositoryInterface,
    private val passwordHasher: PasswordHasher,
) {
    fun reauthenticate(user: User, factor: String) {
        val hash = userPasswordRepository.findCurrentPasswordHash(user)
        if (hash == null || !passwordHasher.matches(factor, hash)) throw ReauthenticationError()
    }
}
```

- [ ] **Step 4: Run — expect PASS**; commit.

```bash
git add -A && git commit -m "feat(usecases): Reauthenticator step-up verifier"
```

---

### Task 9: `AccountDeletionCleaner` + `AccountDeletionTask`

The worker erasure. Loads the tombstoned user, deletes DB rows in FK order in one transaction, then best-effort on-disk cleanup.

**Files:**
- Create: `api-usecases/.../usecases/AccountDeletionCleaner.kt`, `.../tasks/AccountDeletionTask.kt`
- Test: `api-usecases/.../usecases/AccountDeletionCleanerTest.kt`

**Interfaces:**
- Consumes: `UserRepositoryInterface.findUserByIdIncludingDeleted`/`permanentlyDeleteUser`, `PinRepositoryInterface` (`findAllPinsForUser`, `findAllSoftDeletedPinsForUser`, `permanentlyDeleteAllPinsForUser`), `BoardRepositoryInterface.permanentlyDeleteAllBoardsForUser`, `TagRepositoryInterface.deleteAllTagsForUser`, `ImageRepositoryInterface` (`findByPinId`, `deleteByPinId`), `SessionTokenRepositoryInterface.deleteAllForUser`, `UserPasswordHashRepositoryInterface.deleteForUser`, `ClearPinDownload.clear(pinId)`, `ImageStore.delete(storageKey)`, `RenditionCache.evictImage(imageId)`, `TransactionRunner.inTransaction`.
- Produces: `AccountDeletionCleaner.deleteAccountData(userId: UUID)`; `object AccountDeletionTask { const val KIND = "account.delete"; const val MAX_ATTEMPTS = 5 }`.
- Note: confirm the `Image` domain entity exposes `id: UUID` and `storageKey: String` (used below); if the field is named differently, adjust.

- [ ] **Step 1: Add the task identity**

```kotlin
package fr.geoffreyCoulaud.pinryReborn.api.usecases.tasks

object AccountDeletionTask {
    const val KIND = "account.delete"
    const val MAX_ATTEMPTS = 5
}
```

- [ ] **Step 2: Write the failing test** (verifies order + idempotency + best-effort disk)

```kotlin
class AccountDeletionCleanerTest : BaseTest() {
    private val users = mockk<UserRepositoryInterface>(relaxed = true)
    private val pins = mockk<PinRepositoryInterface>(relaxed = true)
    private val boards = mockk<BoardRepositoryInterface>(relaxed = true)
    private val tags = mockk<TagRepositoryInterface>(relaxed = true)
    private val images = mockk<ImageRepositoryInterface>(relaxed = true)
    private val sessions = mockk<SessionTokenRepositoryInterface>(relaxed = true)
    private val passwords = mockk<UserPasswordHashRepositoryInterface>(relaxed = true)
    private val clearDownload = mockk<ClearPinDownload>(relaxed = true)
    private val imageStore = mockk<ImageStore>(relaxed = true)
    private val renditions = mockk<RenditionCache>(relaxed = true)
    private val tx = mockk<TransactionRunner>()
    private val cleaner = AccountDeletionCleaner(users, pins, boards, tags, images, sessions, passwords, clearDownload, imageStore, renditions, tx)

    private val userId = randomUUID()
    private val user = User(id = userId, name = "u", softDeleted = true)

    @BeforeEach
    fun passThroughTransaction() { every { tx.inTransaction(any<() -> Any?>()) } answers { (firstArg<() -> Any?>())() } }

    @Test
    fun `Given a tombstoned user with a pin+image, Then everything is erased in order and disk cleaned`() {
        // Given
        every { users.findUserByIdIncludingDeleted(userId) } returns user
        val pin = /* build a Pin owned by `user` via the existing test helpers */ TODO_pin(user)
        every { pins.findAllPinsForUser(user) } returns listOf(pin)
        every { pins.findAllSoftDeletedPinsForUser(user) } returns emptyList()
        val image = /* Image with id + storageKey */ TODO_image()
        every { images.findByPinId(pin.id) } returns image
        // When
        cleaner.deleteAccountData(userId)
        // Then
        verifyOrder {
            pins.permanentlyDeleteAllPinsForUser(user)
            boards.permanentlyDeleteAllBoardsForUser(user)
            tags.deleteAllTagsForUser(user)
            sessions.deleteAllForUser(userId)
            passwords.deleteForUser(user)
            users.permanentlyDeleteUser(user)
        }
        verify { imageStore.delete(image.storageKey) }
        verify { renditions.evictImage(image.id) }
    }

    @Test
    fun `Given an already-deleted user, Then it is a no-op`() {
        every { users.findUserByIdIncludingDeleted(userId) } returns null
        cleaner.deleteAccountData(userId)
        verify(exactly = 0) { users.permanentlyDeleteUser(any()) }
    }

    @Test
    fun `Given a rendition eviction failure, Then it is swallowed`() {
        every { users.findUserByIdIncludingDeleted(userId) } returns user
        val pin = TODO_pin(user); val image = TODO_image()
        every { pins.findAllPinsForUser(user) } returns listOf(pin)
        every { pins.findAllSoftDeletedPinsForUser(user) } returns emptyList()
        every { images.findByPinId(pin.id) } returns image
        every { renditions.evictImage(image.id) } throws RuntimeException("disk")
        assertDoesNotThrow { cleaner.deleteAccountData(userId) } // committed DB, disk best-effort
    }
}
```
(Replace `TODO_pin`/`TODO_image` with the project's existing pin/image builders from the test fixtures.)

- [ ] **Step 3: Run — expect FAIL**.

- [ ] **Step 4: Implement**

```kotlin
@ApplicationScoped
class AccountDeletionCleaner(
    private val userRepository: UserRepositoryInterface,
    private val pinRepository: PinRepositoryInterface,
    private val boardRepository: BoardRepositoryInterface,
    private val tagRepository: TagRepositoryInterface,
    private val imageRepository: ImageRepositoryInterface,
    private val sessionTokenRepository: SessionTokenRepositoryInterface,
    private val userPasswordRepository: UserPasswordHashRepositoryInterface,
    private val clearPinDownload: ClearPinDownload,
    private val imageStore: ImageStore,
    private val renditionCache: RenditionCache,
    private val transactionRunner: TransactionRunner,
) {
    fun deleteAccountData(userId: UUID) {
        val user = userRepository.findUserByIdIncludingDeleted(userId) ?: return
        val toEvict = mutableListOf<Pair<String, UUID>>() // storageKey to imageId
        transactionRunner.inTransaction {
            val pins = pinRepository.findAllPinsForUser(user) + pinRepository.findAllSoftDeletedPinsForUser(user)
            for (pin in pins) {
                imageRepository.findByPinId(pin.id)?.let { toEvict += it.storageKey to it.id }
                clearPinDownload.clear(pin.id)
                imageRepository.deleteByPinId(pin.id)
            }
            pinRepository.permanentlyDeleteAllPinsForUser(user)
            boardRepository.permanentlyDeleteAllBoardsForUser(user)
            tagRepository.deleteAllTagsForUser(user)
            sessionTokenRepository.deleteAllForUser(user.id)
            userPasswordRepository.deleteForUser(user)
            userRepository.permanentlyDeleteUser(user)
        }
        for ((storageKey, imageId) in toEvict) {
            imageStore.delete(storageKey)
            runCatching { renditionCache.evictImage(imageId) }
        }
    }
}
```

- [ ] **Step 5: Run — expect PASS**; commit.

```bash
git add -A && git commit -m "feat(usecases): AccountDeletionCleaner erases user data in FK order"
```

---

### Task 10: `AccountDeleter` (request path)

**Files:**
- Create: `api-usecases/.../usecases/AccountDeleter.kt`
- Test: `api-usecases/.../usecases/AccountDeleterTest.kt`

**Interfaces:**
- Consumes: `Reauthenticator.reauthenticate`, `UserRepositoryInterface.markPendingDeletion`, `SessionRevoker.revokeAll`, `EnqueueTask.enqueue`, `TransactionRunner.inTransaction`, `AccountDeletionTask`.
- Produces: `AccountDeleter.requestDeletion(user: User, factor: String)`.

- [ ] **Step 1: Write the failing test**

```kotlin
class AccountDeleterTest : BaseTest() {
    private val reauth = mockk<Reauthenticator>(relaxed = true)
    private val users = mockk<UserRepositoryInterface>(relaxed = true)
    private val sessionRevoker = mockk<SessionRevoker>(relaxed = true)
    private val enqueue = mockk<EnqueueTask>(relaxed = true)
    private val tx = mockk<TransactionRunner>()
    private val deleter = AccountDeleter(reauth, users, sessionRevoker, enqueue, tx)
    private val user = User(id = randomUUID(), name = "u")

    @BeforeEach
    fun passThroughTransaction() { every { tx.inTransaction(any<() -> Any?>()) } answers { (firstArg<() -> Any?>())() } }

    @Test
    fun `Given a valid factor, Then it tombstones, revokes and enqueues`() {
        // When
        deleter.requestDeletion(user, "secret")
        // Then
        verify { reauth.reauthenticate(user, "secret") }
        verifyOrder {
            users.markPendingDeletion(user)
            sessionRevoker.revokeAll(user)
            enqueue.enqueue(
                kind = AccountDeletionTask.KIND,
                payload = user.id.toString(),
                maxAttempts = AccountDeletionTask.MAX_ATTEMPTS,
                dedupKey = "${AccountDeletionTask.KIND}:${user.id}",
            )
        }
    }

    @Test
    fun `Given a failed step-up, Then nothing happens`() {
        every { reauth.reauthenticate(user, "bad") } throws ReauthenticationError()
        assertThrows<ReauthenticationError> { deleter.requestDeletion(user, "bad") }
        verify(exactly = 0) { users.markPendingDeletion(any()) }
    }
}
```

- [ ] **Step 2: Run — expect FAIL**.

- [ ] **Step 3: Implement**

```kotlin
@ApplicationScoped
class AccountDeleter(
    private val reauthenticator: Reauthenticator,
    private val userRepository: UserRepositoryInterface,
    private val sessionRevoker: SessionRevoker,
    private val enqueueTask: EnqueueTask,
    private val transactionRunner: TransactionRunner,
) {
    fun requestDeletion(user: User, factor: String) {
        reauthenticator.reauthenticate(user, factor)
        transactionRunner.inTransaction {
            userRepository.markPendingDeletion(user)
            sessionRevoker.revokeAll(user)
            enqueueTask.enqueue(
                kind = AccountDeletionTask.KIND,
                payload = user.id.toString(),
                maxAttempts = AccountDeletionTask.MAX_ATTEMPTS,
                dedupKey = "${AccountDeletionTask.KIND}:${user.id}",
            )
        }
    }
}
```

- [ ] **Step 4: Run — expect PASS**; commit.

```bash
git add -A && git commit -m "feat(usecases): AccountDeleter tombstones, revokes and enqueues deletion"
```

---

### Task 11: `DELETE /me` + step-up header + task handler (integration, end-to-end)

**Files:**
- Create: `api-presentation-quarkus/.../security/ReauthenticationHeader.kt`
- Create: `api-usecases/.../usecases/exceptions/ReauthenticationHeaderError.kt` (`MalformedReauthenticationError`)
- Modify: `api-usecases/.../usecases/exceptions/ErrorCode.kt` (+`UNSUPPORTED_REAUTHENTICATION_FACTOR`), `api-presentation-quarkus/.../mappers/BaseErrorMapper.kt`
- Create: `api-presentation-quarkus/.../tasks/AccountDeletionTaskHandler.kt`
- Modify: `api-presentation-quarkus/.../controllers/MeController.kt`
- Test: `api-application/.../MeDeleteIntegrationTest.kt`

**Interfaces:**
- Consumes: `AccountDeleter.requestDeletion`, `AccountDeletionCleaner.deleteAccountData`, `TaskHandler`, `AccountDeletionTask`.
- Produces: `ReauthenticationHeader.parsePasswordFactor(headerValue: String?): String`; `MalformedReauthenticationError` (`UNSUPPORTED_REAUTHENTICATION_FACTOR`).

- [ ] **Step 1: Add the error code + mapper arm** — `ErrorCode.UNSUPPORTED_REAUTHENTICATION_FACTOR`; in `BaseErrorMapper.statusFor`: `ErrorCode.UNSUPPORTED_REAUTHENTICATION_FACTOR -> Response.Status.BAD_REQUEST.statusCode`.

- [ ] **Step 2: Add the error class**

```kotlin
class MalformedReauthenticationError :
    BaseError(message = "Malformed or unsupported re-authentication factor",
        code = ErrorCode.UNSUPPORTED_REAUTHENTICATION_FACTOR)
```

- [ ] **Step 3: Write the header parser + its unit test** (presentation test)

```kotlin
// ReauthenticationHeader.kt
object ReauthenticationHeader {
    const val HEADER = "X-Reauthentication"
    private const val PASSWORD_KIND = "password"

    /** Parse `<kind> <base64url(value)>`. Missing header -> 403 (ReauthenticationError);
     *  unparseable / unsupported kind / bad base64 -> 400 (MalformedReauthenticationError). */
    fun parsePasswordFactor(headerValue: String?): String {
        if (headerValue == null) throw ReauthenticationError()
        val parts = headerValue.trim().split(" ", limit = 2)
        if (parts.size != 2 || parts[0] != PASSWORD_KIND) throw MalformedReauthenticationError()
        return try {
            String(java.util.Base64.getUrlDecoder().decode(parts[1]), Charsets.UTF_8)
        } catch (e: IllegalArgumentException) {
            throw MalformedReauthenticationError()
        }
    }
}
```

Unit test (`ReauthenticationHeaderTest`), covering all four branches: valid → decoded value; `null` → `ReauthenticationError`; `"totp abc"` (wrong kind) / `"password"` (no value) → `MalformedReauthenticationError`; `"password !!!"` (bad base64) → `MalformedReauthenticationError`.

- [ ] **Step 4: Add the task handler**

```kotlin
package fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.tasks

import fr.geoffreyCoulaud.pinryReborn.api.usecases.AccountDeletionCleaner
import fr.geoffreyCoulaud.pinryReborn.api.usecases.tasks.AccountDeletionTask
import fr.geoffreyCoulaud.pinryReborn.api.usecases.tasks.TaskContext
import fr.geoffreyCoulaud.pinryReborn.api.usecases.tasks.TaskHandler
import jakarta.enterprise.context.ApplicationScoped
import java.util.UUID

@ApplicationScoped
class AccountDeletionTaskHandler(
    private val accountDeletionCleaner: AccountDeletionCleaner,
) : TaskHandler {
    override val kind = AccountDeletionTask.KIND
    override fun handle(payload: String, context: TaskContext) =
        accountDeletionCleaner.deleteAccountData(UUID.fromString(payload))
}
```

(No registry edit — `Instance<TaskHandler>` auto-discovers it.)

- [ ] **Step 5: Write the failing integration test**

```kotlin
@QuarkusTest
class MeDeleteIntegrationTest : IntegrationTest() {
    private fun stepUp(password: String) =
        "password " + java.util.Base64.getUrlEncoder().encodeToString(password.toByteArray())

    @Test
    fun `Given a valid step-up, Then 202 and the account becomes unusable`() {
        val auth = createAuthenticatedUser(password = "password123")
        given().authenticatedAs(auth).header("X-Reauthentication", stepUp("password123"))
            .delete("/api/v1/me").then().statusCode(202)
        // token rejected, login refused
        given().authenticatedAs(auth).get("/api/v1/me").then().statusCode(401)
        given().contentType("application/json")
            .body("""{"name":"${auth.user.name}","password":"password123"}""")
            .post("/api/v1/sessions").then().statusCode(401)
    }

    @Test
    fun `Given a wrong step-up, Then 403 and the account survives`() {
        val auth = createAuthenticatedUser(password = "password123")
        given().authenticatedAs(auth).header("X-Reauthentication", stepUp("wrongpass"))
            .delete("/api/v1/me").then().statusCode(403).body("code", equalTo("REAUTHENTICATION_FAILED"))
        given().authenticatedAs(auth).get("/api/v1/me").then().statusCode(200)
    }

    @Test
    fun `Given an unsupported factor kind, Then 400`() {
        val auth = createAuthenticatedUser()
        given().authenticatedAs(auth).header("X-Reauthentication", "totp 123456")
            .delete("/api/v1/me").then().statusCode(400)
            .body("code", equalTo("UNSUPPORTED_REAUTHENTICATION_FACTOR"))
    }

    @Test
    fun `Given no step-up header, Then 403`() {
        val auth = createAuthenticatedUser()
        given().authenticatedAs(auth).delete("/api/v1/me").then().statusCode(403)
    }

    @Test
    fun `Given no token, Then 401`() {
        given().header("X-Reauthentication", stepUp("x")).delete("/api/v1/me").then().statusCode(401)
    }
}
```

> **Note on async:** the default Quarkus test profile runs the task worker, so the enqueued `account.delete` task is processed shortly after the 202. The assertions above only rely on the synchronous part (tombstone + revoke → 401 on token and login). If you want to assert full data erasure end-to-end, add a test that seeds a pin+image, deletes the account, then polls (e.g. Awaitility, if already a dependency) until `GET /api/v1/me` re-registration of the same name succeeds — proof the task freed the username. Keep the erasure-order guarantees in the `AccountDeletionCleanerTest` (Task 9); do not duplicate them here.

- [ ] **Step 6: Add the endpoint** to `MeController` (inject `AccountDeleter`)

```kotlin
@DELETE
@Authenticated
fun deleteAccount(@HeaderParam(ReauthenticationHeader.HEADER) reauthHeader: String?): RestResponse<Unit> {
    val factor = ReauthenticationHeader.parsePasswordFactor(reauthHeader)
    accountDeleter.requestDeletion(securityIdentity.getUser(), factor)
    return RestResponse.ResponseBuilder.create<Unit>(RestResponse.Status.ACCEPTED).build()
}
```

- [ ] **Step 7: Run — expect PASS**; then `./gradlew :api-presentation-quarkus:test :api-application:test`.

- [ ] **Step 8: Commit**

```bash
git add -A
git commit -m "feat(presentation): DELETE /me (async account deletion) with X-Reauthentication step-up"
```

---

### Task 12: Regenerate OpenAPI + full gate

**Files:**
- Modify: `docs/openapi.json`

- [ ] **Step 1: Regenerate the OpenAPI document** the way the repo already does it (check `README`/build for the task; typically the app exposes `/q/openapi` in dev, or a gradle task writes `docs/openapi.json`). Confirm `PUT /api/v1/me/password` and `DELETE /api/v1/me` appear.

- [ ] **Step 2: Run the full gate**

Run: `./gradlew check koverVerify`
Expected: PASS — all modules, `detekt` clean, **100% branch coverage** per package. If Kover flags an uncovered branch, add the missing test (both sides of every conditional) before proceeding.

- [ ] **Step 3: Commit**

```bash
git add -A
git commit -m "docs(openapi): regenerate for profile-management endpoints"
```

---

## Self-Review

**Spec coverage** (each spec section → task):
- §2 change password → Tasks 3, 5, 6. Delete account → Tasks 2, 7, 9, 10, 11. Step-up brick → Tasks 8, 11. Password history → Task 3, 5.
- §3 hard-delete/async/tombstone → Tasks 2, 9, 10, 11. Ebean `@SoftDelete` mirrored on domain → Task 2. Change-password intrinsic current password → Task 5. Step-up header + factor-kind + base64 → Task 11. Password history / no upsert → Task 3. Revoke-all → Task 5. 403 for re-auth → Tasks 5, 11. Ports (TransactionRunner, PasswordHasher) → Tasks 1, 5, 9, 10.
- §4 `User.softDeleted` → Task 2; `PasswordHasher` port → Task 1; repo additions → Tasks 2, 3, 7; `AccountDeletionTask` → Task 9.
- §5 endpoints/DTO/header → Tasks 6, 11. §6 change-password flow → Task 5. §7 delete request → Task 10. §8 cleaner → Task 9. §9 re-auth forms → Tasks 5 (intrinsic), 8+11 (generic). §10 wiring → all. §11 errors → Tasks 5 (403, 422), 11 (400). §12 migration 1.9 → Task 2 (no hash-table schema change). §13 tests → each task. §14 risks → §14 kept as guidance (enumeration-vs-tombstone check belongs in Task 9/2 review).

**Placeholder scan:** the only intentional gaps are `TODO_pin`/`TODO_image` in Task 9's test (replace with the project's existing pin/image test builders — a fresh reviewer must wire the real fixtures) and the OpenAPI regeneration command (repo-specific). No "add error handling"/"similar to Task N" placeholders.

**Type consistency:** `findCurrentPasswordHash` (Task 3) is used identically in Tasks 5 & 8; `markPendingDeletion`/`permanentlyDeleteUser`/`findUserByIdIncludingDeleted` (Task 2) match their uses in Tasks 9 & 10; `AccountDeletionTask.KIND`/`MAX_ATTEMPTS` (Task 9) match Task 10's enqueue and Task 11's handler; `PasswordHasher.hash`/`matches` (Task 1) match all consumers.

**Open verification for the executor (from spec §14 / recon):**
- Confirm the `Image` domain entity's field names (`id`, `storageKey`) used in Task 9; adjust if different.
- Confirm the per-user pin/board/tag finders key on the FK column (`author_id`) and are not filtered out by the user's Ebean tombstone; if any eagerly joins `UserModel`, call `setIncludeSoftDeletes` in the cleaner (spec §14).
- Confirm `RestResponse.ResponseBuilder.create<Unit>(Status.NO_CONTENT/ACCEPTED)` compiles in this Quarkus version; else use the idiomatic no-body builder already used elsewhere.
