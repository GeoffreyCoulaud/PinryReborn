# Review mandate: evidence

**Artefact: a specification, or a plan.** Your brief names which.

You are reviewing what the document **claims to be true**, not what it proposes to build. Every
other angle judges the design; you judge its premises. A design derived correctly from a false
premise is wrong, and it is wrong in the most expensive way, because the work is planned around it
before anyone measures.

Report findings as `SEVERITY | file:line | issue | suggested fix`, most severe first, where
SEVERITY is one of `CRITICAL`, `MAJOR`, `MINOR`. **Do not edit anything.** Say plainly if you find
nothing.

Severity here is about what rests on the claim, not about how wrong it is. A false statement that
one task depends on is MAJOR; a false statement that a decision was taken on is CRITICAL.

1. **Enumerate the factual claims.** Read the document and list every statement about how something
   behaves: a library, a framework, the build, the database, an existing class in this repository.
   Opinions, goals and intentions are not your subject. "Ebean's `beginTransaction()` nests" is a
   claim. "We should not nest transactions" is not.
2. **For each claim, find the evidence or produce it.** A claim carries the command that established
   it, or a citation into the source it rests on. Where it carries neither, **go and measure it
   yourself** rather than judging whether it sounds right. Read the library's source or its
   documentation, run the query, grep the call sites, write the three-line test. A claim you could
   not settle is itself a finding: report it as unverified and name the command that would settle
   it.
3. **Counted evidence is recounted.** A number offered in support of a decision (how many call
   sites, how many paths, how many navigations, how many rows) is re-derived from the code, never
   read from the document. Counts are where documents lie most often, because nobody re-runs them
   and the code moves. Report the command you ran and the number it gave.
4. **A dated measurement is not a current one.** A claim quoting an earlier document, an earlier
   handoff or an earlier ADR is evidence that it was true once. Re-run it. When the numbers differ,
   the finding is against the document under review, and the stale source is named so it can be
   corrected too.
5. **A spike proves nothing until it isolates its variable.** Where the document rests on a spike or
   an experiment, ask what else could have produced the observed result. A spike that leaves the old
   path in place has not tested the new one. This has shipped here: a spike "confirmed" that an
   observer fires on a produced bean while the bean was still being discovered by another route
   entirely, and the design built on it failed at implementation.
6. **The document against itself.** Does any claim contradict another claim in the same document, or
   a claim in the spec this plan derives from? A count stated twice with two different values is the
   readable form of this; the subtle form is two sentences that cannot both be true.
7. **The claim that decides.** Name the one claim the document's central decision rests on. If it
   turns out false, what happens to the work? Say so explicitly in your report, whatever your
   verdict on it. A reviewer who checked everything and never says which premise is load-bearing has
   given the reader nothing to prioritise.
