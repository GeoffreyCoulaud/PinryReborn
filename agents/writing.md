# Writing and documentation

## Two regimes

| Regime | Property | Rule |
| --- | --- | --- |
| **Dated** | Append-only once frozen: records what was believed on that date. | Never edit a frozen document; write a superseding one. |
| **Living** | Describes the present; drifts silently. | Updated in the same commit as the change. |

| Document | Regime |
|---|---|
| `README.md`, `SECURITY.md`, `docs/backlog.md` | living |
| `AGENTS.md`, `agents/*.md`, `agents/reviews/*` | living: updated in the same commit as the change they describe |
| `docs/openapi.json` | generated: rewritten by the `pre-commit` hook, checked in CI, never edited by hand |
| `docs/specs`, `docs/plans`, `docs/adr`, `docs/handoffs` | dated, append-only |

`docs/plans/` is closed to new files: a plan is now the block table inside its specification
(`docs/adr/0018-a-block-is-a-pull-request.md`, decision 3). The plans already there stay frozen and
readable like every other dated document.

`.claude/` is harness configuration and code, not documentation: outside this regime.

## Rules

- **A dated document freezes at delivery** (branch integrated), not at writing. After delivery,
  changes go in a new dated document, cross-linked both ways, the old one marked
  `Status: Superseded by <file>`.
- **Simultaneity**: a living doc is updated in the same commit as the behaviour it describes,
  never a follow-up `docs:` commit.
- **The backlog is the pressure valve**: findings the operator declined, or that genuinely belong
  to another lot, are proposed for it rather than done or lost. What the operator authorised is
  fixed in the lot instead (`agents/workflow.md`, Scope).
- **A backlog item holds in two lines**, plus a pointer to the dated document that carries its
  reasoning. Symptom and where it lives, nothing else: the argument is usually already written in
  the spec or handoff of the lot that filed it, and copying it here stores it twice and makes the
  file unreadable at the length that costs. Items reached 13 lines each before this rule
  (`docs/adr/0018-a-block-is-a-pull-request.md`).
- **An item whose reasoning lives nowhere else keeps it.** Dated documents are append-only, so a
  finding whose argument was only ever written into the backlog cannot be moved out of it now:
  compressing it would destroy it, not relocate it. The items in that state are the ones the file
  marks; do not put a count here, which would drift on the next edit. **This is an exception to
  inherit, not to create**: a new item is filed by a lot that has a spec and a handoff, so its
  reasoning goes there and the entry stays at two lines.
- **Renumbering breaks anchors**: after reordering sections, re-check every cross-reference.
- **An edit that does not apply is reported**, never assumed landed.
- **A dated document does not put a number on a living file**: it records what it did; the count
  is read where it lives. Say "the items this lot leaves open are 1, 2 and 14", never "the band
  holds three".
- **A rule or a review mandate changes only in a lot whose subject it is**, never in passing to
  make the work at hand easier: the change is the lot's declared subject, or arrives in its own
  `docs(agents):` commit. Exception (Simultaneity): a rule discovered while writing the code that
  establishes it ships in that code's commit.
- **Rules govern work from adoption onward**: pre-existing violations are inventoried once and
  reported, never swept or fixed as a side effect; a frozen dated document is never rewritten to
  satisfy a later rule.

## Style

- **Everything in the repository is in English** (identifiers, comments, logs, commits, PRs,
  docs); conversation stays in the user's language; genuine domain data keeps its own language.
- **Write in plain language**: lead with the point, active voice, present tense, short sentences,
  common words; lists, tables and headings where they carry structure faster than prose. Applies
  to everything written for the repository and every message to the user.
- **Never use an em dash or en dash** anywhere humans read. Use a colon, period, parentheses or
  hyphen. Exception: agent-only scratch artefacts. Enforced by the gate (`checkNoLongDashes`) and
  the pre-commit hook.
- **A comment holds in two lines.** Past that it is documentation and goes where documentation
  lives (spec, ADR, backlog, handoff); the comment keeps the one sentence that says why plus the
  pointer.
- **Comments explain why, not what.** Density matches the surrounding file.
- **No abbreviations in code, comments, KDocs or logs**: domain terms are spelled out ("garbage
  collection", not "GC"). Narrative documents may abbreviate after the first definition.
