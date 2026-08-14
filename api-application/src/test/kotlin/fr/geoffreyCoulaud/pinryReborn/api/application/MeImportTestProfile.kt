package fr.geoffreyCoulaud.pinryReborn.api.application

import io.quarkus.test.junit.QuarkusTestProfile
import java.util.UUID

/**
 * Isolated, writable `imports.data_dir` and `images.data_dir` for the class run, mirroring
 * [MeExportTestProfile]: without it the tests would write to the production defaults
 * (`/var/lib/pinry/*`), which are not writable in CI, and successive local runs would collide.
 */
class MeImportTestProfile : QuarkusTestProfile {
    override fun getConfigOverrides(): Map<String, String> = mapOf(
        "imports.data_dir" to "build/test-import-data/${UUID.randomUUID()}",
        "images.data_dir" to "build/test-image-data/${UUID.randomUUID()}",
    )
}
