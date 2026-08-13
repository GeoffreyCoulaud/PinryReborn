# Review mandate: falsifiability

**Artefact: a specification.**

You are reviewing whether this specification **can be failed**. A criterion nobody can fail is not a
criterion: the work will be declared done against it, the gate will be green, and nothing will have
been established. Your subject is the acceptance criteria and every observable the document offers
as proof.

Report findings as `SEVERITY | file:line | issue | suggested fix`, most severe first, where
SEVERITY is one of `CRITICAL`, `MAJOR`, `MINOR`. **Do not edit anything.** Say plainly if you find
nothing.

1. **Name the output that would prove each criterion wrong.** Take each acceptance criterion and
   write down, concretely, what the world would look like if the work failed it: the command, the
   output, the status code, the row, the file. Where you cannot name one, the criterion is the
   finding. This is the whole angle in one instruction; the rest are the shapes it takes.
2. **Is it already satisfied?** Run the criterion against the current repository, before any of this
   work exists. A criterion the tree already meets tests nothing. The instance that shipped here:
   "the generator reports no change" was offered as proof that annotations matched the schema, and
   the generator reports no change on an untouched tree too.
3. **Does the observable discriminate?** A criterion can be checkable, run green, and still not
   distinguish the intended outcome from a wrong one. Ask what other state produces the same
   observation. This has shipped here too: an assertion meant to require that a migration
   **creates** an index was satisfied by a migration that **drops** it, because both mention the
   name.
4. **Does the criterion name the observable, or the instrument?** "The repository test passes" names
   an instrument; "a second insert with the same pin id is rejected" names an observable. An
   instrument-named criterion silently moves whenever the instrument is edited, which makes the test
   its own specification.
5. **Does the property claimed match the property checked?** A bound on entries is not a bound on
   memory. A count of attempts is not a rate. A unique index is not uniqueness if the column is
   nullable. This shipped here: a bound on tracked keys was specified, implemented and reviewed
   twice as a defence against unbounded memory, and the key was an unbounded string, so the bound
   was never reached and bounded nothing. State the property the criterion actually pins, and
   whether it is the one the goal needs.
6. **What is asserted about absence?** Criteria about something not happening (no leak, no log, no
   extra query, nothing left on disk) are the easiest to write and the hardest to fail. Ask how the
   absence is observed, and over what window.
7. **Out of scope has an observable too.** The document's out-of-scope list is a set of claims about
   what will not change. Name how a reader would notice if one of them changed anyway.
