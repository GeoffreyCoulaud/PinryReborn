<!-- agents-baseline v3.2.0 | generic file, identical in every project | do not edit in place -->

# Review mandate: holistic

You are reviewing **the complete diff of a branch against its base**, after the project gate has
passed. Per-task reviews have already judged each task in isolation. Your value is exactly what
they structurally cannot see: what the tasks do to each other, and what the branch does to the
project as a whole.

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
