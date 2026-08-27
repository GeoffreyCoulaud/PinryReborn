package fr.geoffreyCoulaud.pinryReborn.api.usecases

import fr.geoffreyCoulaud.pinryReborn.api.domain.exports.ExportArchiveStore
import fr.geoffreyCoulaud.pinryReborn.api.domain.images.ImageStore
import fr.geoffreyCoulaud.pinryReborn.api.domain.images.RenditionCache
import fr.geoffreyCoulaud.pinryReborn.api.domain.imports.ImportArchiveStore
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.BoardRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.ImageRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.PinRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.SessionTokenRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.TagRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.TransactionRunner
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.UserDataExportRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.UserDataImportIssueRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.UserDataImportRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.UserPasswordHashRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.UserRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exports.ExportArchiveKey
import fr.geoffreyCoulaud.pinryReborn.api.usecases.imports.ImportArchiveKey
import jakarta.enterprise.context.ApplicationScoped
import java.util.UUID

/**
 * Async worker erasure for a tombstoned account: loads the user, deletes all its rows in FK order
 * inside one transaction, then does best-effort on-disk cleanup (image bytes and export archives)
 * after the commit.
 */
@Suppress("LongParameterList")
@ApplicationScoped
class AccountDeletionCleaner(
    private val userRepository: UserRepositoryInterface,
    private val pinRepository: PinRepositoryInterface,
    private val boardRepository: BoardRepositoryInterface,
    private val tagRepository: TagRepositoryInterface,
    private val imageRepository: ImageRepositoryInterface,
    private val sessionTokenRepository: SessionTokenRepositoryInterface,
    private val userPasswordRepository: UserPasswordHashRepositoryInterface,
    private val clearPinDownload: ClearPinDownload,
    private val imageStore: ImageStore,
    private val renditionCache: RenditionCache,
    private val userDataExportRepository: UserDataExportRepositoryInterface,
    private val exportArchiveStore: ExportArchiveStore,
    private val userDataImportRepository: UserDataImportRepositoryInterface,
    private val userDataImportIssueRepository: UserDataImportIssueRepositoryInterface,
    private val importArchiveStore: ImportArchiveStore,
    private val transactionRunner: TransactionRunner,
) {
    fun deleteAccountData(userId: UUID) {
        val user = userRepository.findUserByIdIncludingDeleted(userId) ?: return
        val toEvict = mutableListOf<Pair<String, UUID>>() // storageKey to imageId
        // Collected before the transaction: the rows are deleted inside it, but the archive keys are
        // derived from the ids (not read from the rows), so they must be captured while the rows exist.
        val exportIds = userDataExportRepository.findAllExportIdsForUser(user.id)
        val importIds = userDataImportRepository.findAllImportIdsForUser(user.id)
        transactionRunner.inTransaction {
            val pinIds = pinRepository.findAllPinIdsForUser(user)
            for (pinId in pinIds) {
                imageRepository.findByPinId(pinId)?.let { toEvict += it.storageKey to it.id }
                clearPinDownload.clear(pinId)
                imageRepository.deleteByPinId(pinId)
            }
            pinRepository.permanentlyDeleteAllPinsForUser(user)
            boardRepository.permanentlyDeleteAllBoardsForUser(user)
            tagRepository.deleteAllTagsForUser(user)
            sessionTokenRepository.deleteAllForUser(user.id)
            userPasswordRepository.deleteForUser(user)
            userDataExportRepository.deleteAllForUser(user.id)
            // The issues before the rows they hang off, both before the user row.
            userDataImportIssueRepository.deleteAllForUser(user.id)
            userDataImportRepository.deleteAllForUser(user.id)
            userRepository.permanentlyDeleteUser(user)
        }
        for ((storageKey, imageId) in toEvict) {
            imageStore.deleteQuietly(storageKey)
            renditionCache.evictImageQuietly(imageId)
        }
        // Derive each archive key from its id, not from the (now-deleted) row: this reclaims an
        // archive promoted by a builder that died before writing its storageKey column.
        for (exportId in exportIds) {
            exportArchiveStore.deleteQuietly(
                ExportArchiveKey.forExport(exportId, exportArchiveStore.format.fileExtension),
            )
        }
        // Both sides of the lifecycle: an archive already promoted, and an upload still under tmp/ that
        // nothing else would reclaim before the staged-file age caught it.
        for (importId in importIds) {
            importArchiveStore.deleteQuietly(ImportArchiveKey.forImport(importId))
            importArchiveStore.discardPartialUploadQuietly(importId)
        }
    }
}
