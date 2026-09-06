package fr.geoffreyCoulaud.pinryReborn.api.usecases.exports

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.UserDataExport
import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.UserDataExportState
import fr.geoffreyCoulaud.pinryReborn.api.domain.exports.ArchiveFormat
import fr.geoffreyCoulaud.pinryReborn.api.domain.exports.ArchiveSink
import fr.geoffreyCoulaud.pinryReborn.api.domain.exports.ExportArchiveStore
import fr.geoffreyCoulaud.pinryReborn.api.domain.storage.StagedFile
import io.mockk.every
import java.io.InputStream
import java.time.Instant

/**
 * A fake [ExportArchiveStore] holding what the disk holds, so a criterion about the canonical key is
 * asserted as state rather than as `verify(exactly = 0)` on the call the test itself configured.
 */
internal class FakeExportArchiveStore(
    override val format: ArchiveFormat = ArchiveFormat("application/zip", "zip"),
    /** What the next staging answers with: a var, so two attempts of one build hold distinct handles. */
    var staged: StagedFile = StagedFile(path = "tmp/staged.zip", byteSize = 1L, contentHash = "staged"),
    /** The open transaction a promote ran in, so the fence and its effect are read as one number. */
    private val transactionOf: () -> Int? = { null },
) : ExportArchiveStore {
    /** The canonical keys and the bytes each holds: what a losing attempt must never overwrite. */
    val promoted = linkedMapOf<String, StagedFile>()
    val discarded = mutableListOf<StagedFile>()
    val deleted = mutableListOf<String>()
    val promotedInTransactions = mutableListOf<Int?>()
    val sink = RecordingSink()

    /** What a promote does before it lands, so a case drives the disk failure the net must cover. */
    var beforePromote: () -> Unit = {}

    /** What lands once this attempt has staged, which is where a rival's whole transaction fits. */
    var afterStage: () -> Unit = {}

    /** What a discard does before it lands, so a case drives the unlink the net must survive. */
    var beforeDiscard: () -> Unit = {}

    // Named apart from the mock sibling's stageCalls, which counts the mock: a rival keyed on the
    // wrong one never arrives, and the case goes green without testing the race.
    /** How far a build got, which is what a racing actor lands on rather than a call ordinal. */
    var stagings = 0
        private set

    override fun hasFreeSpace(requiredBytes: Long): Boolean = true

    override fun stage(block: (ArchiveSink) -> Unit): StagedFile {
        stagings++
        block(sink)
        afterStage()
        return staged
    }

    override fun promote(staged: StagedFile, storageKey: String) {
        beforePromote()
        promotedInTransactions += transactionOf()
        promoted[storageKey] = staged
    }

    override fun openStream(storageKey: String, skipBytes: Long): InputStream =
        error("a build never reads an archive back")

    override fun delete(storageKey: String) {
        deleted += storageKey
        promoted.remove(storageKey)
    }

    override fun discard(staged: StagedFile) {
        beforeDiscard()
        discarded += staged
    }

    override fun discardOrphanedStagedFiles(olderThan: Instant): Int = 0

    override fun forEachStorageKeyOnDisk(block: (Sequence<String>) -> Unit) = block(promoted.keys.asSequence())
}

/**
 * The fixtures over [FakeExportArchiveStore], for the cases whose criterion is what the disk holds
 * afterwards. Never a mock store here: [UserDataExportMockStoreFixtures] holds that one.
 */
@Suppress("AbstractClassCanBeConcreteClass") // Abstract by intent: a fixture base, as the import suite has.
internal abstract class UserDataExportFakeStoreFixtures : UserDataExportFixtures() {
    /** The store as a fake, for the cases whose criterion is what the disk holds afterwards. */
    protected val fakeArchiveStore = FakeExportArchiveStore(
        staged = stagedFile,
        transactionOf = { transactions.current },
    )

    /** The builder over the fake store, so a case reads the disk instead of a store's calls. */
    protected val fakeStoreBuilder = builderOver(fakeArchiveStore)

    /**
     * The other attempt of the same build, whole, between this one's staging and its completion. A
     * rival landing inside the fence's own read instead is overwritten by a promote placed before it.
     */
    protected fun rivalPublishes(rivalStaged: StagedFile) {
        fakeArchiveStore.afterStage = {
            fakeArchiveStore.promote(rivalStaged, storageKey)
            val row = requireNotNull(stored()) { "the rival publishes over this attempt's own stamped row" }
            seedRow(
                row.copy(
                    state = UserDataExportState.READY, storageKey = storageKey,
                    byteSize = rivalStaged.byteSize, sha256 = rivalStaged.contentHash,
                ),
            )
        }
    }

    /**
     * The whole path for [fakeStoreBuilder]: the fake answers the free space, the format and the
     * staging itself, so no mock store is stubbed and `checkUnnecessaryStub` stays satisfied.
     */
    protected fun stubFakeStoreBuild(row: UserDataExport = anExport()) {
        stubRow(row)
        stubRowWrites()
        every { userRepository.findUserById(userId) } returns user
        every { clock.now() } returns now
        stubEmptyCollections()
    }
}
