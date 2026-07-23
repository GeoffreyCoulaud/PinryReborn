package fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.controllers

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Page
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.User
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.UserDataExport
import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.UserDataExportState
import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.dtos.common.CursorDirectionDto
import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.dtos.common.CursorDto
import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.dtos.output.UserDataExportOutputDto
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exports.OpenedExport
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exports.UserDataExportDeleter
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exports.UserDataExportDownloader
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exports.UserDataExportGetter
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exports.UserDataExportRequester
import fr.geoffreyCoulaud.pinryReborn.api.utilities.createRandomString
import io.mockk.every
import io.mockk.mockk
import io.quarkus.security.identity.SecurityIdentity
import jakarta.ws.rs.core.StreamingOutput
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.Base64
import java.util.UUID.randomUUID

class MeExportControllerTest {
    private val requester = mockk<UserDataExportRequester>()
    private val getter = mockk<UserDataExportGetter>()
    private val downloader = mockk<UserDataExportDownloader>()
    private val deleter = mockk<UserDataExportDeleter>()
    private val securityIdentity = mockk<SecurityIdentity>()
    private val controller = MeExportController(
        requester = requester,
        getter = getter,
        downloader = downloader,
        deleter = deleter,
        securityIdentity = securityIdentity,
    )

    private fun aUser() = User(id = randomUUID(), name = createRandomString())

    private fun pendingExport(userId: java.util.UUID) = UserDataExport(
        id = randomUUID(),
        userId = userId,
        state = UserDataExportState.PENDING,
        formatVersion = 1,
        requestedAt = Instant.parse("2026-07-22T10:00:00Z"),
    )

    private fun anOpenedExport(id: java.util.UUID, bytes: ByteArray, totalByteSize: Long = bytes.size.toLong()) =
        OpenedExport(
            exportId = id,
            mediaType = "application/zip",
            fileExtension = "zip",
            totalByteSize = totalByteSize,
            sha256 = "abc123",
            completedAt = Instant.parse("2026-07-22T10:00:00Z"),
            stream = ByteArrayInputStream(bytes),
        )

    @Test
    fun `Given a valid reauthentication header, Then requestExport returns 202 with the created export`() {
        // Given
        val user = aUser()
        val header = "password " + Base64.getUrlEncoder().encodeToString("hunter2".toByteArray())
        val export = pendingExport(user.id)
        every { securityIdentity.getAttribute<User>("user") } returns user
        every { requester.request(user, "hunter2") } returns export

        // When
        val response = controller.requestExport(header)

        // Then
        assertEquals(202, response.status)
        assertEquals(export.id, (response.entity as UserDataExportOutputDto).id)
    }

    @Test
    fun `Given no cursor and no page size, Then listExports uses the default page size`() {
        // Given
        val user = aUser()
        val page = Page<UserDataExport>(items = emptyList(), previousCursor = null, nextCursor = null)
        every { securityIdentity.getAttribute<User>("user") } returns user
        every { getter.list(user, null, MeExportController.DEFAULT_PAGE_SIZE) } returns page

        // When
        val response = controller.listExports(cursorInput = null, pageSizeInput = null)

        // Then
        assertEquals(200, response.status)
    }

    @Test
    fun `Given a cursor and a page size, Then listExports uses them`() {
        // Given
        val user = aUser()
        val pivotId = randomUUID()
        val cursorInput = CursorDto(pivotId = pivotId, direction = CursorDirectionDto.FORWARD)
        val page = Page<UserDataExport>(items = emptyList(), previousCursor = null, nextCursor = null)
        every { securityIdentity.getAttribute<User>("user") } returns user
        every { getter.list(user, match { it.pivotId == pivotId }, 5) } returns page

        // When
        val response = controller.listExports(cursorInput = cursorInput, pageSizeInput = 5)

        // Then
        assertEquals(200, response.status)
    }

    @Test
    fun `Given a known id, Then getExport returns it`() {
        // Given
        val user = aUser()
        val export = pendingExport(user.id)
        every { securityIdentity.getAttribute<User>("user") } returns user
        every { getter.get(user, export.id) } returns export

        // When
        val response = controller.getExport(export.id)

        // Then
        assertEquals(200, response.status)
        assertEquals(export.id, (response.entity as UserDataExportOutputDto).id)
    }

    @Test
    fun `Given no Range header, Then downloadExport streams the whole body as 200`() {
        // Given
        val user = aUser()
        val id = randomUUID()
        val bytes = byteArrayOf(1, 2, 3, 4, 5)
        val opened = anOpenedExport(id, bytes)
        every { securityIdentity.getAttribute<User>("user") } returns user
        every { downloader.open(user, id, 0) } returns opened

        // When
        val response = controller.downloadExport(id, rangeHeader = null)

        // Then
        assertEquals(200, response.status)
        assertEquals("application/zip", response.getHeaderString("Content-Type"))
        assertEquals("5", response.getHeaderString("Content-Length"))
        assertEquals("\"abc123\"", response.getHeaderString("ETag"))
        assertEquals("bytes", response.getHeaderString("Accept-Ranges"))
        assertNull(response.getHeaderString("Content-Range"))
        val disposition = response.getHeaderString("Content-Disposition")
        assertTrue(disposition.contains("2026-07-22-pinry-export-"))
        assertTrue(disposition.contains(".zip"))

        val out = ByteArrayOutputStream()
        (response.entity as StreamingOutput).write(out)
        assertArrayEquals(bytes, out.toByteArray())
    }

    @Test
    fun `Given a partial Range header, Then downloadExport streams only that slice as 206`() {
        // Given
        val user = aUser()
        val id = randomUUID()
        val bytes = byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9)
        val opened = anOpenedExport(id, bytes)
        every { securityIdentity.getAttribute<User>("user") } returns user
        every { downloader.open(user, id, 0) } returns opened

        // When
        val response = controller.downloadExport(id, rangeHeader = "bytes=2-4")

        // Then
        assertEquals(206, response.status)
        assertEquals("3", response.getHeaderString("Content-Length"))
        assertEquals("bytes 2-4/10", response.getHeaderString("Content-Range"))
        val out = ByteArrayOutputStream()
        (response.entity as StreamingOutput).write(out)
        assertArrayEquals(byteArrayOf(2, 3, 4), out.toByteArray())
    }

    @Test
    fun `Given a stream shorter than the announced size, Then the bounded copy stops at end of stream`() {
        // Given
        val user = aUser()
        val id = randomUUID()
        val actualBytes = byteArrayOf(9, 9, 9)
        val opened = anOpenedExport(id, actualBytes, totalByteSize = 10L)
        every { securityIdentity.getAttribute<User>("user") } returns user
        every { downloader.open(user, id, 0) } returns opened

        // When
        val response = controller.downloadExport(id, rangeHeader = null)
        val out = ByteArrayOutputStream()
        (response.entity as StreamingOutput).write(out)

        // Then
        assertArrayEquals(actualBytes, out.toByteArray())
    }

    @Test
    fun `Given a known id, Then deleteExport returns 204`() {
        // Given
        val user = aUser()
        val id = randomUUID()
        every { securityIdentity.getAttribute<User>("user") } returns user
        every { deleter.delete(user, id) } returns Unit

        // When
        val response = controller.deleteExport(id)

        // Then
        assertEquals(204, response.status)
    }
}
