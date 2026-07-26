# Handoff: INVALID_LOGIN + documentation regime cleanup, and macOS vips-ffm gate fix

Branch: `chore/cleanup-invalid-login-doc-regime` (cut from `main` on 2026-07-26).
Tier: Direct (debt reduction and a build-config fix; no design decision, no public-surface change).

## Current state

Three logical changes on the branch, all backward-safe:

1. `refactor(error): remove unreachable INVALID_LOGIN error code` deletes the dead
   `PinCreationError` / `PinCreationBadLoginError` hierarchy (the subclass was declared in
   `api-usecases` but constructed nowhere; verified by `rg PinCreationBadLoginError --type kotlin`),
   drops the `INVALID_LOGIN` enum entry, removes the `BaseErrorMapper.statusFor` arm (the `when`
   stays exhaustive with no `else`), removes the mapping test, and updates the two `agents/project.md`
   prose mentions that described the removed code (the API-contract note and the Module-conventions
   example, now `UserCreationError` + `BoardCreationError`).
2. `docs(agents): complete documentation regime table and clarify denied tools` classifies the
   documents the table had left out (`SECURITY.md`, `agents/project.md`, the byte-identical
   `agents-baseline` files labelled "sourced", and a note that `.claude/` is harness config) and
   explains the two `.claude/settings.json` deny-list entries.
3. `build: auto-resolve homebrew libvips lookup for vips-ffm on macOS` makes the full gate pass on a
   macOS host after `brew install vips`. Single-source in the root `build.gradle.kts`.

## What was built

- One fewer `ErrorCode`, one fewer dead exception hierarchy, one fewer misleading doc example.
- The Documentation regime table now accounts for every document in the repo.
- The two deny-list entries are explained (one is a no-op).
- The full Gradle gate now runs on macOS after `brew install vips` (previously blocked: vips-ffm
  could not load homebrew's libvips, so the imaging tests and the image-touching `api-application`
  integration tests failed and the `pre-push` hook blocked every push).

## Pitfalls / friction

- **The holistic review caught a missed second mention.** The first pass updated the API-contract
  paragraph that referenced `INVALID_LOGIN` but missed the `PinCreationError` example in Module
  conventions ninety lines down. Landing the fix in commit 1 (simultaneity: doc hunk and code hunk in
  one commit) required a `git reset --mixed HEAD~2` + rebuild, which the Claude Code auto-classifier
  blocks as destructive without explicit operator authorization (the operator approved it).
- **vips-ffm + macOS SIP + the Quarkus test task.** vips-ffm resolves libvips via DYLD_LIBRARY_PATH
  on macOS, but SIP strips DYLD_* from the signed Adoptium JDK, so homebrew's libs are invisible to
  the JVM. The fix is the `vipsffm.libpath.{vips,glib,gobject}.override` properties (all three are
  required, not just vips). They must be applied in `afterEvaluate`: config set in the plain
  `tasks.withType<Test>` block does NOT reach the api-application (Quarkus) test JVM (verified for
  both the systemProperty and jvmArgs forms — the -D is absent from that JVM's command line and the
  imaging tests fail with UnsatisfiedLinkError), but set in `afterEvaluate` it is present and they
  pass. The precise Quarkus internal is not load-bearing; the observation is. Captured in the root
  `build.gradle.kts`.

## Not validated against real conditions

- The full gate now passes on this macOS host after `brew install vips` (verified:
  `./gradlew check koverVerify --rerun-tasks` green, no env vars). Before the build fix, the
  imaging tests and the `api-application` image-touching integration tests failed on
  `VipsLibLookup` load; that blocker is resolved in the build.
- The `EnterPlanMode` no-op claim rests on this harness exposing `ExitPlanMode` but not
  `EnterPlanMode`. `/permissions` is the source of truth on the effective tool set.

## Suggested next step

- Integrate: the gate passes locally, so `git push` works from this host (the `pre-push` hook runs the
  same gate). Open a PR and merge with `gh pr merge --rebase`.
- Then the natural P1 follow-up is user-data import (the other half of the export that shipped).
