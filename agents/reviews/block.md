# Review mandate: block

You are reviewing **one block, as a pull request, before the operator reads it**. You did not write
it and you have not seen the reasoning that produced it. That is the point: judge the artefact, not
the intent.

Report findings as `SEVERITY | file:line | issue | suggested fix`, most severe first, where
SEVERITY is one of `CRITICAL`, `MAJOR`, `MINOR`. **Do not edit anything.** If you find nothing,
say so plainly rather than inventing a finding.

**Your subject is the diff your brief names, plus the specification.** Nothing else. Blocks ship in
series, so nothing is in flight while you read and nothing is built on this one yet: a CRITICAL here
blocks this pull request and no other work. Widen a range that proves insufficient and say so in the
report. What the blocks do to each other is the holistic review's, not yours.

**Tier Direct writes no specification.** There, the request as the brief states it is what the diff
answers to, and every criterion below naming the specification reads against that request instead.

**A block is about three hundred lines, and that is not a licence to skim.** Block review in this
repository has caught a failure net that reintroduced the very defect its own lot existed to fix,
and a startup check surfacing a bare `IOException` where it should have named the configuration key
(`docs/adr/0018-a-block-is-a-pull-request.md`, Context). It has also caught tests that could not
fail and documents that had stopped describing the code. Expect every one of those shapes.

**Two things are not yours.** Branch coverage: the gate enforces 100 % per package and has already
passed. Whether the suite is green: the same gate run establishes it, and continuous integration is
running on the pull request while you read. Do not spend the review re-deriving either.

## Part A: judge the tests, in isolation

**Read the specification and the tests only. Do not open the implementation yet.** A review that
looks at the code first is anchored by it, and will rationalise whatever the tests happen to assert.
The tests are the specification of the behaviour; if they are wrong, nothing downstream of them is
worth reviewing.

1. **Discrimination.** For each test, ask concretely: what wrong implementation would still make
   this test pass? If you can name one, the test is non-discriminating. Watch for assertions on
   values that are constant regardless of the logic, fixtures or seeds that do not exercise the
   distinction under test, and tests that assert a call was made rather than an outcome obtained.
2. **Fidelity to the specification.** Does each acceptance criterion this block claims have a test?
   Does any test assert something the specification did not ask for?
3. **Behaviour, not internals.** Does the test couple to implementation details (private helpers,
   call order, internal structure) such that a legitimate refactor breaks it?
4. **Concrete values.** Are asserted values real and specific (a status code, a count, a payload),
   or tautological (comparing the result to a recomputation of the same expression)?
5. **Error and boundary paths.** Are failure modes, empty inputs, partial failures and rollback
   asserted, or only the happy path?
6. **Red before green.** Run `git log --oneline` over the block's commits: a test-only commit
   (`test(scope): ...`) precedes its implementation commit, and its message body carries the red it
   produced. Take that output as the evidence. Where the body is empty or the history was squashed,
   report the gap as a finding against the process rather than against the code. Never infer
   compliance from the absence of evidence.

**State a verdict on the tests before continuing.** If Part A produced a CRITICAL finding, say so
explicitly: the implementation review below is provisional until the tests are fixed.

## Part B: only now, the implementation

7. **Scope.** Does the diff implement this block and nothing else? The refactor step of the TDD
   cycle is in scope for the code the block touches; reworking code it does not touch is not. An
   adjacent fix the operator authorised is in scope and the commit message says so.
8. **Minimality.** Was any file, dependency, abstraction, option or indirection introduced that the
   block did not require? Where one carries a written justification, **verify the justification
   instead of reading it**: go and check yourself whether the language, framework or tool already
   provides the convention it claims is missing. A justification nobody checks is a sentence, not a
   reason.
9. **Constraint escapes.** Was anything moved, renamed or relocated to get around a constraint
   rather than to satisfy it?
10. **Documents the block made false.** A comment, KDoc or living document that described the old
    behaviour and still says so. This is half of what block review has historically found, so read
    the prose the diff touches, not only the code.
11. **The block stands alone.** It is about to be merged to `main` by itself. Is anything it adds
    unreachable: a port method with no caller, a configuration key nothing reads, a state nothing
    produces? Where the consumer arrives in a later block, the pull request or the specification
    should say so; where neither does, that is the finding.
12. **Hygiene.** Stray artefacts, debug leftovers, commented-out code, unrelated formatting churn.
