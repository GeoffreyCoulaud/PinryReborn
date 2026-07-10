package fr.geoffreyCoulaud.pinryReborn.api.usecases

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.ImageDownload
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.User
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.ImageDownloadRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.PinRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.TransactionRunner
import fr.geoffreyCoulaud.pinryReborn.api.domain.time.Clock
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.ImagePermissionError
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.ImagePinDoesNotExistError
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.ImageSourceUrlInvalidError
import fr.geoffreyCoulaud.pinryReborn.api.usecases.tasks.EnqueueTask
import fr.geoffreyCoulaud.pinryReborn.api.usecases.tasks.PinDownloadTask
import jakarta.enterprise.context.ApplicationScoped
import java.net.URI
import java.net.URISyntaxException
import java.util.UUID

@ApplicationScoped
class RequestPinImageDownload(
    private val pinRepository: PinRepositoryInterface,
    private val imageDownloadRepository: ImageDownloadRepositoryInterface,
    private val enqueueTask: EnqueueTask,
    private val transactionRunner: TransactionRunner,
    private val clock: Clock,
) {
    fun request(pinId: UUID, requester: User, sourceUrl: String): ImageDownload {
        val pin = pinRepository.findPinById(pinId) ?: throw ImagePinDoesNotExistError()
        if (pin.author.id != requester.id) throw ImagePermissionError()
        validate(sourceUrl)
        return transactionRunner.inTransaction {
            val now = clock.now()
            val task =
                enqueueTask.enqueue(
                    kind = PinDownloadTask.KIND,
                    payload = pinId.toString(),
                    maxAttempts = PinDownloadTask.MAX_ATTEMPTS,
                    dedupKey = "${PinDownloadTask.KIND}:$pinId",
                )
            imageDownloadRepository.upsertPending(pinId, sourceUrl, task.id, now)
        }
    }

    private fun validate(sourceUrl: String) {
        val scheme =
            try {
                URI(sourceUrl).scheme?.lowercase()
            } catch (e: URISyntaxException) {
                throw ImageSourceUrlInvalidError(e)
            }
        if (scheme != "http" && scheme != "https") throw ImageSourceUrlInvalidError()
    }
}
