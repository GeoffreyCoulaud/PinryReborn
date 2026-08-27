package fr.geoffreyCoulaud.pinryReborn.api.application

import fr.geoffreyCoulaud.pinryReborn.api.worker.ExportsConfig
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * The two keys the export sweep reads, resolved by the container: an anonymous implementation of
 * the mapping asserts nothing about `@WithDefault`. Default profile, so this joins the instance.
 */
@QuarkusTest
class ExportsConfigIntegrationTest {
    @Inject
    lateinit var config: ExportsConfig

    @Test
    fun `Given no configuration, Then the interrupted grace is the age a build is presumed dead at`() {
        // Given / Then: anchored on the staged file age, never on the task lease. An attempt lasts
        // as long as its staging progresses, so a shorter grace condemns a live builder, which then
        // meets a non-PENDING row at its fence and throws away a complete archive.
        assertEquals(Duration.ofHours(6), config.interruptedGrace())
        assertEquals(config.stagedFileMaxAge(), config.interruptedGrace())
    }

    @Test
    fun `Given no configuration, Then the sweep batch size is the one the orphan sweep already uses`() {
        // Given / Then: both new selections are bounded at the query, so the first run after a
        // deployment catches up over successive ticks instead of materialising the whole history
        assertEquals(500, config.sweepBatchSize())
    }
}
