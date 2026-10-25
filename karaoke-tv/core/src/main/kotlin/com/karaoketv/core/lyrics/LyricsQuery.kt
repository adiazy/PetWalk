package com.karaoketv.core.lyrics

data class LyricsQuery(
    val title: String,
    val artist: String? = null,
    val album: String? = null,
    val durationMs: Long? = null,
    /** Absolute path of a local audio file, used to find a sidecar .lrc next to it. */
    val localPath: String? = null,
) {
    /**
     * Cleans up typical file/video metadata: unknown artists, "Artist - Title" titles,
     * and decorations like "(Official Video)" or "[Karaoke Version]".
     */
    fun cleaned(): LyricsQuery {
        var t = title.trim()
        var a = artist?.trim()?.takeUnless { it.isEmpty() || it.equals("<unknown>", ignoreCase = true) }

        if (a == null) {
            val parts = t.split(Regex("""\s+[-–—]\s+"""), limit = 2)
            if (parts.size == 2) {
                a = parts[0].trim()
                t = parts[1].trim()
            }
        }
        t = t.replace(NOISE, "").replace(Regex("""\s{2,}"""), " ").trim()
        return copy(title = t, artist = a, album = album?.trim()?.takeUnless { it.equals("<unknown>", true) })
    }

    companion object {
        private val NOISE = Regex(
            """\s*[(\[](official|lyrics?|lyric video|audio|video|hd|hq|karaoke|instrumental|remaster(ed)?)[^)\]]*[)\]]""",
            RegexOption.IGNORE_CASE,
        )
    }
}

fun interface LyricsProvider {
    /** Returns lyrics for [query] or null. May block; call from a background thread. */
    fun find(query: LyricsQuery): Lyrics?
}

/** What a lookup searched for, what it found, and any provider errors along the way. */
data class LyricsSearchReport(
    val query: LyricsQuery,
    val lyrics: Lyrics?,
    val problems: List<String>,
)

/** Tries each provider in order; prefers synced lyrics, falling back to the first plain result. */
class LyricsRepository(private val providers: List<LyricsProvider>) : LyricsProvider {
    override fun find(query: LyricsQuery): Lyrics? = search(query).lyrics

    fun search(query: LyricsQuery): LyricsSearchReport {
        val q = query.cleaned()
        var plain: Lyrics? = null
        val problems = mutableListOf<String>()
        for (provider in providers) {
            val result = try {
                provider.find(q)
            } catch (e: Exception) {
                problems += "${provider.name}: ${e.message ?: e::class.simpleName}"
                null
            } ?: continue
            if (result.synced) return LyricsSearchReport(q, result, problems)
            if (plain == null) plain = result
        }
        return LyricsSearchReport(q, plain, problems)
    }

    private val LyricsProvider.name get() = this::class.simpleName?.removeSuffix("Provider") ?: "Lyrics source"
}
