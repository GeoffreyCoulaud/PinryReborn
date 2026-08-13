# Review mandate: operations

**Artefact: a specification.**

You are reviewing what this design does **on a running server, over time**. Tests exercise the happy
path on an empty database with a fast disk. Production runs for months, fills the disk, restarts at
the wrong moment, and meets the error path that no test reaches.

Your subject is resources, lifecycle and failure. Not correctness of the logic: other angles have
that.

Report findings as `SEVERITY | file:line | issue | suggested fix`, most severe first, where
SEVERITY is one of `CRITICAL`, `MAJOR`, `MINOR`. **Do not edit anything.** Say plainly if you find
nothing.

1. **What grows, and what bounds it.** For every store the design writes to (rows, files, cache
   entries, in-memory maps, temp files), name what removes entries and under what condition. A
   store with no stated eviction grows until the disk is full. Say which of the growths is bounded
   by a number, which by a sweep, and which by nothing.
2. **What leaks on the failure path.** Take each operation that creates something before it commits:
   a temp file, a promoted file, a reserved permit, an open transaction, a lease. Now make the next
   step throw. What is left behind, and who cleans it? This shipped here: a rendition whose cache
   store failed left one temp file per request in `java.io.tmpdir`, frequently a tmpfs, which turns
   a disk leak into memory exhaustion.
3. **Which path is actually the common one.** A design often names a fallback for a rare case. Check
   whether it is rare. The precedent here: renditions staged in `java.io.tmpdir` while the
   destination was under the data directory, so the cross-filesystem fallback that "rarely" runs was
   the path every request took, and it needed a flag it did not have.
4. **Time budgets that must nest.** Where the design states more than one duration (a timeout, a
   lease, a retry interval, a forget-after, a backoff step), write them in order and check they
   nest. A fetch timeout longer than the lease that protects it means a second worker starts while
   the first still runs. Two intervals checked in isolation can be individually correct and jointly
   wrong: a forget-after longer than the last backoff step means a counter never walks back down.
5. **Startup and shutdown.** When is this validated, and what happens if it is invalid? A guard in a
   lazily-created bean fires on first use, so an invalid configuration boots clean and then fails
   every request forever. Say which failures should refuse to start and whether the design makes
   them do that. On shutdown: what is in flight, and what happens to it.
6. **Restart and repetition.** The worker retries, the server restarts, the sweep runs again. For
   each operation, ask what a second execution does: is it idempotent, does it double-count, does it
   resurrect something. A cleanup that assumes it runs once is a cleanup that runs twice.
7. **What the operator sees.** When this fails at three in the morning, what is in the log, and does
   it name the cause? An error path that swallows its cause, or a counter reported as a success
   metric when it counts attempts, leaves an operator with nothing. Best-effort operations say so at
   the point where somebody reads the number.
