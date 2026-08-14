package fr.geoffreyCoulaud.pinryReborn.api.application.wiring

import fr.geoffreyCoulaud.pinryReborn.api.domain.images.ImageProbe
import fr.geoffreyCoulaud.pinryReborn.api.domain.images.ImageStore
import fr.geoffreyCoulaud.pinryReborn.api.domain.imports.ImportArchiveStore
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.BoardRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.ImageRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.PinRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.TagRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.TaskQueueInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.TransactionRunner
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.UserDataImportIssueRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.UserDataImportRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.UserRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.time.Clock
import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.config.ImagesConfig
import fr.geoffreyCoulaud.pinryReborn.api.storage.filesystem.FilesystemZipImportArchiveStore
import fr.geoffreyCoulaud.pinryReborn.api.usecases.imports.ReapAbandonedUserDataImports
import fr.geoffreyCoulaud.pinryReborn.api.usecases.imports.UserDataImportChunkReceiver
import fr.geoffreyCoulaud.pinryReborn.api.usecases.imports.UserDataImportRunner
import fr.geoffreyCoulaud.pinryReborn.api.worker.ImportsConfig
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Produces

/**
 * The three import beans ARC cannot build itself, their scalars coming from `imports.*` in the worker
 * module and the runner's two image bounds from `images.*`, as [TaskHandlerProducers] takes them.
 */
@ApplicationScoped
class ImportProducers {
    @Produces
    @ApplicationScoped
    fun importArchiveStore(config: ImportsConfig): ImportArchiveStore =
        FilesystemZipImportArchiveStore(config.dataDir(), config.maxLineBytes())

    // LongParameterList: four ports, the clock, and the two Durations ARC cannot resolve on its own.
    @Suppress("LongParameterList")
    @Produces
    @ApplicationScoped
    fun reapAbandonedUserDataImports(
        repository: UserDataImportRepositoryInterface,
        archiveStore: ImportArchiveStore,
        taskQueue: TaskQueueInterface,
        clock: Clock,
        transactionRunner: TransactionRunner,
        config: ImportsConfig,
    ): ReapAbandonedUserDataImports =
        ReapAbandonedUserDataImports(
            repository, archiveStore, taskQueue, clock, transactionRunner,
            uploadGrace = config.uploadGrace(),
            stagedFileMaxAge = config.stagedFileMaxAge(),
        )

    // A producer's parameter list is the injection points of what it builds, and this receiver takes
    // four ports plus the config its two bounds come from. Grouping them would only hide them.
    @Suppress("LongParameterList")
    @Produces
    @ApplicationScoped
    fun userDataImportChunkReceiver(
        repository: UserDataImportRepositoryInterface,
        archiveStore: ImportArchiveStore,
        clock: Clock,
        transactionRunner: TransactionRunner,
        config: ImportsConfig,
    ): UserDataImportChunkReceiver =
        UserDataImportChunkReceiver(
            repository, archiveStore, clock, transactionRunner,
            maxArchiveBytes = config.maxArchiveBytes(),
            minimumFreeBytes = config.minimumFreeBytes(),
        )

    /**
     * The `images.*` bounds are reused rather than given import twins: an archived medium is bounded by
     * what this instance hosts, [ImagesConfig]'s to say. `LongParameterList`: ten ports and two configs.
     */
    @Suppress("LongParameterList")
    @Produces
    @ApplicationScoped
    fun userDataImportRunner(
        importRepository: UserDataImportRepositoryInterface,
        issueRepository: UserDataImportIssueRepositoryInterface,
        userRepository: UserRepositoryInterface,
        tagRepository: TagRepositoryInterface,
        boardRepository: BoardRepositoryInterface,
        pinRepository: PinRepositoryInterface,
        imageRepository: ImageRepositoryInterface,
        archiveStore: ImportArchiveStore,
        imageStore: ImageStore,
        imageProbe: ImageProbe,
        transactionRunner: TransactionRunner,
        clock: Clock,
        config: ImportsConfig,
        imagesConfig: ImagesConfig,
    ): UserDataImportRunner =
        UserDataImportRunner(
            importRepository, issueRepository, userRepository, tagRepository, boardRepository,
            pinRepository, imageRepository, archiveStore, imageStore, imageProbe, transactionRunner, clock,
            maxMetadataBytes = config.maxMetadataBytes(),
            maxEntries = config.maxEntries(),
            maxImageBytes = imagesConfig.maxFileBytes(),
            maxPixels = imagesConfig.maxPixels(),
            leaseRenewalLines = config.leaseRenewalLines(),
            reportDetailLimit = config.reportDetailLimit(),
        )
}
