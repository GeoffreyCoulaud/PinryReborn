package fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.controllers

import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.config.RenditionsConfig

enum class RenditionSize {
    TINY, SMALL, MEDIUM, LARGE;

    fun pxFrom(config: RenditionsConfig): Int = when (this) {
        TINY -> config.tiny()
        SMALL -> config.small()
        MEDIUM -> config.medium()
        LARGE -> config.large()
    }

    companion object {
        fun fromName(name: String): RenditionSize? = entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
    }
}
