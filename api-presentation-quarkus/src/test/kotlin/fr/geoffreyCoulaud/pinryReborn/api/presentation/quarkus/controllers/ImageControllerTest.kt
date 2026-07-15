package fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.controllers

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Image
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.User
import fr.geoffreyCoulaud.pinryReborn.api.domain.images.ImageStore
import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.config.ApiConfig
import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.config.ImagesConfig
import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.dtos.output.ImageOutputDto
import fr.geoffreyCoulaud.pinryReborn.api.usecases.DeletePinImage
import fr.geoffreyCoulaud.pinryReborn.api.usecases.GetPinImage
import fr.geoffreyCoulaud.pinryReborn.api.usecases.RequestPinImageDownload
import fr.geoffreyCoulaud.pinryReborn.api.usecases.ResolvePinImageState
import fr.geoffreyCoulaud.pinryReborn.api.usecases.SetPinImage
import fr.geoffreyCoulaud.pinryReborn.api.usecases.SetPinImageResult
import fr.geoffreyCoulaud.pinryReborn.api.utilities.createRandomString
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.quarkus.security.identity.SecurityIdentity
import jakarta.ws.rs.core.StreamingOutput
import org.jboss.resteasy.reactive.multipart.FileUpload
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import java.util.UUID.randomUUID

class ImageControllerTest {
    private val setPinImage = mockk<SetPinImage>()
    private val getPinImage = mockk<GetPinImage>()
    private val deletePinImage = mockk<DeletePinImage>()
    private val requestPinImageDownload = mockk<RequestPinImageDownload>()
    private val resolvePinImageState = mockk<ResolvePinImageState>()
    private val imageStore = mockk<ImageStore>()
    private val imagesConfig = mockk<ImagesConfig>()
    private val securityIdentity = mockk<SecurityIdentity>()
    private val apiConfig = mockk<ApiConfig>()
    private val controller = ImageController(
        setPinImage = setPinImage,
        getPinImage = getPinImage,
        deletePinImage = deletePinImage,
        requestPinImageDownload = requestPinImageDownload,
        resolvePinImageState = resolvePinImageState,
        imageStore = imageStore,
        imagesConfig = imagesConfig,
        securityIdentity = securityIdentity,
        apiConfig = apiConfig,
    )

    @TempDir
    lateinit var tempDir: Path

    private fun anImage(pinId: UUID) = Image(
        id = randomUUID(),
        pinId = pinId,
        mimeType = "image/png",
        width = 8,
        height = 6,
        animated = false,
        byteSize = 4,
        contentHash = createRandomString(),
        storageKey = "originals/x/$pinId/y.png",
        createdAt = Instant.EPOCH,
    )

    @Test
    fun `Given a pin with no existing image, Then setImage returns 201 with the created image`() {
        // Given
        val pinId = randomUUID()
        val user = User(id = randomUUID(), name = createRandomString())
        val maxBytes = 123L
        val maxPixels = 456L
        val uploadedPath = Files.createTempFile(tempDir, "upload-", ".tmp")
        Files.write(uploadedPath, byteArrayOf(1, 2, 3))
        val fileUpload = mockk<FileUpload>()
        every { fileUpload.uploadedFile() } returns uploadedPath
        val image = anImage(pinId)
        every { securityIdentity.getAttribute<User>("user") } returns user
        every { imagesConfig.maxFileBytes() } returns maxBytes
        every { imagesConfig.maxPixels() } returns maxPixels
        every {
            setPinImage.set(pinId = pinId, requester = user, upload = any(), maxBytes = maxBytes, maxPixels = maxPixels)
        } returns SetPinImageResult(image = image, replaced = false)
        every { apiConfig.baseUrl() } returns "https://host"

        // When
        val response = controller.setImage(pinId, fileUpload)

        // Then
        assertEquals(201, response.status)
        val dto = response.entity as ImageOutputDto
        assertEquals(image.id, dto.id)
        assertEquals("https://host/api/v1/pins/$pinId/image", dto.url)
    }

    @Test
    fun `Given a pin with an existing image, Then setImage returns 200 with the replaced image`() {
        // Given
        val pinId = randomUUID()
        val user = User(id = randomUUID(), name = createRandomString())
        val maxBytes = 123L
        val maxPixels = 456L
        val uploadedPath = Files.createTempFile(tempDir, "upload-", ".tmp")
        Files.write(uploadedPath, byteArrayOf(1, 2, 3))
        val fileUpload = mockk<FileUpload>()
        every { fileUpload.uploadedFile() } returns uploadedPath
        val image = anImage(pinId)
        every { securityIdentity.getAttribute<User>("user") } returns user
        every { imagesConfig.maxFileBytes() } returns maxBytes
        every { imagesConfig.maxPixels() } returns maxPixels
        every {
            setPinImage.set(pinId = pinId, requester = user, upload = any(), maxBytes = maxBytes, maxPixels = maxPixels)
        } returns SetPinImageResult(image = image, replaced = true)
        every { apiConfig.baseUrl() } returns "https://host"

        // When
        val response = controller.setImage(pinId, fileUpload)

        // Then
        assertEquals(200, response.status)
        val dto = response.entity as ImageOutputDto
        assertEquals(image.id, dto.id)
    }

    @Test
    fun `Given a matching If-None-Match header, Then getImage returns 304 without streaming`() {
        // Given
        val pinId = randomUUID()
        val user = User(id = randomUUID(), name = createRandomString())
        val image = anImage(pinId)
        every { securityIdentity.getAttribute<User>("user") } returns user
        every { getPinImage.get(pinId = pinId, requester = user) } returns image

        // When
        val response = controller.getImage(pinId, image.contentHash)

        // Then
        assertEquals(304, response.status)
        assertNull(response.entity)
        verify(exactly = 0) { imageStore.openStream(any()) }
    }

    @Test
    fun `Given no matching If-None-Match header, Then getImage returns 200 with headers and streams the bytes`() {
        // Given
        val pinId = randomUUID()
        val user = User(id = randomUUID(), name = createRandomString())
        val image = anImage(pinId)
        val bytes = byteArrayOf(9, 8, 7, 6)
        every { securityIdentity.getAttribute<User>("user") } returns user
        every { getPinImage.get(pinId = pinId, requester = user) } returns image
        every { imageStore.openStream(image.storageKey) } returns ByteArrayInputStream(bytes)

        // When
        val response = controller.getImage(pinId, null)

        // Then
        assertEquals(200, response.status)
        assertEquals(image.mimeType, response.getHeaderString("Content-Type"))
        assertEquals(image.contentHash, response.getHeaderString("ETag"))
        assertEquals("private, must-revalidate", response.getHeaderString("Cache-Control"))
        assertEquals(image.byteSize.toString(), response.getHeaderString("Content-Length"))

        val streamingOutput = response.entity as StreamingOutput
        val out = ByteArrayOutputStream()
        streamingOutput.write(out)
        assertArrayEquals(bytes, out.toByteArray())
    }

    @Test
    fun `Given a valid request, Then deleteImage returns 204`() {
        // Given
        val pinId = randomUUID()
        val user = User(id = randomUUID(), name = createRandomString())
        every { securityIdentity.getAttribute<User>("user") } returns user
        every { deletePinImage.delete(pinId = pinId, requester = user) } returns Unit

        // When
        val response = controller.deleteImage(pinId)

        // Then
        assertEquals(204, response.status)
    }
}
