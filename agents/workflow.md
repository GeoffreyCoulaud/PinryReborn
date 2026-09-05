# Workflow

How work moves through this repository: scope, evidence, design judgement, phases, reviews, integration. Engineering
norms are in `agents/engineering.md`; writing rules in
`agents/writing.md`.

## Scope

- **Stay inside the repository.** Never read, list or search `$HOME`, parent directories, or another repository; a git
  worktree is its own root.
- **An adjacent defect has three tiers**. No diff hunk should be unexplainable by the request or by the answer the
  operator gave.
    1. **Trivial, obviously correct and contained** : fixed in the change that finds it, flagged in the final message.
       This is the boy-scout rule.
    2. **Larger, and reachable inside this lot**: stop and ask the operator **at the moment of discovery**, stating the
       defect, the size of the fix and the block it would join. Not at the next boundary: the operator prefers being
       interrupted while the context is live over rebuilding it afterward. This tier is where the backlog drains.
    3. **Refused by the operator, or genuinely another lot's**: the backlog.
- **Asking is not optional and neither is stopping.** Tier 2 is a question, so the work waits for the answer. Fixing it
  unasked and backlogging it silently are both wrong.

## Evidence

Nothing is asserted without the command that established it, nothing changed without the diff.

- **A measurement an existing document carries is dated, not current**: re-run it before acting on it, and correct the
  document when the numbers differ.
- **A check that cannot fail is not a check.** Before offering a command as evidence, name the output that would have
  proved you wrong.
- **File content is written with the edit tool, never by a command** (redirection, heredoc, `tee`,
  `sed -i`). Exceptions: throwaway output, and commands whose declared product is the file (formatter, scaffolder,
  generator, compiler).
- **Refuted beats plausible.** Drop a hypothesis the user's evidence contradicts.
- **Consult the declared documentation source**, for any library, CLI or version-dependent value. The source resolves to
  current upstream docs: Quarkus, Ebean, libvips (vips-ffm), Gradle. Name the source when a claim rests on it.

## Design (how to decide)

Decisions already taken are under Design invariants in `agents/engineering.md`.

- **Prefer the convention the tool already has**; a new abstraction is justified in one line naming the convention found
  insufficient.
- **Fix the design, do not work around it.** Smells: the same explanatory comment repeated at several sites; a domain
  type widened to nullable; a workaround for a tool limitation nobody verified...
- **Name the root cause, not only the symptom**: a backlog entry born from a structural symptom names the design smell
  and the refactor that removes it. Fixing inline is still forbidden.
- **Refactor as a first-class solution**: propose it during Discuss/Design, the human arbitrates, the ADR records. Never
  refactor inline without being asked.
- **Never move or rename something to escape a constraint**: satisfy it or report a blocker.
- **A setting that should not exist is not fixed by a good default**: say it should not be there.

## Phases

A work session produces a `lot`, composed of autonomous `blocks`.

**Each block is its own branch off `main`**.   
Branch before the first file is written.   
Committing is cheap: commit autonomously.

### What a block is

A block is the smallest change that can be merged to `main` on its own.

Three conditions:

- **Green alone.** `./gradlew gate` passes at the block's tip. A block therefore never ends between a red test commit
  and the implementation that answers it.
- **Coherent alone.** Nothing it adds is unreachable: every new port method has a caller, every configuration key is
  read, every new state is produced somewhere. Where a surface's real consumer arrives in a later block, the spec says
  so and the pull request repeats it.
- **Readable alone.** The diff excluding dated documents stays under 600 lines. Past that the block splits, or the spec
  states in one line why it cannot.

### The phases

Discuss and Spec run once for the lot. Then **Act, Verify and Integrate run once per block, in series**: a block's pull
request is merged before the next block starts. Wrap closes the lot.

1. **Discuss** : Explore the project, read the backlog, and ask the user questions to align. No code, no files.
2. **Spec** : One document, `docs/specs/<ISO date>-<slug>.md` describing in detail the work to do. Also reviewed by the
   user, in addition to an autonomous adversarial agent review.
3. **Act.** One block. A block that exceeds the budget anyway may still be dispatched, and the brief says why. Strict
   TDD as `agents/engineering.md` states it. An adjacent defect found here takes one of the three tiers under Scope, and
   tier 2 stops the work to ask.
4. **Verify, entirely on the local branch. No pull request exists yet.** Run the full gate. **On the last block of the
   lot, write Wrap's documents first** : they belong in this block's diff, and a review that runs after them reads them.
5. **Integrate.** Push and open the pull request. It is merged only after the human has reviewed it (rebase only, no
   local-merge exemption), approval never assumed. **A red run, or a change the human asks for, returns the block to
   Verify**:, commit the fix, re-run the gate, and re-run the block review over the new commits only. A merge never
   rests on a review that did not read what is being merged. Then clean up the branch or worktree, and the next block
   starts from `main`.
6. **Wrap.** Once per lot. Two halves, and the first runs inside the last block, before its Verify:
   (a) the backlog reconciled, an item closed by a block having been deleted in that block's own pull request; (b) the
   handoff in `docs/handoffs/<ISO date> - handoff - <context>.md`: current state, what was built, pitfalls, what is not
   validated, next step. The second half runs after that pull request merges: (c) tag if the spec called for a release;
   (d) report what was done and the friction points, including the third spec angle chosen with the reason given for it,
   and every tier-2 question asked with the answer it got. That report is the input to Improve.

## The backlog

- **Open items only.** No shipped section: completed work is recorded by its handoff, git history and tag. An item a
  block closes is deleted in that block's PR; Wrap reconciles in the last block's diff, and the result is re-checked on
  `main` after that merge.
- **A lot closes the backlog items adjacent to its subject.** Binding, not advisory
  (`docs/adr/0018-a-block-is-a-pull-request.md`, decision 6): the spec names every adjacent item, and for each one it
  leaves open it states why, which is the operator's to accept. A lot with no adjacent item says so. An adopted item
  that does not fit the block's budget becomes its own block; it is not thereby dropped.
- **An item holds in two lines**, plus a pointer to the dated document carrying its reasoning, with the one exception
  `agents/writing.md` states: an item whose reasoning lives nowhere else keeps it, and says so. An entry long enough to
  need scrolling is an entry nobody rereads. **There is no cap on how many items the backlog holds**: a cap discards
  findings to satisfy a number, and what grew here was the entries, not their count.
- **A review finding has four exits**: fixed inside the lot; a backlog item (work someone will do); an accepted limit
  (written where the decision lives, never copied to the backlog); or refused, with the reason in the handoff. Wrap
  states which exit each finding took. The default is the first: the backlog receives what the operator refused or what
  genuinely belongs to another lot, not what was merely out of the original scope.
- **Banded by nature before priority**: Open work (P0, P1, P2), Known limits (pointers to documents), Before beta (dated
  events). A limit is not debt.
