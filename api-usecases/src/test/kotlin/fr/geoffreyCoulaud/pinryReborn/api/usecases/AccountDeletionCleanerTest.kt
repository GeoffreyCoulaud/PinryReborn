package fr.geoffreyCoulaud.pinryReborn.api.usecases

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Image
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Pin
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.User
import fr.geoffreyCoulaud.pinryReborn.api.domain.exports.ArchiveFormat
import fr.geoffreyCoulaud.pinryReborn.api.domain.exports.ExportArchiveStore
import fr.geoffreyCoulaud.pinryReborn.api.domain.images.ImageStore
import fr.geoffreyCoulaud.pinryReborn.api.domain.images.RenditionCache
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.BoardRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.ImageRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.PinRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.SessionTokenRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.TagRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.TransactionRunner
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.UserDataExportRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.UserPasswordHashRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.UserRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.utilities.BaseTest
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import java.util.UUID.randomUUID

class AccountDeletionCleanerTest : BaseTest() {
    private val users = mockk<UserRepositoryInterface>(relaxed = true)
    private val pins = mockk<PinRepositoryInterface>(relaxed = true)
    private val boards = mockk<BoardRepositoryInterface>(relaxed = true)
    private val tags = mockk<TagRepositoryInterface>(relaxed = true)
    private val images = mockk<ImageRepositoryInterface>(relaxed = true)
    private val sessions = mockk<SessionTokenRepositoryInterface>(relaxed = true)
    private val passwords = mockk<UserPasswordHashRepositoryInterface>(relaxed = true)
    private val clearDownload = mockk<ClearPinDownload>(relaxed = true)
    private val imageStore = mockk<ImageStore>(relaxed = true)
    private val renditions = mockk<RenditionCache>(relaxed = true)
    private val exports = mockk<UserDataExportRepositoryInterface>(relaxed = true)
    private val exportArchiveStore = mockk<ExportArchiveStore>(relaxed = true)
    private val tx = mockk<TransactionRunner>()
    private val cleaner = AccountDeletionCleaner(
        users, pins, boards, tags, images, sessions, passwords, clearDownload, imageStore, renditions,
        exports, exportArchiveStore, tx,
    )

    private val userId = randomUUID()
    private val user = User(id = userId, name = "u", softDeleted = true, createdAt = Instant.now())

    private fun buildPin() = Pin(
        id = randomUUID(), author = user, sourceContextUrl = "https://ctx",
        sourceMediaUrl = null, description = "desc", tags = emptyList(), boards = emptyList(),
        createdAt = Instant.now(),
        updatedAt = Instant.now(),
    )

    private fun buildImage(pinId: UUID) = Image(
        id = randomUUID(), pinId = pinId, mimeType = "image/png", width = 1, height = 1,
        animated = false, byteSize = 1, contentHash = "h", storageKey = "originals/x/$pinId/i.png",
        createdAt = Instant.parse("2026-07-10T00:00:00Z"),
    )

    @Test
    fun `Given a tombstoned user with a pin+image, Then everything is erased in order and disk cleaned`() {
        // Given
        every { tx.inTransaction(any<() -> Any?>()) } answers { (firstArg<() -> Any?>())() }
        every { users.findUserByIdIncludingDeleted(userId) } returns user
        val pin = buildPin()
        every { pins.findAllPinIdsForUser(user) } returns listOf(pin.id)
        val image = buildImage(pin.id)
        every { images.findByPinId(pin.id) } returns image

        // When
        cleaner.deleteAccountData(userId)

        // Then
        verifyOrder {
            clearDownload.clear(pin.id)
            images.deleteByPinId(pin.id)
            pins.permanentlyDeleteAllPinsForUser(user)
            boards.permanentlyDeleteAllBoardsForUser(user)
            tags.deleteAllTagsForUser(user)
            sessions.deleteAllForUser(userId)
            passwords.deleteForUser(user)
            users.permanentlyDeleteUser(user)
        }
        verify { imageStore.delete(image.storageKey) }
        verify { renditions.evictImage(image.id) }
    }

    @Test
    fun `Given an already-deleted user, Then it is a no-op`() {
        // Given
        every { users.findUserByIdIncludingDeleted(userId) } returns null

        // When
        cleaner.deleteAccountData(userId)

        // Then
        verify(exactly = 0) { users.permanentlyDeleteUser(any()) }
    }

    @Test
    fun `Given a rendition eviction failure, Then it is swallowed`() {
        // Given
        every { tx.inTransaction(any<() -> Any?>()) } answers { (firstArg<() -> Any?>())() }
        every { users.findUserByIdIncludingDeleted(userId) } returns user
        val pin = buildPin()
        val image = buildImage(pin.id)
        every { pins.findAllPinIdsForUser(user) } returns listOf(pin.id)
        every { images.findByPinId(pin.id) } returns image
        every { renditions.evictImage(image.id) } throws RuntimeException("disk")

        // When / Then
        assertDoesNotThrow { cleaner.deleteAccountData(userId) } // committed DB, disk best-effort
    }

    @Test
    fun `Given a user with an export, Then its rows are deleted and its derived bytes erased after the commit`() {
        // Given
        every { tx.inTransaction(any<() -> Any?>()) } answers { (firstArg<() -> Any?>())() }
        every { users.findUserByIdIncludingDeleted(userId) } returns user
        every { pins.findAllPinIdsForUser(user) } returns emptyList()
        val exportId = randomUUID()
        every { exports.findAllExportIdsForUser(userId) } returns listOf(exportId)
        every { exportArchiveStore.format } returns ArchiveFormat(mediaType = "application/zip", fileExtension = "zip")

        // When
        cleaner.deleteAccountData(userId)

        // Then
        verifyOrder {
            exports.deleteAllForUser(userId)
            users.permanentlyDeleteUser(user)
        }
        // The key is DERIVED from the id, not read from the row, so a build that died before writing
        // its row (leaving a promoted file with no storageKey column) is still reclaimed.
        verify { exportArchiveStore.delete("exports/$exportId.zip") }
    }

    @Test
    fun `Given a user with no export, Then no archive delete is attempted`() {
        // Given
        every { tx.inTransaction(any<() -> Any?>()) } answers { (firstArg<() -> Any?>())() }
        every { users.findUserByIdIncludingDeleted(userId) } returns user
        every { pins.findAllPinIdsForUser(user) } returns emptyList()
        every { exports.findAllExportIdsForUser(userId) } returns emptyList()

        // When
        cleaner.deleteAccountData(userId)

        // Then
        verify { exports.deleteAllForUser(userId) }
        verify(exactly = 0) { exportArchiveStore.delete(any()) }
    }

    @Test
    fun `Given a pin with no image, Then it is cleared and deleted but nothing is evicted`() {
        // Given
        every { tx.inTransaction(any<() -> Any?>()) } answers { (firstArg<() -> Any?>())() }
        every { users.findUserByIdIncludingDeleted(userId) } returns user
        val pin = buildPin()
        every { pins.findAllPinIdsForUser(user) } returns listOf(pin.id)
        every { images.findByPinId(pin.id) } returns null

        // When
        cleaner.deleteAccountData(userId)

        // Then
        verify { clearDownload.clear(pin.id) }
        verify { images.deleteByPinId(pin.id) }
        verify(exactly = 0) { imageStore.delete(any()) }
        verify(exactly = 0) { renditions.evictImage(any()) }
    }
}
