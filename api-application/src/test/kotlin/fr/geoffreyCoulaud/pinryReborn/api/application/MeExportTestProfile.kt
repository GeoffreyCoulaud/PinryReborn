package fr.geoffreyCoulaud.pinryReborn.api.application

import io.quarkus.test.junit.QuarkusTestProfile
import java.util.UUID

/**
 * Isolated, writable `exports.data_dir` for the class run (a fresh UUID-suffixed directory under
 * the module's `build/`, mirroring [MeDeleteCompletionTestProfile]), `exports.minimum_interval`
 * pinned to zero so a second request in the same test is never refused by the cooldown (spec
 * `docs/specs/2026-07-22-user-data-export.md` §9), and a writable `images.data_dir` for the tests
 * that upload a real image fixture through the mode-A pin image endpoint.
 *
 * Shared by [MeExportIntegrationTest] and [MeExportCompletionIntegrationTest]: without it, both
 * would write to the production defaults (`/var/lib/pinry/exports`, `/var/lib/pinry/images`), which
 * are not writable in CI, and successive local runs would collide on the same directories.
 */
class MeExportTestProfile : QuarkusTestProfile {
    override fun getConfigOverrides(): Map<String, String> = mapOf(
        "exports.data_dir" to "build/test-export-data/${UUID.randomUUID()}",
        "exports.minimum_interval" to "PT0S",
        "images.data_dir" to "build/test-image-data/${UUID.randomUUID()}",
    )
}
