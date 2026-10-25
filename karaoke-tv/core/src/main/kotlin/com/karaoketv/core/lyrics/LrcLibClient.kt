package com.karaoketv.core.lyrics

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import kotlin.math.abs

@Serializable
data class LrcLibTrack(
    val id: Long,
    val trackName: String? = null,
    val artistName: String? = null,
    val albumName: String? = null,
    /** Seconds. */
    val duration: Double? = null,
    val instrumental: Boolean = false,
    val plainLyrics: String? = null,
    val syncedLyrics: String? = null,
)

fun interface HttpGet {
    /** Returns (status code, body). */
    fun get(url: String, headers: Map<String, String>): Pair<Int, String>
}

class UrlConnectionHttpGet(private val timeoutMs: Int = 10_000) : HttpGet {
    override fun get(url: String, headers: Map<String, String>): Pair<Int, String> {
        val conn = URI(url).toURL().openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            headers.forEach(conn::setRequestProperty)
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            return code to (stream?.bufferedReader()?.use { it.readText() } ?: "")
        } finally {
            conn.disconnect()
        }
    }
}

/**
 * Client for LRCLIB (https://lrclib.net), a free, open, community-built database of
 * time-synced lyrics. No API key is needed; it asks clients to send a descriptive User-Agent.
 */
class LrcLibClient(
    private val baseUrl: String = "https://lrclib.net",
    private val userAgent: String = "KaraokeTV/0.1 (https://github.com/adiazy/PetWalk)",
    private val http: HttpGet = UrlConnectionHttpGet(timeoutMs = 6_000),
    /** Waits between retries; replaceable in tests. */
    private val sleep: (Long) -> Unit = Thread::sleep,
) : LyricsProvider {
    private val json = Json { ignoreUnknownKeys = true }

    override fun find(query: LyricsQuery): Lyrics? {
        val artist = query.artist
        val durationS = query.durationMs?.let { it / 1000 }

        // Exact lookup from LRCLIB's own database. (/api/get-cached never waits on outside
        // sources the way /api/get does, and both need the song's length.)
        if (artist != null && durationS != null) {
            val params = buildList {
                add("track_name" to query.title)
                add("artist_name" to artist)
                query.album?.let { add("album_name" to it) }
                add("duration" to durationS.toString())
            }
            val (code, body) = request("/api/get-cached", params)
            if (code == 200) {
                toLyrics(json.decodeFromString<LrcLibTrack>(body))?.takeIf { it.synced }?.let { return it }
            }
        }

        // Then searches from most to least specific; stop at the first good synced match.
        val searches = buildList<Pair<() -> List<LrcLibTrack>, Boolean>> {
            if (artist != null) {
                add({ search(query.title, artist) } to false)
                query.primaryArtist()?.let { primary -> add({ search(query.title, primary) } to false) }
                add({ search("$artist ${query.title}") } to true)
            }
            add({ search(query.title) } to true)
        }
        var plain: Lyrics? = null
        for ((run, freeText) in searches) {
            val best = pickBest(run(), query, freeText) ?: continue
            val lyrics = toLyrics(best) ?: continue
            if (lyrics.synced) return lyrics
            if (plain == null) plain = lyrics
        }
        return plain
    }

    /**
     * Best result for [query]: synced first, then the right artist, then closest length.
     * Free-text results can be other songs, so they must match the title and (if known) artist.
     */
    private fun pickBest(results: List<LrcLibTrack>, query: LyricsQuery, freeText: Boolean): LrcLibTrack? =
        results
            .asSequence()
            .filter { it.syncedLyrics != null || it.plainLyrics != null || it.instrumental }
            .filter { durationGap(it, query.durationMs) <= MAX_DURATION_GAP_S }
            .filter { track ->
                val titleOk = similar(track.trackName, query.title)
                val artistOk = query.artist == null || similar(track.artistName, query.artist) ||
                    similar(track.artistName, query.primaryArtist())
                val lengthOk = query.durationMs != null && track.duration != null && durationGap(track, query.durationMs) <= 2.0
                if (freeText) titleOk && (artistOk || lengthOk) else titleOk || lengthOk
            }
            .sortedWith(
                compareByDescending<LrcLibTrack> { it.syncedLyrics != null }
                    .thenByDescending { query.artist != null && similar(it.artistName, query.artist) }
                    .thenBy { durationGap(it, query.durationMs) },
            )
            .firstOrNull()

    /** Loose comparison: case, accents, punctuation and "the" don't matter; containment counts. */
    private fun similar(a: String?, b: String?): Boolean {
        if (a == null || b == null) return false
        val x = normalize(a)
        val y = normalize(b)
        if (x.isEmpty() || y.isEmpty()) return false
        return x == y || (minOf(x.length, y.length) >= 4 && (x.contains(y) || y.contains(x)))
    }

    private fun normalize(s: String) = java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD)
        .replace(Regex("""\p{M}+"""), "")
        .replace(Regex("""^the\s+"""), "")
        .replace("&", "and")
        .replace(Regex("""[^a-z0-9]+"""), "")

    /** Free-text search, e.g. for browsing lyrics on the TV. */
    fun search(title: String, artist: String? = null): List<LrcLibTrack> {
        val params = if (artist != null) {
            listOf("track_name" to title, "artist_name" to artist)
        } else {
            listOf("q" to title)
        }
        val (code, body) = request("/api/search", params)
        if (code in BUSY_CODES) throw IOException("LRCLIB's server is busy right now (HTTP $code). Try again in a minute.")
        if (code != 200) throw IOException("LRCLIB answered HTTP $code")
        return json.decodeFromString<List<LrcLibTrack>>(body)
    }

    fun toLyrics(track: LrcLibTrack): Lyrics? {
        val parsed = when {
            track.syncedLyrics != null -> LrcParser.parse(track.syncedLyrics, SOURCE)
            track.plainLyrics != null -> LrcParser.parse(track.plainLyrics, SOURCE)
            track.instrumental -> Lyrics(listOf(LyricLine(0, "(Instrumental)")), synced = false, SOURCE)
            else -> return null
        }
        return parsed.copy(title = track.trackName, artist = track.artistName)
    }

    private fun durationGap(track: LrcLibTrack, durationMs: Long?): Double {
        if (durationMs == null || track.duration == null) return 0.0
        return abs(track.duration - durationMs / 1000.0)
    }

    /**
     * LRCLIB is a free, volunteer-run service and sometimes answers "busy" (503 and friends).
     * Those are temporary, so wait a little and try again before giving up.
     */
    private fun request(path: String, params: List<Pair<String, String>>): Pair<Int, String> {
        val qs = params.joinToString("&") { (k, v) -> "$k=${encode(v)}" }
        val url = "$baseUrl$path?$qs"
        val headers = mapOf("User-Agent" to userAgent, "Accept" to "application/json")
        var response = http.get(url, headers)
        for (delayMs in RETRY_DELAYS_MS) {
            if (response.first !in BUSY_CODES) break
            sleep(delayMs)
            response = http.get(url, headers)
        }
        return response
    }

    private fun encode(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    companion object {
        const val SOURCE = "LRCLIB"
        private const val MAX_DURATION_GAP_S = 8.0
        private val BUSY_CODES = setOf(429, 502, 503, 504)
        private val RETRY_DELAYS_MS = listOf(700L, 2_000L)
    }
}
