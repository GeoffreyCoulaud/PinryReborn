package fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.tasks

import fr.geoffreyCoulaud.pinryReborn.api.domain.time.Clock
import jakarta.enterprise.context.ApplicationScoped
import java.time.Instant

@ApplicationScoped
class SystemClock : Clock {
    override fun now(): Instant = Instant.now()
}
