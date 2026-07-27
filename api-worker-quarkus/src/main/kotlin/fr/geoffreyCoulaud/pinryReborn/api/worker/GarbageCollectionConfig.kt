package fr.geoffreyCoulaud.pinryReborn.api.worker

import io.smallrye.config.ConfigMapping
import io.smallrye.config.WithDefault
import java.time.Duration

@ConfigMapping(prefix = "garbage-collection", namingStrategy = ConfigMapping.NamingStrategy.SNAKE_CASE)
interface GarbageCollectionConfig {
    @WithDefault("P1D")
    fun interval(): Duration

    @WithDefault("PT24H")
    fun tombstoneGrace(): Duration

    @WithDefault("P7D")
    fun terminalTaskGrace(): Duration

    @WithDefault("500")
    fun orphanBatchSize(): Int
}
