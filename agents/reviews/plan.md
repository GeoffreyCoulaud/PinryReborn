<!-- agents-baseline v3.0.0 | generic file, identical in every project | do not edit in place -->

# Review mandate: plan

You are reviewing **an implementation plan before any task is dispatched**. You did not write it.
Every defect you leave here propagates into every task derived from it, which is why this review
exists at all: a plan defect found now costs one edit, found at merge it costs a full re-run.

Read the approved spec first, then the plan. Report findings as
`SEVERITY | file:line | issue | suggested fix`, most severe first, where SEVERITY is one of
`CRITICAL`, `MAJOR`, `MINOR`. **Do not edit anything.** Say plainly if you find nothing.

1. **Checkable criteria.** Does every task state acceptance criteria that can be verified by
   running something, rather than by judgement? A criterion nobody can check is a criterion nobody
   will meet.
2. **Internal consistency.** Do any two tasks contradict each other? Does a task depend on
   something a later task creates? Is any task's precondition never established by an earlier one?
3. **Asserted values.** Every concrete value written into the plan (status codes, counts, names,
   paths, fixtures, seeds, error messages) is checked against the spec and against the existing
   code. This is where plans lie most often: a status code that does not match the project's
   conventions, a seed that does not produce the case it claims to.
4. **Test discrimination.** For each test the plan names, ask: what wrong implementation would
   still pass it? Flag any test or fixture that would pass against a broken implementation. Flag
   any task whose only test asserts that a function was called.
5. **Failure paths.** Are error handling, partial failure, concurrency and rollback specified
   rather than implied? A batch operation with no stated behaviour for a mid-batch failure is an
   incomplete task, not a detail.
6. **Feasibility.** Is anything unimplementable as written, or does it assume an API, a flag, a
   permission or a behaviour that has not been verified?
7. **Scope fidelity.** Does the plan implement the spec exactly? Flag anything invented that the
   spec does not ask for, and anything the spec asks for that no task covers.
8. **Independence.** For tasks meant to run in parallel, do they touch disjoint files? Overlapping
   writes across parallel tasks are a defect of the plan, not of the implementer.
9. **Decision record.** Did the decisions this spec settles get recorded as an ADR? If their
   absence was justified on the grounds that no architectural question arose, test that claim
   against what the plan actually commits to: a plan that picks a library, a storage format, a
   protocol, a boundary or an error contract is settling one.
