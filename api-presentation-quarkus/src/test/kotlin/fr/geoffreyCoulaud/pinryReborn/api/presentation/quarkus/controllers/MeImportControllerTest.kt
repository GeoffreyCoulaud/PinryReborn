package fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.controllers

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Cursor
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Page
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.User
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.UserDataImport
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.UserDataImportIssue
import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.CursorDirection
import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.UserDataImportIssueKind
import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.UserDataImportState
import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.dtos.common.CursorDirectionDto
import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.dtos.common.CursorDto
import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.dtos.output.UserDataImportIssueListOutputDto
import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.dtos.output.UserDataImportListOutputDto
import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.dtos.output.UserDataImportOutputDto
import fr.geoffreyCoulaud.pinryReborn.api.usecases.imports.UserDataImportArchiveCompleter
import fr.geoffreyCoulaud.pinryReborn.api.usecases.imports.UserDataImportCanceller
import fr.geoffreyCoulaud.pinryReborn.api.usecases.imports.UserDataImportChunkReceiver
import fr.geoffreyCoulaud.pinryReborn.api.usecases.imports.UserDataImportCreator
import fr.geoffreyCoulaud.pinryReborn.api.usecases.imports.UserDataImportGetter
import fr.geoffreyCoulaud.pinryReborn.api.usecases.imports.UserDataImportIssueLister
import fr.geoffreyCoulaud.pinryReborn.api.utilities.TestTime
import fr.geoffreyCoulaud.pinryReborn.api.utilities.createRandomString
import io.mockk.every
import io.mockk.mockk
import io.quarkus.security.identity.SecurityIdentity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.time.Instant
import java.util.UUID
import java.util.UUID.randomUUID

class MeImportControllerTest {
    private val creator = mockk<UserDataImportCreator>()
    private val chunkReceiver = mockk<UserDataImportChunkReceiver>()
    private val archiveCompleter = mockk<UserDataImportArchiveCompleter>()
    private val getter = mockk<UserDataImportGetter>()
    private val issueLister = mockk<UserDataImportIssueLister>()
    private val canceller = mockk<UserDataImportCanceller>()
    private val securityIdentity = mockk<SecurityIdentity>()
    private val controller = MeImportController(
        creator = creator,
        chunkReceiver = chunkReceiver,
        archiveCompleter = archiveCompleter,
        getter = getter,
        issueLister = issueLister,
        canceller = canceller,
        securityIdentity = securityIdentity,
    )

    private fun aUser() = User(id = randomUUID(), name = createRandomString(), createdAt = TestTime.now)

    private fun awaitingImport(userId: UUID) = UserDataImport(
        id = randomUUID(),
        userId = userId,
        state = UserDataImportState.AWAITING_ARCHIVE,
        requestedAt = Instant.parse("2026-08-14T10:00:00Z"),
    )

    @Test
    fun `Given an authenticated caller, Then createImport returns 202 with the opened import`() {
        // Given
        val user = aUser()
        val userDataImport = awaitingImport(user.id)
        every { securityIdentity.getAttribute<User>("user") } returns user
        every { creator.create(user) } returns userDataImport

        // When
        val response = controller.createImport()

        // Then
        assertEquals(202, response.status)
        assertEquals(userDataImport.id, (response.entity as UserDataImportOutputDto).id)
        assertEquals("AWAITING_ARCHIVE", (response.entity as UserDataImportOutputDto).state)
    }

    @Test
    fun `Given no cursor and no page size, Then listImports uses the default page size`() {
        // Given: a page with a row in it, since an empty one is also what a controller answering a
        // constant empty list returns, and the status alone cannot tell the two apart.
        val user = aUser()
        val userDataImport = awaitingImport(user.id)
        val page = Page(items = listOf(userDataImport), previousCursor = null, nextCursor = null)
        every { securityIdentity.getAttribute<User>("user") } returns user
        every { getter.list(user, null, MeImportController.DEFAULT_PAGE_SIZE) } returns page

        // When
        val response = controller.listImports(cursorInput = null, pageSizeInput = null)

        // Then
        assertEquals(200, response.status)
        val entity = response.entity as UserDataImportListOutputDto
        assertEquals(userDataImport.id, entity.imports.single().id)
        assertEquals("AWAITING_ARCHIVE", entity.imports.single().state)
    }

    @Test
    fun `Given a cursor and a page size, Then listImports uses them`() {
        // Given
        val user = aUser()
        val pivotId = randomUUID()
        val cursorInput = CursorDto(pivotId = pivotId, direction = CursorDirectionDto.FORWARD)
        val userDataImport = awaitingImport(user.id).copy(state = UserDataImportState.COMPLETED)
        val nextCursor = Cursor(pivotId = userDataImport.id, direction = CursorDirection.FORWARD)
        val page = Page(items = listOf(userDataImport), previousCursor = null, nextCursor = nextCursor)
        every { securityIdentity.getAttribute<User>("user") } returns user
        every { getter.list(user, match { it.pivotId == pivotId }, 5) } returns page

        // When
        val response = controller.listImports(cursorInput = cursorInput, pageSizeInput = 5)

        // Then
        assertEquals(200, response.status)
        val entity = response.entity as UserDataImportListOutputDto
        assertEquals(userDataImport.id, entity.imports.single().id)
        assertEquals("COMPLETED", entity.imports.single().state)
        assertEquals(userDataImport.id, entity.pagination.nextCursor?.pivotId)
    }

    @Test
    fun `Given a known id, Then getImport returns it`() {
        // Given
        val user = aUser()
        val userDataImport = awaitingImport(user.id)
        every { securityIdentity.getAttribute<User>("user") } returns user
        every { getter.get(user, userDataImport.id) } returns userDataImport

        // When
        val response = controller.getImport(userDataImport.id)

        // Then
        assertEquals(200, response.status)
        assertEquals(userDataImport.id, (response.entity as UserDataImportOutputDto).id)
    }

    @Test
    fun `Given an offset, Then uploadChunk hands the body straight to the receiver and answers the new length`() {
        // Given: the very stream instance is stubbed, so a controller buffering the body into another
        // one fails to match here. Its unread state is asserted below, for the same reason.
        val user = aUser()
        val userDataImport = awaitingImport(user.id)
        val bytes = byteArrayOf(1, 2, 3, 4)
        val body = ByteArrayInputStream(bytes)
        every { securityIdentity.getAttribute<User>("user") } returns user
        every { chunkReceiver.receive(user, userDataImport.id, 16L, body) } returns
            userDataImport.copy(uploadedBytes = 20L)

        // When
        val response = controller.uploadChunk(userDataImport.id, offsetInput = 16L, body = body)

        // Then
        assertEquals(200, response.status)
        assertEquals(20L, (response.entity as UserDataImportOutputDto).uploadedBytes)
        assertEquals(bytes.size, body.available())
    }

    @Test
    fun `Given no offset, Then uploadChunk appends at the start of the upload`() {
        // Given
        val user = aUser()
        val userDataImport = awaitingImport(user.id)
        val body = ByteArrayInputStream(byteArrayOf(1, 2, 3, 4))
        every { securityIdentity.getAttribute<User>("user") } returns user
        every { chunkReceiver.receive(user, userDataImport.id, 0L, body) } returns
            userDataImport.copy(uploadedBytes = 4L)

        // When
        val response = controller.uploadChunk(userDataImport.id, offsetInput = null, body = body)

        // Then
        assertEquals(200, response.status)
        assertEquals(4L, (response.entity as UserDataImportOutputDto).uploadedBytes)
    }

    @Test
    fun `Given a finished upload, Then completeArchive returns 202 with the pending import`() {
        // Given
        val user = aUser()
        val userDataImport = awaitingImport(user.id).copy(state = UserDataImportState.PENDING)
        every { securityIdentity.getAttribute<User>("user") } returns user
        every { archiveCompleter.complete(user, userDataImport.id) } returns userDataImport

        // When
        val response = controller.completeArchive(userDataImport.id)

        // Then
        assertEquals(202, response.status)
        assertEquals("PENDING", (response.entity as UserDataImportOutputDto).state)
    }

    @Test
    fun `Given no cursor and no page size, Then listIssues uses the default page size`() {
        // Given
        val user = aUser()
        val importId = randomUUID()
        val issue = UserDataImportIssue(
            id = randomUUID(),
            importId = importId,
            kind = UserDataImportIssueKind.PIN_HAS_NO_MEDIA,
            line = 12,
            subject = "pin",
            detail = "no media",
        )
        val page = Page(items = listOf(issue), previousCursor = null, nextCursor = null)
        every { securityIdentity.getAttribute<User>("user") } returns user
        every { issueLister.list(user, importId, null, MeImportController.DEFAULT_PAGE_SIZE) } returns page

        // When
        val response = controller.listIssues(importId, cursorInput = null, pageSizeInput = null)

        // Then
        assertEquals(200, response.status)
        val entity = response.entity as UserDataImportIssueListOutputDto
        assertEquals("PIN_HAS_NO_MEDIA", entity.issues.single().kind)
    }

    @Test
    fun `Given a cursor and a page size, Then listIssues uses them`() {
        // Given: a page with a row and a cursor in it, since an empty one is also what a controller
        // answering a constant empty list returns, and the status alone cannot tell the two apart.
        val user = aUser()
        val importId = randomUUID()
        val pivotId = randomUUID()
        val cursorInput = CursorDto(pivotId = pivotId, direction = CursorDirectionDto.BACKWARD)
        val issue = UserDataImportIssue(
            id = randomUUID(),
            importId = importId,
            kind = UserDataImportIssueKind.MEDIA_DIGEST_MISMATCH,
            line = 7,
            subject = "images/one.png",
            detail = "digest mismatch",
        )
        val previousCursor = Cursor(pivotId = issue.id, direction = CursorDirection.BACKWARD)
        val page = Page(items = listOf(issue), previousCursor = previousCursor, nextCursor = null)
        every { securityIdentity.getAttribute<User>("user") } returns user
        every { issueLister.list(user, importId, match { it.pivotId == pivotId }, 5) } returns page

        // When
        val response = controller.listIssues(importId, cursorInput = cursorInput, pageSizeInput = 5)

        // Then
        assertEquals(200, response.status)
        val entity = response.entity as UserDataImportIssueListOutputDto
        assertEquals(issue.id, entity.issues.single().id)
        assertEquals("MEDIA_DIGEST_MISMATCH", entity.issues.single().kind)
        assertEquals(issue.id, entity.pagination.previousCursor?.pivotId)
    }

    @Test
    fun `Given a known id, Then cancelImport returns 204`() {
        // Given
        val user = aUser()
        val importId = randomUUID()
        every { securityIdentity.getAttribute<User>("user") } returns user
        every { canceller.cancel(user, importId) } returns Unit

        // When
        val response = controller.cancelImport(importId)

        // Then
        assertEquals(204, response.status)
    }
}
