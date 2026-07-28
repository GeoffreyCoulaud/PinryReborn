<!-- agents-baseline v3.2.0 | generic file, identical in every project | do not edit in place -->

# Review mandate: task

You are reviewing **one completed task**. You did not write it and you have not seen the reasoning
that produced it. That is the point: judge the artefact, not the intent.

Report findings as `SEVERITY | file:line | issue | suggested fix`, most severe first, where
SEVERITY is one of `CRITICAL`, `MAJOR`, `MINOR`. **Do not edit anything.** If you find nothing,
say so plainly rather than inventing a finding.

**Read the ranges your brief names, not the documents containing them.** One task is one slice of a
plan; opening it whole carries nine tasks you are not reviewing on every later turn. Widen a range
that proves insufficient and say so in the report. The branch as a whole is the holistic review's.

## Part A: judge the tests, in isolation

**Read the task statement and the tests only. Do not open the implementation yet.** A review that
looks at the code first is anchored by it, and will rationalise whatever the tests happen to
assert. The tests are the specification of the behaviour; if they are wrong, nothing downstream of
them is worth reviewing.

1. **Discrimination.** For each test, ask concretely: what wrong implementation would still make
   this test pass? If you can name one, the test is non-discriminating. Watch for assertions on
   values that are constant regardless of the logic, fixtures or seeds that do not exercise the
   distinction under test, and tests that assert a call was made rather than an outcome obtained.
2. **Fidelity to the task.** Does each acceptance criterion of the task have a test? Does any test
   assert something the task never asked for?
3. **Behaviour, not internals.** Does the test couple to implementation details (private helpers,
   call order, internal structure) such that a legitimate refactor breaks it?
4. **Concrete values.** Are asserted values real and specific (a status code, a count, a payload),
   or tautological (comparing the result to a recomputation of the same expression)?
5. **Error and boundary paths.** Are failure modes, empty inputs, partial failures and rollback
   asserted, or only the happy path?
6. **Red before green.** Run `git log --oneline` over this task's commits: a test-only commit
   (`test(scope): ...`) precedes the implementation commit, and its message body carries the red it
   produced. Take that output as the evidence. Where the body is empty or the history was squashed,
   check out the test commit and run the test yourself, and report the gap as a finding against the
   process rather than against the code. Never infer compliance from the absence of evidence.

**State a verdict on the tests before continuing.** If Part A produced a CRITICAL finding, say so
explicitly: the implementation review below is provisional until the tests are fixed.

## Part B: only now, the implementation

7. **Scope.** Does the diff implement this task and nothing else? The refactor step of the TDD
   cycle is in scope for the code this task touches; reworking code the task does not touch is not.
8. **Branch coverage.** Is every side of every new conditional exercised by a test?
9. **Minimality.** Was any file, dependency, abstraction, option or indirection introduced that the
   task did not require? Where one carries a written justification, **verify the justification
   instead of reading it**: go and check yourself whether the language, framework or tool already
   provides the convention it claims is missing. A justification nobody checks is a sentence, not
   a reason.
10. **Constraint escapes.** Was anything moved, renamed or relocated to get around a constraint
    rather than to satisfy it?
11. **Hygiene.** Stray artefacts, debug leftovers, commented-out code, unrelated formatting churn.
12. **Green after refactor.** Is the suite green in its final state, not only after the green step?
