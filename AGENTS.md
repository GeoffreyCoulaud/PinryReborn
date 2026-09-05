# AGENTS.md

Pinry Reborn's API server: business logic of a self-hosted pin board (users, pins, boards, tags, images, exports),
exposed as an HTTP API. Kotlin + Quarkus, Clean Architecture, SQLite store, filesystem image storage, task worker for
long operations.

Process, engineering norms and writing conventions live in separate documents; read the one your task needs:

- `agents/workflow.md` : phases, tiers, review mandates, backlog rules.
- `agents/engineering.md` : TDD, coverage, gate perimeter, Kotlin and backend norms.
- `agents/writing.md` : documentation regimes, language and style rules.
- `docs/handoffs/` : the newest file is the entry point (current state, pitfalls, next step).
- `docs/backlog.md` : open items only.

## Where the code lives

Twelve Gradle modules (`settings.gradle.kts`). Layering enforced by the build graph and
`ArchitectureKonsistTest`.

| Module                     | Role                                                                      |
|----------------------------|---------------------------------------------------------------------------|
| `api-domain`               | Pure: entities, enums, ports. No project dependency.                      |
| `api-usecases`             | Business logic, exceptions, search, exports, task contracts.              |
| `api-persistence-sqlite`   | Ebean/SQLite: models, mappers, repositories, migrations (`dbmigration/`). |
| `api-presentation-quarkus` | Jakarta REST: controllers, DTOs, mappers, security, OpenAPI.              |
| `api-storage-filesystem`   | Image store, rendition cache, export archives.                            |
| `api-imaging-vips`         | libvips adapter (vips-ffm).                                               |
| `api-fetch-http`           | Remote image fetch behind an address policy.                              |
| `api-system`               | Clock, bcrypt, token generation.                                          |
| `api-worker-quarkus`       | Task worker: dispatcher, handlers, export retention.                      |
| `api-utilities`            | Shared helpers, `BaseTest` fixture (testFixtures).                        |
| `api-application`          | Composition root + end-to-end integration tests.                          |
| `detekt-rules`             | Project detekt rules; outside the layering.                               |

## Setup (once per clone)

- `git config core.hooksPath .githooks` (enables pre-commit and pre-push hooks).
- Native libvips: `brew install vips` (macOS) or `libvips42t64` (Ubuntu 24.04), otherwise
  `api-imaging-vips` and image-touching integration tests cannot load the library.
- `python3` on the PATH (`.claude/hooks/evidence-guard.py` runs on every Bash command; without python3 it enforces
  nothing, silently).

## Commands

- Runner: `./gradlew` (committed wrapper; JDK 25 toolchain auto-provisioned).
- **Gate (the single local knob)**: `./gradlew gate`
- One test: `./gradlew :api-usecases:test --tests "UserCreatorTest"`
- New migration (after changing an entity model): `./gradlew :api-persistence-sqlite:generateDbMigration`.
- Destructive migration (drop): re-run the generator with the property **in the generator's JVM**, not on the Gradle
  CLI:
  `JAVA_TOOL_OPTIONS="-Dddl.migration.pendingDropsFor=<version>" ./gradlew :api-persistence-sqlite:generateDbMigration`.
  Commit both pairs together. Precedent: `1.13` and `1.14__dropsFor_1.13`.
- No auto-fix task: detekt has no formatting rules, ktlint is IDE-only. Fix findings by hand.

## CI

CI (`validate.yml`) is not a caller of `gate`: it enumerates the gate's parts. A check added to
`gate` alone runs on no pull request. CI also builds the container image and checks
`docs/openapi.json` sync; no local command covers either.

## Gotchas

- **A local merge to `main` bypasses CI** (`enforce_admins` is false). Always push and open a PR; merge is rebase-only
  (`gh pr merge --rebase`).
- **Never edit an applied migration**: the checksum changes and Ebean refuses the history. A correction is a new
  migration.
- **Unique constraint on SQLite**: `@Index(definition = "create unique index ...")`, never
  `unique = true` (Ebean emits an unsupported `ALTER TABLE` that becomes a silent no-op comment).
  `DbMigrationModelCoverageTest` fails on the no-op marker.
- **Partial or expression index**: `definition` alone, no `columnNames`, no `unique = true`.
- **The `pre-commit` hook rewrites `docs/openapi.json`**, stages it, and exits non-zero when it changed: re-run the
  commit. It also rejects em/en-dashes in staged text.
- **A changed detekt rule is not picked up by a live Gradle daemon** (cached classpath: false green). Run
  `./gradlew --stop` before trusting a local gate after a rule change.
- **detekt baselines are per module** (`config/detekt/baseline-<module>.xml`): the `detektBaseline`
  task rewrites rather than merges.
