package fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Board
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Cursor
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Pin
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Tag
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.User
import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.CursorDirection
import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.PinSortStrategy
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.mappers.BoardModelMapper.toModel
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.models.BoardModel
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.models.PinModel
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.repositories.PinRepository
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.repositories.TagRepository
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.repositories.UserRepository
import fr.geoffreyCoulaud.pinryReborn.api.utilities.createRandomString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID.randomUUID

class PinRepositoryTest : RepositoryTest() {
    private val repository = PinRepository(database)
    private val userRepository = UserRepository(database)
    private val tagRepository = TagRepository(database)

    private fun createAndSaveUser(): User =
        userRepository.saveUser(
            User(
                id = randomUUID(),
                name = createRandomString(),
            ),
        )

    private fun createAndSaveTag(
        name: String,
        user: User,
    ): Tag =
        tagRepository.saveTag(
            Tag(
                id = randomUUID(),
                author = user,
                name = name,
            ),
        )

    private fun createAndSaveBoard(
        name: String,
        user: User,
    ): Board {
        val board = Board(
            id = randomUUID(),
            author = user,
            name = name,
            description = "",
        )
        database.save(board.toModel())
        return board
    }

    private fun softDeleteBoardModel(board: Board) {
        val model = database.find(BoardModel::class.java, board.id)!!
        model.softDeletedAt = Instant.now()
        database.save(model)
    }

    private fun restoreBoardModel(board: Board) {
        val model = database.find(BoardModel::class.java, board.id)!!
        model.softDeletedAt = null
        database.save(model)
    }

    private fun createPin(): Pin =
        Pin(
            id = randomUUID(),
            author = createAndSaveUser(),
            sourceContextUrl = "https://example.com",
            sourceMediaUrl = "https://example.com/image.jpeg",
            description = "Something",
            tags = emptyList(),
            boards = emptyList(),
        )

    private fun createPinWithTags(vararg tags: Tag): Pin =
        createPin()
            .copy(tags = tags.toList())

    private fun createPinWithBoards(vararg boards: Board): Pin =
        createPin()
            .copy(boards = boards.toList())

    @Test
    fun `When saving a new pin, then should create it`() {
        // Given
        val pin = createPin()

        // When
        repository.savePin(pin)

        // Then
        val model = database.find(PinModel::class.java, pin.id)
        assertNotNull(model)
        assertEquals(pin.id, model!!.id)
        assertEquals(pin.author.id, model.author.id)
        assertEquals(pin.sourceContextUrl, model.sourceContextUrl)
        assertEquals(pin.sourceMediaUrl, model.sourceMediaUrl)
        assertEquals(pin.description, model.description)
    }

    @Test
    fun `When saving an existing pin, then should update it`() {
        // Given
        val pin = createPin()
        repository.savePin(pin)
        val updatedPin =
            pin.copy(
                sourceContextUrl = "https://new-example.com/new.jpeg",
                sourceMediaUrl = "https://new-example.com/new_image.jpeg",
                description = "New description",
            )

        // When
        repository.savePin(updatedPin)

        // Then
        val model = database.find(PinModel::class.java, pin.id)
        assertNotNull(model)
        assertEquals(pin.id, model!!.id)
        assertEquals(updatedPin.sourceContextUrl, model.sourceContextUrl)
        assertEquals(updatedPin.sourceMediaUrl, model.sourceMediaUrl)
        assertEquals(updatedPin.description, model.description)
    }

    @Test
    fun `When getting a pin, then should return it`() {
        // Given
        val pin = createPin()
        repository.savePin(pin)

        // When
        val actual = repository.findPinById(pin.id)

        // Then
        // createdAt/updatedAt are populated on read but absent on the never-persisted `pin`
        // reference (it was constructed in memory and never reassigned from savePin's return),
        // so they are normalized away rather than compared.
        assertNotNull(actual)
        assertEquals(pin, actual!!.copy(createdAt = null, updatedAt = null))
    }

    @Test
    fun `When getting a nonexistent pin, then should return null`() {
        // Given
        // When
        val actual = repository.findPinById(randomUUID())

        // Then
        assertNull(actual)
    }

    @Test
    fun `When changing a pin's tag, then should properly update them`() {
        // Given
        val user = createAndSaveUser()
        val tag1 = createAndSaveTag(name = "tag1", user = user)
        val tag2 = createAndSaveTag(name = "tag2", user = user)
        val tag3 = createAndSaveTag(name = "tag3", user = user)
        val pin = createPinWithTags(tag1, tag2)
        repository.savePin(pin)
        val updatedPin = pin.copy(tags = listOf(tag2, tag3))

        // When
        repository.savePin(updatedPin)

        // Then
        val actual = repository.findPinById(pin.id)
        assertNotNull(actual)
        assertEquals(setOf(tag2, tag3), actual!!.tags.toSet())
    }

    // --- Board membership tests ---

    @Test
    fun `Given a pin saved with two boards, Then findPinById returns both active boards`() {
        // Given
        val user = createAndSaveUser()
        val board1 = createAndSaveBoard(name = "Travel", user = user)
        val board2 = createAndSaveBoard(name = "Food", user = user)
        val pin = createPinWithBoards(board1, board2)

        // When
        repository.savePin(pin)
        val loaded = repository.findPinById(pin.id)

        // Then
        assertNotNull(loaded)
        assertEquals(setOf(board1.id, board2.id), loaded!!.boards.map { it.id }.toSet())
    }

    @Test
    fun `Given a pin whose board is soft-deleted, Then that board is excluded from the pin's boards`() {
        // Given
        val user = createAndSaveUser()
        val active = createAndSaveBoard(name = "Active", user = user)
        val recycled = createAndSaveBoard(name = "Recycled", user = user)
        val pin = createPinWithBoards(active, recycled)
        repository.savePin(pin)
        softDeleteBoardModel(recycled)

        // When
        val loaded = repository.findPinById(pin.id)

        // Then
        assertNotNull(loaded)
        assertEquals(listOf(active.id), loaded!!.boards.map { it.id })
    }

    @Test
    fun `Given a recycled-board membership, When the pin is re-saved, Then the join row is preserved`() {
        // Given
        val user = createAndSaveUser()
        val active = createAndSaveBoard(name = "Active", user = user)
        val recycled = createAndSaveBoard(name = "Recycled", user = user)
        val pin = createPinWithBoards(active, recycled)
        repository.savePin(pin)
        softDeleteBoardModel(recycled)
        // The reloaded pin only exposes the active board (mirrors getBoardsForPin's active-only load).
        val reloaded = repository.findPinById(pin.id)!!

        // When - re-saving the pin (as PinTagger.setTags does) must not touch the recycled join row
        repository.savePin(reloaded)

        // Then - restoring the board shows the pin is still joined to it
        restoreBoardModel(recycled)
        val afterRestore = repository.findPinById(pin.id)!!
        assertEquals(setOf(active.id, recycled.id), afterRestore.boards.map { it.id }.toSet())
    }

    @Test
    fun `When changing a pin's boards, then should properly update them`() {
        // Given
        val user = createAndSaveUser()
        val board1 = createAndSaveBoard(name = "board1", user = user)
        val board2 = createAndSaveBoard(name = "board2", user = user)
        val board3 = createAndSaveBoard(name = "board3", user = user)
        val pin = createPinWithBoards(board1, board2)
        repository.savePin(pin)
        val updatedPin = pin.copy(boards = listOf(board2, board3))

        // When
        repository.savePin(updatedPin)

        // Then
        // Compared by id only: `board2`/`board3` come from this file's own `createAndSaveBoard`
        // helper, which saves the model directly and returns the original in-memory Board (its
        // createdAt/updatedAt stay null), unlike `actual`'s boards which are freshly read.
        val actual = repository.findPinById(pin.id)
        assertNotNull(actual)
        assertEquals(setOf(board2.id, board3.id), actual!!.boards.map { it.id }.toSet())
    }

    // --- Soft delete tests ---

    private fun createAndSavePin(author: User): Pin {
        val pin = Pin(
            id = randomUUID(),
            author = author,
            sourceContextUrl = "https://example.com",
            sourceMediaUrl = "https://example.com/image.jpeg",
            description = "Something",
            tags = emptyList(),
            boards = emptyList(),
        )
        return repository.savePin(pin)
    }

    @Test
    fun `Given soft-deleted pin, Then findPinsForUser excludes it`() {
        // Given
        val user = createAndSaveUser()
        val pin = createAndSavePin(user)
        repository.softDeletePin(pin)

        // When
        val page = repository.findPinsForUser(
            reader = user,
            cursor = null,
            pageSize = 10,
            sortStrategy = PinSortStrategy.CREATED_AT_ASC,
        )

        // Then
        assertTrue(page.items.isEmpty())
    }

    @Test
    fun `Given soft-deleted pin, Then findAllPinsForUser excludes it`() {
        // Given
        val user = createAndSaveUser()
        val pin = createAndSavePin(user)
        repository.softDeletePin(pin)

        // When
        val pins = repository.findAllPinsForUser(user)

        // Then
        assertTrue(pins.isEmpty())
    }

    @Test
    fun `Given soft-deleted pin, Then findAllSoftDeletedPinsForUser includes it`() {
        // Given
        val user = createAndSaveUser()
        createAndSavePin(user)
        val softDeletedPin = createAndSavePin(user)
        repository.softDeletePin(softDeletedPin)

        // When
        val pins = repository.findAllSoftDeletedPinsForUser(user)

        // Then
        assertEquals(1, pins.size)
        assertEquals(softDeletedPin.id, pins[0].id)
    }

    @Test
    fun `Given no soft-deleted pins, Then findAllSoftDeletedPinsForUser returns an empty list`() {
        // Given
        val user = createAndSaveUser()
        createAndSavePin(user)

        // When
        val pins = repository.findAllSoftDeletedPinsForUser(user)

        // Then
        assertTrue(pins.isEmpty())
    }

    @Test
    fun `Given another user's soft-deleted pin, Then findAllSoftDeletedPinsForUser excludes it`() {
        // Given
        val userA = createAndSaveUser()
        val userB = createAndSaveUser()
        val userASoftDeletedPin = createAndSavePin(userA)
        repository.softDeletePin(userASoftDeletedPin)
        val userBSoftDeletedPin = createAndSavePin(userB)
        repository.softDeletePin(userBSoftDeletedPin)

        // When
        val pins = repository.findAllSoftDeletedPinsForUser(userA)

        // Then
        assertEquals(1, pins.size)
        assertEquals(userASoftDeletedPin.id, pins[0].id)
    }

    @Test
    fun `Given soft-deleted pin, Then findSoftDeletedPinsForUser includes it`() {
        // Given
        val user = createAndSaveUser()
        val pin = createAndSavePin(user)
        repository.softDeletePin(pin)

        // When
        val page = repository.findSoftDeletedPinsForUser(
            reader = user,
            cursor = null,
            pageSize = 10,
            sortStrategy = PinSortStrategy.CREATED_AT_ASC,
        )

        // Then
        assertEquals(1, page.items.size)
        assertEquals(pin.id, page.items[0].id)
    }

    @Test
    fun `Given pin, Then softDeletePin sets softDeletedAt`() {
        // Given
        val user = createAndSaveUser()
        val pin = createAndSavePin(user)

        // When
        val result = repository.softDeletePin(pin)

        // Then
        assertNotNull(result.softDeletedAt)
    }

    @Test
    fun `Given soft-deleted pin, Then restorePin clears softDeletedAt`() {
        // Given
        val user = createAndSaveUser()
        val pin = createAndSavePin(user)
        val softDeleted = repository.softDeletePin(pin)

        // When
        val result = repository.restorePin(softDeleted)

        // Then
        assertNull(result.softDeletedAt)
    }

    @Test
    fun `Given soft-deleted pin, Then permanentlyDeletePin removes it and its tag associations`() {
        // Given
        val user = createAndSaveUser()
        val tag = createAndSaveTag(name = "tag1", user = user)
        val pin = createPinWithTags(tag).copy(author = user)
        repository.savePin(pin)
        val softDeleted = repository.softDeletePin(pin)

        // When
        repository.permanentlyDeletePin(softDeleted)

        // Then
        assertNull(repository.findPinById(pin.id))
    }

    @Test
    fun `Given soft-deleted pin, Then permanentlyDeletePin removes it and its board associations`() {
        // Given
        val user = createAndSaveUser()
        val board = createAndSaveBoard(name = "board1", user = user)
        val pin = createPinWithBoards(board).copy(author = user)
        repository.savePin(pin)
        val softDeleted = repository.softDeletePin(pin)

        // When
        // If the pin_board_model row were not deleted first, this would fail with a foreign
        // key constraint violation (pin_board_model.pin_id references pins on delete restrict).
        repository.permanentlyDeletePin(softDeleted)

        // Then
        assertNull(repository.findPinById(pin.id))
    }

    @Test
    fun `Given multiple soft-deleted pins, Then permanentlyDeleteAllSoftDeletedPinsForUser removes all`() {
        // Given
        val user = createAndSaveUser()
        val pin1 = createAndSavePin(user)
        val pin2 = createAndSavePin(user)
        val activePin = createAndSavePin(user)
        repository.softDeletePin(pin1)
        repository.softDeletePin(pin2)

        // When
        repository.permanentlyDeleteAllSoftDeletedPinsForUser(user)

        // Then
        assertNull(repository.findPinById(pin1.id))
        assertNull(repository.findPinById(pin2.id))
        assertNotNull(repository.findPinById(activePin.id))
    }

    @Test
    fun `Given multiple soft-deleted pins with boards, Then permanentlyDeleteAllSoftDeletedPinsForUser removes them`() {
        // Given
        val user = createAndSaveUser()
        val board = createAndSaveBoard(name = "board1", user = user)
        val pin1 = repository.savePin(createPinWithBoards(board).copy(author = user))
        val pin2 = repository.savePin(createPinWithBoards(board).copy(author = user))
        repository.softDeletePin(pin1)
        repository.softDeletePin(pin2)

        // When
        // If the pin_board_model rows were not deleted first, this would fail with a foreign
        // key constraint violation (pin_board_model.pin_id references pins on delete restrict).
        repository.permanentlyDeleteAllSoftDeletedPinsForUser(user)

        // Then
        assertNull(repository.findPinById(pin1.id))
        assertNull(repository.findPinById(pin2.id))
    }

    @Test
    fun `Given no soft-deleted pins, Then permanentlyDeleteAllSoftDeletedPinsForUser is a no-op`() {
        // Given
        val user = createAndSaveUser()
        val activePin = createAndSavePin(user)

        // When
        repository.permanentlyDeleteAllSoftDeletedPinsForUser(user)

        // Then
        assertNotNull(repository.findPinById(activePin.id))
    }

    @Test
    fun `Given active and soft-deleted pins, Then permanentlyDeleteAllPinsForUser removes all`() {
        // Given
        val user = createAndSaveUser()
        val tag = createAndSaveTag(name = "tag1", user = user)
        val board = createAndSaveBoard(name = "board1", user = user)
        val activePin = repository.savePin(createPinWithTags(tag).copy(author = user, boards = listOf(board)))
        val toSoftDelete = createAndSavePin(user)
        repository.softDeletePin(toSoftDelete)

        // When
        // If the pin_tag_model / pin_board_model rows were not deleted first, this would fail
        // with a foreign key constraint violation (references pins on delete restrict).
        repository.permanentlyDeleteAllPinsForUser(user)

        // Then
        assertEquals(emptyList<Pin>(), repository.findAllPinsForUser(user))
        assertEquals(emptyList<Pin>(), repository.findAllSoftDeletedPinsForUser(user))
        assertNull(repository.findPinById(activePin.id))
        assertNull(repository.findPinById(toSoftDelete.id))
    }

    @Test
    fun `Given no pins for the user, Then permanentlyDeleteAllPinsForUser is a no-op`() {
        // Given
        val user = createAndSaveUser()

        // When
        repository.permanentlyDeleteAllPinsForUser(user)

        // Then
        assertEquals(emptyList<Pin>(), repository.findAllPinsForUser(user))
        assertEquals(emptyList<Pin>(), repository.findAllSoftDeletedPinsForUser(user))
    }

    @Test
    fun `Given active and soft-deleted pins, Then findAllPinIdsForUser returns all their ids`() {
        // Given
        val user = createAndSaveUser()
        val activePin = createAndSavePin(user)
        val softDeletedPin = repository.softDeletePin(createAndSavePin(user))

        // When
        val pinIds = repository.findAllPinIdsForUser(user)

        // Then
        assertEquals(setOf(activePin.id, softDeletedPin.id), pinIds.toSet())
    }

    // --- Pagination cursor resolution ---

    @Test
    fun `Given a cursor pointing to an existing pin, Then findPinsForUser resumes from it`() {
        // Given
        val user = createAndSaveUser()
        val firstPin = createAndSavePin(user)
        val secondPin = createAndSavePin(user)
        forceCreationInstants("pins", listOf(firstPin.id, secondPin.id))
        val cursor = Cursor(pivotId = firstPin.id, direction = CursorDirection.FORWARD)

        // When
        val page =
            repository.findPinsForUser(
                reader = user,
                cursor = cursor,
                pageSize = 10,
                sortStrategy = PinSortStrategy.CREATED_AT_ASC,
            )

        // Then
        assertTrue(page.items.none { it.id == firstPin.id })
        assertNotNull(page.items.find { it.id == secondPin.id })
    }

    @Test
    fun `Given a cursor pointing to a nonexistent pin, Then findPinsForUser treats it as the first page`() {
        // Given
        val user = createAndSaveUser()
        val pin = createAndSavePin(user)
        val cursor = Cursor(pivotId = randomUUID(), direction = CursorDirection.FORWARD)

        // When
        val page =
            repository.findPinsForUser(
                reader = user,
                cursor = cursor,
                pageSize = 10,
                sortStrategy = PinSortStrategy.CREATED_AT_ASC,
            )

        // Then
        assertEquals(1, page.items.size)
        assertEquals(pin.id, page.items[0].id)
    }

    @Test
    fun `Given a cursor pointing to an existing soft-deleted pin, Then findSoftDeletedPinsForUser resumes from it`() {
        // Given
        val user = createAndSaveUser()
        val firstPin = repository.softDeletePin(createAndSavePin(user))
        val secondPin = repository.softDeletePin(createAndSavePin(user))
        forceCreationInstants("pins", listOf(firstPin.id, secondPin.id))
        val cursor = Cursor(pivotId = firstPin.id, direction = CursorDirection.FORWARD)

        // When
        val page =
            repository.findSoftDeletedPinsForUser(
                reader = user,
                cursor = cursor,
                pageSize = 10,
                sortStrategy = PinSortStrategy.CREATED_AT_ASC,
            )

        // Then
        assertTrue(page.items.none { it.id == firstPin.id })
        assertNotNull(page.items.find { it.id == secondPin.id })
    }

    @Test
    fun `Given a cursor pointing to a nonexistent pin, Then findSoftDeletedPinsForUser treats it as the first page`() {
        // Given
        val user = createAndSaveUser()
        val pin = repository.softDeletePin(createAndSavePin(user))
        val cursor = Cursor(pivotId = randomUUID(), direction = CursorDirection.FORWARD)

        // When
        val page =
            repository.findSoftDeletedPinsForUser(
                reader = user,
                cursor = cursor,
                pageSize = 10,
                sortStrategy = PinSortStrategy.CREATED_AT_ASC,
            )

        // Then
        assertEquals(1, page.items.size)
        assertEquals(pin.id, page.items[0].id)
    }

    @Test
    fun `Given many pins, Then findPinsForUser exposes both cursors`() {
        // Given
        val user = createAndSaveUser()
        repeat(3) { createAndSavePin(user) }

        // When
        val page =
            repository.findPinsForUser(
                reader = user,
                cursor = null,
                pageSize = 2,
                sortStrategy = PinSortStrategy.CREATED_AT_ASC,
            )

        // Then
        assertEquals(2, page.items.size)
        assertNotNull(page.nextCursor)
        assertNotNull(page.previousCursor)
    }

    @Test
    fun `Given many soft-deleted pins, Then findSoftDeletedPinsForUser exposes both cursors`() {
        // Given
        val user = createAndSaveUser()
        repeat(3) { repository.softDeletePin(createAndSavePin(user)) }

        // When
        val page =
            repository.findSoftDeletedPinsForUser(
                reader = user,
                cursor = null,
                pageSize = 2,
                sortStrategy = PinSortStrategy.CREATED_AT_ASC,
            )

        // Then
        assertEquals(2, page.items.size)
        assertNotNull(page.nextCursor)
        assertNotNull(page.previousCursor)
    }

    @Test
    fun `Given no soft-deleted pins, Then findSoftDeletedPinsForUser returns an empty page with no cursors`() {
        // Given
        val user = createAndSaveUser()
        createAndSavePin(user)

        // When
        val page =
            repository.findSoftDeletedPinsForUser(
                reader = user,
                cursor = null,
                pageSize = 10,
                sortStrategy = PinSortStrategy.CREATED_AT_ASC,
            )

        // Then
        assertTrue(page.items.isEmpty())
        assertNull(page.nextCursor)
        assertNull(page.previousCursor)
    }

    // --- findActivePinsForBoard ---

    @Test
    fun `Given pins in and out of a board, Then findActivePinsForBoard returns only the board's pins`() {
        // Given
        val user = createAndSaveUser()
        val board = createAndSaveBoard(name = "board1", user = user)
        val otherBoard = createAndSaveBoard(name = "board2", user = user)
        val inBoard = repository.savePin(createPinWithBoards(board).copy(author = user))
        repository.savePin(createPinWithBoards(otherBoard).copy(author = user))

        // When
        val page = repository.findActivePinsForBoard(
            reader = user,
            boardId = board.id,
            cursor = null,
            pageSize = 10,
            sortStrategy = PinSortStrategy.CREATED_AT_ASC,
        )

        // Then
        assertEquals(listOf(inBoard.id), page.items.map { it.id })
    }

    @Test
    fun `Given a soft-deleted pin in a board, Then findActivePinsForBoard excludes it`() {
        // Given
        val user = createAndSaveUser()
        val board = createAndSaveBoard(name = "board1", user = user)
        val pin = repository.savePin(createPinWithBoards(board).copy(author = user))
        repository.softDeletePin(pin)

        // When
        val page = repository.findActivePinsForBoard(
            reader = user,
            boardId = board.id,
            cursor = null,
            pageSize = 10,
            sortStrategy = PinSortStrategy.CREATED_AT_ASC,
        )

        // Then
        assertTrue(page.items.isEmpty())
    }

    @Test
    fun `Given another user's pin in the board, Then findActivePinsForBoard excludes it`() {
        // Given
        val owner = createAndSaveUser()
        val otherUser = createAndSaveUser()
        val board = createAndSaveBoard(name = "board1", user = owner)
        repository.savePin(createPinWithBoards(board).copy(author = owner))

        // When
        val page = repository.findActivePinsForBoard(
            reader = otherUser,
            boardId = board.id,
            cursor = null,
            pageSize = 10,
            sortStrategy = PinSortStrategy.CREATED_AT_ASC,
        )

        // Then
        assertTrue(page.items.isEmpty())
    }

    @Test
    fun `Given a cursor pointing to an existing pin in a board, Then findActivePinsForBoard resumes from it`() {
        // Given
        val user = createAndSaveUser()
        val board = createAndSaveBoard(name = "board1", user = user)
        val firstPin = repository.savePin(createPinWithBoards(board).copy(author = user))
        val secondPin = repository.savePin(createPinWithBoards(board).copy(author = user))
        forceCreationInstants("pins", listOf(firstPin.id, secondPin.id))
        val cursor = Cursor(pivotId = firstPin.id, direction = CursorDirection.FORWARD)

        // When
        val page = repository.findActivePinsForBoard(
            reader = user,
            boardId = board.id,
            cursor = cursor,
            pageSize = 10,
            sortStrategy = PinSortStrategy.CREATED_AT_ASC,
        )

        // Then
        assertTrue(page.items.none { it.id == firstPin.id })
        assertNotNull(page.items.find { it.id == secondPin.id })
    }

    @Test
    fun `Given a cursor pointing to a nonexistent pin, Then findActivePinsForBoard treats it as the first page`() {
        // Given
        val user = createAndSaveUser()
        val board = createAndSaveBoard(name = "board1", user = user)
        val pin = repository.savePin(createPinWithBoards(board).copy(author = user))
        val cursor = Cursor(pivotId = randomUUID(), direction = CursorDirection.FORWARD)

        // When
        val page = repository.findActivePinsForBoard(
            reader = user,
            boardId = board.id,
            cursor = cursor,
            pageSize = 10,
            sortStrategy = PinSortStrategy.CREATED_AT_ASC,
        )

        // Then
        assertEquals(1, page.items.size)
        assertEquals(pin.id, page.items[0].id)
    }

    // --- Creation timestamps ---

    @Test
    fun `Given a saved pin, Then reading it back exposes its timestamps`() {
        // Given
        val user = createAndSaveUser()
        val pin = createAndSavePin(user)

        // When
        val found = repository.findPinById(pin.id)

        // Then
        assertNotNull(found?.createdAt)
        assertNotNull(found?.updatedAt)
    }

    @Test
    fun `Given many pins in a board, Then findActivePinsForBoard exposes both cursors`() {
        // Given
        val user = createAndSaveUser()
        val board = createAndSaveBoard(name = "board1", user = user)
        repeat(3) { repository.savePin(createPinWithBoards(board).copy(author = user)) }

        // When
        val page = repository.findActivePinsForBoard(
            reader = user,
            boardId = board.id,
            cursor = null,
            pageSize = 2,
            sortStrategy = PinSortStrategy.CREATED_AT_ASC,
        )

        // Then
        assertEquals(2, page.items.size)
        assertNotNull(page.nextCursor)
        assertNotNull(page.previousCursor)
    }
}
