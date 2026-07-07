package fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.models

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.declaration.KoClassDeclaration
import com.lemonappdev.konsist.api.verify.assertTrue
import jakarta.persistence.Entity
import jakarta.persistence.MappedSuperclass
import org.junit.jupiter.api.Test

/**
 * Guardrail for operator decision B1: the `models` package (+ `.bases`) is excluded from the
 * Kover branch-coverage gate because Ebean bytecode-enhancement injects untestable branches into
 * entity classes. This test keeps that exclusion safe by enforcing that every class in the
 * package stays a pure field-storage entity, so no hand-written branchy logic can hide there.
 *
 * Scoped to `scopeFromProduction`, not `scopeFromModule`: the latter also walks this very test's
 * source set, and this file itself resides in `..models..`, so it would match its own filter.
 */
class ModelsPackageArchTest {
    private val modelClasses: List<KoClassDeclaration> =
        Konsist
            .scopeFromProduction(moduleName = "api-persistence-sqlite")
            .classes()
            .filter { it.resideInPackage("..persistence.sqlite.models..") }

    @Test
    fun `Given the coverage-excluded models package, Then every class is a persistence entity`() {
        modelClasses.assertTrue(strict = true) {
            it.hasAnnotationOf(Entity::class) || it.hasAnnotationOf(MappedSuperclass::class)
        }
    }

    @Test
    fun `Given the coverage-excluded models package, Then no class declares functions`() {
        modelClasses.assertTrue(strict = true) { it.functions().isEmpty() }
    }

    @Test
    fun `Given the coverage-excluded models package, Then no property has a custom accessor`() {
        modelClasses.assertTrue(strict = true) {
            it.properties().all { property -> !property.hasGetter && !property.hasSetter }
        }
    }
}
