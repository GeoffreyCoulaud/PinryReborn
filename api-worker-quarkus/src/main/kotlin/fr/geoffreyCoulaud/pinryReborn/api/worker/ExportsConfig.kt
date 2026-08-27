package fr.geoffreyCoulaud.pinryReborn.api.worker

import io.smallrye.config.ConfigMapping
import io.smallrye.config.WithDefault
import java.time.Duration

@ConfigMapping(prefix = "exports", namingStrategy = ConfigMapping.NamingStrategy.SNAKE_CASE)
interface ExportsConfig {
    @WithDefault("/var/lib/pinry/exports")
    fun dataDir(): String

    @WithDefault("P7D")
    fun retention(): Duration

    @WithDefault("PT1H")
    fun minimumInterval(): Duration

    @WithDefault("PT1H")
    fun purgeInterval(): Duration

    @WithDefault("PT6H")
    fun stagedFileMaxAge(): Duration

    /**
     * Anchored on [stagedFileMaxAge], not on the task lease: an attempt lasts as long as its staging
     * progresses, and a shorter grace condemns a live builder, which then discards a valid archive.
     */
    @WithDefault("PT6H")
    fun interruptedGrace(): Duration

    @WithDefault("500")
    fun pageSize(): Int

    /** Bounds each sweep selection at the query, as `garbage-collection.orphan_batch_size` does. */
    @WithDefault("500")
    fun sweepBatchSize(): Int

    @WithDefault("1073741824")
    fun minimumFreeBytes(): Long
}
