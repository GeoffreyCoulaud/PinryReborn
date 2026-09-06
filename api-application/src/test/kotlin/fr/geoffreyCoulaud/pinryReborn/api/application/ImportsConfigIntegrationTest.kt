package fr.geoffreyCoulaud.pinryReborn.api.application

import fr.geoffreyCoulaud.pinryReborn.api.worker.ImportsConfig
import io.quarkus.runtime.configuration.MemorySize
import io.quarkus.test.junit.QuarkusTest
import io.smallrye.config.WithDefault
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * The one place both halves of the chunk bound are readable: `imports.*` is declared in the worker
 * module, the body limit is Quarkus's own. Default profile, so this joins the running instance.
 */
@QuarkusTest
class ImportsConfigIntegrationTest {
    @Inject
    lateinit var config: ImportsConfig

    @ConfigProperty(name = "quarkus.http.limits.max-body-size")
    lateinit var maxBodySize: MemorySize

    @Test
    fun `Given the defaults, Then a whole chunk stays strictly under the request body limit`() {
        // Given: the framework refuses the body before the use case sees it, so a chunk the client is
        // allowed to send and the server is allowed to receive cannot be the same number
        val maxChunkBytes = config.maxChunkBytes()
        val maxBodyBytes = maxBodySize.asLongValue()

        // Then: strictly, since equal values leave no room for the request line and its headers
        assertTrue(
            maxChunkBytes < maxBodyBytes,
            "imports.max_chunk_bytes ($maxChunkBytes) must be strictly under " +
                "quarkus.http.limits.max-body-size ($maxBodyBytes)",
        )
    }

    @Test
    fun `Given no configuration, Then the import defaults are the specified ones`() {
        // Given / Then: spec section 9's table, which the mapping is the single source of. The data
        // directory is read off the mapping rather than resolved: this module's test properties must
        // override it, since ImportDataDirectoryCheck probes it and would refuse every boot here.
        assertEquals(
            "/var/lib/pinry/imports",
            ImportsConfig::class.java.getMethod("dataDir").getAnnotation(WithDefault::class.java).value,
        )
        assertEquals(21_474_836_480, config.maxArchiveBytes())
        assertEquals(16_777_216, config.maxChunkBytes())
        assertEquals(200_000, config.maxEntries())
        assertEquals(16_777_216, config.maxMetadataBytes())
        assertEquals(1_048_576, config.maxLineBytes())
        assertEquals(1_073_741_824, config.minimumFreeBytes())
        assertEquals(Duration.ofHours(24), config.uploadGrace())
        assertEquals(Duration.ofHours(1), config.sweepInterval())
        assertEquals(Duration.ofHours(48), config.stagedFileMaxAge())
        assertEquals(500, config.sweepBatchSize())
        assertEquals(200, config.leaseRenewalLines())
        assertEquals(Duration.ofMinutes(10), config.retryFloor())
        assertEquals(500, config.reportDetailLimit())
    }

    @Test
    fun `Given the test properties, Then the data directory key is the one the mapping reads`() {
        // Given / Then: the assertion above reads an annotation, which restates the source rather than
        // exercising it. This one shows the key wired: an override under `imports.data_dir` arrives.
        assertEquals("build/test-import-data/shared", config.dataDir())
    }
}
