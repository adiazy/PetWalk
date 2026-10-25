package com.karaoketv.core.lyrics

import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

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
        // Drop "- Remastered 2009" style endings first so they aren't mistaken for "Artist - Title".
        var t = title.trim().replace(VERSION_SUFFIX, "").trim().ifEmpty { title.trim() }
        var a = artist?.trim()?.takeUnless { it.isEmpty() || it.equals("<unknown>", ignoreCase = true) }

        if (a == null) {
            val parts = t.split(Regex("""\s+[-–—]\s+"""), limit = 2)
            if (parts.size == 2) {
                a = parts[0].trim()
                t = parts[1].trim()
            }
        }
        t = t.replace(NOISE, "").replace(FEAT_BRACKETED, "").replace(FEAT_TRAILING, "")
            .replace(VERSION_SUFFIX, "").replace(Regex("""\s{2,}"""), " ").trim().ifEmpty { title.trim() }
        a = a?.replace(FEAT_BRACKETED, "")?.replace(FEAT_TRAILING, "")?.trim()?.ifEmpty { null }
        return copy(title = t, artist = a, album = album?.trim()?.takeUnless { it.equals("<unknown>", true) })
    }

    /** First of several credited artists ("A & B", "A, B", "A x B", "A feat. B"), if different. */
    fun primaryArtist(): String? = artist?.split(ARTIST_SPLIT, limit = 2)?.first()?.trim()?.takeIf { it.isNotEmpty() && it != artist }

    companion object {
        private val NOISE = Regex(
            """\s*[(\[](official|lyrics?|lyric video|audio|video|hd|hq|karaoke|instrumental|explicit|clean|bonus|mono|stereo|live|""" +
                """(\d{4} )?remaster(ed)?|[^)\]]*\b(version|edit)\b)[^)\]]*[)\]]""",
            RegexOption.IGNORE_CASE,
        )
        private val FEAT_BRACKETED = Regex("""\s*[(\[]\s*(feat\.?|ft\.?|featuring|with)\s+[^)\]]*[)\]]""", RegexOption.IGNORE_CASE)
        private val FEAT_TRAILING = Regex("""\s+(feat\.?|ft\.?|featuring)\s+.*$""", RegexOption.IGNORE_CASE)
        private val VERSION_SUFFIX = Regex(
            """\s+[-–—]\s+((\d{4} )?(digital )?remaster(ed)?( \d{4})?|live( at .*| in .*)?|single version|radio edit|album version|mono|stereo|explicit|bonus track|acoustic)\s*$""",
            RegexOption.IGNORE_CASE,
        )
        private val ARTIST_SPLIT = Regex("""\s*(,|&|;|/|\bx\b|\band\b|\bfeat\.?|\bft\.?)\s*""", RegexOption.IGNORE_CASE)
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

/**
 * Tries [providers] (fast, local files) in order, then asks all [online] sources at the same time
 * so a slow backup doesn't add to the wait. Prefers synced lyrics, in list order.
 */
class LyricsRepository(
    private val providers: List<LyricsProvider>,
    private val online: List<LyricsProvider> = emptyList(),
    private val onlineTimeoutMs: Long = 15_000,
) : LyricsProvider {
    override fun find(query: LyricsQuery): Lyrics? = search(query).lyrics

    fun search(query: LyricsQuery, includeOnline: Boolean = true): LyricsSearchReport {
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
        if (!includeOnline || online.isEmpty()) return LyricsSearchReport(q, plain, problems)

        val futures = online.map { provider -> provider to POOL.submit(Callable { provider.find(q) }) }
        val deadline = System.currentTimeMillis() + onlineTimeoutMs
        try {
            for ((provider, future) in futures) {
                val result = try {
                    future.get((deadline - System.currentTimeMillis()).coerceAtLeast(1), TimeUnit.MILLISECONDS)
                } catch (e: TimeoutException) {
                    problems += "${provider.name}: no answer after ${onlineTimeoutMs / 1000} s"
                    null
                } catch (e: ExecutionException) {
                    val cause = e.cause ?: e
                    problems += "${provider.name}: ${cause.message ?: cause::class.simpleName}"
                    null
                } ?: continue
                if (result.synced) return LyricsSearchReport(q, result, problems)
                if (plain == null) plain = result
            }
        } finally {
            futures.forEach { it.second.cancel(true) }
        }
        return LyricsSearchReport(q, plain, problems)
    }

    private val LyricsProvider.name
        get() = when (this) {
            is LrcLibClient -> "Online lyrics"
            is LyricsOvhClient -> "Backup lyrics"
            else -> this::class.simpleName?.removeSuffix("Provider") ?: "Lyrics source"
        }

    private companion object {
        val POOL: ExecutorService = Executors.newCachedThreadPool { r -> Thread(r, "lyrics-lookup").apply { isDaemon = true } }
    }
}
