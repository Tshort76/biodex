package dev.tlong.biodex.data.net

/**
 * ARCHITECTURE.md 5.2's three outcomes, kept distinct everywhere in the network layer.
 *
 * [NotFound] is not a failure and must never be rendered as one: "no Wikipedia article" and
 * "no recording of this animal" are ordinary answers about the world (M18), while [Failed]
 * means the app could not ask.
 */
sealed interface LookupResult<out T> {
    data class Found<T>(val value: T) : LookupResult<T>

    data object NotFound : LookupResult<Nothing>

    data class Failed(val reason: String) : LookupResult<Nothing>

    fun valueOrNull(): T? = (this as? Found)?.value
}
