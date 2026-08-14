package fr.geoffreyCoulaud.pinryReborn.api.worker

import io.smallrye.config.ConfigMapping
import io.smallrye.config.WithDefault
import java.time.Duration

/** Spec section 9's table, which this mapping is the single source of. Next to [ExportsConfig]. */
// One accessor per key of that table, which trips the per-interface threshold. Splitting is not
// available: a nested group renames its keys (imports.sweep.interval), and the keys are the contract.
@Suppress("TooManyFunctions")
@ConfigMapping(prefix = "imports", namingStrategy = ConfigMapping.NamingStrategy.SNAKE_CASE)
interface ImportsConfig {
    @WithDefault("/var/lib/pinry/imports")
    fun dataDir(): String

    @WithDefault("21474836480") // 20 GiB
    fun maxArchiveBytes(): Long

    // Strictly under quarkus.http.limits.max-body-size, which the framework enforces first: a chunk
    // equal to it is refused before this bound ever answers. ImportsConfigIntegrationTest holds that.
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
