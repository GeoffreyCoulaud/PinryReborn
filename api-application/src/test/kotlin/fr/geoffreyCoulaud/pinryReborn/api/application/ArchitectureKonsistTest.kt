package fr.geoffreyCoulaud.pinryReborn.api.application

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.architecture.KoArchitectureCreator.assertArchitecture
import com.lemonappdev.konsist.api.architecture.Layer
import com.lemonappdev.konsist.api.verify.assertFalse
import com.lemonappdev.konsist.api.verify.assertNotEmpty
import com.lemonappdev.konsist.api.verify.assertTrue
import org.junit.jupiter.api.Test

/**
 * Konsist guardrails that fail the build when a module breaks the Clean / Hexagonal boundaries
 * documented in AGENTS.md, so the dependency rules are enforced in CI rather than by review
 * discipline. Konsist reads source files, so `scopeFromProduction` sees every module's main code
 * regardless of where this test runs; it is placed in `api-application` (the only module without
 * the Kover branch-coverage gate) to avoid a near-empty dedicated module.
 *
 * The two "scope is not empty" tests guard against a mistyped `moduleName` silently making an
 * import assertion pass on an empty file list.
 */
class ArchitectureKonsistTest {
    @Test
    fun `Given api-usecases production, Then its scope is not empty`() {
        Konsist.scopeFromProduction(moduleName = "api-usecases").files.assertNotEmpty()
    }

    @Test
    fun `Given api-domain production, Then its scope is not empty`() {
        Konsist.scopeFromProduction(moduleName = "api-domain").files.assertNotEmpty()
    }

    @Test
    fun `Given the module layers, Then dependencies follow the hexagonal DAG`() {
        Konsist.scopeFromProduction().assertArchitecture {
            val domain = Layer("Domain", "fr.geoffreyCoulaud.pinryReborn.api.domain..")
            val usecases = Layer("Usecases", "fr.geoffreyCoulaud.pinryReborn.api.usecases..")
            val persistence = Layer("Persistence", "fr.geoffreyCoulaud.pinryReborn.api.persistence..")
            val presentation = Layer("Presentation", "fr.geoffreyCoulaud.pinryReborn.api.presentation..")
            val worker = Layer("Worker", "fr.geoffreyCoulaud.pinryReborn.api.worker..")
            val system = Layer("System", "fr.geoffreyCoulaud.pinryReborn.api.system..")
            val storage = Layer("Storage", "fr.geoffreyCoulaud.pinryReborn.api.storage..")
            val imaging = Layer("Imaging", "fr.geoffreyCoulaud.pinryReborn.api.imaging..")
            val fetch = Layer("Fetch", "fr.geoffreyCoulaud.pinryReborn.api.fetch..")

            // api-application (composition root) and api-utilities are intentionally not modelled:
            // the composition root may depend on everything, and imports to an unmodelled layer are
            // ignored, so domain.dependsOnNothing() stays true even if domain uses api-utilities.
            domain.dependsOnNothing()
            usecases.dependsOn(domain)
            persistence.dependsOn(domain)
            presentation.dependsOn(usecases, domain)
            worker.dependsOn(usecases, domain)
            system.dependsOn(domain)
            storage.dependsOn(domain)
            imaging.dependsOn(domain)
            fetch.dependsOn(domain)
        }
    }

    @Test
    fun `Given api-usecases, Then it imports no persistence, transaction, web, or crypto library`() {
        val forbidden = listOf("jakarta.transaction", "io.ebean", "jakarta.ws.rs", "org.mindrot")
        Konsist
            .scopeFromProduction(moduleName = "api-usecases")
            .imports
            .assertFalse { imp -> forbidden.any { imp.name == it || imp.name.startsWith("$it.") } }
    }

    @Test
    fun `Given api-domain, Then it imports only its own package and pure value types`() {
        val allowedExternal =
            setOf(
                "java.time.Instant",
                "java.time.Duration",
                "java.util.UUID",
                // Byte-stream boundary type on the image ports; adapters perform the actual I/O.
                "java.io.InputStream",
            )
        Konsist
            .scopeFromProduction(moduleName = "api-domain")
            .imports
            .assertTrue { imp ->
                imp.name.startsWith("fr.geoffreyCoulaud.pinryReborn.api.domain.") || imp.name in allowedExternal
            }
    }
}
