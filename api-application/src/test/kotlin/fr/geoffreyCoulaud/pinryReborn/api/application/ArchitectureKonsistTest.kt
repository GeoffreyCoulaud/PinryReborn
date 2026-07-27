package fr.geoffreyCoulaud.pinryReborn.api.application

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.architecture.KoArchitectureCreator.assertArchitecture
import com.lemonappdev.konsist.api.architecture.Layer
import com.lemonappdev.konsist.api.ext.list.withName
import com.lemonappdev.konsist.api.ext.list.withNameStartingWith
import com.lemonappdev.konsist.api.ext.list.withoutName
import com.lemonappdev.konsist.api.ext.list.withoutNameStartingWith
import com.lemonappdev.konsist.api.verify.assertEmpty
import com.lemonappdev.konsist.api.verify.assertNotEmpty
import org.junit.jupiter.api.Test

/**
 * Konsist guardrails that fail the build when a module breaks the Clean / Hexagonal boundaries
 * documented in docs/project.md, so the dependency rules are enforced in CI rather than by review
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
        // Filter the imports down to the offending ones, then assert nothing survives: when the rule
        // breaks, `assertEmpty` names every culprit import instead of a single opaque `false`.
        // Trailing dots pin each prefix to a package boundary so a sibling like `io.ebeanx` cannot
        // masquerade as `io.ebean`.
        Konsist
            .scopeFromProduction(moduleName = "api-usecases")
            .imports
            .withNameStartingWith(
                "jakarta.transaction.", // transaction
                "io.ebean.", // persistence
                "jakarta.ws.rs.", // web
                "org.mindrot.", // crypto
            )
            .assertEmpty()
    }

    @Test
    fun `Given api-domain and api-usecases, Then they read the wall clock only through the Clock port`() {
        // `java.time.Instant` is an allowed import (entities carry instants), so the import rules above
        // cannot catch a direct `Instant.now()`. It matters twice over: a hidden clock makes a use case
        // untestable, and it bypasses SystemClock, whose truncation keeps a stamped instant equal to
        // itself across a save-then-read. Matched on file text because the offence is a call, not an
        // import.
        val wallClockReads =
            listOf("Instant.now(", "LocalDate.now(", "LocalDateTime.now(", "System.currentTimeMillis(")
        listOf("api-domain", "api-usecases").forEach { module ->
            Konsist
                .scopeFromProduction(moduleName = module)
                .files
                .filter { file -> wallClockReads.any { file.text.contains(it) } }
                .assertEmpty()
        }
    }

    @Test
    fun `Given api-domain, Then it imports only its own package and pure value types`() {
        // Drop the allowed imports (own package + pure value types); anything left is a violation,
        // and `assertEmpty` reports each one by name.
        Konsist
            .scopeFromProduction(moduleName = "api-domain")
            .imports
            .withoutNameStartingWith("fr.geoffreyCoulaud.pinryReborn.api.domain.")
            .withoutName(
                "java.time.Instant",
                "java.time.Duration",
                "java.util.UUID",
                // Byte-stream boundary type on the image ports; adapters perform the actual I/O.
                "java.io.InputStream",
            )
            .assertEmpty()
    }

    @Test
    fun `Given production sources, Then none imports the Identifier string qualifier`() {
        // The inject-by-type convention forbids @Identifier string qualifiers in production: a
        // dependency is a dedicated type, and the container provides the instance. `assertEmpty`
        // names every file that still imports the qualifier if the convention regresses.
        Konsist
            .scopeFromProduction()
            .imports
            .withName("io.smallrye.common.annotation.Identifier")
            .assertEmpty()
    }
}
