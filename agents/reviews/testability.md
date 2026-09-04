# Review mandate: testability

**Artefact: a specification**, block table included.

You are reviewing whether what this document asks for **can be pinned by a test in this project**,
under this project's rules. The gate demands 100 % branch coverage per package. Logging is not
asserted, it is a side effect of outcomes. Some branches are unreachable from the fixtures the tool
that would test them can build. A behaviour nobody can assert is not thereby forbidden, but it must
be declared as an accepted limit rather than discovered at the end of a task.

Report findings as `SEVERITY | file:line | issue | suggested fix`, most severe first, where
SEVERITY is one of `CRITICAL`, `MAJOR`, `MINOR`. **Do not edit anything.** Say plainly if you find
nothing.

1. **Every named path has a test, and every test has a named path.** Where the document names a path
   to cover (a retry, a reap, a rollback, a mid-batch failure), find the test that pins it. Where it
   names several and provides one, say which are unpinned. This shipped here: a spec named reap as a
   path to cover, the implementer covered retry only, and the gap survived to the review.
2. **Can the test be written at all?** For each assertion the document implies, ask what would have
   to be constructed to make it fail. A branch reachable only from a state no fixture can build is a
   branch the 100 % bound will reject at the end of the task, not at its start. The precedent: a
   detekt rule whose import check created a null branch that `lint()` snippets could never reach, so
   the rule had to be redesigned rather than tested.
3. **What the project does not assert.** Logging is the standing example: `api-usecases` binds a
   no-op logger and its tests assert outcomes. A criterion phrased as "a warning is logged" is
   invisible to the gate. This shipped here: one of three transitions logged, and nothing failed.
   Where a behaviour is only observable through a channel this project does not assert, say so and
   propose either an observable outcome or an explicit accepted limit.
4. **Does the test discriminate?** For each test the document names, ask what wrong implementation
   would still pass it. Flag assertions on values that are constant regardless of the logic,
   fixtures that do not exercise the distinction under test, and tests that assert a call was made
   rather than an outcome obtained.
5. **Where coverage forces a design.** The 100 % bound is per package, so a branch reachable only
   from a direct unit call decides where the code can live. This is not a defect to report but a
   consequence to surface: say when the document's structure and the coverage rule are going to
   collide, before an implementer discovers it.
6. **Test cost.** A new `@QuarkusTest` class costs a full boot in every gate run. Does the document
   ask for a new suite where a case in an existing one would do? A suite is justified by a scenario
   no existing suite can host, never by a case that could be a method.
7. **Fixtures that weaken as they are shared.** Where the document reuses an existing fixture, base
   class or seed, check that it still exercises the distinction the new behaviour needs. A shared
   fixture widened for a new case often stops discriminating for the old ones.
8. **Does each block own its tests?** Read the block table: a block delivers behaviour and the tests
   that pin it, in that block. Tests collected into a later "write the tests" block arrive green,
   with no red they could have been written from, and nothing is left to show they hold.
