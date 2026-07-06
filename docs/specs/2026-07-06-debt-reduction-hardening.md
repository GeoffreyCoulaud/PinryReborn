# Spec: réduction de dette (auth · erreurs · validation)

Date: 2026-07-06
Statut: en revue
Slug: `debt-reduction-hardening`

## 1. Contexte et objectif

Le handoff `docs/handoffs/2026-07-06 - handoff - ci-supply-chain.md` a remonté, hors
périmètre CI, une liste de dette repérée à la revue initiale. Cette spec traite en **une
seule salve** les chantiers à impact réel et concret :

1. Durcissement de l'authentification.
2. Gestion d'erreurs HTTP globale (aujourd'hui inexistante, dupliquée à la main).
3. Validation d'entrée (aujourd'hui absente).
4. Deux quick wins (`GreetingController` vestige, `addPackage` erroné).

L'upload d'images (autre item de la revue) est **explicitement remis à la salve
suivante** et n'est pas traité ici.

L'exploration du code a établi les faits suivants (références vérifiées) :

- **Auto-login** : `UserAuthenticator.checkLogin` fait `val hash = userPasswordRepository
  .findUserPasswordHash(user) ?: return user` : un utilisateur sans ligne de hash est
  authentifié par n'importe quel mot de passe. Intentionnel à l'origine (relicat d'avant
  l'implémentation des mots de passe), figé par un test (`UserAuthenticatorTest`).
- **Oracle temporel** : BCrypt n'est exécuté que si l'utilisateur existe et a un hash ;
  utilisateur inexistant → `throw` immédiat. Le temps de réponse révèle l'existence.
- **Énumération à la création** : nom dupliqué → `UsernameAlreadyTakenError` non mappée →
  **HTTP 500** (figé par `UserCreationIntegrationTest`), alors qu'un succès renvoie 200.
- **Aucun `ExceptionMapper`** : chaque controller mappe ses exceptions à la main via
  `try/catch` dupliqués ; `createUser`, `createPin`, `emptyRecycleBin` laissent filer un
  500. Point d'appui : toutes les exceptions métier héritent de
  `BaseError(message, code: ErrorCode)`.
- **Aucune validation** : pas de `quarkus-hibernate-validator`, aucune annotation Jakarta,
  aucun `@Valid`. Les DTOs acceptent nom/mot de passe vides.
- **Aucune contrainte d'unicité sur `users.name`** (pas même sensible à la casse) :
  l'unicité repose uniquement sur le check applicatif de `UserCreator`, donc une race sur
  deux créations simultanées peut déjà produire des doublons.
- **Bon point préservé** : les 401 d'auth sont déjà uniformes (`"Invalid username or
  password"`), pas de fuite dans le message.

## 2. Décisions actées

1. **Auto-login supprimé** (fail-closed) : hash absent → échec. C'est un relicat, pas une
   feature. Le test qui le valide est inversé.
2. **Oracle temporel traité** : chemin d'auth à coût BCrypt constant (comparaison contre
   un hash bidon quand l'utilisateur n'existe pas ou n'a pas de hash).
3. **Format d'erreur = RFC 7807** (`application/problem+json`), roulé à la main (aucune
   dépendance tierce ajoutée, cf. attention supply chain). Confirmé par la doc Quarkus :
   pas de support 7807 natif pour les exceptions applicatives.
4. **Refactor complet** de la gestion d'erreurs : mapper global + retrait des `try/catch`
   des controllers.
5. **Validation sur les trois DTOs d'entrée** (pas seulement `UserInputDto`).
6. **Usernames insensibles à la casse**, imposé au niveau DB par un index unique
   `collate nocase` (ferme aussi la race d'unicité pré-existante).
7. Livré en **une branche, une PR** vers `main` via le gate CI.

## 3. Portée

### Dans le périmètre

- Suppression de l'auto-login + chemin d'auth constant-time (`api-usecases`).
- `ProblemDetail` (DTO 7807) + `ExceptionMapper<BaseError>` +
  `ExceptionMapper<AuthenticationFailedException>` +
  `ExceptionMapper<ConstraintViolationException>` (`api-presentation-quarkus`).
- Retrait des `try/catch` de tous les controllers.
- `quarkus-hibernate-validator` + annotations de validation sur les 3 DTOs + `@Valid`.
- Username insensible à la casse : `findUserByName` en `ieq`, normalisation dans
  `UserCreator`, migration index unique `collate nocase`.
- Suppression `GreetingController` + son test.
- Correction `EbeanDatabaseProducer.addPackage`.
- Tests (intégration, use-case, repo) couvrant tous les changements.

### Hors périmètre (suite)

- **Upload d'images** (salve suivante, spec dédiée).
- Toute autre évolution fonctionnelle non listée ci-dessus.

## 4. Workstream 1 — Durcissement auth (`api-usecases`)

Fichier : `UserAuthenticator.kt`.

- **Supprimer l'auto-login** : plus de `?: return user` sur le hash absent. Hash absent →
  `UserAuthenticationInvalidPasswordError`.
- **Constant-time** : introduire un `DUMMY_HASH` constant (un hash BCrypt valide
  précalculé, littéral dans le code). Structurer `checkLogin` pour qu'un `BCrypt.checkpw`
  soit exécuté dans **tous** les cas d'échec (utilisateur inexistant, hash absent, mauvais
  mot de passe), afin d'égaliser le coût :

  ```
  val user = userRepository.findUserByName(login.userName)
  val hash = user?.let { userPasswordRepository.findUserPasswordHash(it) }
  if (user == null || hash == null) {
      BCrypt.checkpw(login.password, DUMMY_HASH) // coût constant, résultat ignoré
      throw if (user == null) UserAuthenticationUserDoesNotExistError()
            else UserAuthenticationInvalidPasswordError()
  }
  return user.takeIf { checkPassword(login.password, hash) }
      ?: throw UserAuthenticationInvalidPasswordError()
  ```

  Les deux exceptions internes restent distinctes (uniformisées en 401 côté présentation,
  aucune fuite externe).

Tests use-case : inverser `UserAuthenticatorTest` « no saved password → work » en
« no saved password → échec ». Vérifier que le mauvais mot de passe et l'utilisateur
inexistant lèvent bien leur exception respective.

## 5. Workstream 2 — Gestion d'erreurs RFC 7807 (`api-presentation-quarkus`)

### 5.1 `ProblemDetail`

Nouveau DTO de sortie (sérialisé par Jackson déjà présent via `quarkus-rest-jackson`).
Champs RFC 7807 : `type` (URI, défaut `about:blank`), `title`, `status` (int),
`detail` (message), `instance` (chemin de la requête). Membre d'extension : `code`
(nom de l'`ErrorCode`).

### 5.2 `ExceptionMapper<BaseError>`

`@Provider`. `when(error.code)` → statut HTTP. Produit `application/problem+json` avec un
`ProblemDetail`. La **table `ErrorCode → statut` vit en présentation** (jamais sur l'enum
`ErrorCode` de `api-usecases`, pour ne pas y faire fuiter une préoccupation HTTP).

Mapping cible :

| ErrorCode | Statut |
|---|---|
| `USERNAME_ALREADY_EXISTS` | 409 |
| `PIN_DOES_NOT_EXIST` | 404 |
| `PIN_INSUFFICIENT_PERMISSIONS` | 403 |
| `PIN_NOT_SOFT_DELETED`, `PIN_ALREADY_SOFT_DELETED` | 409 |
| `SEARCH_EMPTY_QUERY` | 400 |
| `INVALID_LOGIN` | 400 |
| `USER_DOES_NOT_EXIST`, `INVALID_PASSWORD`, `INVALID_HTTP_AUTHORIZATION_SCHEME` | 401 |

(Les codes d'auth passent en pratique par `AuthenticationFailedException`, cf. 5.3 ; leur
présence dans la table est un filet de sécurité.)

### 5.3 `ExceptionMapper<AuthenticationFailedException>`

`@Provider` + `@Priority(Priorities.AUTHENTICATION)`. Rend un 401 en `problem+json`,
message uniforme (`"Invalid username or password"`), conserve le header
`WWW-Authenticate: Basic realm=...`. Ne divulgue jamais la cause précise.

### 5.4 Retrait des `try/catch`

Tous les controllers (`PinController`, `PinRecycleBinController`, `PinSearchController`,
`TagSearchController`, `UserController`) : on retire les `try/catch` de mapping. Les
exceptions `BaseError` remontent au mapper. Les handlers renvoient directement leur
réponse de succès.

Note : la validation manuelle `query.isNullOrBlank()` des controllers de recherche est
conservée telle quelle (query param, pas un body ; `@Valid` ne s'y applique pas
naturellement) — ou remplacée par `@NotBlank @QueryParam` si trivial. Décision
d'implémentation, sans impact externe.

## 6. Workstream 3 — Validation d'entrée

### 6.1 Dépendance

Ajouter `quarkus-hibernate-validator` au catalogue `gradle/libs.versions.toml` et au
`build.gradle.kts` de `api-presentation-quarkus`.

### 6.2 Contraintes

- `UserInputDto` : `name` `@NotBlank @Size(min=3, max=50) @Pattern("^[A-Za-z0-9_-]+$")` ;
  `password` `@NotBlank @Size(min=8, max=72)` (72 = limite de troncature silencieuse de
  jBCrypt).
- `PinCreationInputDto` : `sourceContextUrl`/`sourceMediaUrl` `@NotBlank` ;
  `description` `@Size(max=2000)`.
- `PinTagsInputDto` : `tags` non nul, éléments non blancs (`List<@NotBlank String>`).
- `@Valid` sur les paramètres de body des controllers concernés.

### 6.3 `ExceptionMapper<ConstraintViolationException>`

Override du format Quarkus par défaut : rend un 400 en `problem+json` cohérent avec 5.1,
`detail` agrégeant les violations. `code` = `VALIDATION_ERROR` (nouveau `ErrorCode`, ou
valeur dédiée en présentation — voir §9).

## 7. Workstream 4 — Username insensible à la casse

- `UserRepository.findUserByName` : `QUserModel().name.ieq(name).findOne()` (Ebean
  case-insensitive equals).
- `UserCreator` : trim du nom en entrée ; le check d'unicité devient insensible à la casse
  via le lookup ci-dessus. Le nom est **stocké tel que saisi** (casse d'affichage
  préservée).
- **Migration écrite à la main** (`api-persistence-sqlite/src/main/resources/dbmigration/`,
  prochaine version, ex. `1.2.sql`) :
  `create unique index ix_users_name_nocase on users (name collate nocase);`
  Ferme la race d'unicité pré-existante **et** impose l'unicité insensible à la casse.

## 8. Workstream 5 — Quick wins

- Supprimer `api-presentation-quarkus/.../controllers/GreetingController.kt` et
  `api-application/.../GreetingIntegrationTest.kt` (vestige ; health réel = `/q/health`).
- `EbeanDatabaseProducer.kt` : `addPackage("fr.geoffreyCoulaud.pinryReborn.adapters
  .persistence.models")` → `"fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.models"`.

## 9. Points de détail à trancher en implémentation

- **`VALIDATION_ERROR`** : ajouter une valeur à l'enum `ErrorCode` (usecases) OU gérer un
  code de présentation dédié dans le mapper des violations (les `ConstraintViolationException`
  ne sont pas des `BaseError`). Préférence : constante en présentation pour ne pas polluer
  l'enum métier avec un cas purement HTTP/validation.
- **Query params de recherche** : conserver la validation manuelle vs `@NotBlank` sur
  `@QueryParam` (cf. 5.4). Sans impact externe.

## 10. Tests (ordre TDD du projet : intégration → use-case → repo)

- **Intégration** (`api-application`) :
  - User sans hash → login 401 (n'était plus atteignable via l'API mais testable via seed
    repo) ; remplace l'ancienne attente d'auto-login si un tel test existe.
  - Création `Bob` puis `bob` → 409 `problem+json`.
  - Login avec `BOB` (créé `Bob`) → succès.
  - Nom dupliqué → 409 (remplace le 500 figé dans `UserCreationIntegrationTest`).
  - Body invalide (nom vide, password < 8, password > 72) → 400 `problem+json`.
  - Corps d'erreur : content-type `application/problem+json`, champs 7807 + `code`.
- **Use-case** (`api-usecases`) : inversion du test no-hash ; cas mauvais mot de passe /
  utilisateur inexistant ; unicité insensible à la casse dans `UserCreator`.
- **Repo** (`api-persistence-sqlite`) : `findUserByName` insensible à la casse ; l'index
  unique `collate nocase` rejette un doublon de casse différente.
- Le **timing constant n'est pas testé automatiquement** (non fiable en CI) : on garantit
  seulement le passage par `BCrypt.checkpw` bidon dans les chemins d'échec.

## 11. Fichiers créés / modifiés (récap)

Créés :
- `api-presentation-quarkus/.../dtos/output/ProblemDetail.kt`
- `api-presentation-quarkus/.../mappers/BaseErrorMapper.kt` (ou `exceptions/`)
- `api-presentation-quarkus/.../mappers/AuthenticationFailedExceptionMapper.kt`
- `api-presentation-quarkus/.../mappers/ConstraintViolationExceptionMapper.kt`
- `api-persistence-sqlite/src/main/resources/dbmigration/1.2.sql` (index unique nocase)

Modifiés :
- `api-usecases/.../UserAuthenticator.kt` (auto-login, constant-time)
- `api-usecases/.../UserCreator.kt` (trim/normalisation)
- `api-persistence-sqlite/.../repositories/UserRepository.kt` (`ieq`)
- `api-persistence-sqlite/.../EbeanDatabaseProducer.kt` (addPackage)
- Tous les controllers (retrait try/catch)
- Les 3 DTOs d'entrée (annotations)
- `gradle/libs.versions.toml` + `build.gradle.kts` présentation (validator)
- Tests impactés (use-case, intégration, repo)

Supprimés :
- `GreetingController.kt` + `GreetingIntegrationTest.kt`

## 12. Risques

- **Tests d'intégration reposant sur un user sans hash** : retirer l'auto-login peut en
  casser. À auditer en Act ; adapter le setup pour créer les users avec mot de passe.
- **Ebean `ieq`** : confirmer la génération SQL sensée sur SQLite (fonctionnellement, `ieq`
  produit un `lower(...) = lower(...)` ou équivalent ; l'index `collate nocase` couvre
  l'unicité indépendamment).
- **Format des réponses d'erreur existantes** : les tests d'intégration actuels assertent
  surtout des codes de statut (préservés) ; l'ajout d'un corps `problem+json` ne devrait
  pas les casser, mais vérifier les éventuelles assertions de content-type.

## 13. Critères d'acceptation

1. `./gradlew detekt test` vert.
2. Un user sans hash ne peut plus se connecter (401).
3. Nom dupliqué → 409 `problem+json` (plus de 500).
4. Corps d'erreur au format RFC 7807 avec extension `code` sur toutes les erreurs métier,
   auth et validation.
5. `Bob`/`bob` traités comme le même utilisateur (création rejetée, login casse-insensible).
6. Body invalide → 400 `problem+json`.
7. `GreetingController` et son test supprimés ; `addPackage` corrigé.
8. Aucune dépendance tierce hors `quarkus-hibernate-validator`.
9. Gate CI vert sur la PR.
