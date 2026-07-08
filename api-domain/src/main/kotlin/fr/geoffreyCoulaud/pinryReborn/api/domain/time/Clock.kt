package fr.geoffreyCoulaud.pinryReborn.api.domain.time

import java.time.Instant

interface Clock {
    fun now(): Instant
}
