package fr.geoffreyCoulaud.pinryReborn.api.application.wiring

import fr.geoffreyCoulaud.pinryReborn.api.domain.exports.ExportArchiveStore
import fr.geoffreyCoulaud.pinryReborn.api.domain.images.RenditionCache
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.ImageRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.TaskQueueInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.UserDataExportRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.UserRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.time.Clock
import fr.geoffreyCoulaud.pinryReborn.api.usecases.AccountDeletionCleaner
import fr.geoffreyCoulaud.pinryReborn.api.usecases.ReapOrphanedStorage
import fr.geoffreyCoulaud.pinryReborn.api.usecases.ReapTombstonedAccounts
import fr.geoffreyCoulaud.pinryReborn.api.usecases.tasks.ReapTerminalTasks
import fr.geoffreyCoulaud.pinryReborn.api.worker.GcConfig
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Produces

/**
 * CDI wiring for the three GC sweeps whose constructor takes a primitive ARC cannot resolve
 * ([ReapOrphanedStorage] takes an `Int`, [ReapTombstonedAccounts] a `Duration`,
 * [ReapTerminalTasks] a `Duration`). Mirrors [ExportProducers.reapExpiredUserDataExports]:
 * `GcConfig` lives in `api-worker-quarkus`, so a use case in `api-usecases` cannot take it
 * directly and the primitive is read here. `ReapExpiredSessionTokens` is `@ApplicationScoped`
 * already (its dependencies are all injectable beans), so it has no producer here.
 */
@ApplicationScoped
class GcProducers {
    @Produces
    @ApplicationScoped
    fun reapOrphanedStorage(
        renditionCache: RenditionCache,
        exportArchiveStore: ExportArchiveStore,
        imageRepository: ImageRepositoryInterface,
        userDataExportRepository: UserDataExportRepositoryInterface,
        config: GcConfig,
    ): ReapOrphanedStorage =
        ReapOrphanedStorage(
            renditionCache,
            exportArchiveStore,
            imageRepository,
            userDataExportRepository,
            batchSize = config.orphanBatchSize(),
        )

    @Produces
    @ApplicationScoped
    fun reapTombstonedAccounts(
        userRepository: UserRepositoryInterface,
        accountDeletionCleaner: AccountDeletionCleaner,
        clock: Clock,
        config: GcConfig,
    ): ReapTombstonedAccounts =
        ReapTombstonedAccounts(
            userRepository,
            accountDeletionCleaner,
            clock,
            tombstoneGrace = config.tombstoneGrace(),
        )

    @Produces
    @ApplicationScoped
    fun reapTerminalTasks(
        taskQueue: TaskQueueInterface,
        clock: Clock,
        config: GcConfig,
    ): ReapTerminalTasks =
        ReapTerminalTasks(
            taskQueue,
            clock,
            terminalTaskGrace = config.terminalTaskGrace(),
        )
}
