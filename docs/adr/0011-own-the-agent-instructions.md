# 0011. Own the agent instructions

Status: Accepted
Date: 2026-08-12

## Context

Since 2026-07-23 this repository has run its agent instructions as frozen copies of a separate
repository, `agents-baseline`. `AGENTS.md`, two optional modules and three review mandates were
byte-identical across projects, replaced by copy on a version bump and never edited here. Everything
situated lived in `agents/project.md`. Two bumps were recorded: v2.1.0 in
`docs/adr/0001-adopt-agents-baseline.md`, v3.2.0 in
`docs/adr/0005-adopt-agents-baseline-v3.2.md`. The tree carries v3.4.0.

The regime bought one thing: a rule true of every project was written once and every project got it.
It cost a round trip through a second repository for every lesson, plus a statement-by-statement
reexamination of `agents/project.md` against each arriving delta, which ADR 0005 documents at
length. In practice the operator carries both repositories alone, and the round trip has become the
reason lessons do not get written down rather than the mechanism that shares them.

The freeze also did a second, unrelated thing: it made it impossible for an agent to amend, in the
middle of a lot, the rules that judge that lot. That property is worth keeping and does not need an
upstream repository.

A third cost was structural rather than procedural. Because the generic file could not be edited,
every situated statement that restricted a generic one had to be written as a commentary on another
document: "`AGENTS.md` offers squash or rebase; here only rebase exists". Twelve such references
accumulated in `agents/project.md`, and four more in the two modules. They are pure plumbing, and
they exist only because the two halves could not be written in one place.

## Options considered

1. **Keep the regime.** Rejected: the round trip is the cost being refused, and no amount of
   tooling removes it while two repositories must agree.
2. **Unfreeze but keep the file split.** The freeze goes, the four files stay. Cheapest, but it
   preserves a split whose only justification was the freeze, along with the sixteen cross-references
   it forces, and it leaves "where does this rule go" a judgement call with no answer.
3. **Unfreeze and consolidate.** The freeze goes and the four instruction files become one. Chosen.
4. **Consolidate everything, mandates included.** Rejected on a technical ground: the review
   mandates are forbidden reading for the main agent so that an implementer does not write towards
   its own criteria. Merging them into `AGENTS.md` would load them into every session.

## Decision

Settled by the operator on 2026-08-12.

1. **The repository owns its instructions.** The copies stop being copies. No file carries the
   generic marker, and `agents-baseline` is named nowhere outside the frozen dated documents that
   record the adoption. This is a clean cut, not a loosened link: there is no upstream to follow and
   no provenance line to maintain.
2. **Four instruction files become one `AGENTS.md`.** `agents/project.md` and the two modules merge
   into it. The three review mandates keep their own files, unfrozen, for the reason in option 4.
3. **The hook loses its third rule.** `evidence-guard.py` blocked any edit to a file whose first
   line carried the marker. With no marked file left, the rule guards nothing, so it goes rather
   than remaining as unreachable code. Its two other rules are untouched.
4. **A rule changes only in a lot whose subject it is.** This replaces what the freeze incidentally
   provided. The observable is a separate `docs(agents):` commit, which the Improve phase already
   practises.
5. **ADR 0001 and ADR 0005 stay `Accepted`.** They arbitrated contradictions between the project's
   own rules and the arriving ones, and every one of those arbitrations still holds. What ends is
   the adoption mechanism, not its outcomes, so marking them superseded would retract decisions that
   remain in force.

No engineering norm is added, weakened or reworded. `docs/specs/2026-08-12-consolidate-agents-instructions.md`
carries the closed list of what the merge deletes and the check that nothing else did.

## Two records rescued from the instructions

Both sentences below documented the `AGENTS.md` that existed **before** the 2026-07-23 adoption, a
file that has not existed for three weeks. They are records, not instructions, so they leave the
instruction file and are kept here.

From `agents/project.md:233-247`, "Claims the old `AGENTS.md` made that the code disproved, recorded
rather than deleted":

- It listed six modules; `settings.gradle.kts` declared eleven at the time and twelve once
  `detekt-rules` arrived. The five it never mentioned (`api-storage-filesystem`, `api-imaging-vips`,
  `api-fetch-http`, `api-system`, `api-worker-quarkus`) all existed when it was replaced.
- It said `api-usecases` may depend on `api-domain` only; `api-usecases/build.gradle.kts` also
  declares `api-utilities`. The dependency table had drifted from the build graph, which is why the
  build graph and `ArchitectureKonsistTest` are the authority.
- It said JUnit 5; `gradle/libs.versions.toml` pins `junit = "6.1.1"`.

From `agents/project.md:151`, on the authentication scheme: "HTTP Basic is gone; the old `AGENTS.md`
still announced it."

The lesson they share outlives the file that prompted them: a table describing the code drifts from
the code, and the authority is whatever the build or a test enforces.

## Consequences

- **A lesson true of every project now lands here**, in `AGENTS.md`, like any other. There is no
  upstream proposal step and no second repository to keep in step.
- **Nothing shares rules with another project any more.** A future project starting from this one
  copies a file and forks it; convergence is no longer maintained by anything. This is the accepted
  price, and it is the point of the decision rather than a side effect.
- **The instruction file roughly quadruples in length**, since it now carries the situated half, the
  language module and the backend module. It is what an agent already loaded at every session
  through the `@` imports, so the context cost is unchanged and the reading cost is lower by the
  sixteen cross-references the split forced.
- **Amending the rules is now possible in any session**, which the freeze made impossible. Decision
  4 is the only thing standing between that and a lot quietly rewriting its own criteria, and it is
  a convention, not a mechanism. A hook could enforce it later if it turns out not to hold.
- **`.claude/CLAUDE.md` is unchanged**, and so is every build, CI and git-hook path: none of them
  ever named the merged files.
