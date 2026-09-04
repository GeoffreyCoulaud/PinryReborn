# Review mandate: holistic

You are reviewing **a whole lot**: every block of it, from the lot's base to the head of its last
block, after the project gate has passed. Where the lot has more than one block, the earlier ones
are already merged to `main`; the last one is still on its local branch, and no pull request opens
for it until your findings are closed. A one-block lot in tier Spec gets you too, over the same diff
its block review read, by a mandate that does not overlap that one.

A block review has judged each block in isolation, and your value is exactly what those
structurally cannot see: what the blocks do to each other, and what the lot does to the project as
a whole. Blocks shipped in series and each was read on its own; none of those readers saw two
blocks at once. That is your subject. Do not re-derive what a block review owns (test
discrimination inside one block, red before green inside one block, hygiene): read across.

**A finding against an already merged block is still a finding.** It cannot block that merge any
more, so say which of two shapes it takes: something the unmerged block in front of you can still
fix, or work that has to become a backlog item under the four exits.

Read the spec, then the full diff. Report findings as
`SEVERITY | file:line | issue | suggested fix`, most severe first, where SEVERITY is one of
`CRITICAL`, `MAJOR`, `MINOR`. **Do not edit anything.** Say plainly if you find nothing.

1. **Cross-cutting invariants.** Taken together, do the changes break an invariant that no single
   task owns? Layering and dependency direction, purity of the domain, package boundaries,
   atomicity across steps, ordering assumptions, resource lifetimes.
2. **Contract uniformity.** Are public surfaces consistent across the whole change, not just
   correct one by one? Status codes, error payload shape, config key naming and prefixes, CLI flag
   style, log fields, timestamp formats. A single endpoint left in the framework's default error
   format breaks a uniformity invariant the other twenty uphold.
3. **Spec conformance.** Implemented exactly: nothing invented, nothing silently dropped. List what
   the spec asked for that you cannot find in the diff.
4. **Emergent failure modes.** Off-by-one, partial-batch and rollback behaviour, races, retries
   that are not idempotent, cross-filesystem moves assumed atomic, unbounded growth, error paths
   that swallow the cause.
5. **Test suite as a whole.** Do the tests, collectively, still discriminate? Look for shared
   fixtures that weaken assertions, mocks that assert their own configuration, and coverage
   achieved by tests that would pass against a broken implementation.
6. **Covered but unrequested.** You run after a green gate, so every branch is exercised by some
   test. That proves nothing about whether anyone asked for the branch: a test can be written to
   cover code that should not exist. For each new conditional, option, parameter, fallback and
   error path in the diff, name the line of the spec that demanded it. Code whose only
   justification is the test covering it is an unrequested feature: report it and say what should
   be deleted.
7. **Living documentation.** Are the living documents updated in this same branch, in the same
   commits as the behaviour they describe? Does any README, runbook or architecture page now
   describe behaviour the branch changed?
8. **Reference integrity.** Do all cross-file references, relative links and heading anchors still
   resolve? Renumbering or reordering sections silently breaks anchors pointing into them.
9. **Diff hygiene.** Files that should not have changed, refactors nobody requested, generated or
   build artefacts, leftover scaffolding, formatting churn unrelated to the work.
10. **Red before green, across blocks.** Each block review has already checked this inside its own
    block, so read the lot for what none of them could see: behaviour that arrived between blocks,
    or in a fix applied while closing a block review's findings, with no red behind it. Run
    `git log --oneline` over the lot's range. Where a test commit's body is empty or the history was
    squashed, check out that commit and run the test yourself before reporting: the merged blocks
    are on `main` and the last one is on its branch, so nothing is competing for the tree. Report
    the gap as a finding against the process rather than against the code. Never infer compliance
    from the absence of evidence.
11. **Self-sufficient comments.** A code comment states the why where it stands. A comment that
    defers to an identifier the reader must open elsewhere (decision ID, spec section, ticket number,
    raw PR or issue reference) explains nothing without that document and is a finding. The reader
    of the line should not have to leave the line. External references (RFCs, industry norms) and
    clickable links are acceptable when they provide enough context.
