package fr.geoffreyCoulaud.pinryReborn.api.application

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.architecture.KoArchitectureCreator.assertArchitecture
import com.lemonappdev.konsist.api.architecture.Layer
import com.lemonappdev.konsist.api.ext.list.withImport
import com.lemonappdev.konsist.api.ext.list.withName
import com.lemonappdev.konsist.api.ext.list.withNameStartingWith
import com.lemonappdev.konsist.api.ext.list.withPackage
import com.lemonappdev.konsist.api.ext.list.withParent
import com.lemonappdev.konsist.api.ext.list.withParentInterfaceOf
import com.lemonappdev.konsist.api.ext.list.withPropertyNamed
import com.lemonappdev.konsist.api.ext.list.withoutName
import com.lemonappdev.konsist.api.ext.list.withoutNameStartingWith
import com.lemonappdev.konsist.api.ext.list.withoutParentInterfaceOf
import com.lemonappdev.konsist.api.ext.list.withoutPath
import com.lemonappdev.konsist.api.verify.assertEmpty
import com.lemonappdev.konsist.api.verify.assertNotEmpty
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.models.SoftDeletableModel
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
    /**
     * The persistence models that declared themselves recyclable, which is what the two assertions
     * below derive their reach from instead of naming types.
     */
    private val recyclableModels =
        Konsist
            .scopeFromProduction(moduleName = "api-persistence-sqlite")
            .classes()
            .withParentInterfaceOf(SoftDeletableModel::class)

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

    @Test
    fun `Given production sources, Then none imports the Ebean generated-timestamp annotations`() {
        // The domain owns every business instant: a column Ebean auto-stamps on insert or update is
        // not a source of truth, so @WhenCreated and @WhenModified are barred from production.
        // `assertEmpty` names every importing file if the ban regresses.
        Konsist
            .scopeFromProduction()
            .imports
            .withName(
                "io.ebean.annotation.WhenCreated",
                "io.ebean.annotation.WhenModified",
            )
            .assertEmpty()
    }

    @Test
    fun `Given api-persistence-sqlite production, Then some model declares itself recyclable`() {
        recyclableModels.assertNotEmpty()
    }

    @Test
    fun `Given the persistence models, Then each one carrying a recycling instant is recyclable`() {
        // Opting out of the marker would be the way around every rule that reads it, so a model
        // that carries the instant has to declare itself. Scoped to the persistence models on
        // purpose: the domain entities carry the same property and answer to no query bean.
        Konsist
            .scopeFromProduction(moduleName = "api-persistence-sqlite")
            .classes()
            .withPackage("..persistence.sqlite.models..")
            .withPropertyNamed("softDeletedAt")
            .withoutParentInterfaceOf(SoftDeletableModel::class)
            .assertEmpty()
    }

    @Test
    fun `Given production sources, Then none outside queries and pagination names a recyclable query bean`() {
        // Ebean's generator names a model's query bean after the model, so the barred imports are
        // computed from what declared itself recyclable and no type name is written here. Two
        // packages are exempt: `queries` builds those queries, and `pagination` carries the type in
        // a supertype and in every signature without ever constructing one, so satisfying the
        // assertion would mean moving that file to escape it.
        val queryBeanNames = recyclableModels.map { "Q${it.name}" }
        Konsist
            .scopeFromProduction()
            .files
            .withoutPath("..persistence.sqlite.queries..", "..persistence.sqlite.pagination..")
            .withImport { it.name.substringAfterLast(".") in queryBeanNames }
            .assertEmpty()
    }

    @Test
    fun `Given production sources, Then io_ebean Database is confined to its sanctioned homes`() {
        // `io.ebean.Database` is confined to three sanctioned homes (ADR 0008); the `..` wildcard
        // prefix on the paths is deliberate, since Konsist end-matches a bare name and misses `.kt`.
        Konsist
            .scopeFromProduction()
            .files
            .withImport { it.name == "io.ebean.Database" }
            .withoutPath("..EbeanDatabaseProducer.kt", "..EbeanPersistor.kt", "..EbeanTransactionControl.kt")
            .assertEmpty()
    }

    @Test
    fun `Given production sources, Then no class extends an Ebean bean finder`() {
        // Bars the active-record shape (`BeanRepository` / `BeanFinder`) this project moved off
        // (ADR 0008); `withParentClassOf` skips external supertypes, so match the bare name (`0ea264d`).
        val ebeanBeanFinderSupertypes = setOf("BeanRepository", "BeanFinder")
        Konsist
            .scopeFromProduction()
            .classes()
            .withParent { parent ->
                parent.name.substringBefore("<").substringAfterLast(".").trim() in ebeanBeanFinderSupertypes
            }
            .assertEmpty()
    }

    @Test
    fun `Given production sources, Then nothing imports an Ebean static facade`() {
        // The static facades `io.ebean.DB` / `io.ebean.Ebean` run on the default server, so the `Database`
        // confinement does not reach them; `DatabaseStaticFacadeCall` closes the call form (ADR 0008).
        Konsist
            .scopeFromProduction()
            .files
            .withImport { it.name in setOf("io.ebean.DB", "io.ebean.Ebean") }
            .assertEmpty()
    }
}
