This file provides guidance to AI Agents when working with code in this repository.

## Project status: alpha

Breaking changes and data loss are acceptable, and **nobody should be running Pinry Reborn yet**. Treat this as a
decision input, not a disclaimer: when the only thing standing in the way of the clean fix is "a database somewhere
already applied this", take the clean fix and note the consequence. The migration history is append-only for now and
will be flattened into a single baseline at beta (see `docs/backlog.md`).

## Hard rules (enforced, non-negotiable — do not relax)

- **Tests are the spec.** Strict TDD: write the failing test first, watch it fail, then the minimal implementation;
  review judges the tests first. **100% branch coverage per package**, gated in CI and the pre-push hooks. Never lower
  the threshold; add the missing test (exercise *both* sides of every conditional).
- **Clean / Hexagonal.** `api-domain/` is **pure** — no I/O, no config/DB/network/clock/logging imports. All I/O lives
  in the adapters, and the dependency graph is a DAG (`ArchitectureKonsistTest` enforces both). Corollary: **domain
  data is stamped by use cases, never invented by adapters** — creation and update instants, ids and state transitions
  are business facts, so the use case sets them (from a port such as `Clock`) and the adapter stores what it is given.
- **Fix the design, don't work around it.** Three smells mean the design is wrong, not the call site: the same
  explanatory comment repeated in several places for one workaround; a domain type widened to nullable or optional so
  existing call sites need not change (a migration-cost argument, not a design one); a workaround for a tool
  limitation nobody verified.
- **Verify before working around.** For library/framework/CLI questions use the **context7 MCP** (current docs) or the
  library's own source, not recalled knowledge — above all before concluding a tool cannot do something. Put the
  evidence in the commit message, never an unchecked claim in a comment.
- **Language: all code is English** (decided 2026-07-07) — identifiers AND prose: comments, docstrings, runtime-emitted
  messages/logs, CI step names, and commit messages. **New docs under `docs/specs/`, `docs/plans/` and `docs/handoffs/`
  are written in English** ; past docs keep their original language (no retro-translation). Conversational replies to
  the operator stay their chosen language.
- **Conventional commits** (`feat(domain):`, `fix(domain):`, `test:`, `chore:`, `docs:`).
- **Subagent-driven execution** (Act phase) + **holistic review** (Verify phase): the cross-cutting review regularly
  catches bugs — don't skip it.

## Architecture

This is a Kotlin API server following **Clean Architecture** with strict layer boundaries.

### Modules

```
api-domain/              # Domain entities & repository interfaces
api-usecases/            # Business logic use cases
api-persistence-sqlite/  # Ebean ORM implementations
api-presentation-quarkus/# REST controllers & DTOs
api-application/         # Entry point & integration tests
api-utilities/           # Shared utilities & test fixtures
```

### Dependency Rules (STRICT)

| Module                     | May Depend On                                                    |
|----------------------------|------------------------------------------------------------------|
| `api-domain`               | Nothing (pure). May use `api-utilities` if absolutely necessary. |
| `api-usecases`             | `api-domain` only                                                |
| `api-persistence-sqlite`   | `api-domain`, `api-utilities`                                    |
| `api-presentation-quarkus` | `api-usecases`, `api-domain`                                     |
| `api-application`          | All modules (composition root)                                   |

**Never poke holes through layers.** Presentation must not call persistence directly. Use cases must not depend on
persistence implementations.

## Key Technologies

- **Quarkus 3** - REST framework with Jakarta REST, HTTP Basic Auth via Quarkus Security
- **Ebean 19** - ORM with Kotlin query beans and SQLite
- **Java 25** (Adoptium)
- **libvips** (native) required for the imaging tests (local dev + CI)
- **Testing**: JUnit 5, MockK, REST Assured

## Database Migrations

Migrations live in `api-persistence-sqlite/src/main/resources/dbmigration/`. To generate a new migration after changing
entity models:

```bash
./gradlew :api-persistence-sqlite:generateDbMigration
```

## Workflow — Discuss → Spec → Act → Verify → Wrap → Improve

Six phases, always in order. **Committing is cheap** — you're allowed to commit autonomously.

### 1. Discuss

**Free-form text** discussion with the user. Use `brainstorm` or `pick-my-brain` skills if clarification is needed. *
*No `AskUserQuestion` tool** — ask the question in the message directly. No code, no plan — just understanding.

Start from **`docs/backlog.md`** — the living, priority-ordered backlog of **open items only** (what already
shipped is tracked in git history, the handoffs, and the tags, **not** in the backlog). It is the source of truth
for "what's left"; keep it current (see Wrap).

### 2. Spec

Two forms, depending on complexity:

- **Simple / obvious** : inline spec in the conversation, a few paragraphs.
- **Structured** : spec markdown (`docs/specs/<date>-<slug>.md`) + plan markdown (`docs/plans/<date>-<slug>.md`) if
  needed.

**The spec is reviewed and approved by the user** before writing plans (if any). Plans are not reviewed — they follow
from the approved spec.

**Do not use `EnterPlanMode`.** The project workflow is self-contained, not coupled to Claude Code's plan-mode feature.

### 3. Act

`main` is **integration-only** ; never edit directly on it. As soon as code or docs will be modified, **branch first**.

**Branching :** ask the user (4 options) :

1. Stay on current branch
2. New branch **in-place** (`git switch -c <branch>`) — suggested default for edits the user follows in their editor
3. New **worktree** (`EnterWorktree`) — suggested default when dispatching coding agents
4. Other (user describes)

Naming: `<type>/<kebab-slug>` (conventional-commit types: `feat`, `fix`, `docs`, `chore`, `test`, `refactor`).

**Execution: subagent-driven by default.** Delegate work to subagents (`Agent`) to keep the main context clean.
Exception: very simple, short, localized action (e.g. one file, one change) → do inline. Use the
`subagent-driven-development` or `dispatching-parallel-agents` skill as appropriate.

**Worktrees:** `EnterWorktree` creates `.claude/worktrees/<name>`, moves the agent session there, the user's editor
stays on `main`. `.claude/worktrees/` is gitignored. `worktree.baseRef = "head"`.

### 4. Verify

Run the **full gate**. Review the produced code **holistically** — this review regularly catches cross-cutting bugs.

Any non-documentation change reaches `main` **through a PR** (see Wrap) so CI validation runs before merge — this
holistic review is the last local check before that PR.

### 5. Wrap

Once the gate is green and code reviewed:

1. **Write the handoff** in `docs/handoffs/<ISO date> - handoff - <context>.md`: current state, what was just built,
   learned pitfalls, suggested next step, what is NOT validated against real hardware. The handoff is committed before
   continuing the wrap phase. **Also refresh `docs/backlog.md`**: the backlog holds **open items only** — the
   sub-project you just finished is now recorded by its handoff, git history, and tag, so **do not add a "shipped"
   entry** (the backlog has no such section). Delete (or narrow) the item you just completed, add any newly
   discovered open items, and update "Last reviewed".
2. **Integrate.** **Push the branch and open a PR** for any change touching code, config, tests, `deploy/`, or CI:
   `main`'s branch protection requires the `validate / gate` check, but `enforce_admins: false` means a local admin
   merge silently bypasses CI — don't. Wait for the gate green, then merge (linear history is required → **squash or
   rebase**, not a merge commit). **After merging, reconcile the backlog on `main`**: confirm the finished item has
   been **removed** from `docs/backlog.md` (the backlog carries no "shipped" section — its record lives in the
   handoff, git history, and tag); if a stale entry survived the pre-merge refresh, delete it with a doc-only commit
   to `main`. **Exception — documentation-only** (diff touches only `docs/**` + root `*.md`): a
   local merge/commit to `main` is fine, no PR needed. "Leave as-is" stays available when the user wants to handle it
   later.
3. **Tag** annotated `vX.Y.Z-<name>` (not pushed), one per subsystem.
4. **Clean up** branch and/or worktree if applicable.

Use the `finishing-a-development-branch` skill to guide this phase.

### 6. Improve

**Not optional, and not skippable because the work "went fine".** Once the sub-project is integrated, turn what
went wrong into something the build catches next time. A lesson that stays in the conversation is lost when the
session ends.

**This phase opens with a discussion, exactly like Discuss. Never act alone in it.** Start by stating, in free-form
text: the **failures met** during the sub-project, and the **remedy proposed** for each. The question to answer is
*what did a human have to notice that the gate should have noticed?* — so review the session for corrections the
operator made, bugs the holistic review caught, and assumptions that turned out false. **The operator validates both
the list of failures and the remedies retained**; write, commit and push nothing before that.

Once validated, give each retained remedy the cheapest durable form:

- **Hard rule in this file** when it is a judgement call a tool cannot check.
- **Konsist test** (`ArchitectureKonsistTest`) when it is a structural invariant over the source.
- **detekt rule** when it is a local code pattern.
- **Plain test** when the invariant is about repository content (see `DbMigrationModelCoverageTest`).
- **Backlog item** when the fix is real work rather than a rule.

Record nothing the gate already enforces, prefer one precise rule over three vague ones, and keep this file dense:
a rule nobody finishes reading enforces nothing. Commit separately (`docs(agents):`, `test(architecture):`).

## Build Commands

```bash
./gradlew quarkusDev                              # Start dev server with hot reload
./gradlew test                                    # Run all tests
./gradlew :api-usecases:test                      # Run tests for a specific module
./gradlew :api-usecases:test --tests "UserCreatorTest"  # Run a single test class
./gradlew build                                   # Build the application
./gradlew :api-persistence-sqlite:generateDbMigration   # Generate Ebean DB migrations
```

## Testing

### Testing Order

Write tests in this order, each failing before moving to implementation:

1. **Integration tests** (`api-application`) - REST Assured end-to-end tests
2. **Use-case unit tests** (`api-usecases`) - MockK-based business logic tests
3. **Repository tests** (`api-persistence-sqlite`) - Ebean database tests

### Red-Green-Refactor Cycle

1. **Red**: Write a failing test
2. **Green**: Write minimal code to make it pass
3. **Refactor**: Clean up while keeping tests green

### Test Naming Convention

Test names use backticks with **"Given..., Then..."** format (no "when" in the name):

```kotlin
@Test
fun `Given a valid user, Then creation succeeds`() {
    ...
}

@Test
fun `Given duplicate username, Then throws UserCreationError`() {
    ...
}
```

### Test Body Structure

Tests follow **Given-When-Then** structure with explicit comments:

```kotlin
@Test
fun `Given valid credentials, Then authentication succeeds`() {
    // Given
    val username = "testuser"
    val password = "password123"

    // When
    val result = authenticator.authenticate(username, password)

    // Then
    assertNotNull(result)
}
```

### Test Maintainability

- **Create helper methods** for repeated setup (e.g., `createAndSaveUser()`)
- **Use test variables** with meaningful names, not inline literals
- **Leverage `createRandomString()`** from utilities for unique test data
- **Extend base test classes**: `IntegrationTest`, `BaseTest`, `RepositoryTest`

## Module Conventions

- Domain entities in `api-domain/entities/` have corresponding repository interfaces in `api-domain/repositories/`
- Persistence implementations in `api-persistence-sqlite/repositories/` use mappers in `mappers/` to convert between DB
  models and domain entities
- Use cases in `api-usecases/` throw domain-specific exceptions (e.g., `UserCreationError`, `PinCreationError`)
- REST controllers in `api-presentation-quarkus/controllers/` use DTOs in `dtos/` for input/output