# Review mandate: precedent

**Artefact: a specification.**

You are reviewing whether what this document proposes **agrees with what the project already
decided**. A design that is defensible in isolation and different from its five neighbours costs
more than a worse design that matches them, because every reader afterwards has to learn which of
the two rules applies where.

Your job is not to defend the existing convention. It is to make sure a departure is a decision
somebody took, with a reason, rather than something that happened.

Report findings as `SEVERITY | file:line | issue | suggested fix`, most severe first, where
SEVERITY is one of `CRITICAL`, `MAJOR`, `MINOR`. **Do not edit anything.** Say plainly if you find
nothing.

1. **Find the nearest sibling and read it.** For each behaviour the document specifies, find the
   closest thing this repository already does: the same operation on another entity, the same
   contract on another endpoint, the same lifecycle on another bean. Read that code. Report every
   difference in shape, and say for each whether the document acknowledges it. The instance here:
   one recycle bin validated ownership before state and its new sibling did the reverse, which
   turned a 409 into a 404 and a 403 into a 409, and left a declared error code dead.
2. **Check the written invariants.** `AGENTS.md` and `agents/engineering.md` carry hard rules about
   layering, purity of the domain, where logging lives, what a port may expose, and how errors
   surface. Read them against the document. A rule broken in a specification is broken in every task
   derived from it.
3. **Check the ADRs that are still in force.** `docs/adr/` records decisions with their reasons.
   Where the document contradicts one, the finding is not "this is wrong" but "this reverses ADR
   NNNN, and the document does not say so". A reversal is legitimate and it is a decision: it needs
   its own record.
4. **A departure states its reason, and the reason is checked.** Where the document does justify
   diverging, verify the justification rather than reading it: go and see whether the convention it
   calls insufficient really is. A justification nobody checks is a sentence, not a reason.
5. **Two sources for one value.** Does the document introduce a second place where a value already
   living somewhere is restated? A default in an annotation and again in a properties file, a
   constant in code and again in a document, a limit in the spec and again in a test fixture. Both
   copies are correct on the day they are written, and one of them drifts. This shipped here: a
   properties file restating defaults the annotations already carried turned a test of the defaults
   into a test of the file.
6. **Naming and vocabulary.** Does the document name things the way the codebase names them? A new
   word for an existing concept creates two concepts in the reader's head. Check the terms against
   the domain types, not against general usage.
7. **Where a precedent is wrong, say so separately.** If the sibling you found is itself defective,
   that is a finding about the sibling, not a reason to bless a departure. Report it as its own
   entry, marked as pre-existing, so it can go to the backlog instead of being fixed in passing.
