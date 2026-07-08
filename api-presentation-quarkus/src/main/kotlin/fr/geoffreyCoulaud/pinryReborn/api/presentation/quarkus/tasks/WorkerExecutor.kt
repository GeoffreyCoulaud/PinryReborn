package fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.tasks

import java.time.Duration
import java.util.concurrent.ExecutorService
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

interface WorkerExecutor {
    fun trySubmit(job: Runnable): Boolean
    fun shutdownAndDrain(timeout: Duration): Boolean
}

class BoundedWorkerExecutor(
    private val permits: Semaphore,
    private val pool: ExecutorService,
) : WorkerExecutor {
    override fun trySubmit(job: Runnable): Boolean {
        if (!permits.tryAcquire()) return false
        pool.execute {
            try {
                job.run()
            } finally {
                permits.release()
            }
        }
        return true
    }

    override fun shutdownAndDrain(timeout: Duration): Boolean {
        pool.shutdown()
        return pool.awaitTermination(timeout.toMillis(), TimeUnit.MILLISECONDS)
    }
}
