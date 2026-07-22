package fr.geoffreyCoulaud.pinryReborn.api.worker

import fr.geoffreyCoulaud.pinryReborn.api.usecases.exports.UserDataExportBuilder
import fr.geoffreyCoulaud.pinryReborn.api.usecases.tasks.TaskContext
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.util.UUID

class UserDataExportTaskHandlerTest {
    private val builder: UserDataExportBuilder = mockk(relaxed = true)
    private val handler = UserDataExportTaskHandler(builder)

    @Test
    fun `Given the final attempt, Then the builder is told it is the last one`() {
        // Given
        val exportId = UUID.randomUUID()

        // When
        handler.handle(exportId.toString(), TaskContext(attempt = 3, maxAttempts = 3))

        // Then
        verify { builder.build(exportId, isLastAttempt = true, any()) }
    }

    @Test
    fun `Given an earlier attempt, Then the builder is told it is not the last one`() {
        // Given
        val exportId = UUID.randomUUID()

        // When
        handler.handle(exportId.toString(), TaskContext(attempt = 1, maxAttempts = 3))

        // Then
        verify { builder.build(exportId, isLastAttempt = false, any()) }
    }
}
