package fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.tasks

import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.TaskQueueInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.tasks.TaskState
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.quarkus.runtime.StartupEvent
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes

/**
 * Exposes the task queue's current backlog to Prometheus as three gauges, sampled lazily by
 * Micrometer at scrape time (each scrape runs 3 quick `countByState` queries).
 */
@ApplicationScoped
class TaskQueueMetrics(
    private val taskQueue: TaskQueueInterface,
    private val registry: MeterRegistry,
) {
    fun onStart(
        @Observes ignored: StartupEvent,
    ) {
        registerGauge("tasks.pending", "Number of pending tasks", TaskState.PENDING)
        registerGauge("tasks.running", "Number of running tasks", TaskState.RUNNING)
        registerGauge("tasks.dead", "Number of dead tasks", TaskState.DEAD)
        logger.info { "task queue metrics registered" }
    }

    private fun registerGauge(name: String, description: String, state: TaskState) {
        Gauge.builder(name, taskQueue) { it.countByState(state).toDouble() }
            .description(description)
            .register(registry)
    }

    private companion object {
        private val logger = KotlinLogging.logger {}
    }
}
