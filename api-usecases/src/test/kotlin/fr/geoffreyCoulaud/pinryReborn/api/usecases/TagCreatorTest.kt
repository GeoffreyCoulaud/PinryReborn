package fr.geoffreyCoulaud.pinryReborn.api.usecases

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Tag
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.User
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.TagRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.time.Clock
import fr.geoffreyCoulaud.pinryReborn.api.utilities.TestTime
import fr.geoffreyCoulaud.pinryReborn.api.utilities.createRandomString
import io.mockk.every
import io.mockk.mockk
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import java.util.UUID.randomUUID

class TagCreatorTest {
    private val tagRepository = mockk<TagRepositoryInterface>()
    private val clockInstant = Instant.parse("2026-07-23T10:00:00Z")
    private val clock = mockk<Clock> { every { now() } returns clockInstant }
    private val useCase = TagCreator(tagRepository = tagRepository, clock = clock)

    @Test
    fun `When creating a new tag, should succeed`() {
        // Given
        val user = User(id = randomUUID(), name = "John Doe", createdAt = TestTime.now)
        val tagString = createRandomString()
        every { tagRepository.findUserTagByName(user, tagString) } returns null
        every { tagRepository.saveTag(any()) } answers { firstArg() }

        // When, Then
        assertDoesNotThrow {
            useCase.findOrCreate(name = tagString, user = user)
        }
    }

    @Test
    fun `When trying to re-create an existing tag, should return the existing tag`() {
        // Given
        val user = User(id = randomUUID(), name = "John Doe", createdAt = TestTime.now)
        val tagString = createRandomString()
        val tag = Tag(id = randomUUID(), name = tagString, author = user, createdAt = TestTime.now)
        every { tagRepository.findUserTagByName(user = user, name = tagString) } returns tag

        // When
        val result = useCase.findOrCreate(user = user, name = tagString)

        // Then
        assertEquals(result, tag)
    }

    @Test
    fun `Given two concurrent taggings of one new name, Then one tag exists and neither caller fails`() {
        // Given: a store that folds and refuses like ix_tags_author_name_nocase, and a rendezvous that
        // forces both readers to miss before either writes.
        val user = User(id = randomUUID(), name = "John Doe", createdAt = TestTime.now)
        val name = createRandomString()
        val bothRead = CountDownLatch(2)
        val repository = IndexedTagRepository(bothRead)
        val racingUseCase = TagCreator(tagRepository = repository, clock = FixedClock(clockInstant))
        val failures = ConcurrentLinkedQueue<Throwable>()
        val taggings =
            List(2) {
                Thread { racingUseCase.findOrCreate(name = name, user = user) }
                    .apply { setUncaughtExceptionHandler { _, error -> failures.add(error) } }
            }

        // When
        taggings.forEach { it.start() }
        taggings.forEach { it.join() }

        // Then
        assertTrue(failures.isEmpty()) { "Expected no failure from a concurrent tagging, got: $failures" }
        assertEquals(1, repository.rowCount)
    }

    /** A clock a second thread can read: MockK's stubs are not what this test is measuring. */
    private class FixedClock(private val instant: Instant) : Clock {
        override fun now(): Instant = instant
    }

    /**
     * Stands in for `ix_tags_author_name_nocase`: one row per author and folded name, a second insert
     * refused the way the untranslated violation reaches a client, as a 500.
     *
     * [bothRead] is what makes the race deterministic rather than sampled: each read announces itself
     * and waits for the other, so both miss before either writes. The wait is bounded, so a pair the
     * implementation serialises proceeds on the timeout instead of deadlocking.
     */
    private class IndexedTagRepository(private val bothRead: CountDownLatch) : TagRepositoryInterface {
        private val rows = ConcurrentHashMap<String, Tag>()

        val rowCount: Int get() = rows.size

        override fun findUserTagByName(user: User, name: String): Tag? {
            val found = rows[foldedKey(user, name)]
            bothRead.countDown()
            bothRead.await(RENDEZVOUS_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
            return found
        }

        override fun saveTag(tag: Tag): Tag {
            val existing = rows.putIfAbsent(foldedKey(tag.author, tag.name), tag)
            check(existing == null) { "[SQLITE_CONSTRAINT_UNIQUE] UNIQUE constraint failed: tags.author_id, tags.name" }
            return tag
        }

        override fun findAllTagsForUser(user: User): List<Tag> = error("A tagging lists no tag")

        override fun deleteAllTagsForUser(user: User) = error("A tagging deletes no tag")

        private fun foldedKey(user: User, name: String): String = "${user.id}:${name.lowercase()}"
    }

    private companion object {
        // Generous, and never paid once the pair is serialised: the thread held out of the read
        // releases the rendezvous itself.
        const val RENDEZVOUS_TIMEOUT_MILLIS = 2_000L
    }
}
