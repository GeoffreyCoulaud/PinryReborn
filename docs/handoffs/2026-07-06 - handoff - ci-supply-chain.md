# Handoff: CI + supply chain security

Date: 2026-07-06
Spec: `docs/specs/2026-07-06-ci-supply-chain.md`

## Mise à jour post-merge (2026-07-06)

Les trois PRs (#1, #2, #5) sont mergées sur `main`. Le **chemin release a été validé
en conditions réelles** : le run `release` a publié l'image multi-arch sur GHCR avec les
trois attestations cosign, et `grype-scan` (lancé via `workflow_dispatch`) a **vérifié
les attestations avec succès**. L'hypothèse du `CERT_REGEXP` sur `validate.yml` était
donc **correcte** : aucun correctif nécessaire. La section « NON validé » ci-dessous est
conservée pour mémoire de ce qui était incertain au moment de la construction.

## État actuel

Trois phases construites, chacune sur une branche empilée sur la précédente, en attente
d'intégration séquentielle (plan a : merge PR 1, rebase et PR 2, merge, rebase et PR 3).

| Phase | Branche | Commits | État |
|---|---|---|---|
| 1. Gate CI + detekt | `ci/gate-detekt` | `e6e0c5b`, `df117e8` | **PR #1 ouverte, CI verte**, mergeable |
| 2. Conteneurisation | `feat/container-image` | `2d740ff`, `7b08454` | committée, vérifiée en local, pas de PR |
| 3. Supply chain | `ci/supply-chain-security` | `1658bab`, `22fb810` | committée, pas de PR |

`main` n'a rien reçu (hors la spec, doc-only). Rien des phases 2/3 n'est poussé.

## Ce qui a été construit

- **Phase 1** : detekt 1.23.8 appliqué à tous les modules (config partagée
  `config/detekt/detekt.yml`, baselines par module, 13 violations baselinées). Workflows
  `validate.yml` (réutilisable : `lint`/`test`/`gate`), `pr.yml` (+ commitlint non
  requis), `release.yml`, `dependabot.yml` (gradle + github-actions). Check requis :
  `validate / gate`. Actions épinglées au SHA.
- **Phase 2** : `Dockerfile` runtime (glibc `eclipse-temurin:21-jre`, non-root, `/data`
  volume via `DB_PATH`, HEALTHCHECK sur `/q/health`), extension `quarkus-smallrye-health`,
  job `build-image` dans `validate.yml` (build multi-arch amd64/arm64, push GHCR si
  `push=true`), dependabot `docker`.
- **Phase 3** : sur le chemin release, 2 SBOM (CycloneDX + Syft-JSON) + 3 attestations
  cosign keyless (cyclonedx, syft, openvex) sur l'image poussée ; `grype-scan.yml`
  quotidien (SBOM Syft attesté + VEX → SARIF) ; `security/vex.openvex.json` (vide) ;
  `SECURITY.md`.

## Vérifié

- Gate `./gradlew detekt test` : **vert en local ET en CI sur PR #1**.
- Image : `docker build`, run, `/q/health` = `UP`, flux bout-en-bout (POST user → auth
  basic → GET pins), migrations SQLite exécutées, build **multi-arch amd64+arm64** OK.
- YAML des workflows parse, `vex.openvex.json` JSON valide, toutes actions épinglées SHA.

## NON validé (nécessite un vrai run CI / GHCR, impossible en local)

Tout le **chemin release** (`push=true`), qui ne s'exécute qu'au push sur `main` :

1. **Push de l'image sur GHCR** et tags `metadata-action` (latest/sha ; semver sur tag `v*`).
2. **Signature cosign keyless** : hypothèse que l'identité du certificat SAN est
   `.github/workflows/validate.yml` (car la signature a lieu dans le workflow
   réutilisable, pas dans `release.yml`). **À confirmer au premier release** : si la
   vérification `grype-scan` échoue, ajuster `CERT_REGEXP` dans `grype-scan.yml`.
3. Génération des SBOM par `anchore/sbom-action` sur l'image poussée.
4. `grype-scan.yml` : extraction des attestations + scan + upload SARIF (lançable
   manuellement via `workflow_dispatch` une fois une image publiée).
5. `id-token: write` accordé aux jobs `validate` appelants (requis par le workflow
   réutilisable) : validé seulement au démarrage du run.

## Pièges rencontrés

- **detekt/Kotlin 2.2.21** : detekt 1.23.8 fonctionne ; baseline **par module**
  obligatoire (le task `detektBaseline` réécrit sa cible sans fusionner).
- **Datasource prod pas manquante** : `EbeanDatabaseProducer` la configure en code via
  `DB_PATH` (défaut `data.db`) + migrations. Correction vs spec : aucun changement
  `application.properties` nécessaire.
- **Base image glibc** (pas alpine/musl) car `sqlite-jdbc` charge des libs natives
  (confirmé par les migrations qui tournent dans le conteneur).
- **`gh` dans une fonction shell zsh** : `command not found` (quirk d'env) ; appeler
  `/usr/bin/gh` directement.

## À noter hors périmètre (dette repérée)

- `EbeanDatabaseProducer.setPackages("...adapters.persistence.models")` pointe un package
  **inexistant** (le vrai est `...api.persistence.sqlite.models`). Latent, non traité.
- Chantiers de la revue initiale non traités : durcissement auth (auto-login si pas de
  hash, énumération d'utilisateurs), validation d'entrée, ExceptionMapper global,
  `GreetingController` vestige, upload d'images.

## Prochaine étape suggérée

1. Merger **PR #1** (squash/rebase, linéaire).
2. `git rebase --onto main ci/gate-detekt feat/container-image`, pousser, ouvrir **PR #2**.
3. Après merge PR #2 : `git rebase --onto main feat/container-image ci/supply-chain-security`,
   pousser, ouvrir **PR #3**.
4. Au premier push sur `main` (release) : vérifier la publication GHCR + les 3 attestations
   (`cosign verify-attestation ... ghcr.io/geoffreycoulaud/pinry-reborn-api:latest`), puis
   lancer `grype-scan` manuellement pour confirmer le `CERT_REGEXP`.
