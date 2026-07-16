package fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.controllers

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Image
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.User
import fr.geoffreyCoulaud.pinryReborn.api.domain.images.ImageStore
import fr.geoffreyCoulaud.pinryReborn.api.domain.images.RenditionCache
import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.config.ApiConfig
import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.config.ImagesConfig
import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.config.RenditionsConfig
import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.dtos.output.ImageOutputDto
import fr.geoffreyCoulaud.pinryReborn.api.usecases.DeletePinImage
import fr.geoffreyCoulaud.pinryReborn.api.usecases.GetPinImageRendition
import fr.geoffreyCoulaud.pinryReborn.api.usecases.RequestPinImageDownload
import fr.geoffreyCoulaud.pinryReborn.api.usecases.ResolvePinImageState
import fr.geoffreyCoulaud.pinryReborn.api.usecases.ServedImage
import fr.geoffreyCoulaud.pinryReborn.api.usecases.SetPinImage
import fr.geoffreyCoulaud.pinryReborn.api.usecases.SetPinImageResult
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.ImageDoesNotExistError
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.ImageRenditionSizeInvalidError
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
import org.junit.jupiter.api.Assertions.assertThrows
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
    private val getPinImageRendition = mockk<GetPinImageRendition>()
    private val deletePinImage = mockk<DeletePinImage>()
    private val requestPinImageDownload = mockk<RequestPinImageDownload>()
    private val resolvePinImageState = mockk<ResolvePinImageState>()
    private val imageStore = mockk<ImageStore>()
    private val imagesConfig = mockk<ImagesConfig>()
    private val renditionCache = mockk<RenditionCache>()
    private val renditionsConfig = mockk<RenditionsConfig>()
    private val securityIdentity = mockk<SecurityIdentity>()
    private val apiConfig = mockk<ApiConfig>()
    private val controller = ImageController(
        setPinImage = setPinImage,
        getPinImageRendition = getPinImageRendition,
        deletePinImage = deletePinImage,
        requestPinImageDownload = requestPinImageDownload,
        resolvePinImageState = resolvePinImageState,
        imageStore = imageStore,
        imagesConfig = imagesConfig,
        renditionCache = renditionCache,
        renditionsConfig = renditionsConfig,
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

    private fun aUser() = User(id = randomUUID(), name = createRandomString())

    @Test
    fun `Given a pin with no existing image, Then setImage returns 201 with the created image`() {
        // Given
        val pinId = randomUUID()
        val user = aUser()
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
        val user = aUser()
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
    fun `Given no size requested and a matching If-None-Match header, Then getImage returns 304 without streaming`() {
        // Given
        val pinId = randomUUID()
        val user = aUser()
        val image = anImage(pinId)
        every { securityIdentity.getAttribute<User>("user") } returns user
        every { getPinImageRendition.get(pinId, user, null, true) } returns ServedImage.Original(image)

        // When
        val response = controller.getImage(pinId, size = null, animated = null, ifNoneMatch = image.contentHash)

        // Then
        assertEquals(304, response.status)
        assertNull(response.entity)
        verify(exactly = 0) { imageStore.openStream(any()) }
    }

    @Test
    fun `Given no size and no matching ETag, Then getImage returns 200 with headers and streams the bytes`() {
        // Given
        val pinId = randomUUID()
        val user = aUser()
        val image = anImage(pinId)
        val bytes = byteArrayOf(9, 8, 7, 6)
        every { securityIdentity.getAttribute<User>("user") } returns user
        every { getPinImageRendition.get(pinId, user, null, true) } returns ServedImage.Original(image)
        every { imageStore.openStream(image.storageKey) } returns ByteArrayInputStream(bytes)

        // When
        val response = controller.getImage(pinId, size = null, animated = null, ifNoneMatch = null)

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
    fun `Given size small and a rendition, Then it serves image webp with a synthetic ETag`() {
        // Given
        val pinId = randomUUID()
        val imageId = randomUUID()
        val user = aUser()
        every { securityIdentity.getAttribute<User>("user") } returns user
        every { renditionsConfig.small() } returns 240
        every { getPinImageRendition.get(pinId, user, 240, true) } returns
            ServedImage.Rendition(imageId, "v1-240-a.webp", 240, animated = true)
        every { renditionCache.openStream(imageId, "v1-240-a.webp") } returns ByteArrayInputStream(byteArrayOf(7, 7))

        // When
        val response = controller.getImage(pinId, size = "small", animated = null, ifNoneMatch = null)

        // Then
        assertEquals(200, response.status)
        assertEquals("image/webp", response.getHeaderString("Content-Type"))
        assertEquals("v1-$imageId-240-a", response.getHeaderString("ETag"))
        assertEquals("private, must-revalidate", response.getHeaderString("Cache-Control"))
        val streamingOutput = response.entity as StreamingOutput
        val out = ByteArrayOutputStream()
        streamingOutput.write(out)
        assertArrayEquals(byteArrayOf(7, 7), out.toByteArray())
    }

    @Test
    fun `Given animated is explicitly false, Then it is passed through instead of the default`() {
        // Given
        val pinId = randomUUID()
        val imageId = randomUUID()
        val user = aUser()
        every { securityIdentity.getAttribute<User>("user") } returns user
        every { renditionsConfig.small() } returns 240
        every { getPinImageRendition.get(pinId, user, 240, false) } returns
            ServedImage.Rendition(imageId, "v1-240-s.webp", 240, animated = false)
        every { renditionCache.openStream(imageId, "v1-240-s.webp") } returns ByteArrayInputStream(byteArrayOf(4))

        // When
        val response = controller.getImage(pinId, size = "small", animated = false, ifNoneMatch = null)

        // Then
        assertEquals(200, response.status)
        verify { getPinImageRendition.get(pinId, user, 240, false) }
    }

    @Test
    fun `Given a static rendition, Then the synthetic ETag ends with s instead of a`() {
        // Given
        val pinId = randomUUID()
        val imageId = randomUUID()
        val user = aUser()
        every { securityIdentity.getAttribute<User>("user") } returns user
        every { renditionsConfig.small() } returns 240
        every { getPinImageRendition.get(pinId, user, 240, true) } returns
            ServedImage.Rendition(imageId, "v1-240-s.webp", 240, animated = false)
        every { renditionCache.openStream(imageId, "v1-240-s.webp") } returns ByteArrayInputStream(byteArrayOf(3))

        // When
        val response = controller.getImage(pinId, size = "small", animated = null, ifNoneMatch = null)

        // Then
        assertEquals(200, response.status)
        assertEquals("v1-$imageId-240-s", response.getHeaderString("ETag"))
        val streamingOutput = response.entity as StreamingOutput
        val out = ByteArrayOutputStream()
        streamingOutput.write(out)
        assertArrayEquals(byteArrayOf(3), out.toByteArray())
    }

    @Test
    fun `Given size small and a matching synthetic ETag, Then getImage returns 304 without streaming`() {
        // Given
        val pinId = randomUUID()
        val imageId = randomUUID()
        val user = aUser()
        every { securityIdentity.getAttribute<User>("user") } returns user
        every { renditionsConfig.small() } returns 240
        every { getPinImageRendition.get(pinId, user, 240, true) } returns
            ServedImage.Rendition(imageId, "v1-240-a.webp", 240, animated = true)

        // When
        val response = controller.getImage(
            pinId,
            size = "small",
            animated = null,
            ifNoneMatch = "v1-$imageId-240-a",
        )

        // Then
        assertEquals(304, response.status)
        assertNull(response.entity)
        verify(exactly = 0) { renditionCache.openStream(any(), any()) }
    }

    @Test
    fun `Given an unknown size, Then it throws ImageRenditionSizeInvalidError`() {
        // Given
        val user = aUser()
        every { securityIdentity.getAttribute<User>("user") } returns user

        // Then
        assertThrows(ImageRenditionSizeInvalidError::class.java) {
            controller.getImage(randomUUID(), size = "huge", animated = null, ifNoneMatch = null)
        }
    }

    @Test
    fun `Given a rendition cache entry evicted concurrently, Then writing the body throws ImageDoesNotExistError`() {
        // Given
        val pinId = randomUUID()
        val imageId = randomUUID()
        val user = aUser()
        every { securityIdentity.getAttribute<User>("user") } returns user
        every { renditionsConfig.small() } returns 240
        every { getPinImageRendition.get(pinId, user, 240, true) } returns
            ServedImage.Rendition(imageId, "v1-240-a.webp", 240, animated = true)
        every { renditionCache.openStream(imageId, "v1-240-a.webp") } returns null

        // When
        val response = controller.getImage(pinId, size = "small", animated = null, ifNoneMatch = null)
        val streamingOutput = response.entity as StreamingOutput

        // Then
        assertEquals(200, response.status)
        assertThrows(ImageDoesNotExistError::class.java) {
            streamingOutput.write(ByteArrayOutputStream())
        }
    }

    @Test
    fun `Given a valid request, Then deleteImage returns 204`() {
        // Given
        val pinId = randomUUID()
        val user = aUser()
        every { securityIdentity.getAttribute<User>("user") } returns user
        every { deletePinImage.delete(pinId = pinId, requester = user) } returns Unit

        // When
        val response = controller.deleteImage(pinId)

        // Then
        assertEquals(204, response.status)
    }
}
