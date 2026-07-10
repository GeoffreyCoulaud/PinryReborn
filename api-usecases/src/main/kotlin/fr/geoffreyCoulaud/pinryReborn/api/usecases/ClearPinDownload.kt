package fr.geoffreyCoulaud.pinryReborn.api.usecases

import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.ImageDownloadRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.usecases.tasks.CancelTask
import jakarta.enterprise.context.ApplicationScoped
import java.util.UUID

/**
 * Tears down a pin's mode-B download when a direct upload or an image delete supersedes it:
 * cancel the (possibly in-flight) task best-effort, then drop the download row. A still-running
 * fetch is neutralised by DownloadPinImage's CAS-on-PENDING swap, which finds no PENDING row.
 */
@ApplicationScoped
class ClearPinDownload(
    private val imageDownloadRepository: ImageDownloadRepositoryInterface,
    private val cancelTask: CancelTask,
) {
    fun clear(pinId: UUID) {
        val download = imageDownloadRepository.findByPinId(pinId) ?: return
        runCatching { cancelTask.cancel(download.taskId) }
        imageDownloadRepository.deleteByPinId(pinId)
    }
}
