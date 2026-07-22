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

    @WithDefault("500")
    fun pageSize(): Int
}
