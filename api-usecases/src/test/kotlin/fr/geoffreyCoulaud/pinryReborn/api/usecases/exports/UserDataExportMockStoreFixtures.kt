package fr.geoffreyCoulaud.pinryReborn.api.usecases.exports

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.UserDataExport
import fr.geoffreyCoulaud.pinryReborn.api.domain.exports.ArchiveFormat
import fr.geoffreyCoulaud.pinryReborn.api.domain.exports.ArchiveSink
import fr.geoffreyCoulaud.pinryReborn.api.domain.exports.ExportArchiveStore
import fr.geoffreyCoulaud.pinryReborn.api.domain.storage.StagedFile
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs

/**
 * The fixtures over the archive store as a mock, for the cases whose window opens before the
 * completion. Never the fake store here: [UserDataExportFakeStoreFixtures] holds that one.
 */
@Suppress("AbstractClassCanBeConcreteClass") // Abstract by intent: a fixture base, as the import suite has.
internal abstract class UserDataExportMockStoreFixtures : UserDataExportFixtures() {
    protected val archiveStore = mockk<ExportArchiveStore>()

    protected val builder = builderOver(archiveStore)

    protected lateinit var sink: RecordingSink

    /** How far the build got, which is what a racing actor lands on rather than a call ordinal. */
    protected var stageCalls = 0

    protected fun stubArchiveStore() {
        sink = RecordingSink()
        every { archiveStore.stage(any()) } answers {
            stageCalls++
            firstArg<(ArchiveSink) -> Unit>().invoke(sink)
            StagedFile(path = "tmp/staged.zip", byteSize = stagedByteSize, contentHash = stagedHash)
        }
    }

    /** A staging that fails once the build has committed to it, which is the window site 2 answers for. */
    protected fun stubFailingStage() {
        every { archiveStore.stage(any()) } answers {
            stageCalls++
            error("the archive could not be staged")
        }
    }

    /** What a build reads before it stages: the row, the account, the disk and the archive format. */
    protected fun stubBuildEntry(row: UserDataExport = anExport()) {
        stubRow(row)
        every { userRepository.findUserById(userId) } returns user
        every { archiveStore.hasFreeSpace(minimumFreeBytes) } returns true
        every { archiveStore.format } returns ArchiveFormat("application/zip", "zip")
    }

    /** A build that reaches its staging, whatever the staging then does. */
    protected fun stubBuildToStaging(row: UserDataExport = anExport()) {
        stubBuildEntry(row)
        stubRowWrites()
        every { clock.now() } returns now
    }

    /** The whole path: an empty archive, staged, promoted and published. */
    protected fun stubHappyPathBuild(row: UserDataExport = anExport()) {
        stubBuildToStaging(row)
        stubArchiveStore()
        stubEmptyCollections()
        every { archiveStore.promote(any(), any()) } just runs
    }
}
