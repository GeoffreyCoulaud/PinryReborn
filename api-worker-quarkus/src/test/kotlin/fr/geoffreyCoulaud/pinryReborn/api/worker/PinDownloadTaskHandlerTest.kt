package fr.geoffreyCoulaud.pinryReborn.api.worker

import fr.geoffreyCoulaud.pinryReborn.api.usecases.DownloadPinImage
import fr.geoffreyCoulaud.pinryReborn.api.usecases.tasks.PinDownloadTask
import fr.geoffreyCoulaud.pinryReborn.api.usecases.tasks.TaskContext
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.UUID.randomUUID

class PinDownloadTaskHandlerTest {
    private val downloadPinImage: DownloadPinImage = mockk(relaxed = true)
    private val handler = PinDownloadTaskHandler(downloadPinImage, maxBytes = 100, maxPixels = 200)

    @Test fun `Given the handler, Then its kind is pin download`() {
        assertEquals(PinDownloadTask.KIND, handler.kind)
    }

    @Test fun `Given a pinId payload, Then it delegates with the configured limits`() {
        val pinId = randomUUID()
        handler.handle(pinId.toString(), TaskContext(1, 5))
        verify { downloadPinImage.download(pinId, TaskContext(1, 5), 100, 200) }
    }
}
