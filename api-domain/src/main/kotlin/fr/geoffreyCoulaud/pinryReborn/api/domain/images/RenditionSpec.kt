package fr.geoffreyCoulaud.pinryReborn.api.domain.images

/** A transform request: fit the shortest side to [shortestSide] px, keeping animation iff [animated]. */
data class RenditionSpec(val shortestSide: Int, val animated: Boolean)
