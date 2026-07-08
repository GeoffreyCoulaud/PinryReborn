package fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.tasks

import java.time.Duration
import java.util.concurrent.ExecutorService
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

interface WorkerExecutor {
    /** Non-blocking attempt to reserve a worker slot. Returns false if the pool is at capacity. */
    fun tryAcquire(): Boolean

    /** Runs [job] on the pool, assuming a permit was already reserved via [tryAcquire]. Releases it when done. */
    fun submit(job: Runnable)

    /** Gives back a permit that was reserved via [tryAcquire] but never handed to [submit]. */
    fun release()

    fun shutdownAndDrain(timeout: Duration): Boolean
}

class BoundedWorkerExecutor(
    private val permits: Semaphore,
    private val pool: ExecutorService,
) : WorkerExecutor {
    override fun tryAcquire(): Boolean = permits.tryAcquire()

    override fun submit(job: Runnable) {
        pool.execute {
            try {
                job.run()
            } finally {
                permits.release()
            }
        }
    }

    override fun release() {
        permits.release()
    }

    override fun shutdownAndDrain(timeout: Duration): Boolean {
        pool.shutdown()
        return pool.awaitTermination(timeout.toMillis(), TimeUnit.MILLISECONDS)
    }
}
