# Spec: CI + supply chain security

Date: 2026-07-06
Statut: en revue
Slug: `ci-supply-chain`

## 1. Contexte et objectif

`AGENTS.md` décrit un workflow qui suppose déjà une CI serveur : branch protection
sur `main` exigeant le check `validate / gate`, PR obligatoire pour tout changement de
code. Or **aucune CI n'existe** aujourd'hui (`.github/` absent), et le projet n'a **ni
Dockerfile, ni image publiée, ni analyse statique branchée dans le build** (le
`.idea/detekt.xml` n'est que la config du plugin IDE).

On veut mettre en place, en s'inspirant de deux projets existants :

- **`mulewatch`** : la structure CI (workflow réutilisable `validate.yml`, jobs
  `lint`/`test`, job d'agrégation `gate`, `pr.yml` / `release.yml`, actions épinglées au
  SHA, `dependabot.yml` groupé).
- **`slskd-lidarr-bridge`** : la supply chain security (SBOM CycloneDX + Syft-JSON,
  attestations cosign keyless OIDC, OpenVEX, scan Grype quotidien vers SARIF,
  `SECURITY.md`).

Constat structurant : **la supply chain security n'existe que parce qu'une image est
publiée sur GHCR.** Il faut donc conteneuriser et publier l'image avant de pouvoir la
porter ici. Le travail se découpe naturellement en trois phases livrées ensemble.

## 2. Décisions actées

1. Les trois phases sont livrées dans le même effort (mais en PRs séparées, voir §8).
2. Image **JVM `fast-jar`, multi-arch `linux/amd64` + `linux/arm64`**. Le natif
   (Mandrel) est explicitement remis à plus tard.
3. **detekt** est ajouté comme plugin Gradle ; le job `lint` du gate exécute `detekt`.
4. Image : `ghcr.io/geoffreycoulaud/pinry-reborn-api`. Product OpenVEX :
   `pkg:oci/pinry-reborn-api`.

## 3. Portée

### Dans le périmètre

- Workflows GitHub Actions : `validate.yml` (réutilisable), `pr.yml`, `release.yml`,
  `grype-scan.yml`.
- Analyse statique detekt intégrée au build Gradle.
- `Dockerfile` runtime de l'app Quarkus (image JVM).
- Publication multi-arch sur GHCR + attestations cosign (CycloneDX, Syft-JSON, OpenVEX).
- `security/vex.openvex.json`, `SECURITY.md`, `.github/dependabot.yml`.
- Ajouts minimaux rendus nécessaires par la conteneurisation (voir §6.1) :
  configuration datasource de prod et endpoint de santé.

### Hors périmètre (suites possibles)

- Image native Mandrel.
- Déploiement réel (compose/k8s, `deploy/`), volumes de prod, secrets.
- Les autres chantiers de la revue (durcissement auth, validation d'entrée,
  ExceptionMapper global, upload d'images). Traités séparément.

## 4. Architecture CI cible

```
pr.yml            (pull_request)      -> validate.yml (push=false)  [+ commitlint, optionnel]
release.yml       (push main + v*)    -> validate.yml (push=true)
grype-scan.yml    (cron quotidien)    -> scan Grype du SBOM Syft attesté -> SARIF

validate.yml (workflow_call, input: push)
  job lint          : detekt
  job test          : gradle test (+ build)
  job build-image   : needs [lint, test]
                      - build artefact Quarkus (quarkusBuild)
                      - buildx multi-arch
                      - push + attestations SEULEMENT si push=true
  job gate          : needs [lint, test, build-image], if: always()
                      -> c'est le check requis "validate / gate"
```

Principes repris de `mulewatch` :

- Le job `gate` agrège les résultats avec `if: always()` et échoue explicitement si un
  job requis n'est pas `success` (un check *skipped* est considéré comme passant par
  GitHub, d'où la nécessité de toujours exécuter le gate). **Seul `validate / gate` est
  exigé par la branch protection.**
- Actions tierces **épinglées au SHA** avec commentaire `# vX.Y.Z`.
- `concurrency` avec `cancel-in-progress` sur les PRs.
- `permissions` minimales par job (`contents: read` par défaut ; `packages: write` +
  `id-token: write` uniquement sur le job qui pousse et atteste).

Le build de l'image suit le modèle `mulewatch` (build dans `validate`, poussé seulement
si `push=true`) plutôt que le modèle `slskd` (build seulement dans release) : ainsi
**chaque PR vérifie que l'image se construit** sans rien publier.

Simplification vs `mulewatch` : pinry n'a **qu'une seule image** et les artefacts JVM
sont **indépendants de l'architecture**. On construit donc l'artefact Quarkus **une
fois**, puis `docker buildx build --platform amd64,arm64` assemble directement le
manifeste multi-arch (base JRE par arch). Pas besoin de la mécanique
build-par-arch + merge-de-digests de mulewatch, ni de Gradle émulé sous QEMU.

## 5. Phase 1 : gate CI + detekt

### 5.1 detekt

- Ajout du plugin detekt au `libs.versions.toml` et application dans le bloc
  `subprojects` de `build.gradle.kts` racine (tous les modules Kotlin).
- Version detekt : **à valider contre Kotlin 2.2.21** (compatibilité analyseur). Risque
  connu, voir §9. Docs à consulter via context7 pendant l'implémentation.
- Config detekt Gradle générée (`config/detekt/detekt.yml`) - la config IDE XML n'est
  pas utilisée par le plugin Gradle.
- Traitement des violations existantes : les corriger si peu nombreuses, sinon générer
  un `detekt-baseline.xml`. Objectif : `./gradlew detekt` vert.

### 5.2 Workflows

- `.github/workflows/validate.yml` : jobs `lint` (`./gradlew detekt`), `test`
  (`./gradlew test`), `build-image` (§6), `gate`.
  - Setup : `actions/checkout`, `actions/setup-java` (Temurin 21), cache Gradle
    (`gradle/actions/setup-gradle`).
- `.github/workflows/pr.yml` : `on: pull_request`, concurrency, appelle `validate.yml`
  avec `push: false`.
- `.github/workflows/release.yml` : `on: push` (`branches: [main]`, `tags: ['v*']`),
  appelle `validate.yml` avec `push: true`.
- **commitlint (optionnel)** : job PR-only réutilisant `.commitlintrc.json`. Proposé
  comme check **non requis** (informationnel), hors du gate. À confirmer en revue.

### 5.3 dependabot

`.github/dependabot.yml`, une PR groupée hebdomadaire par écosystème :

- `gradle` (racine)
- `github-actions`
- `docker` (ajouté en phase 2, dès qu'un Dockerfile existe)

## 6. Phase 2 : conteneurisation + publication GHCR

### 6.1 Prérequis rendus nécessaires par la conteneurisation

Ces deux points sont **de nouveaux ajouts au périmètre**, conséquence directe du fait
qu'une image doit pouvoir démarrer et être vérifiable. À valider en revue.

- **Datasource de prod** : le `application.properties` principal **ne définit aucune
  datasource** (seul le profil de test définit `:memory:`). L'image ne peut pas démarrer
  sans. Proposition : config SQLite fichier sous un chemin monté (ex. `/data/pinry.db`),
  migrations Ebean exécutées au démarrage. Minimal, sans volume de prod formalisé (hors
  périmètre).
- **Endpoint de santé** : ajout de `quarkus-smallrye-health` pour disposer de
  `/q/health` (utilisé par le `HEALTHCHECK` de l'image et un éventuel smoke test).

### 6.2 Dockerfile

- Image **runtime seule** : `FROM eclipse-temurin:21-jre` (Adoptium, cohérent avec le
  toolchain Gradle), copie de `api-application/build/quarkus-app/`, `ENTRYPOINT` sur
  `quarkus-run.jar`, utilisateur non-root, `HEALTHCHECK` sur `/q/health`.
- L'artefact Quarkus est **construit par la CI avant** le `docker build` (les jars sont
  arch-indépendants). Compromis assumé et documenté : le Dockerfile n'est pas
  auto-suffisant pour un `docker build` brut sans build Gradle préalable.

### 6.3 Publication

Dans le job `build-image` de `validate.yml`, étapes actives seulement si `push=true` :

- `docker/setup-qemu-action` + `docker/setup-buildx-action`.
- Login GHCR (`docker/login-action`, `secrets.GITHUB_TOKEN`).
- `docker/metadata-action` : tags `latest` (sur `main`), `type=sha`, `type=semver` sur
  tags `v*`.
- `docker/build-push-action` : `platforms: linux/amd64,linux/arm64`, `push: true`,
  cache `type=gha`.
- `permissions` du job : `contents: read`, `packages: write`, `id-token: write`.

Note sur les tags : `AGENTS.md` (phase Wrap) prévoit des tags annotés **non poussés**.
La release se déclenche donc surtout sur **push `main`** (→ `latest` + `sha`) ; le
tagging semver reste **opt-in** si un tag `v*` est un jour poussé. Pas de contrôle strict
"version fichier == tag" pour l'instant (le projet est en `1.0.0-SNAPSHOT`).

## 7. Phase 3 : supply chain security

Reprise fidèle de `slskd-lidarr-bridge`, adaptée à l'image unique de pinry.

### 7.1 Attestations (dans `build-image`, si `push=true`)

- Génération de deux SBOM sur l'image poussée (par digest) via `anchore/sbom-action` :
  **CycloneDX** (consommateurs externes) et **Syft-JSON** (nécessaire au scan quotidien
  VEX-aware : la forme native préserve l'identité d'image que le VEX image-scoped exige).
- `cosign attest` keyless OIDC, trois attestations : `cyclonedx`, `https://syft.dev/bom`,
  `openvex` (à partir de `security/vex.openvex.json`).

### 7.2 OpenVEX + SECURITY.md

- `security/vex.openvex.json` : document OpenVEX initial, product
  `pkg:oci/pinry-reborn-api`, **liste de statements vide au départ** (remplie au fil des
  triages). Structure et conventions identiques à la référence (statements image-scoped,
  subcomponent PURL sans version).
- `SECURITY.md` : adapté depuis la référence (process de triage VEX, ajout de statements
  via `vexctl`, vérification locale avec un SBOM Syft-JSON, private vulnerability
  reporting). Nom d'image et product PURL remplacés.

### 7.3 Scan Grype quotidien

- `.github/workflows/grype-scan.yml` : `on: schedule` (cron quotidien) +
  `workflow_dispatch`.
- Récupère le SBOM Syft attesté depuis l'image via `cosign verify-attestation` (identité
  cert = le `release.yml`), applique l'OpenVEX attesté, lance `anchore/scan-action`
  (`fail-build: false`, sortie SARIF), upload SARIF vers Code scanning
  (`github/codeql-action/upload-sarif`).
- `permissions` : `contents: read`, `packages: read`, `security-events: write`.

## 8. Fichiers créés / modifiés (récap)

Créés :

- `.github/workflows/validate.yml`
- `.github/workflows/pr.yml`
- `.github/workflows/release.yml`
- `.github/workflows/grype-scan.yml`
- `.github/dependabot.yml`
- `Dockerfile` (+ `.dockerignore`)
- `config/detekt/detekt.yml` (+ éventuel `detekt-baseline.xml`)
- `security/vex.openvex.json`
- `SECURITY.md`

Modifiés :

- `gradle/libs.versions.toml` (plugin detekt, extension quarkus-smallrye-health)
- `build.gradle.kts` racine (application detekt aux subprojects)
- `api-application/build.gradle.kts` (dépendance smallrye-health)
- `api-application/src/main/resources/application.properties` (datasource prod)

Découpage en PRs (branche par phase, chacune passant le gate avant merge) :

- **PR 1** : phase 1 (gate CI + detekt + dependabot gradle/actions). Débloque la branch
  protection.
- **PR 2** : phase 2 (Dockerfile, health, datasource prod, build-image + publication,
  dependabot docker).
- **PR 3** : phase 3 (SBOM/attestations, OpenVEX, SECURITY.md, grype-scan).

## 9. Risques et dépendances

- **detekt vs Kotlin 2.2.21** : compatibilité de l'analyseur à vérifier. Peut imposer une
  version detekt précise ou tolérer des warnings. Consulter les docs detekt via context7.
- **Violations detekt existantes** : volume inconnu ; plan de repli = baseline.
- **Datasource prod manquante** : bloquant pour une image qui démarre ; traité en §6.1
  mais élargit le périmètre initial. À valider.
- **cosign keyless** exige `id-token: write` et que le repo autorise GHCR packages ; la
  regex d'identité du scan quotidien doit pointer exactement `release.yml`.
- **Tags non poussés** (convention `AGENTS.md`) : le tagging semver ne se déclenchera
  que si un tag `v*` est effectivement poussé ; sinon publication sur `main` uniquement.
- **Smoke test d'image** : dépend de §6.1 (datasource + health). Proposé en
  nice-to-have, non bloquant pour le gate.

## 10. Critères d'acceptation

- `./gradlew detekt` vert localement et en CI.
- Une PR déclenche `validate` : `lint`, `test`, `build-image` (sans push) et
  `validate / gate` verts. L'image se construit multi-arch.
- Un merge sur `main` publie `ghcr.io/geoffreycoulaud/pinry-reborn-api:latest` (+ `sha`)
  multi-arch avec les trois attestations cosign vérifiables.
- `grype-scan.yml` s'exécute (manuellement via `workflow_dispatch`), récupère le SBOM
  attesté, applique le VEX, publie un SARIF dans l'onglet Security.
- `dependabot.yml` valide (visible dans l'onglet dépendances).
- `SECURITY.md` et `security/vex.openvex.json` cohérents (product PURL
  `pkg:oci/pinry-reborn-api`).

## 11. Points à confirmer en revue

1. Ajout de `commitlint` en check PR **non requis** : OK ou on l'intègre au gate / on
   l'omet ?
2. Ajout de la **datasource prod SQLite** + `quarkus-smallrye-health` dans ce périmètre
   (nécessaire pour une image fonctionnelle) : OK ?
3. Smoke test de l'image (démarrage + `/q/health`) inclus ou différé ?
