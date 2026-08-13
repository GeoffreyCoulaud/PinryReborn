# Review mandate: security

**Artefact: a specification.**

You are reviewing what this specification lets a hostile caller do. Assume the caller reads the
source, holds a valid account, and is patient. Your subject is the design, not the code that does
not exist yet: an authorization rule stated in the wrong order, a key an attacker chooses, a guard
that checks one hop of a multi-hop operation.

This is a self-hosted pin board with a single-writer SQLite store, a filesystem image store and a
task worker. Its trust boundary is the HTTP surface; its expensive resources are disk, memory and
bcrypt.

Report findings as `SEVERITY | file:line | issue | suggested fix`, most severe first, where
SEVERITY is one of `CRITICAL`, `MAJOR`, `MINOR`. **Do not edit anything.** Say plainly if you find
nothing.

1. **Who is allowed, and checked in what order.** For each operation, name the identity it requires
   and where ownership is verified. Order matters and is a frequent defect: checking state before
   ownership tells an outsider whether a resource exists. Say what each error path reveals to a
   caller who is not entitled to know it.
2. **What the caller controls, and how far it travels.** Trace every attacker-supplied value: a
   name, an identifier, a URL, a filename, a size, a page number. Where does it end up? A map key, a
   path segment, a query, a log line, an allocation size. **Attacker-chosen input used as a key or a
   size is the shape to hunt.** This shipped here: a login limiter keyed on the submitted name in
   full, so sixty names of one megabyte retained tens of megabytes while the entry-count bound
   never noticed.
3. **Enumeration and oracles.** Does any pair of responses differ in a way that answers a question
   the caller should not be able to ask? Status code, error code, message, response time, and the
   presence of a header all count. Constant-time-by-construction is the property to look for, not
   constant-time-by-accident.
4. **Guards that check one hop.** Where the document specifies a validation before an operation that
   re-resolves, re-reads or redirects, the guard covers the first hop only. The precedent here is
   the outbound fetch: the address policy checks the first resolved IP and the client re-resolves at
   connect, which is a consciously accepted gap because DNS is not attacker-controlled in this
   deployment. Report the shape when you meet it, and say what makes it acceptable or not here.
5. **Cost asymmetry.** What does one request cost the server, and what does it cost the caller?
   bcrypt, image decoding, archive building and full-table scans are the expensive ones. A path
   where an unauthenticated caller triggers an expensive operation is a finding even when it is
   authorized.
6. **Secrets and tokens.** Where a token, hash or credential appears in the design: how is it
   generated, what is persisted, what is returned, what could be logged. A plaintext token in a
   response is intended; the same token in a log line is not.
7. **Accepted gaps are written down.** Where the document knowingly leaves something open, check
   that it says so and says why. An accepted limit belongs in the spec or its ADR, where the next
   reader meets it. Silence about a known gap is the finding, not the gap.
