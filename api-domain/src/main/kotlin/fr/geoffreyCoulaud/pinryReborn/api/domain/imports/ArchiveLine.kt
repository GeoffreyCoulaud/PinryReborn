package fr.geoffreyCoulaud.pinryReborn.api.domain.imports

/**
 * One line of a JSON Lines entry, carrying either its parsed [value] or the [failure] that stopped
 * it. A malformed line is reported, never thrown: one bad entry must not fail an import.
 */
interface ArchiveLine<out T> {
    val line: Int
    val value: T?
    val failure: String?
}
