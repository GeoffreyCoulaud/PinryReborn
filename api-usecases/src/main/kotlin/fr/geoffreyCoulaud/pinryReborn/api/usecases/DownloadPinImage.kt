package fr.geoffreyCoulaud.pinryReborn.api.usecases

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Image
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Pin
import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.DownloadReason
import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.DownloadStatus
import fr.geoffreyCoulaud.pinryReborn.api.domain.images.FetchAccessDeniedException
import fr.geoffreyCoulaud.pinryReborn.api.domain.images.FetchException
import fr.geoffreyCoulaud.pinryReborn.api.domain.images.FetchFailedException
import fr.geoffreyCoulaud.pinryReborn.api.domain.images.FetchNotFoundException
import fr.geoffreyCoulaud.pinryReborn.api.domain.images.FetchTooLargeException
import fr.geoffreyCoulaud.pinryReborn.api.domain.images.FetchUnreachableException
import fr.geoffreyCoulaud.pinryReborn.api.domain.images.ImageFetcher
import fr.geoffreyCoulaud.pinryReborn.api.domain.images.ImageProbe
import fr.geoffreyCoulaud.pinryReborn.api.domain.images.ImageProbeException
import fr.geoffreyCoulaud.pinryReborn.api.domain.images.ImageStore
import fr.geoffreyCoulaud.pinryReborn.api.domain.images.ImageTooLargeException
import fr.geoffreyCoulaud.pinryReborn.api.domain.images.ImageTooManyPixelsException
import fr.geoffreyCoulaud.pinryReborn.api.domain.images.ProbeResult
import fr.geoffreyCoulaud.pinryReborn.api.domain.images.StagedFile
import fr.geoffreyCoulaud.pinryReborn.api.domain.images.TooManyRedirectsException
import fr.geoffreyCoulaud.pinryReborn.api.domain.images.UndecodableImageException
import fr.geoffreyCoulaud.pinryReborn.api.domain.images.UnsupportedImageFormatException
import fr.geoffreyCoulaud.pinryReborn.api.domain.images.UrlNotAllowedException
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.ImageDownloadRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.ImageRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.PinRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.TransactionRunner
import fr.geoffreyCoulaud.pinryReborn.api.domain.time.Clock
import fr.geoffreyCoulaud.pinryReborn.api.usecases.tasks.TaskContext
import fr.geoffreyCoulaud.pinryReborn.api.usecases.tasks.exceptions.PermanentTaskException
import jakarta.enterprise.context.ApplicationScoped
import java.util.UUID
import java.util.UUID.randomUUID

@ApplicationScoped
class DownloadPinImage(
    private val pinRepository: PinRepositoryInterface,
    private val imageRepository: ImageRepositoryInterface,
    private val imageDownloadRepository: ImageDownloadRepositoryInterface,
    private val imageStore: ImageStore,
    private val imageProbe: ImageProbe,
    private val imageFetcher: ImageFetcher,
    private val transactionRunner: TransactionRunner,
    private val clock: Clock,
) {
    fun download(pinId: UUID, context: TaskContext, maxBytes: Long, maxPixels: Long) {
        val downloadRow = imageDownloadRepository.findByPinId(pinId)
        if (downloadRow == null || downloadRow.status != DownloadStatus.PENDING) return
        val pin = pinRepository.findPinById(pinId) ?: return

        val staged = stageFromSource(pinId, downloadRow.sourceUrl, maxBytes, context)
        val probeResult = probeStaged(pinId, staged, maxPixels, context)
        val image = buildImage(pin, pinId, staged, probeResult)
        promoteAndSwap(pinId, staged, image, context)
    }

    @Suppress("TooGenericExceptionCaught")
    private fun stageFromSource(pinId: UUID, sourceUrl: String, maxBytes: Long, context: TaskContext): StagedFile =
        try {
            imageFetcher.openStream(sourceUrl).use { imageStore.stage(it, maxBytes) }
        } catch (e: FetchException) {
            val reason = mapFetch(e)
            if (reason == DownloadReason.UNREACHABLE) {
                failRetryable(pinId, reason, context, e)
            } else {
                failPermanent(pinId, reason)
            }
        } catch (ignored: ImageTooLargeException) {
            failPermanent(pinId, DownloadReason.TOO_LARGE)
        } catch (e: Exception) {
            // A failure while streaming the fetched body (connection reset, read timeout, I/O error)
            // is a transient reachability problem. Route it through the failure policy as UNREACHABLE
            // so the download row is updated and an exhausted retry becomes terminal FAILED rather than
            // leaving the row stuck PENDING.
            failRetryable(pinId, DownloadReason.UNREACHABLE, context, e)
        }

    @Suppress("TooGenericExceptionCaught")
    private fun probeStaged(pinId: UUID, staged: StagedFile, maxPixels: Long, context: TaskContext): ProbeResult =
        try {
            imageProbe.probe(staged, maxPixels)
        } catch (e: ImageProbeException) {
            imageStore.discard(staged)
            failPermanent(pinId, mapProbe(e))
        } catch (e: Exception) {
            // A probe failure outside the declared ImageProbeException contract (e.g. a native/FFM
            // error) must still route through the failure policy; otherwise the download row is
            // left stuck PENDING and the staged temp file leaks. Treat it as a transient internal
            // error so an exhausted retry becomes terminal FAILED instead of DEAD-with-PENDING-row.
            imageStore.discard(staged)
            failRetryable(pinId, DownloadReason.INTERNAL_ERROR, context, e)
        }

    @Suppress("TooGenericExceptionCaught")
    private fun promoteAndSwap(pinId: UUID, staged: StagedFile, image: Image, context: TaskContext) {
        val superseded = imageRepository.findByPinId(pinId)
        try {
            imageStore.promote(staged, image.storageKey)
            val swapped =
                transactionRunner.inTransaction {
                    if (imageDownloadRepository.deleteIfPending(pinId) > 0) {
                        imageRepository.save(image)
                        true
                    } else {
                        false
                    }
                }
            if (swapped) {
                // Best-effort delete of the superseded file on a successful mode-B replace (spec
                // section 8 step 7), mirroring the mode-A path: the new row is committed, so a
                // failure here must not fail the task. Only after a real swap; a no-op swap kept
                // the old image, which must not be touched.
                superseded?.let { old -> runCatching { imageStore.delete(old.storageKey) } }
            } else {
                imageStore.delete(image.storageKey)
            }
        } catch (e: Exception) {
            imageStore.discard(staged)
            imageStore.delete(image.storageKey)
            failRetryable(pinId, DownloadReason.INTERNAL_ERROR, context, e)
        }
    }

    private fun buildImage(pin: Pin, pinId: UUID, staged: StagedFile, probe: ProbeResult): Image {
        val imageId = randomUUID()
        val storageKey = "originals/${pin.author.id}/$pinId/$imageId.${probe.format.extension}"
        return Image(
            id = imageId, pinId = pinId, mimeType = probe.format.mimeType, width = probe.width,
            height = probe.height, animated = probe.animated, byteSize = staged.byteSize,
            contentHash = staged.contentHash, storageKey = storageKey, createdAt = clock.now(),
        )
    }

    private fun mapFetch(e: FetchException): DownloadReason =
        when (e) {
            is UrlNotAllowedException -> DownloadReason.URL_NOT_ALLOWED
            is FetchAccessDeniedException -> DownloadReason.ACCESS_DENIED
            is FetchNotFoundException -> DownloadReason.NOT_FOUND
            is FetchTooLargeException -> DownloadReason.TOO_LARGE
            is TooManyRedirectsException -> DownloadReason.FETCH_FAILED
            is FetchFailedException -> DownloadReason.FETCH_FAILED
            is FetchUnreachableException -> DownloadReason.UNREACHABLE
        }

    private fun mapProbe(e: ImageProbeException): DownloadReason =
        when (e) {
            is ImageTooManyPixelsException -> DownloadReason.TOO_MANY_PIXELS
            is UnsupportedImageFormatException -> DownloadReason.INVALID_IMAGE
            is UndecodableImageException -> DownloadReason.INVALID_IMAGE
        }

    private fun failPermanent(pinId: UUID, reason: DownloadReason): Nothing {
        imageDownloadRepository.markFailed(pinId, reason, clock.now())
        throw PermanentTaskException(reason.name)
    }

    private fun failRetryable(pinId: UUID, reason: DownloadReason, context: TaskContext, cause: Exception): Nothing {
        if (context.attempt >= context.maxAttempts) {
            imageDownloadRepository.markFailed(pinId, reason, clock.now())
            throw PermanentTaskException(reason.name)
        }
        imageDownloadRepository.recordLastError(pinId, cause.message ?: reason.name, clock.now())
        throw cause
    }
}
