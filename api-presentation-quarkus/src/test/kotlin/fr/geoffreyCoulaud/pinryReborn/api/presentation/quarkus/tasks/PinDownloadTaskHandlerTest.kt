package fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.tasks

import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.config.ImagesConfig
import fr.geoffreyCoulaud.pinryReborn.api.usecases.DownloadPinImage
import fr.geoffreyCoulaud.pinryReborn.api.usecases.tasks.PinDownloadTask
import fr.geoffreyCoulaud.pinryReborn.api.usecases.tasks.TaskContext
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.UUID.randomUUID

class PinDownloadTaskHandlerTest {
    private val downloadPinImage: DownloadPinImage = mockk(relaxed = true)
    private val imagesConfig: ImagesConfig = mockk()
    private val handler = PinDownloadTaskHandler(downloadPinImage, imagesConfig)

    @Test fun `Given the handler, Then its kind is pin download`() {
        assertEquals(PinDownloadTask.KIND, handler.kind)
    }

    @Test fun `Given a pinId payload, Then it delegates with the configured limits`() {
        val pinId = randomUUID()
        every { imagesConfig.maxFileBytes() } returns 100
        every { imagesConfig.maxPixels() } returns 200
        handler.handle(pinId.toString(), TaskContext(1, 5))
        verify { downloadPinImage.download(pinId, TaskContext(1, 5), 100, 200) }
    }
}
