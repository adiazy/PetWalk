package com.karaoketv.core.library

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/** Tags read from inside an audio file (ID3, Vorbis comments, MP4 atoms…). */
@Serializable
data class TrackTags(
    val title: String? = null,
    val artist: String? = null,
    val albumArtist: String? = null,
    val album: String? = null,
    val track: Int? = null,
    val disc: Int? = null,
    val year: Int? = null,
    val durationMs: Long? = null,
) {
    companion object {
        /** "3", "03", "3/12" → 3. */
        fun parseNumber(raw: String?): Int? = raw?.trim()?.substringBefore('/')?.trim()?.toIntOrNull()?.takeIf { it > 0 }

        /** "1985", "1985-06-10", "2004-05-01T00:00:00Z" → the year. */
        fun parseYear(raw: String?): Int? =
            raw?.let { Regex("""(1[89]\d{2}|20\d{2})""").find(it)?.value?.toInt() }
    }
}

/**
 * The library index: each file's tags, keyed by path and checked against the file's size and
 * modification time, so tags are only read again for new or changed files.
 *
 * Storage is pluggable ([read]/[write]) so the index can live on the NAS and be shared by every
 * TV. [save] merges with whatever is stored now, so two devices saving don't erase each other.
 * Blocking; use from a background thread.
 */
class TagCache(
    private val read: () -> String?,
    private val write: (String) -> Unit,
) {
    @Serializable
    data class Entry(val size: Long, val modified: Long, val tags: TrackTags?)

    private val json = Json { ignoreUnknownKeys = true }
    private val entries: MutableMap<String, Entry> = decode(runCatching(read).getOrNull())
    private val changed = HashSet<String>()

    /** The stored tags if the file hasn't changed; `null` means "read it again". */
    @Synchronized
    fun get(path: String, size: Long, modified: Long): Entry? =
        entries[path]?.takeIf { it.size == size && it.modified == modified }

    @Synchronized
    fun put(path: String, size: Long, modified: Long, tags: TrackTags?) {
        entries[path] = Entry(size, modified, tags)
        changed += path
    }

    /**
     * Writes the index: what's stored now, plus this device's new entries, minus files that no
     * longer exist ([keep], if given). Throws if it can't be written.
     */
    @Synchronized
    fun save(keep: Set<String>? = null) {
        val merged = decode(runCatching(read).getOrNull())
        for (path in changed) entries[path]?.let { merged[path] = it }
        for ((path, entry) in entries) merged.putIfAbsent(path, entry)
        if (keep != null) merged.keys.retainAll(keep)
        write(json.encodeToString(merged.toMap()))
        entries.clear()
        entries.putAll(merged)
        changed.clear()
    }

    private fun decode(text: String?): MutableMap<String, Entry> =
        text?.let { runCatching { json.decodeFromString<Map<String, Entry>>(it).toMutableMap() }.getOrNull() }
            ?: mutableMapOf()

    companion object {
        /** An index kept in a local file. */
        fun inFile(file: File) = TagCache(
            read = { file.takeIf { it.exists() }?.readText() },
            write = { text ->
                file.parentFile?.mkdirs()
                val tmp = File(file.path + ".tmp")
                tmp.writeText(text)
                if (!tmp.renameTo(file)) {
                    file.delete()
                    tmp.renameTo(file)
                }
            },
        )
    }
}
