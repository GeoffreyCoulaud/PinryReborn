package fr.geoffreyCoulaud.pinryReborn.api.utilities

import java.time.Instant

/**
 * A fixed instant tests build fixtures with, instead of reading the wall clock.
 *
 * Millisecond-coarse so it round-trips through the SQLite store unchanged
 * ([fr.geoffreyCoulaud.pinryReborn.api.system.SystemClock] truncates for the same reason). Tests
 * needing a different instant derive it (`TestTime.now.plusSeconds(60)`) or use an explicit
 * `Instant.parse` for an ordered set. In testFixtures, which the `WallClockRead` rule excludes.
 */
object TestTime {
    val now: Instant = Instant.parse("2026-07-23T10:00:00Z")
}
