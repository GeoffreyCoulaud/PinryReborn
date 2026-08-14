package fr.geoffreyCoulaud.pinryReborn.api.application

import io.quarkus.test.junit.QuarkusTestProfile
import java.util.UUID

/** Writable, per-run data directories, mirroring [MeExportTestProfile]: the production defaults are
 * not writable in CI and successive local runs would collide on them. `exports.data_dir` is here
 * because the round trip pours a real export into a real import. */
class MeImportTestProfile : QuarkusTestProfile {
    override fun getConfigOverrides(): Map<String, String> = mapOf(
        "imports.data_dir" to "build/test-import-data/${UUID.randomUUID()}",
        "images.data_dir" to "build/test-image-data/${UUID.randomUUID()}",
        "exports.data_dir" to "build/test-export-data/${UUID.randomUUID()}",
    )
}
