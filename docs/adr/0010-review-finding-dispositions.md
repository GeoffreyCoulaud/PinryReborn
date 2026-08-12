# 0010. A review finding has four exits, and the backlog is banded by nature

Status: Accepted
Date: 2026-08-12
Specification: `docs/specs/2026-08-12-p2-debt-triage.md`
Related: `docs/adr/0001-adopt-agents-baseline.md` (the workflow whose Wrap phase feeds the backlog),
`docs/adr/0008-structural-soft-delete-read-isolation.md` (the ADR that already records the limits one
of these items was duplicating).

## Context

The workflow requires a per-task review and a holistic review over the branch diff, both by fresh
subagents, and both work: the operational-debt wave's holistic review found a MAJOR that the gate
could not see, and the unique-constraint lot's plan review killed a check that could not fail before
any code was written. The reviews are not the problem.

What they produce has one place to go. A finding is either fixed inside the lot or written into
`docs/backlog.md`, and nothing else is offered, so everything a lot decides not to do becomes debt by
default. The unique-constraint lot closed one item and added seven. The band that receives them,
P2, held sixteen of the backlog's twenty-eight items on 2026-08-12, and the count had been rising
since the operational-debt wave closed at nineteen on 2026-08-02.

Reading the sixteen shows they are not one kind of thing:

- **Open work.** Inverse associations on the persistence models: a real change, deferred because it
  needs Ebean behaviour proofs first.
- **Limits already recorded elsewhere.** The soft-delete read-isolation residuals are documented in
  ADR 0008 and in the spec's section 4.6, with the note that none is reachable today. The backlog
  entry is a copy, and a copy of a decision drifts from it.
- **Dated events.** Flattening the migration history happens at beta. No session can start it early,
  so it is not queued work; it is a date.
- **Arbitrations nobody arbitrates.** Whether to split `PinRepositoryTest` sat open for ten days
  while both of its sibling classes already carried the answer in their own KDoc.

Only the first is debt. The other three inflate the number that is supposed to say how much debt
there is, which is how a backlog stops being read.

A fourth pattern sits underneath. Three of the newest items are about the tests that guard the
migrations rather than about the product, so reviewing a guard produced a finding about the guard.
Nothing in the workflow says where that recursion stops, and each turn of it lands in the same band.

## Decision

1. **A review finding has four exits.** Fixed inside the lot; a **backlog item**, which means work
   someone will do; an **accepted limit**, written where the decision lives (its ADR, its spec, its
   handoff) and not copied into the backlog; or **refused**, with the reason in the handoff. Wrap
   states which exit each finding took, which is the observable.

2. **The backlog is banded by nature before it is banded by priority.** *Open work* (P0, P1, P2) is
   what someone will do. *Known limits* points at the document that records each one, and holds no
   copy of it. *Before beta* holds dated events. A limit is not debt and is not counted as debt.

3. **A case joins an existing integration suite; a suite is not created for a case.** A new
   `@QuarkusTest` class costs a full boot in the gate, so it is justified by a scenario an existing
   suite cannot host, never by a case that could be a method in one. Where no suite fits and the
   links are pinned separately, the composition is the coverage and the finding is an accepted limit
   under exit 3.

Rules 1 and 2 are what stop the growth. Rule 3 is the first thing they had to arbitrate, and it is
recorded here because it decided two of the sixteen in opposite directions: the tombstoned-name case
joins `UserCreationIntegrationTest`, which already boots, and the export precedence case does not get
a class of its own because its three links are each pinned.

## Consequences

- **The backlog's size becomes readable again.** Three P2 items after this lot, all of them work
  someone will do.
- **A limit now has one home and one only.** Removing the copy means the backlog no longer warns a
  reader about the soft-delete residuals; ADR 0008 does, and the backlog's *Known limits* band points
  at it. A reader who only reads the backlog sees the pointer, not the substance. Accepted: a copy
  that drifts is worse than a pointer that is followed.
- **"Refused" has to be written.** The exit exists so a finding can be declined on the record; it is
  not a way to decline silently. A refusal with no reason in the handoff is the same silence this
  project refused for unique constraints in ADR 0009.
- **No tool enforces any of the three.** They are judgement rules and they live in
  `agents/project.md`, where `AGENTS.md` Improve sends a judgement call. The gate cannot tell an
  accepted limit from a forgotten one, so the compensating control is that Wrap enumerates the
  findings and their exits, and the next lot's holistic review reads that list.
- **The recursion of guards is not solved here.** Rule 1 gives a finding about a guard the same four
  exits as any other, which bounds where it lands but not how deep the chain goes. If the pattern
  returns, it is its own question.
