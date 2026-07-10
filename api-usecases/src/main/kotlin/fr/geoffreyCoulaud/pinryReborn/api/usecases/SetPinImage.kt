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
    private val clearPinDownload: ClearPinDownload,
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
        // Promote/save can fail for many reasons: an I/O failure during promote (disk full,
        // permission denied -- FilesystemImageStore.promote throws java.io.IOException, a
        // checked exception, not a RuntimeException), a DB constraint violation on save, or any
        // other Throwable. Whatever the cause, both the staged temp file AND a
        // promoted-but-unsaved file at storageKey must never be left behind. Catch broadly,
        // clean up both paths, and rethrow unchanged so the caller still sees the original
        // failure.
        @Suppress("TooGenericExceptionCaught")
        val saved = try {
            imageStore.promote(staged, storageKey)
            imageRepository.save(image)
        } catch (e: Exception) {
            imageStore.discard(staged)
            imageStore.delete(storageKey)
            throw e
        }
        // Deleting the superseded file is best-effort only: the new row is already committed, so
        // a failure here (old file already gone, transient I/O error, ...) must not turn a
        // successful upload into a 500.
        existing?.let { old -> runCatching { imageStore.delete(old.storageKey) } }
        clearPinDownload.clear(pinId)
        return SetPinImageResult(image = saved, replaced = existing != null)
    }
}
