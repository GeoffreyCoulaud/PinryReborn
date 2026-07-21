package fr.geoffreyCoulaud.pinryReborn.api.usecases

import fr.geoffreyCoulaud.pinryReborn.api.domain.images.ImageStore
import fr.geoffreyCoulaud.pinryReborn.api.domain.images.RenditionCache
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.BoardRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.ImageRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.PinRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.SessionTokenRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.TagRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.TransactionRunner
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.UserPasswordHashRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.UserRepositoryInterface
import jakarta.enterprise.context.ApplicationScoped
import java.util.UUID

/**
 * Async worker erasure for a tombstoned account: loads the user, deletes all its rows in FK order
 * inside one transaction, then does best-effort on-disk image cleanup after the commit.
 */
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
    private val transactionRunner: TransactionRunner,
) {
    fun deleteAccountData(userId: UUID) {
        val user = userRepository.findUserByIdIncludingDeleted(userId) ?: return
        val toEvict = mutableListOf<Pair<String, UUID>>() // storageKey to imageId
        transactionRunner.inTransaction {
            val pins = pinRepository.findAllPinsForUser(user) + pinRepository.findAllSoftDeletedPinsForUser(user)
            for (pin in pins) {
                imageRepository.findByPinId(pin.id)?.let { toEvict += it.storageKey to it.id }
                clearPinDownload.clear(pin.id)
                imageRepository.deleteByPinId(pin.id)
            }
            pinRepository.permanentlyDeleteAllPinsForUser(user)
            boardRepository.permanentlyDeleteAllBoardsForUser(user)
            tagRepository.deleteAllTagsForUser(user)
            sessionTokenRepository.deleteAllForUser(user.id)
            userPasswordRepository.deleteForUser(user)
            userRepository.permanentlyDeleteUser(user)
        }
        for ((storageKey, imageId) in toEvict) {
            imageStore.delete(storageKey)
            runCatching { renditionCache.evictImage(imageId) }
        }
    }
}
