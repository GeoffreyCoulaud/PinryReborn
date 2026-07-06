# Réduction de dette (auth · erreurs · validation) — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Supprimer l'auto-login et l'oracle temporel de l'auth, centraliser la gestion d'erreurs HTTP en RFC 7807, ajouter la validation d'entrée, rendre les usernames insensibles à la casse, et nettoyer deux vestiges.

**Architecture:** Kotlin, Clean Architecture multi-modules (`api-domain`, `api-usecases`, `api-persistence-sqlite`, `api-presentation-quarkus`, `api-application`). L'auth durcie vit dans `api-usecases`. La gestion d'erreurs (mappers + DTO `ProblemDetail`) et la validation vivent dans `api-presentation-quarkus`, câblées runtime par `api-application`. La casse-insensibilité combine un lookup Ebean `ieq` et une migration SQLite `collate nocase`.

**Tech Stack:** Kotlin 2.4, Quarkus 3.37 (quarkus-rest, quarkus-security, quarkus-hibernate-validator), Ebean 19, SQLite, jBCrypt, JUnit 5 + MockK + REST Assured, detekt 1.23.8.

## Global Constraints

- **Layering strict** : présentation dépend de `api-usecases`/`api-domain` ; jamais de persistence. La table `ErrorCode → statut HTTP` vit en présentation, pas sur l'enum `ErrorCode` (usecases).
- **Aucune dépendance tierce nouvelle** hors `io.quarkus:quarkus-hibernate-validator` (extension officielle, version via BOM).
- **Corps d'erreur = RFC 7807** : media type `application/problem+json`, champs `type`/`title`/`status`/`detail`/`instance` + extension `code`.
- **Gate** : `./gradlew detekt test` doit être vert. Contraintes detekt à respecter : `MaxLineLength = 120` ; `ReturnCount.max = 2` ; `ThrowsCount.max = 2` ; `NewLineAtEndOfFile` (chaque fichier finit par un `\n`) ; `MagicNumber` actif mais `ignoreNamedArgument = true` (⇒ toujours utiliser des arguments nommés dans les annotations : `@Size(min = 3, max = 50)`).
- **Username** : ASCII-only, pattern `^[A-Za-z0-9._-]+$`, longueur 3 à 50 ; unicité insensible à la casse. **Password** : longueur 8 à 72 (72 = limite de troncature jBCrypt).
- **Pas d'em-dash / en-dash** dans les messages destinés à l'utilisateur (messages d'erreur inclus) : utiliser `:`, `.`, parenthèses ou `-`.
- **Commits fréquents**, un par tâche, message conventional-commit, terminé par :
  `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>`

---

## Task 1: Quick wins (GreetingController + addPackage)

Deux nettoyages isolés et sûrs, groupés en un commit.

**Files:**
- Delete: `api-presentation-quarkus/src/main/kotlin/fr/geoffreyCoulaud/pinryReborn/api/presentation/quarkus/controllers/GreetingController.kt`
- Delete: `api-application/src/test/kotlin/fr/geoffreyCoulaud/pinryReborn/api/application/GreetingIntegrationTest.kt`
- Modify: `api-persistence-sqlite/src/main/kotlin/fr/geoffreyCoulaud/pinryReborn/api/persistence/sqlite/EbeanDatabaseProducer.kt:445`

**Interfaces:**
- Consumes: rien.
- Produces: rien (aucun autre code ne référence `GreetingController` hors son test, déjà vérifié).

- [ ] **Step 1: Supprimer les deux fichiers Greeting**

```bash
git rm api-presentation-quarkus/src/main/kotlin/fr/geoffreyCoulaud/pinryReborn/api/presentation/quarkus/controllers/GreetingController.kt
git rm api-application/src/test/kotlin/fr/geoffreyCoulaud/pinryReborn/api/application/GreetingIntegrationTest.kt
```

- [ ] **Step 2: Corriger le package Ebean**

Dans `EbeanDatabaseProducer.kt`, remplacer la ligne `addPackage` :

```kotlin
            .addPackage("fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.models")
```

(remplace `"fr.geoffreyCoulaud.pinryReborn.adapters.persistence.models"`, qui pointait un package inexistant. La valeur correcte est celle déjà utilisée par `ebean-test.properties`.)

- [ ] **Step 3: Vérifier le gate**

Run: `./gradlew detekt test`
Expected: PASS. Aucun test ne référence plus `/hello` ; la construction Ebean prod utilise désormais le bon package.

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "$(cat <<'EOF'
chore: supprime GreetingController vestige et corrige le package Ebean

GreetingController et son test d'intégration étaient un vestige (health
réel = /q/health). addPackage pointait un package inexistant
(adapters.persistence.models) ; le vrai est api.persistence.sqlite.models.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

---

## Task 2: Durcissement de l'authentification

Supprime l'auto-login (fail-closed) et l'oracle temporel (coût BCrypt constant). Met à jour le test use-case et supprime le test d'intégration devenu caduc.

**Files:**
- Modify: `api-usecases/src/main/kotlin/fr/geoffreyCoulaud/pinryReborn/api/usecases/UserAuthenticator.kt`
- Test: `api-usecases/src/test/kotlin/fr/geoffreyCoulaud/pinryReborn/api/usecases/UserAuthenticatorTest.kt`
- Modify (delete test): `api-application/src/test/kotlin/fr/geoffreyCoulaud/pinryReborn/api/application/PinCreationIntegrationTest.kt`

**Interfaces:**
- Consumes: `UserRepositoryInterface.findUserByName(name): User?`, `UserPasswordHashRepositoryInterface.findUserPasswordHash(user): HashedPassword?`, exceptions `UserAuthenticationUserDoesNotExistError`, `UserAuthenticationInvalidPasswordError`.
- Produces: `UserAuthenticator.authenticate(login): User` (signature inchangée). Nouveau comportement : hash absent ⇒ `UserAuthenticationInvalidPasswordError` (plus d'auto-login) ; un `BCrypt.checkpw` est exécuté dans tous les chemins d'échec.

- [ ] **Step 1: Réécrire le test use-case (Red)**

Dans `UserAuthenticatorTest.kt`, remplacer intégralement le test `When authenticating with basic auth and no saved password, then should work` par la version qui attend un échec :

```kotlin
    @Test
    fun `When authenticating with basic auth and no saved password, then should throw`() {
        // Given
        val user = User(id = UUID.randomUUID(), name = createRandomString())
        val password = createRandomString()
        val login = BasicAuthLogin(user.name, password)
        every { userRepository.findUserByName(any()) } returns user
        every { userPasswordRepository.findUserPasswordHash((any())) } returns null

        // When, Then
        assertThrows<UserAuthenticationInvalidPasswordError> {
            useCase.authenticate(login)
        }
    }
```

(Les trois autres tests du fichier restent inchangés.)

- [ ] **Step 2: Lancer le test, vérifier qu'il échoue (Red)**

Run: `./gradlew :api-usecases:test --tests "UserAuthenticatorTest"`
Expected: FAIL sur le test réécrit (l'implémentation actuelle renvoie l'utilisateur au lieu de lever `UserAuthenticationInvalidPasswordError`).

- [ ] **Step 3: Réécrire `UserAuthenticator` (Green)**

Remplacer intégralement le contenu de `UserAuthenticator.kt` par :

```kotlin
package fr.geoffreyCoulaud.pinryReborn.api.usecases

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.HashedPassword
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Login
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Login.BasicAuthLogin
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.User
import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.PasswordHashAlgorithm
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.UserPasswordHashRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.UserRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.UserAuthenticationInvalidPasswordError
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.UserAuthenticationUserDoesNotExistError
import jakarta.enterprise.context.ApplicationScoped
import org.mindrot.jbcrypt.BCrypt

@ApplicationScoped
class UserAuthenticator(
    private val userRepository: UserRepositoryInterface,
    private val userPasswordRepository: UserPasswordHashRepositoryInterface,
) {
    /**
     * Précalculé une fois. Sert à payer un coût BCrypt constant lorsque l'utilisateur
     * n'existe pas ou n'a pas de hash, afin d'éviter un oracle temporel (énumération).
     */
    private val dummyHash: String = BCrypt.hashpw("constant-time-guard", BCrypt.gensalt())

    fun authenticate(login: Login): User =
        when (login) {
            is BasicAuthLogin -> checkLogin(login)
        }

    private fun checkLogin(login: BasicAuthLogin): User {
        val user = userRepository.findUserByName(login.userName)
        val hash = user?.let { userPasswordRepository.findUserPasswordHash(it) }
        if (user == null || hash == null) {
            // Coût constant même sans utilisateur/hash : le résultat est ignoré.
            BCrypt.checkpw(login.password, dummyHash)
            throw if (user == null) {
                UserAuthenticationUserDoesNotExistError()
            } else {
                UserAuthenticationInvalidPasswordError()
            }
        }
        return user.takeIf { checkPassword(login.password, hash) }
            ?: throw UserAuthenticationInvalidPasswordError()
    }

    private fun checkPassword(
        received: String,
        stored: HashedPassword,
    ): Boolean {
        when (stored.algorithm) {
            PasswordHashAlgorithm.BCRYPT -> return BCrypt.checkpw(received, stored.hash)
        }
    }
}
```

Note detekt : `checkLogin` a 1 `return` (ReturnCount ≤ 2 OK) et 2 `throw` (ThrowsCount ≤ 2 OK).

- [ ] **Step 4: Supprimer le test d'intégration caduc**

Dans `PinCreationIntegrationTest.kt`, supprimer intégralement la méthode `creating a pin with user without password succeeds` (le cas d'un utilisateur sans mot de passe pouvant s'authentifier n'existe plus). Ne pas laisser d'import inutilisé.

- [ ] **Step 5: Lancer les tests (Green)**

Run: `./gradlew :api-usecases:test --tests "UserAuthenticatorTest"`
Expected: PASS (les 4 tests).

Run: `./gradlew detekt test`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "$(cat <<'EOF'
fix(auth): supprime l'auto-login sans hash et l'oracle temporel

Un utilisateur sans hash n'est plus authentifié (fail-closed). Le coût
BCrypt est désormais payé dans tous les chemins d'échec (utilisateur
inexistant, hash absent, mauvais mot de passe) pour égaliser le temps de
réponse et empêcher l'énumération par canal temporel.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

---

## Task 3: Infrastructure de gestion d'erreurs RFC 7807

Crée le DTO `ProblemDetail` et deux `ExceptionMapper` (métier + auth). À l'issue de cette tâche, `UserController` (sans try/catch) fait déjà passer le nom dupliqué de 500 à 409.

**Files:**
- Create: `api-presentation-quarkus/src/main/kotlin/fr/geoffreyCoulaud/pinryReborn/api/presentation/quarkus/dtos/output/ProblemDetail.kt`
- Create: `api-presentation-quarkus/src/main/kotlin/fr/geoffreyCoulaud/pinryReborn/api/presentation/quarkus/mappers/BaseErrorMapper.kt`
- Create: `api-presentation-quarkus/src/main/kotlin/fr/geoffreyCoulaud/pinryReborn/api/presentation/quarkus/mappers/AuthenticationFailedExceptionMapper.kt`
- Test: `api-application/src/test/kotlin/fr/geoffreyCoulaud/pinryReborn/api/application/UserCreationIntegrationTest.kt`

**Interfaces:**
- Consumes: `BaseError.code: ErrorCode`, `BaseError.message: String?`, enum `ErrorCode` (10 valeurs), `io.quarkus.security.AuthenticationFailedException`.
- Produces:
  - `ProblemDetail(type, title, status, detail, instance, code)` (data class, tous champs sérialisés Jackson).
  - `BaseErrorMapper : ExceptionMapper<BaseError>` avec `PROBLEM_JSON = "application/problem+json"`.
  - `AuthenticationFailedExceptionMapper : ExceptionMapper<AuthenticationFailedException>`.

- [ ] **Step 1: Créer le DTO `ProblemDetail`**

```kotlin
package fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.dtos.output

/**
 * RFC 7807 Problem Details (application/problem+json).
 * [code] est un membre d'extension portant le nom du code d'erreur applicatif.
 */
data class ProblemDetail(
    val type: String = "about:blank",
    val title: String,
    val status: Int,
    val detail: String?,
    val instance: String,
    val code: String,
)
```

- [ ] **Step 2: Créer `BaseErrorMapper`**

```kotlin
package fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.mappers

import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.dtos.output.ProblemDetail
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.BaseError
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.ErrorCode
import jakarta.ws.rs.core.Context
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.core.UriInfo
import jakarta.ws.rs.ext.ExceptionMapper
import jakarta.ws.rs.ext.Provider

@Provider
class BaseErrorMapper : ExceptionMapper<BaseError> {
    @Context
    lateinit var uriInfo: UriInfo

    override fun toResponse(exception: BaseError): Response {
        val status = statusFor(exception.code)
        val problem = ProblemDetail(
            title = status.reasonPhrase,
            status = status.statusCode,
            detail = exception.message,
            instance = uriInfo.path,
            code = exception.code.name,
        )
        return Response
            .status(status)
            .entity(problem)
            .type(PROBLEM_JSON)
            .build()
    }

    private fun statusFor(code: ErrorCode): Response.Status =
        when (code) {
            ErrorCode.USERNAME_ALREADY_EXISTS -> Response.Status.CONFLICT
            ErrorCode.PIN_DOES_NOT_EXIST -> Response.Status.NOT_FOUND
            ErrorCode.PIN_INSUFFICIENT_PERMISSIONS -> Response.Status.FORBIDDEN
            ErrorCode.PIN_NOT_SOFT_DELETED -> Response.Status.CONFLICT
            ErrorCode.PIN_ALREADY_SOFT_DELETED -> Response.Status.CONFLICT
            ErrorCode.SEARCH_EMPTY_QUERY -> Response.Status.BAD_REQUEST
            ErrorCode.INVALID_LOGIN -> Response.Status.BAD_REQUEST
            ErrorCode.USER_DOES_NOT_EXIST -> Response.Status.UNAUTHORIZED
            ErrorCode.INVALID_PASSWORD -> Response.Status.UNAUTHORIZED
            ErrorCode.INVALID_HTTP_AUTHORIZATION_SCHEME -> Response.Status.UNAUTHORIZED
        }

    companion object {
        const val PROBLEM_JSON = "application/problem+json"
    }
}
```

Le `when` couvre les 10 `ErrorCode` (exhaustif, pas de `else`). Les codes d'auth (401) sont un filet : en pratique l'auth passe par `AuthenticationFailedException` (Step 3).

- [ ] **Step 3: Créer `AuthenticationFailedExceptionMapper`**

```kotlin
package fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.mappers

import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.dtos.output.ProblemDetail
import io.quarkus.security.AuthenticationFailedException
import jakarta.annotation.Priority
import jakarta.ws.rs.Priorities
import jakarta.ws.rs.core.Context
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.core.UriInfo
import jakarta.ws.rs.ext.ExceptionMapper
import jakarta.ws.rs.ext.Provider

@Provider
@Priority(Priorities.AUTHENTICATION)
class AuthenticationFailedExceptionMapper : ExceptionMapper<AuthenticationFailedException> {
    @Context
    lateinit var uriInfo: UriInfo

    override fun toResponse(exception: AuthenticationFailedException): Response {
        val status = Response.Status.UNAUTHORIZED
        val problem = ProblemDetail(
            title = status.reasonPhrase,
            status = status.statusCode,
            detail = "Invalid username or password",
            instance = uriInfo.path,
            code = "AUTHENTICATION_FAILED",
        )
        return Response
            .status(status)
            .header("WWW-Authenticate", "Basic realm=\"Quarkus\"")
            .entity(problem)
            .type("application/problem+json")
            .build()
    }
}
```

Message volontairement uniforme (aucune fuite existence/mot de passe). Header `WWW-Authenticate` conservé pour le challenge Basic.

- [ ] **Step 4: Mettre à jour le test du nom dupliqué (500 → 409 + problem+json)**

Dans `UserCreationIntegrationTest.kt`, remplacer la fin du test `creating a user with duplicate name fails` (le bloc `.statusCode(500)`) par :

```kotlin
        // Try to create user with same name
        given()
            .contentType(ContentType.JSON)
            .body("""{"name": "duplicate_user", "password": "password123"}""")
            .`when`()
            .post("/api/v1/users")
            .then()
            .statusCode(409)
            .contentType("application/problem+json")
            .body("code", equalTo("USERNAME_ALREADY_EXISTS"))
            .body("status", equalTo(409))
```

- [ ] **Step 5: Lancer les tests**

Run: `./gradlew :api-application:test --tests "UserCreationIntegrationTest"`
Expected: PASS. `UserController` n'a pas de try/catch : `UsernameAlreadyTakenError` remonte à `BaseErrorMapper` → 409 `application/problem+json` avec `code = USERNAME_ALREADY_EXISTS`.

Run: `./gradlew detekt test`
Expected: PASS (les tests d'auth existants qui assertent `statusCode(401)` restent verts : ajouter un corps problem+json ne change pas le statut).

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "$(cat <<'EOF'
feat(errors): mapper d'exceptions global RFC 7807 (métier + auth)

Introduit ProblemDetail (application/problem+json) et deux ExceptionMapper:
BaseError -> statut via ErrorCode, AuthenticationFailedException -> 401
uniforme. Le nom d'utilisateur dupliqué passe de 500 à 409.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

---

## Task 4: Retrait des try/catch des controllers

Les mappers de la Task 3 rendent les `try/catch` redondants. On les retire ; les tests d'intégration existants (qui assertent 404/403/409/400) protègent le refactor.

**Files:**
- Modify: `api-presentation-quarkus/.../controllers/PinController.kt`
- Modify: `api-presentation-quarkus/.../controllers/PinRecycleBinController.kt`
- Modify: `api-presentation-quarkus/.../controllers/PinSearchController.kt`
- Modify: `api-presentation-quarkus/.../controllers/TagSearchController.kt`

**Interfaces:**
- Consumes: `BaseErrorMapper` (Task 3) traduit toutes les exceptions `BaseError` propagées.
- Produces: controllers sans mapping d'erreur manuel ; signatures publiques (types de retour `RestResponse<...>`) inchangées.

- [ ] **Step 1: Réécrire `PinController` sans try/catch**

Remplacer les corps des méthodes `getPin`, `listPins`, `softDeletePin`, `setTags` par le chemin heureux seul, et **retirer les imports d'exceptions devenus inutiles** (`PinDeletionPermissionError`, `PinDeletionPinAlreadySoftDeletedError`, `PinDeletionPinDoesNotExistError`, `PinRetrievalPermissionError`, `PinRetrievalPinDoesNotExistError`, `PinTaggingPermissionError`, `PinTaggingPinDoesNotExistError`, `PinTaggingSoftDeletedPinError`) ainsi que l'import `ResponseBuilder` s'il n'est plus utilisé (il reste utilisé par `createPin`, donc le garder). Méthodes cibles :

```kotlin
    @GET
    @Authenticated
    @Path("/{pinId}")
    fun getPin(pinId: UUID): RestResponse<PinOutputDto> {
        val user = securityIdentity.getUser()
        return pinGetter
            .getPinForUser(pinId = pinId, reader = user)
            .toDto()
            .let { RestResponse.ok(it) }
    }
```

```kotlin
    @GET
    @Authenticated
    fun listPins(
        @QueryParam("cursor") @Base64Json cursorInput: CursorDto? = null,
        @QueryParam("pageSize") pageSizeInput: Int? = null,
        @QueryParam("sort") sortInput: PinSortStrategyInputEnum? = null,
    ): RestResponse<PinListOutputDto> {
        val user = securityIdentity.getUser()
        val pageSize = pageSizeInput ?: DEFAULT_PAGE_SIZE
        val sort = sortInput?.toDomain() ?: PinSortStrategy.CREATED_AT_ASC
        val cursor = cursorInput?.let { cursorInput.toDomain() }

        return pinGetter
            .listPinsPaginatedForUser(reader = user, cursor = cursor, pageSize = pageSize, sort = sort)
            .toDto()
            .let { RestResponse.ok(it) }
    }
```

```kotlin
    @DELETE
    @Authenticated
    @Path("/{pinId}")
    fun softDeletePin(pinId: UUID): RestResponse<Void> {
        val user = securityIdentity.getUser()
        pinRecycleBin.softDelete(pinId = pinId, user = user)
        return RestResponse.noContent()
    }
```

```kotlin
    @PUT
    @Authenticated
    @Path("/{pinId}/tags")
    fun setTags(pinId: UUID, tagsDto: PinTagsInputDto): RestResponse<PinOutputDto> {
        val user = securityIdentity.getUser()
        return pinTagger
            .setTags(pinId = pinId, tagNames = tagsDto.tags, user = user)
            .toDto()
            .let { RestResponse.ok(it) }
    }
```

`createPin` reste inchangée (elle n'avait pas de try/catch et garde son `ResponseBuilder.created(...)`).

- [ ] **Step 2: Réécrire `PinRecycleBinController` sans try/catch**

Méthodes cibles (retirer aussi les imports d'exceptions `PinDeletion*`/`PinRetrieval*` devenus inutiles ; `ResponseBuilder` n'est plus utilisé nulle part ici → retirer son import) :

```kotlin
    @GET
    @Authenticated
    fun listRecycledPins(
        @QueryParam("cursor") @Base64Json cursorInput: CursorDto? = null,
        @QueryParam("pageSize") pageSizeInput: Int? = null,
        @QueryParam("sort") sortInput: PinRecycleBinSortStrategyInputEnum? = null,
    ): RestResponse<PinListOutputDto> {
        val user = securityIdentity.getUser()
        val pageSize = pageSizeInput ?: DEFAULT_PAGE_SIZE
        val sort = sortInput?.toDomain() ?: PinSortStrategy.DELETED_AT_DESC
        val cursor = cursorInput?.toDomain()

        return pinRecycleBinGetter
            .listSoftDeletedPinsPaginatedForUser(reader = user, cursor = cursor, pageSize = pageSize, sort = sort)
            .toDto()
            .let { RestResponse.ok(it) }
    }

    @POST
    @Authenticated
    @Path("/{pinId}/restore")
    fun restorePin(pinId: UUID): RestResponse<PinOutputDto> {
        val user = securityIdentity.getUser()
        return pinRecycleBin
            .restore(pinId = pinId, user = user)
            .toDto()
            .let { RestResponse.ok(it) }
    }

    @DELETE
    @Authenticated
    @Path("/{pinId}")
    fun permanentlyDeletePin(pinId: UUID): RestResponse<Void> {
        val user = securityIdentity.getUser()
        pinRecycleBin.permanentlyDelete(pinId = pinId, user = user)
        return RestResponse.noContent()
    }
```

`emptyRecycleBin` reste inchangée (déjà sans try/catch).

- [ ] **Step 3: Simplifier les controllers de recherche**

Dans `PinSearchController.kt` et `TagSearchController.kt`, conserver la pré-validation manuelle du query param (`query.isNullOrBlank()` → 400) mais retirer le `try/catch (SearchEmptyQueryError)` devenu inutile (avec la pré-validation, la query n'est jamais vide ; et si elle l'était, `SearchEmptyQueryError` remonterait au `BaseErrorMapper` → 400). Retirer l'import `SearchEmptyQueryError`. Corps cible (`PinSearchController`) :

```kotlin
    @GET
    @Authenticated
    @Path("/search")
    fun searchPins(
        @QueryParam("q") query: String?,
        @QueryParam("limit") limitParam: Int?,
    ): RestResponse<PinSearchOutputDto> {
        val user = securityIdentity.getUser()

        if (query.isNullOrBlank()) {
            return RestResponse.status(RestResponse.Status.BAD_REQUEST)
        }

        val limit = (limitParam ?: DEFAULT_LIMIT).coerceAtMost(MAX_LIMIT)

        return pinSearcher
            .searchPins(user = user, query = query, limit = limit)
            .toPinSearchDto()
            .let { RestResponse.ok(it) }
    }
```

Appliquer la transformation symétrique à `TagSearchController.searchTags` (avec `tagSearcher` / `toTagSearchDto`).

- [ ] **Step 4: Lancer la suite complète**

Run: `./gradlew detekt test`
Expected: PASS. Les tests d'intégration Pin (retrieval 404, permission 403, soft-delete 409, etc.) valident que le `BaseErrorMapper` reproduit exactement l'ancien mapping. `ReturnCount ≤ 2` respecté (chaque méthode a 1 ou 2 `return`).

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "$(cat <<'EOF'
refactor(errors): retire les try/catch des controllers

Les mappers globaux remplacent le mapping d'erreurs dupliqué. Les
controllers ne gardent que le chemin heureux ; la pré-validation des
query params de recherche est conservee.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

---

## Task 5: Validation d'entrée (hibernate-validator + RFC 7807)

Ajoute la validation Bean sur les 3 DTOs, `@Valid` sur les bodies, et un mapper de violations en problem+json.

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `api-presentation-quarkus/build.gradle.kts`
- Modify: `api-application/build.gradle.kts`
- Modify: `api-presentation-quarkus/.../dtos/input/UserInputDto.kt`
- Modify: `api-presentation-quarkus/.../dtos/input/PinCreationInputDto.kt`
- Modify: `api-presentation-quarkus/.../dtos/input/PinTagsInputDto.kt`
- Modify: `api-presentation-quarkus/.../controllers/UserController.kt`
- Modify: `api-presentation-quarkus/.../controllers/PinController.kt`
- Create: `api-presentation-quarkus/.../mappers/ConstraintViolationExceptionMapper.kt`
- Test: `api-application/.../UserCreationIntegrationTest.kt`

**Interfaces:**
- Consumes: `ProblemDetail` (Task 3), `jakarta.validation.ConstraintViolationException`.
- Produces: `ConstraintViolationExceptionMapper : ExceptionMapper<ConstraintViolationException>` → 400 problem+json, `code = "VALIDATION_ERROR"`. DTOs porteurs de contraintes ; controllers avec `@Valid`.

- [ ] **Step 1: Déclarer la dépendance dans le catalogue**

Dans `gradle/libs.versions.toml`, sous la section `# Quarkus` des `[libraries]`, ajouter (version fournie par le BOM, comme les autres `quarkus-*`) :

```toml
quarkus-hibernate-validator = { module = "io.quarkus:quarkus-hibernate-validator" }
```

- [ ] **Step 2: Câbler la dépendance (compileOnly en présentation, implementation en application)**

Dans `api-presentation-quarkus/build.gradle.kts`, ajouter dans le bloc `dependencies`, après `compileOnly(libs.quarkus.smallrye.openapi)` :

```kotlin
    compileOnly(libs.quarkus.hibernate.validator)
```

Dans `api-application/build.gradle.kts`, ajouter après `implementation(libs.quarkus.smallrye.health)` :

```kotlin
    implementation(libs.quarkus.hibernate.validator)
```

- [ ] **Step 3: Écrire les tests de validation (Red)**

Dans `UserCreationIntegrationTest.kt`, ajouter ces tests et **modifier le test unicode existant** pour qu'il attende 400 :

```kotlin
    @Test
    fun `creating a user with unicode name fails validation`() {
        given()
            .contentType(ContentType.JSON)
            .body("""{"name": "用户名", "password": "password123"}""")
            .`when`()
            .post("/api/v1/users")
            .then()
            .statusCode(400)
            .contentType("application/problem+json")
            .body("code", equalTo("VALIDATION_ERROR"))
    }

    @Test
    fun `creating a user with blank name fails validation`() {
        given()
            .contentType(ContentType.JSON)
            .body("""{"name": "  ", "password": "password123"}""")
            .`when`()
            .post("/api/v1/users")
            .then()
            .statusCode(400)
            .contentType("application/problem+json")
            .body("code", equalTo("VALIDATION_ERROR"))
    }

    @Test
    fun `creating a user with too short password fails validation`() {
        given()
            .contentType(ContentType.JSON)
            .body("""{"name": "shortpass", "password": "short"}""")
            .`when`()
            .post("/api/v1/users")
            .then()
            .statusCode(400)
            .contentType("application/problem+json")
            .body("code", equalTo("VALIDATION_ERROR"))
    }

    @Test
    fun `creating a user with too long password fails validation`() {
        val longPassword = "a".repeat(73)
        given()
            .contentType(ContentType.JSON)
            .body("""{"name": "longpass", "password": "$longPassword"}""")
            .`when`()
            .post("/api/v1/users")
            .then()
            .statusCode(400)
            .contentType("application/problem+json")
            .body("code", equalTo("VALIDATION_ERROR"))
    }
```

Remplacer l'ancien test `creating a user with unicode name succeeds` (qui attendait 200) par le `... fails validation` ci-dessus. Le test `creating a user with special characters in name succeeds` (nom `user_with-special.chars123`, contient `.`) reste **inchangé** : il passe le pattern `^[A-Za-z0-9._-]+$`.

- [ ] **Step 4: Lancer, vérifier l'échec (Red)**

Run: `./gradlew :api-application:test --tests "UserCreationIntegrationTest"`
Expected: FAIL sur les nouveaux tests (aucune validation encore ; l'unicode renvoie 200, les autres renvoient 200 ou 500).

- [ ] **Step 5: Annoter les DTOs (Green)**

`UserInputDto.kt` :

```kotlin
package fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.dtos.input

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size

data class UserInputDto(
    @field:NotBlank
    @field:Size(min = 3, max = 50)
    @field:Pattern(regexp = "^[A-Za-z0-9._-]+$")
    val name: String,
    @field:NotBlank
    @field:Size(min = 8, max = 72)
    val password: String,
)
```

`PinCreationInputDto.kt` :

```kotlin
package fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.dtos.input

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

data class PinCreationInputDto(
    @field:NotBlank
    val sourceContextUrl: String,
    @field:NotBlank
    val sourceMediaUrl: String,
    @field:Size(max = 2000)
    val description: String,
)
```

`PinTagsInputDto.kt` :

```kotlin
package fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.dtos.input

import jakarta.validation.constraints.NotBlank

data class PinTagsInputDto(
    val tags: List<@NotBlank String>,
)
```

- [ ] **Step 6: Ajouter `@Valid` sur les bodies**

`UserController.kt` : ajouter l'import `jakarta.validation.Valid` et annoter le paramètre :

```kotlin
    @POST
    @PermitAll
    fun createUser(@Valid userDto: UserInputDto): RestResponse<UserOutputDto> {
        val userOutputDto = userCreator.createUserWithPassword(name = userDto.name, password = userDto.password).toDto()
        return RestResponse.ok(userOutputDto)
    }
```

`PinController.kt` : ajouter l'import `jakarta.validation.Valid` et annoter les paramètres body de `createPin` et `setTags` :

```kotlin
    fun createPin(@Valid creationDto: PinCreationInputDto): RestResponse<PinOutputDto> {
```

```kotlin
    fun setTags(pinId: UUID, @Valid tagsDto: PinTagsInputDto): RestResponse<PinOutputDto> {
```

- [ ] **Step 7: Créer le mapper de violations**

```kotlin
package fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.mappers

import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.dtos.output.ProblemDetail
import jakarta.validation.ConstraintViolationException
import jakarta.ws.rs.core.Context
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.core.UriInfo
import jakarta.ws.rs.ext.ExceptionMapper
import jakarta.ws.rs.ext.Provider

@Provider
class ConstraintViolationExceptionMapper : ExceptionMapper<ConstraintViolationException> {
    @Context
    lateinit var uriInfo: UriInfo

    override fun toResponse(exception: ConstraintViolationException): Response {
        val status = Response.Status.BAD_REQUEST
        val detail = exception.constraintViolations
            .joinToString(separator = "; ") { "${it.propertyPath}: ${it.message}" }
        val problem = ProblemDetail(
            title = status.reasonPhrase,
            status = status.statusCode,
            detail = detail,
            instance = uriInfo.path,
            code = "VALIDATION_ERROR",
        )
        return Response
            .status(status)
            .entity(problem)
            .type("application/problem+json")
            .build()
    }
}
```

- [ ] **Step 8: Lancer les tests (Green)**

Run: `./gradlew :api-application:test --tests "UserCreationIntegrationTest"`
Expected: PASS.

Si les nouveaux tests renvoient un 400 mais **pas** `application/problem+json` (ancien format Quarkus `{"title":"Constraint Violation","violations":[...]}`), c'est que le mapper built-in a la priorité : remplacer le type mappé par la classe concrète Quarkus. Changer la déclaration en `ExceptionMapper<io.quarkus.hibernate.validator.runtime.jaxrs.ResteasyReactiveViolationException>` (qui étend `ConstraintViolationException`, même accès à `.constraintViolations`) et réexécuter.

Run: `./gradlew detekt test`
Expected: PASS (les tests Pin/Tag existants envoient des bodies valides). `MagicNumber` ne flague pas les `@Size(min = .., max = ..)` (arguments nommés).

- [ ] **Step 9: Commit**

```bash
git add -A
git commit -m "$(cat <<'EOF'
feat(validation): validation d'entree Bean + erreurs RFC 7807

Ajoute quarkus-hibernate-validator, des contraintes sur les 3 DTOs
d'entree, @Valid sur les bodies, et un mapper de ConstraintViolation en
application/problem+json (code VALIDATION_ERROR). Les noms unicode sont
desormais rejetes (ASCII-only).

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

---

## Task 6: Username insensible à la casse

Lookup Ebean `ieq`, normalisation (trim) dans `UserCreator`, et migration SQLite `collate nocase` (ferme aussi la race d'unicité pré-existante).

**Files:**
- Modify: `api-persistence-sqlite/.../repositories/UserRepository.kt`
- Modify: `api-usecases/.../UserCreator.kt`
- Create: `api-persistence-sqlite/src/main/resources/dbmigration/1.2.sql`
- Test: `api-persistence-sqlite/.../UserRepositoryTest.kt`
- Test: `api-usecases/.../UserCreatorTest.kt`
- Test: `api-application/.../UserCreationIntegrationTest.kt`

**Interfaces:**
- Consumes: `QUserModel().name` (query bean Ebean, méthode `ieq`), moteur de migration Ebean (`ebean.migration.run=true`, déjà actif en test comme en prod).
- Produces: `findUserByName` insensible à la casse ; index unique `ix_users_name_nocase` ; création rejetée pour un nom ne différant que par la casse.

- [ ] **Step 1: Écrire les tests repo (Red)**

Dans `UserRepositoryTest.kt`, ajouter :

```kotlin
    @Test
    fun `findUserByName is case-insensitive`() {
        // Given
        val user = User(id = randomUUID(), name = "Bob")
        repository.saveUser(user)

        // When
        val foundUser = repository.findUserByName("bob")

        // Then
        assertNotNull(foundUser)
        assertEquals("Bob", foundUser!!.name)
    }

    @Test
    fun `saving two users whose names differ only by case is rejected`() {
        // Given
        repository.saveUser(User(id = randomUUID(), name = "Alice"))

        // When, Then
        assertThrows<Exception> {
            repository.saveUser(User(id = randomUUID(), name = "alice"))
        }
    }
```

Ajouter l'import `org.junit.jupiter.api.assertThrows`.

- [ ] **Step 2: Lancer, vérifier l'échec (Red)**

Run: `./gradlew :api-persistence-sqlite:test --tests "UserRepositoryTest"`
Expected: FAIL. `findUserByName("bob")` renvoie `null` (equalTo sensible à la casse) ; le second save ne lève pas (aucun index unique).

- [ ] **Step 3: Passer `findUserByName` en `ieq` (Green partiel)**

Dans `UserRepository.kt`, remplacer la méthode `findUserByName` :

```kotlin
    override fun findUserByName(name: String): User? =
        QUserModel()
            .name
            .ieq(name)
            .findOne()
            ?.toDomain()
```

- [ ] **Step 4: Créer la migration `1.2.sql`**

Créer `api-persistence-sqlite/src/main/resources/dbmigration/1.2.sql` :

```sql
-- apply changes
create unique index ix_users_name_nocase on users (name collate nocase);
```

(Note : cet index n'est pas exprimable dans le modèle d'entité Ebean, donc il est écrit à la main et n'apparaîtra pas dans `dbmigration/model` ; un futur `generateDbMigration` ne cherchera pas à le recréer.)

- [ ] **Step 5: Lancer les tests repo (Green)**

Run: `./gradlew :api-persistence-sqlite:test --tests "UserRepositoryTest"`
Expected: PASS. La migration `1.2.sql` s'applique (SQLite `:memory:`, `ebean.migration.run=true`) : le lookup `ieq` trouve `Bob` via `bob`, et l'index `collate nocase` fait échouer le second save.

Si l'exception réelle du second save est précise (ex. `io.ebean.DuplicateKeyException`), affiner `assertThrows<Exception>` vers ce type.

- [ ] **Step 6: Normaliser dans `UserCreator` + test use-case**

Dans `UserCreator.kt`, trimmer le nom dans `createUser` :

```kotlin
    @Transactional
    fun createUser(name: String): User {
        val normalizedName = name.trim()
        // Check that the username is free (case-insensitive via the repository lookup)
        val existingUser = userRepository.findUserByName(normalizedName)
        if (existingUser != null) throw UsernameAlreadyTakenError()
        // Create the user
        val user = User(id = UUID.randomUUID(), name = normalizedName)
        return userRepository.saveUser(user)
    }
```

Dans `UserCreatorTest.kt`, ajouter un test d'unicité insensible à la casse :

```kotlin
    @Test
    fun `When creating a user whose name differs only by case, then should throw`() {
        // Given
        val name = "Bob"
        every { userRepository.findUserByName(any()) } returns mockk(relaxed = true)

        // When, Then
        assertThrows<UsernameAlreadyTakenError> {
            useCase.createUser("bob")
        }
    }
```

(Le mock `findUserByName(any())` renvoie un utilisateur : on vérifie que `createUser` consulte le repo et lève bien `UsernameAlreadyTakenError`. La casse-insensibilité réelle du lookup est couverte par le test repo du Step 1.)

- [ ] **Step 7: Test d'intégration bout-en-bout**

Dans `UserCreationIntegrationTest.kt`, ajouter :

```kotlin
    @Test
    fun `creating a user with a name differing only by case is rejected`() {
        given()
            .contentType(ContentType.JSON)
            .body("""{"name": "CaseUser", "password": "password123"}""")
            .`when`()
            .post("/api/v1/users")
            .then()
            .statusCode(200)

        given()
            .contentType(ContentType.JSON)
            .body("""{"name": "caseuser", "password": "password123"}""")
            .`when`()
            .post("/api/v1/users")
            .then()
            .statusCode(409)
            .body("code", equalTo("USERNAME_ALREADY_EXISTS"))
    }

    @Test
    fun `authentication is case-insensitive on username`() {
        val password = "password123"
        given()
            .contentType(ContentType.JSON)
            .body("""{"name": "CaseLogin", "password": "$password"}""")
            .`when`()
            .post("/api/v1/users")
            .then()
            .statusCode(200)

        given()
            .auth().preemptive().basic("caselogin", password)
            .`when`()
            .get("/api/v1/pins")
            .then()
            .statusCode(200)
    }
```

- [ ] **Step 8: Lancer la suite complète**

Run: `./gradlew detekt test`
Expected: PASS (tous modules).

- [ ] **Step 9: Commit**

```bash
git add -A
git commit -m "$(cat <<'EOF'
feat(users): unicite de username insensible a la casse

findUserByName utilise ieq (Ebean), UserCreator normalise (trim), et un
index unique SQLite collate nocase impose l'unicite au niveau DB (fermant
aussi la race d'unicite pre-existante). Bob et bob sont le meme
utilisateur; l'authentification est insensible a la casse du nom.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

---

## Verify final (après les 6 tâches)

- [ ] **Gate complet**

Run: `./gradlew detekt test`
Expected: PASS.

- [ ] **Revue holistique** des diffs (cross-cutting) : cohérence du format problem+json sur tous les chemins d'erreur (métier, auth, validation), absence d'imports morts, absence d'em-dash dans les messages, respect du layering (aucun import persistence en présentation).

- [ ] **Lancer l'app** (`verify` / `run` skill) : `POST /api/v1/users` nominal (200), nom dupliqué (409 problem+json), body invalide (400 problem+json), login casse-insensible (200), mauvais mot de passe (401 problem+json).

## Self-review (couverture spec)

- §4 auth (auto-login + timing) → Task 2. ✓
- §5 mappers RFC 7807 (BaseError + AuthenticationFailed) → Task 3 ; retrait try/catch → Task 4. ✓
- §6 validation (dep + 3 DTOs + @Valid + ConstraintViolation mapper) → Task 5. ✓
- §7 casse-insensible (ieq + trim + migration) → Task 6. ✓
- §8 quick wins (Greeting + addPackage) → Task 1. ✓
- §9 `VALIDATION_ERROR` en présentation (pas dans l'enum) → Task 5 Step 7. ✓ ; query params conservés → Task 4 Step 3. ✓
- §10 tests (intégration/use-case/repo) → répartis Tasks 2/3/5/6. ✓
- §12 risque test sans hash → Task 2 Step 4 (suppression). ✓
- §13 critères d'acceptation → couverts par les Verify de chaque tâche + Verify final. ✓
