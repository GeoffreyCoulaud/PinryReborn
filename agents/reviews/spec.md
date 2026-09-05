# Review mandate: specification

**Artefact: a specification**, block table included. Run once, in a fresh subagent, before the
operator reads it; the findings are closed in the document before that reading.

You judge two things and settle a third. What the document **claims to be true**, because a design
derived from a false premise is wrong in the most expensive way, planned around before anyone
measures. Whether its **acceptance criteria can be failed**, because a criterion nobody can fail is
declared done against a green gate with nothing established. And whether the **decisions it settles
have a record**.

Report findings as `SEVERITY | file:line | issue | suggested fix`, most severe first, SEVERITY one of
`CRITICAL`, `MAJOR`, `MINOR`. **Do not edit anything.** Say plainly if a part finds nothing. Stay
inside the repository. Finish with the three findings you would fix first.

## Evidence

Severity follows what rests on the claim: false and a block depends on it, MAJOR; false and a
decision was taken on it, CRITICAL.

1. **Enumerate the factual claims**: every statement about how something behaves, a library, the
   build, the database, a class in this repository. Goals and intentions are not your subject.
2. **Find the evidence or produce it.** A claim carries the command that established it or the
   source it cites. Where it carries neither, measure it yourself: read the source, run the query,
   grep the call sites. A claim you could not settle is a finding: report it as unverified with the
   command that would settle it.
3. **Counted evidence is recounted** from the code, never read from the document. Counts are where
   documents lie most often. Report the command and the number it gave.
4. **A dated measurement is not a current one.** A claim quoting an earlier document is evidence it
   was true once. Re-run it; when the numbers differ, the finding is against the document under
   review, and the stale source is named so it can be corrected too.
5. **A spike proves nothing until it isolates its variable.** Ask what else could have produced the
   observed result. A spike that leaves the old path in place has not tested the new one.
6. **The document against itself.** Two sentences that cannot both be true, a count stated twice
   with two values, a claim contradicting the ADR or the backlog item it derives from.
7. **The claim that decides.** Name the one claim the central decision rests on and say what happens
   to the work if it is false, whatever your verdict on it.

## Falsifiability

1. **Name the output that would prove each criterion wrong**: the command, the status code, the row,
   the file. Where you cannot, the criterion is the finding. The rest are the shapes this takes.
2. **Is it already satisfied?** Run it against the tree as it is. A criterion the tree already meets
   tests nothing; "the generator reports no change" was once offered as proof, and it reports no
   change on an untouched tree too.
3. **Does the observable discriminate?** Ask what other state produces the same observation. An
   assertion meant to require that a migration creates an index was satisfied by one that drops it,
   both mentioning the name.
4. **Does it name the observable or the instrument?** "The repository test passes" names an
   instrument, which moves whenever the test is edited.
5. **Does the property checked match the property claimed?** A bound on entries is not a bound on
   memory; a unique index is not uniqueness if the column is nullable; a grep is not a structural
   test.
6. **What is asserted about absence?** No leak, no log, nothing on disk: how is the absence observed,
   and over what window?
7. **Out of scope has an observable too.** It is a set of claims about what will not change; name how
   a reader would notice if one did.

## Decision record

Did the decisions this specification settles get recorded as an ADR? A specification that picks a
library or a library setting, a storage format, a protocol between two components, a boundary, a
public surface or an error contract is settling an architectural question. Test the one-line
justification given for an absent ADR against that list, and say for each such decision where the
document sends it: an ADR it names, an existing ADR, or nothing.
