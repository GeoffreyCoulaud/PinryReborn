package fr.geoffreyCoulaud.pinryReborn.api.worker

import io.smallrye.config.ConfigMapping
import io.smallrye.config.WithDefault
import java.time.Duration

/** Spec section 9's table, which this mapping is the single source of. Next to [ExportsConfig]. */
// One accessor per key of that table, which trips the per-interface threshold. A nested group would
// keep the keys, since @WithParentName exists for exactly that; it would invent a grouping the table
// does not have, which is the reason not to, rather than any renaming.
@Suppress("TooManyFunctions")
@ConfigMapping(prefix = "imports", namingStrategy = ConfigMapping.NamingStrategy.SNAKE_CASE)
interface ImportsConfig {
    @WithDefault("/var/lib/pinry/imports")
    fun dataDir(): String

    @WithDefault("21474836480") // 20 GiB
    fun maxArchiveBytes(): Long

    // Read by no use case, deliberately: the framework's body limit is the enforcer and refuses an
    // oversize chunk before any import code runs. This key records the size a client is told to send,
    // and its invariant against that limit, which ImportsConfigIntegrationTest holds.
    @WithDefault("16777216") // 16 MiB
    fun maxChunkBytes(): Long

    @WithDefault("200000")
    fun maxEntries(): Int

    @WithDefault("16777216") // 16 MiB
    fun maxMetadataBytes(): Long

    @WithDefault("1048576") // 1 MiB
    fun maxLineBytes(): Int

    @WithDefault("1073741824") // 1 GiB
    fun minimumFreeBytes(): Long

    /** Inactivity, not age: measured from creation it would abandon an upload still streaming. */
    @WithDefault("PT24H")
    fun uploadGrace(): Duration

    @WithDefault("PT1H")
    fun sweepInterval(): Duration

    @WithDefault("PT48H")
    fun stagedFileMaxAge(): Duration

    @WithDefault("200")
    fun leaseRenewalLines(): Int

    /** The queue's default backoff spends five attempts in seconds, which no operator can use. */
    @WithDefault("PT10M")
    fun retryFloor(): Duration

    @WithDefault("500")
    fun reportDetailLimit(): Int
}
