package fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.models

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.declaration.KoClassDeclaration
import com.lemonappdev.konsist.api.ext.list.withFunctions
import com.lemonappdev.konsist.api.ext.list.withProperty
import com.lemonappdev.konsist.api.verify.assertEmpty
import com.lemonappdev.konsist.api.verify.assertNotEmpty
import jakarta.persistence.Entity
import jakarta.persistence.MappedSuperclass
import org.junit.jupiter.api.Test

/**
 * Guardrail for operator decision B1: the `models` package (+ `.bases`) is excluded from the
 * Kover branch-coverage gate because Ebean bytecode-enhancement injects untestable branches into
 * entity classes. This test keeps that exclusion safe by enforcing that every class in the
 * package stays a pure field-storage entity, so no hand-written branchy logic can hide there.
 *
 * Each rule reads as "the set of offending classes is empty". The separate non-empty check guards
 * against a mis-scoped filter silently passing every `assertEmpty` on an empty list.
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
    fun `Given the models package, Then it holds classes (the scope is not mis-configured)`() {
        modelClasses.assertNotEmpty()
    }

    @Test
    fun `Given the coverage-excluded models package, Then no class is a non-entity`() {
        modelClasses
            .filterNot { it.hasAnnotationOf(Entity::class) || it.hasAnnotationOf(MappedSuperclass::class) }
            .assertEmpty()
    }

    @Test
    fun `Given the coverage-excluded models package, Then no class declares functions`() {
        modelClasses
            .withFunctions()
            .assertEmpty()
    }

    @Test
    fun `Given the coverage-excluded models package, Then no class has a custom property accessor`() {
        modelClasses
            .withProperty { it.hasGetter || it.hasSetter }
            .assertEmpty()
    }
}
