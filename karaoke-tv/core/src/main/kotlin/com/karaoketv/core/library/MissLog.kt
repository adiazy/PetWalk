package com.karaoketv.core.library

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Remembers when something was last looked for and not found (e.g. lyrics LRCLIB doesn't have),
 * so background downloads don't ask again until [retryAfterMs] has passed. Stored through
 * [read]/[write] (on the NAS) and merged on save, like [TagCache]. Blocking.
 */
class MissLog(
    private val read: () -> String?,
    private val write: (String) -> Unit,
    private val retryAfterMs: Long,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val misses: MutableMap<String, Long> = decode(runCatching(read).getOrNull())

    /** True if [key] was missed recently, so it isn't worth trying again yet. */
    @Synchronized
    fun recentlyMissed(key: String): Boolean = misses[key]?.let { now() - it < retryAfterMs } ?: false

    @Synchronized
    fun missed(key: String) {
        misses[key] = now()
    }

    /** No longer missing (found after all, e.g. a file was added by hand). */
    @Synchronized
    fun found(key: String) {
        misses.remove(key)
    }

    /** Merges with what's stored (newest time wins), drops expired entries, and writes. */
    @Synchronized
    fun save() {
        val merged = decode(runCatching(read).getOrNull())
        for ((key, time) in misses) merged[key] = maxOf(time, merged[key] ?: 0)
        merged.values.removeAll { now() - it >= retryAfterMs }
        write(json.encodeToString(merged.toMap()))
        misses.clear()
        misses.putAll(merged)
    }

    private fun decode(text: String?): MutableMap<String, Long> =
        text?.let { runCatching { json.decodeFromString<Map<String, Long>>(it).toMutableMap() }.getOrNull() }
            ?: mutableMapOf()
}
