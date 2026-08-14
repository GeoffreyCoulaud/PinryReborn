package fr.geoffreyCoulaud.pinryReborn.api.usecases

import fr.geoffreyCoulaud.pinryReborn.api.domain.exports.ExportArchiveStore
import fr.geoffreyCoulaud.pinryReborn.api.domain.images.RenditionCache
import fr.geoffreyCoulaud.pinryReborn.api.domain.imports.ImportArchiveStore
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.ImageRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.UserDataExportRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.UserDataImportRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.utilities.BaseTest
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.UUID.randomUUID

class ReapOrphanedStorageTest : BaseTest() {
    private val renditionCache = mockk<RenditionCache>()
    private val exportArchiveStore = mockk<ExportArchiveStore>()
    private val importArchiveStore = mockk<ImportArchiveStore>()
    private val imageRepository = mockk<ImageRepositoryInterface>()
    private val userDataExportRepository = mockk<UserDataExportRepositoryInterface>()
    private val userDataImportRepository = mockk<UserDataImportRepositoryInterface>()
    private val batchSize = 2

    private val useCase = ReapOrphanedStorage(
        renditionCache = renditionCache,
        exportArchiveStore = exportArchiveStore,
        importArchiveStore = importArchiveStore,
        imageRepository = imageRepository,
        userDataExportRepository = userDataExportRepository,
        userDataImportRepository = userDataImportRepository,
        batchSize = batchSize,
    )

    /** Every run reads all three disks, so a case names what its own half holds and empties the rest. */
    private fun renditionsOnDisk(vararg ids: UUID) {
        every { renditionCache.forEachImageIdOnDisk(any()) } answers {
            firstArg<(Sequence<UUID>) -> Unit>().invoke(ids.asSequence())
        }
    }

    private fun exportsOnDisk(vararg keys: String) {
        every { exportArchiveStore.forEachStorageKeyOnDisk(any()) } answers {
            firstArg<(Sequence<String>) -> Unit>().invoke(keys.asSequence())
        }
    }

    private fun importsOnDisk(vararg keys: String) {
        every { importArchiveStore.forEachStorageKeyOnDisk(any()) } answers {
            firstArg<(Sequence<String>) -> Unit>().invoke(keys.asSequence())
        }
    }

    @Test
    fun `Given a rendition id only on disk, Then reap evicts it`() {
        // Given: disk has one image id with no DB row
        val orphanId = randomUUID()
        renditionsOnDisk(orphanId)
        exportsOnDisk()
        importsOnDisk()
        every { imageRepository.findMissingImageIds(listOf(orphanId)) } returns setOf(orphanId)
        every { renditionCache.evictImage(any()) } just runs

        // When
        val count = useCase.reap()

        // Then
        assertEquals(1, count)
        verify { renditionCache.evictImage(orphanId) }
    }

    @Test
    fun `Given an export storage key only on disk, Then reap deletes it`() {
        // Given: disk has one export archive whose id has no DB row
        val exportId = randomUUID()
        val storageKey = "exports/$exportId.zip"
        renditionsOnDisk()
        exportsOnDisk(storageKey)
        importsOnDisk()
        every { userDataExportRepository.findMissingExportIds(listOf(exportId)) } returns setOf(exportId)
        every { exportArchiveStore.delete(any()) } just runs

        // When
        val count = useCase.reap()

        // Then: the ORIGINAL storage key is deleted, not the parsed id
        assertEquals(1, count)
        verify { exportArchiveStore.delete(storageKey) }
    }

    @Test
    fun `Given an import storage key only on disk, Then reap deletes it`() {
        // Given: an archive a completer promoted before dying, which no row-driven sweep can name
        val importId = randomUUID()
        val storageKey = "imports/$importId.zip"
        renditionsOnDisk()
        exportsOnDisk()
        importsOnDisk(storageKey)
        every { userDataImportRepository.findMissingImportIds(listOf(importId)) } returns setOf(importId)
        every { importArchiveStore.delete(any()) } just runs

        // When
        val count = useCase.reap()

        // Then
        assertEquals(1, count)
        verify { importArchiveStore.delete(storageKey) }
    }

    @Test
    fun `Given an id present in the DB, Then reap leaves it`() {
        // Given: disk has one of each, all three present in the DB
        val liveImageId = randomUUID()
        val liveExportId = randomUUID()
        val liveImportId = randomUUID()
        renditionsOnDisk(liveImageId)
        exportsOnDisk("exports/$liveExportId.zip")
        importsOnDisk("imports/$liveImportId.zip")
        every { imageRepository.findMissingImageIds(listOf(liveImageId)) } returns emptySet()
        every { userDataExportRepository.findMissingExportIds(listOf(liveExportId)) } returns emptySet()
        every { userDataImportRepository.findMissingImportIds(listOf(liveImportId)) } returns emptySet()

        // When
        val count = useCase.reap()

        // Then: nothing reclaimed
        assertEquals(0, count)
        verify(exactly = 0) { renditionCache.evictImage(any()) }
        verify(exactly = 0) { exportArchiveStore.delete(any()) }
        verify(exactly = 0) { importArchiveStore.delete(any()) }
    }

    @Test
    fun `Given more disk ids than batchSize, Then reap processes them across multiple chunks`() {
        // Given: three image ids, batch size 2, none missing from DB
        val idA = randomUUID()
        val idB = randomUUID()
        val idC = randomUUID()
        renditionsOnDisk(idA, idB, idC)
        exportsOnDisk()
        importsOnDisk()
        val capturedChunks = mutableListOf<Collection<UUID>>()
        every { imageRepository.findMissingImageIds(capture(capturedChunks)) } returns emptySet()

        // When
        val count = useCase.reap()

        // Then: chunked as [a, b] then [c]
        assertEquals(0, count)
        assertEquals(listOf(listOf(idA, idB), listOf(idC)), capturedChunks)
    }

    @Test
    fun `Given an unparseable export storage key, Then reap skips it`() {
        // Given: a key not of the form exports/<uuid>.<ext>
        val badKey = "exports/not-a-uuid.zip"
        renditionsOnDisk()
        exportsOnDisk(badKey)
        importsOnDisk()

        // When
        val count = useCase.reap()

        // Then: skipped, never deleted, never queried
        assertEquals(0, count)
        verify(exactly = 0) { exportArchiveStore.delete(any()) }
        verify(exactly = 0) { userDataExportRepository.findMissingExportIds(any()) }
    }

    @Test
    fun `Given keys with a wrong prefix, missing extension or bad UUID, Then reap skips all of them`() {
        // Given: every parser failure path, on both archive halves: wrong prefix, no dot, empty
        // extension, bad UUID. The import half parses the same shape under its own prefix.
        val wrongPrefix = "tmp/orphan.zip"
        val noDot = "exports/${randomUUID()}"
        val emptyExtension = "exports/${randomUUID()}."
        val badUuidWithExt = "exports/also-not-a-uuid.bin"
        renditionsOnDisk()
        exportsOnDisk(wrongPrefix, noDot, emptyExtension, badUuidWithExt)
        importsOnDisk("exports/${randomUUID()}.zip", "imports/not-a-uuid.zip", "imports/${randomUUID()}")

        // When
        val count = useCase.reap()

        // Then: none deleted, none queried (each path returns null before reaching the repository)
        assertEquals(0, count)
        verify(exactly = 0) { exportArchiveStore.delete(any()) }
        verify(exactly = 0) { importArchiveStore.delete(any()) }
        verify(exactly = 0) { userDataExportRepository.findMissingExportIds(any()) }
        verify(exactly = 0) { userDataImportRepository.findMissingImportIds(any()) }
    }
}
