# 0002. Generated artefacts are declared out of the gate perimeter

Status: Accepted
Date: 2026-07-23

## Context

`agents-baseline` v2.2.1 adds a rule to Engineering norms: **"Generated artefacts are declared, not
assumed. Code no human wrote and no test can reach is not source, so it was never inside;
`docs/project.md` names each generator and its exclusion, and a generator absent from the list is
inside."** It arrived with the v2.1.0 to v2.2.1 update of this repository.

ADR 0001 recorded the coverage gate's exclusion of Ebean's kapt output (`Q*` query classes and every
`io.ebean.typequery.Generated` class) as `keep-project`: at v2.1.0 the generic rule said a perimeter
is decided by location with **no per-category exemption**, so excluding classes by annotation
contradicted it, and the exclusion was kept as an override. ADR 0001's Consequences call it "the only
place the generic file is overridden here."

v2.2.1 folds that exemption into the generic rule itself. Generated code nobody wrote and no test can
reach was never inside the perimeter, and the rule now asks `docs/project.md` to name each generator
and its exclusion, annotation filters included. The kapt exclusion therefore stops being an override
of the generic rule and becomes the mechanism the generic rule prescribes.

## Decision

Reframe, without changing the perimeter. No class enters or leaves coverage; only the classification
changes.

- The kapt `Q*` / `io.ebean.typequery.Generated` exclusion is a **generated-artefact declaration**
  under the v2.2.1 rule, not an override. `docs/project.md` names the generator (Ebean's typequery
  kapt processor) and its exclusion (the annotation and the `Q*` query package).
- The `...persistence.sqlite.models` package exclusion (decision B1) **remains a genuine local
  narrowing**: those entity classes are hand-written and Ebean enhances them in place, so their
  bytecode-level bookkeeping is mis-attributed to the wrong source line. It is a location and
  coverage-calibration decision, not a generated artefact, so the generic rule does not absorb it.
- The rebase-only merge convention **remains a local override** of the "squash or rebase" wording.

## Consequences

- `docs/project.md` Gate perimeter no longer frames the kapt exclusion as "the only override". The
  remaining local narrowings of `AGENTS.md` are the models-package exclusion (B1) and the merge
  convention.
- ADR 0001 is a frozen dated document and is left unchanged. Its Consequences bullet stating that the
  annotation exclusion "is the only place the generic file is overridden here" is **revised by this
  ADR**: it held under v2.1.0 and stops holding under v2.2.1. This ADR is the record of the shift.
- No test is added or removed and no coverage figure moves. `build.gradle.kts` remains the authority
  on the perimeter, and this decision changes nothing it enforces.
