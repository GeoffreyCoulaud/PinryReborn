# Handoff: réduction de dette (auth · erreurs · validation)

Date: 2026-07-07
Spec: `docs/specs/2026-07-06-debt-reduction-hardening.md`
Plan: `docs/plans/2026-07-06-debt-reduction-hardening.md`
Branche: `refactor/debt-reduction-hardening`

## État actuel

Salve de réduction de dette **terminée, gate verte, revue**. 10 commits sur la branche
(6 tâches + 1 fix wave + 2 resync openapi + les docs spec/plan de base). Chaque tâche a
passé une revue task-scoped ; une **revue finale holistique** (opus) puis une re-revue du
fix wave sont **clean**. `./gradlew detekt test` vert au HEAD.

Au moment d'écrire : **pas encore intégré sur `main`** (choix d'intégration en attente).
Cette salve traitait les items 1-2-3 de la revue initiale ; **l'item 4 (upload d'images)
reste explicitement pour la salve suivante**, spec dédiée.

## Ce qui a été construit

- **Auth durcie** (`UserAuthenticator`) : auto-login sans hash **supprimé** (fail-closed) ;
  chemin **constant-time** (un `BCrypt.checkpw` sur tous les chemins d'échec, y compris
  utilisateur inexistant) pour tuer l'oracle temporel d'énumération.
- **Erreurs RFC 7807** (`api-presentation-quarkus`) : `ProblemDetail` (7807 + extension
  `code`), `BaseErrorMapper` (`ErrorCode`→statut, `when` exhaustif), 
  `AuthenticationFailedExceptionMapper` (401 credentials invalides),
  `UnauthorizedExceptionMapper` (401 sans credentials), `ConstraintViolationExceptionMapper`
  (400). Helper partagé `problemResponse(...)` + constantes `PROBLEM_JSON_MEDIA_TYPE` /
  `WWW_AUTHENTICATE_BASIC` (les 4 mappers factorisés). `try/catch` retirés des controllers.
  **Tous les chemins d'erreur (métier, auth invalide, auth absente, validation) sortent en
  `application/problem+json`.**
- **Validation d'entrée** : `quarkus-hibernate-validator`, contraintes Bean sur les 3 DTOs
  (username ASCII `^[A-Za-z0-9._-]+$` 3-50, password 8-72), `@Valid` sur les bodies,
  `@NotBlank` sur le query param `q` de recherche → tout en problem+json.
- **Username casse-insensible** : `findUserByName` en `ieq`, `UserCreator` trim, migration
  `1.2.sql` index unique `collate nocase` (ferme aussi la race d'unicité pré-existante).
- **Quick wins** : `GreetingController` supprimé, `EbeanDatabaseProducer.addPackage`
  corrigé (`...api.persistence.sqlite.models`).

## Vérifié

- `./gradlew detekt test` **vert** (tous modules).
- Les tests d'intégration `@QuarkusTest` **bootent la vraie app** (HTTP réel, auth Basic,
  SQLite + migrations) et couvrent bout-en-bout : POST /users 200 ; nom dupliqué 409
  problem+json ; body invalide 400 problem+json ; nom unicode 400 ; `Bob`/`bob` → 409 ;
  login `BOB` → 200 ; mauvais mot de passe 401 **problem+json** (`code=AUTHENTICATION_FAILED`) ;
  **sans credentials** 401 **problem+json** (`code=AUTHENTICATION_REQUIRED`) ; header
  `WWW-Authenticate` dans les deux cas.

## Pièges rencontrés (utiles pour la suite)

- **`docs/openapi.json` est généré au build** (`quarkus.smallrye-openapi.store-schema-directory=../docs`)
  et **régénéré à chaque `./gradlew ... test`**. Il faut le **resync + commit** dès que la
  surface d'API change (retrait d'endpoint, contraintes de validation sur DTO/query params).
  Ici 3 commits `chore(openapi)` dédiés. Le fichier commité était en **dérive** avant cette
  salve (schémas de composants manquants).
- **Query param Kotlin non-null + Quarkus** : Quarkus injecte `null` quand le param est
  absent, et l'intrinsic Kotlin non-null lève une **NPE (500) avant** la validation. Garder
  le param **nullable** (`query: String?`) + `@NotBlank`, puis `requireNotNull(query)` après
  la barrière de validation (pas `!!` : `UnsafeCallOnNullableType`).
- **detekt** : `MagicNumber.ignoreNamedArgument=true` → toujours des **arguments nommés**
  dans les annotations (`@Size(min = 3, max = 50)`). `ReturnCount`/`ThrowsCount` **max 2**.
  `UnusedImports` est `active: false` (hygiène manuelle requise).
- **Violation d'index unique Ebean/SQLite** remonte en `jakarta.persistence.PersistenceException`
  (enveloppant `SQLiteException`/`SQLITE_CONSTRAINT_UNIQUE`), **pas** `io.ebean.DuplicateKeyException`.
- **Mapper d'auth** : `@Priority(Priorities.AUTHENTICATION)` requis ; il n'intercepte que
  le 401 **credentials invalides** (`AuthenticationFailedException`). L'absence totale de
  credentials lève `UnauthorizedException` → 401 **par défaut Quarkus, sans corps 7807**
  (voir « non traité » ci-dessous).

## NON validé / réserves

- **Migration `1.2.sql` sur une DB déjà peuplée** : la création de l'index unique
  `collate nocase` **échoue au boot** si des usernames ne différant que par la casse
  coexistent déjà (l'ancien check applicatif était sensible à la casse et il n'y avait
  aucune contrainte DB). Fresh / petit self-host : OK. **Déploiement sur base existante :
  prévoir une étape de dédup avant migration.** (Pas d'app en prod à ce jour, donc non
  observé en conditions réelles.)
- **Constant-time non testé automatiquement** (mesure de timing non fiable en CI) : garanti
  seulement structurellement (passage par `checkpw` bidon).

## Follow-ups repérés en revue (non bloquants)

- Couverture de bornes plus fine possible (username <3/>50, `description` max 2000,
  `PinTagsInputDto` éléments blancs).
- Naming des tests d'auth en « When…, then… » au lieu du « Given…, Then… » d'AGENTS.md
  (pré-existant dans le fichier).

## Prochaine étape suggérée

1. Intégrer la branche via **PR** (le workflow exige la CI `validate / gate` pour tout
   changement de code) ; merge squash/rebase (historique linéaire).
2. Tag annoté (ex. `v0.2.0-hardening`) après merge, non poussé.
3. Attaquer **l'item 4 : upload d'images** (spec dédiée, phase Discuss/Spec).
