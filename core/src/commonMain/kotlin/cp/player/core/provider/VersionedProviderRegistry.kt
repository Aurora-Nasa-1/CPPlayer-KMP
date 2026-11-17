package cp.player.core.provider

/**
 * Thread-safe registry for provider descriptors loaded in the background.
 *
 * A disk scan records [generation] before loading a descriptor. An install or deletion advances that
 * provider's generation, so a scan that started earlier cannot overwrite a newer descriptor or
 * resurrect a package that was deleted while it was loading.
 */
internal class VersionedProviderRegistry<T>(private val idOf: (T) -> String) {
    private val lock = Any()
    private val values = linkedMapOf<String, T>()
    private val generations = mutableMapOf<String, Long>()

    fun all(): List<T> = synchronized(lock) { values.values.toList() }

    fun ids(): Set<String> = synchronized(lock) { values.keys.toSet() }

    fun get(id: String): T? = synchronized(lock) { values[id] }

    fun generation(id: String): Long = synchronized(lock) { generations[id] ?: 0L }

    /** Replaces a descriptor and invalidates every earlier disk-load token for its ID. */
    fun put(value: T): T? = synchronized(lock) {
        val id = idOf(value)
        generations[id] = (generations[id] ?: 0L) + 1L
        values.put(id, value)
    }

    /** Installs a background-loaded descriptor only if no newer mutation occurred in the meantime. */
    fun putIfUnchanged(value: T, expectedGeneration: Long): Boolean = synchronized(lock) {
        val id = idOf(value)
        if ((generations[id] ?: 0L) != expectedGeneration || id in values) {
            false
        } else {
            values[id] = value
            true
        }
    }

    /** Removes the descriptor and invalidates in-flight loads, including when no value is present. */
    fun remove(id: String): T? = synchronized(lock) {
        generations[id] = (generations[id] ?: 0L) + 1L
        values.remove(id)
    }
}
