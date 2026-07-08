package fr.geoffreyCoulaud.pinryReborn.api.usecases.tasks

import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class TaskHandlerRegistryTest {
    private fun handler(k: String) = object : TaskHandler {
        override val kind = k
        override fun handle(payload: String) = Unit
    }

    @Test
    fun `Given a registered kind, Then handlerFor returns the handler`() {
        // Given
        val h = handler("a")
        val registry = TaskHandlerRegistry(listOf(h, handler("b")))
        // When / Then
        assertSame(h, registry.handlerFor("a"))
    }

    @Test
    fun `Given an unknown kind, Then handlerFor returns null`() {
        // Given
        val registry = TaskHandlerRegistry(listOf(handler("a")))
        // When / Then
        assertNull(registry.handlerFor("z"))
    }
}
