package fr.geoffreyCoulaud.pinryReborn.api.application.wiring

import fr.geoffreyCoulaud.pinryReborn.api.domain.exports.ExportArchiveStore
import fr.geoffreyCoulaud.pinryReborn.api.domain.images.ImageStore
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.BoardRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.ImageRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.PinRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.TagRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.TransactionRunner
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.UserDataExportRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.UserRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.time.Clock
import fr.geoffreyCoulaud.pinryReborn.api.storage.filesystem.FilesystemZipExportArchiveStore
import fr.geoffreyCoulaud.pinryReborn.api.usecases.Reauthenticator
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exports.ReapExpiredUserDataExports
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exports.UserDataExportBuilder
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exports.UserDataExportRequester
import fr.geoffreyCoulaud.pinryReborn.api.usecases.tasks.EnqueueTask
import fr.geoffreyCoulaud.pinryReborn.api.worker.ExportsConfig
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Produces
import org.eclipse.microprofile.config.inject.ConfigProperty

/**
 * CDI wiring for the export use cases, hosted in the composition root because it needs both
 * `exports.*` (owned by the worker module) and the `api-storage-filesystem` adapter, which the
 * worker module must not depend on. Companion to [ImageAdapterProducers] and
 * [TaskHandlerProducers].
 *
 * `FilesystemZipExportArchiveStore`, [UserDataExportRequester], [UserDataExportBuilder] and
 * [ReapExpiredUserDataExports] are deliberately not `@ApplicationScoped` (see their kdoc) since
 * ARC cannot resolve their plain constructor parameters (`Duration`, `Int`, `Long`, `String`) on
 * its own. These producers are the single place that construct them.
 */
@ApplicationScoped
class ExportProducers {
    @Produces
    @ApplicationScoped
    fun exportArchiveStore(config: ExportsConfig): ExportArchiveStore =
        FilesystemZipExportArchiveStore(config.dataDir())

    @Suppress("LongParameterList")
    @Produces
    @ApplicationScoped
    fun userDataExportRequester(
        repository: UserDataExportRepositoryInterface,
        archiveStore: ExportArchiveStore,
        enqueueTask: EnqueueTask,
        reauthenticator: Reauthenticator,
        clock: Clock,
        transactionRunner: TransactionRunner,
        config: ExportsConfig,
    ): UserDataExportRequester =
        UserDataExportRequester(
            repository, archiveStore, enqueueTask, reauthenticator, clock, transactionRunner,
            minimumInterval = config.minimumInterval(),
        )

    @Suppress("LongParameterList")
    @Produces
    @ApplicationScoped
    fun userDataExportBuilder(
        exportRepository: UserDataExportRepositoryInterface,
        userRepository: UserRepositoryInterface,
        pinRepository: PinRepositoryInterface,
        imageRepository: ImageRepositoryInterface,
        boardRepository: BoardRepositoryInterface,
        tagRepository: TagRepositoryInterface,
        imageStore: ImageStore,
        archiveStore: ExportArchiveStore,
        transactionRunner: TransactionRunner,
        clock: Clock,
        config: ExportsConfig,
        @ConfigProperty(name = "quarkus.application.version") applicationVersion: String,
    ): UserDataExportBuilder =
        UserDataExportBuilder(
            exportRepository, userRepository, pinRepository, imageRepository, boardRepository, tagRepository,
            imageStore, archiveStore, transactionRunner, clock,
            applicationVersion = applicationVersion,
            pageSize = config.pageSize(),
            retention = config.retention(),
            minimumFreeBytes = config.minimumFreeBytes(),
        )

    @Produces
    @ApplicationScoped
    fun reapExpiredUserDataExports(
        repository: UserDataExportRepositoryInterface,
        archiveStore: ExportArchiveStore,
        clock: Clock,
        config: ExportsConfig,
    ): ReapExpiredUserDataExports =
        ReapExpiredUserDataExports(repository, archiveStore, clock, stagedFileMaxAge = config.stagedFileMaxAge())
}
