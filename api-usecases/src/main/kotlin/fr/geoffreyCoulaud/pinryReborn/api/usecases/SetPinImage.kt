package fr.geoffreyCoulaud.pinryReborn.api.usecases

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Image
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.User
import fr.geoffreyCoulaud.pinryReborn.api.domain.images.ImageProbe
import fr.geoffreyCoulaud.pinryReborn.api.domain.images.ImageProbeException
import fr.geoffreyCoulaud.pinryReborn.api.domain.images.ImageStore
import fr.geoffreyCoulaud.pinryReborn.api.domain.images.ImageTooLargeException
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.ImageRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.PinRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.time.Clock
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.ImageInvalidError
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.ImagePermissionError
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.ImagePinDoesNotExistError
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.ImageTooLargeError
import jakarta.enterprise.context.ApplicationScoped
import java.io.InputStream
import java.util.UUID
import java.util.UUID.randomUUID

/**
 * Result of [SetPinImage.set]: the persisted canonical image, plus whether it replaced a
 * pre-existing image for the pin (used by the controller to pick 201 vs 200).
 */
data class SetPinImageResult(val image: Image, val replaced: Boolean)

@ApplicationScoped
class SetPinImage(
    private val pinRepository: PinRepositoryInterface,
    private val imageRepository: ImageRepositoryInterface,
    private val imageStore: ImageStore,
    private val imageProbe: ImageProbe,
    private val clock: Clock,
) {
    fun set(pinId: UUID, requester: User, upload: InputStream, maxBytes: Long, maxPixels: Long): SetPinImageResult {
        val pin = pinRepository.findPinById(pinId) ?: throw ImagePinDoesNotExistError()
        if (pin.author.id != requester.id) throw ImagePermissionError()

        val staged = try {
            imageStore.stage(upload, maxBytes)
        } catch (e: ImageTooLargeException) {
            throw ImageTooLargeError(e)
        }

        val probeResult = try {
            imageProbe.probe(staged, maxPixels)
        } catch (e: ImageProbeException) {
            imageStore.discard(staged)
            // Keep the client-facing message fixed (consistent with the other ImageError
            // siblings); the underlying probe detail is preserved via `cause` for logs, not
            // echoed to the API caller.
            throw ImageInvalidError("Invalid image", e)
        }

        val imageId = randomUUID()
        val storageKey = "originals/${requester.id}/$pinId/$imageId.${probeResult.format.extension}"
        val image = Image(
            id = imageId, pinId = pinId, mimeType = probeResult.format.mimeType,
            width = probeResult.width, height = probeResult.height, byteSize = staged.byteSize,
            contentHash = staged.contentHash, storageKey = storageKey, createdAt = clock.now(),
        )
        val existing = imageRepository.findByPinId(pinId)
        // Promote/save can fail for many reasons (I/O failure, DB constraint, disk pressure);
        // whatever the cause, the staged temp file must never be left behind. Catch broadly,
        // discard, and rethrow unchanged so the caller still sees the original failure.
        @Suppress("TooGenericExceptionCaught")
        try {
            imageStore.promote(staged, storageKey)
            val saved = imageRepository.save(image)
            existing?.let { imageStore.delete(it.storageKey) }
            return SetPinImageResult(image = saved, replaced = existing != null)
        } catch (e: RuntimeException) {
            imageStore.discard(staged)
            throw e
        }
    }
}
