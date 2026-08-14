package fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Board
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Pin
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Tag
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.User
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.mappers.BoardModelMapper.toModel
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.models.BoardModel
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.repositories.PinRepository
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.repositories.TagRepository
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.repositories.UserRepository
import fr.geoffreyCoulaud.pinryReborn.api.utilities.createRandomString
import java.time.Instant
import java.util.UUID.randomUUID

/**
 * The fixtures the `PinRepository` suite shares, declared once instead of copied into each slice.
 *
 * The suite is split across [PinRepositoryTest], [PinRepositorySoftDeleteTest],
 * [PinRepositoryPaginationTest], [PinRepositoryRecycledMembershipTest] and
 * [PinRepositoryContentHashTest] to keep every class under detekt's `LargeClass` threshold.
 */
@Suppress("AbstractClassCanBeConcreteClass") // Abstract by intent: a fixture base for the slices above.
abstract class PinRepositoryFixtures : RepositoryTest() {
    protected val repository = PinRepository(persistor)
    private val userRepository = UserRepository(persistor)
    private val tagRepository = TagRepository(persistor)

    // Cursor pagination breaks ties on the id, which is random, so a test asserting a deterministic
    // order stamps the pins itself rather than hoping two saves land on different milliseconds.
    protected val firstInstant: Instant = Instant.parse("2026-01-01T00:00:00Z")
    protected val secondInstant: Instant = firstInstant.plusSeconds(1)

    protected fun createAndSaveUser(): User =
        userRepository.saveUser(
            User(
                id = randomUUID(),
                name = createRandomString(),
                createdAt = storableNow(),
            ),
        )

    protected fun createAndSaveTag(
        name: String,
        user: User,
    ): Tag =
        tagRepository.saveTag(
            Tag(
                id = randomUUID(),
                author = user,
                name = name,
                createdAt = storableNow(),
            ),
        )

    protected fun createAndSaveBoard(
        user: User,
        name: String = createRandomString(),
    ): Board {
        val board = Board(
            id = randomUUID(),
            author = user,
            name = name,
            description = "",
            createdAt = storableNow(),
            updatedAt = storableNow(),
        )
        database.save(board.toModel())
        return board
    }

    protected fun softDeleteBoardModel(board: Board) {
        val model = database.find(BoardModel::class.java, board.id)!!
        model.softDeletedAt = storableNow()
        database.save(model)
    }

    protected fun restoreBoardModel(board: Board) {
        val model = database.find(BoardModel::class.java, board.id)!!
        model.softDeletedAt = null
        database.save(model)
    }

    protected fun createPin(): Pin =
        Pin(
            id = randomUUID(),
            author = createAndSaveUser(),
            sourceContextUrl = "https://example.com",
            sourceMediaUrl = "https://example.com/image.jpeg",
            description = "Something",
            tags = emptyList(),
            boards = emptyList(),
            createdAt = storableNow(),
            updatedAt = storableNow(),
        )

    protected fun createPinWithTags(vararg tags: Tag): Pin =
        createPin()
            .copy(tags = tags.toList())

    protected fun createPinWithBoards(vararg boards: Board): Pin =
        createPin()
            .copy(boards = boards.toList())

    protected fun createAndSavePin(
        author: User,
        createdAt: Instant = storableNow(),
    ): Pin {
        val pin = Pin(
            id = randomUUID(),
            author = author,
            sourceContextUrl = "https://example.com",
            sourceMediaUrl = "https://example.com/image.jpeg",
            description = "Something",
            tags = emptyList(),
            boards = emptyList(),
            createdAt = createdAt,
            updatedAt = createdAt,
        )
        return repository.savePin(pin)
    }
}
