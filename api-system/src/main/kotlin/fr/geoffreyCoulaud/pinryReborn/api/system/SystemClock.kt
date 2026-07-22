package fr.geoffreyCoulaud.pinryReborn.api.system

import fr.geoffreyCoulaud.pinryReborn.api.domain.time.Clock
import jakarta.enterprise.context.ApplicationScoped
import java.time.Instant

@ApplicationScoped
class SystemClock : Clock {
    override fun now(): Instant = Instant.now()
}
