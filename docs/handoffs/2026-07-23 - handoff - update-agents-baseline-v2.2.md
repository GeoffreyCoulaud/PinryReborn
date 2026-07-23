# Handoff: update agents-baseline v2.1.0 to v2.2.1

Date: 2026-07-23
Branch: `chore/adopt-agents-baseline` (backup at `chore/adopt-agents-baseline-backup`)

## Current state

The repository was on `agents-baseline` v2.1.0 (adopted 2026-07-23, see
`docs/adr/0001-adopt-agents-baseline.md` and the sibling adopt handoff). This run is the version
bump to v2.2.1, driven by the `adopting-agents-baseline` skill in its `update` mode: generic files
recopied, `docs/project.md` reexamined against what the bump changed. Nothing is committed; the tree
holds the changes on the work branch, for integration through a PR.

## What the update did

**Generic files recopied (no decision, byte-identical upstream):**

- `AGENTS.md` to v2.2.1: condensed (4090 to 3839 words) and extended. New rules that concern this
  project: "Generated artefacts are declared, not assumed" (Engineering norms), "Proof outlives the
  session in the commit message" and "the tool cannot do that is a claim like any other" (Evidence),
  "Fix the design, do not work around it" with its three smells (Design), the Direct tier now runs
  Wrap, and a dated document freezes on delivery rather than on commit.
- `.claude/hooks/evidence-guard.py`, `docs/reviews/{plan,task,holistic}.md`, `docs/agents/backend.md`
  to v2.2.0: version marker only, no behaviour change.
- `docs/agents/kotlin.md` to v2.2.0: drops the JUnit major version and defers to the version catalog
  (`gradle/libs.versions.toml` pins `junit = 6.1.1`). This matches the refuted-claim note already in
  `docs/project.md` Design invariants.

**`.claude/settings.json` merged.** `permissions.allow` gains `Agent` and `Task` on top of the
project's `Bash(./gradlew:*)` and `Bash(git stash:*)`; `deny` unchanged, no conflict. This is the
subagent permission the earlier adoption asked upstream for (handoff "ask upstream to ship the
subagent permission"): the three review phases now run without a per-call approval. The permission
removes the prompt only; a session told out-of-band not to spawn subagents stays told.

**`docs/project.md` reexamined (delta sort).** Two statements folded upstream in the v2.2.0 baseline
(commit 91b2749, operator-validated) were dropped as now-covered duplication: "Fix the design, do
not work around it" (Design invariants) and "the evidence for a library or tool claim goes in the
commit message" (Conventions). One situated declaration the generic file now delegates was added:
the integration branch is `main`.

## The three contradictions, and how the operator settled them

The bump moved four things the v2.1.0 generic file hard-coded into `docs/project.md`, and folded one
recorded override into the generic rule. Three needed the operator; the integration-branch and
documentation-path declarations followed from them.

| # | Contradiction | Resolution |
|---|---|---|
| C1 | v2.2.1 Evidence delegates the current-documentation source to `docs/project.md`, which named none; no `.mcp.json`, no vendored docs | **keep-project**: declare the absence. The rule resolves to the upstream documentation of the stack (Quarkus, Ebean, libvips, Gradle), named when a claim rests on it. Consistent with ADR 0001, which repealed the context7 tooling statement locally. |
| C2 | v2.2.1 Wrap delegates the documentation-only local-merge path list to `docs/project.md`; the project's own gotcha warns any local merge to `main` bypasses CI | **no exemption**: `docs/project.md` declares no local-merge paths, so every change, documentation-only included, integrates through a PR. |
| C3 | Gate perimeter framed the kapt `@Generated` exclusion as "the only override the generic rule forbids"; v2.2.1 adds "Generated artefacts are declared" and now sanctions it | **adopt-baseline**: reframe the kapt exclusion as a declared generated artefact, recorded in the new `docs/adr/0002-generated-artefacts-in-gate-perimeter.md`. The models-package exclusion (B1) and the rebase-only merge remain the genuine local narrowings. |

ADR 0001 is frozen and untouched; ADR 0002 carries the C3 revision and cross-references it. The gate
perimeter (inside/outside) did not change: no class enters or leaves coverage.

## What is NOT validated

- **The three session checks were not run by this handoff.** `/context` (every expected file loads),
  `/hooks` (the PreToolUse entry is listed), `/permissions` (the deny rules took effect and `Agent`
  and `Task` are allowed) each fail silently and only a live session of the operator's can confirm
  them. The backlog item "Finish the documentation regime table, and explain the two denied tools"
  already flags that `/permissions` needs to settle whether `EnterPlanMode` denies anything real.
- **The project gate was not run.** The change touches only agent-baseline files, `docs/project.md`
  and the ADRs, no Kotlin or build config, so `./gradlew check koverVerify` measures unchanged code.
  CI runs it on the PR regardless.

## Next step

1. Run `/context`, `/hooks`, `/permissions` in a fresh session to confirm the three silent checks.
2. Open a PR from `chore/adopt-agents-baseline` (all changes go through a PR; `docs/project.md` is
   excluded from any documentation-only merge exemption, and this project declares none anyway).
3. After merge, delete the work and backup branches. The originals of the replaced generic files live
   under `/tmp/agents-baseline-adopt/` until then.
