package com.karaoketv.core.lyrics

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL
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
        val conn = URL(url).openConnection() as HttpURLConnection
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
    private val http: HttpGet = UrlConnectionHttpGet(),
) : LyricsProvider {
    private val json = Json { ignoreUnknownKeys = true }

    override fun find(query: LyricsQuery): Lyrics? {
        if (query.artist != null) {
            val params = buildList {
                add("track_name" to query.title)
                add("artist_name" to query.artist)
                query.album?.let { add("album_name" to it) }
                query.durationMs?.let { add("duration" to (it / 1000).toString()) }
            }
            val (code, body) = request("/api/get", params)
            if (code == 200) {
                val track = json.decodeFromString<LrcLibTrack>(body)
                toLyrics(track)?.let { return it }
            }
        }

        val results = search(query.title, query.artist)
        val best = results
            .filter { it.syncedLyrics != null || it.plainLyrics != null || it.instrumental }
            .sortedWith(
                compareByDescending<LrcLibTrack> { it.syncedLyrics != null }
                    .thenBy { durationGap(it, query.durationMs) },
            )
            .firstOrNull { durationGap(it, query.durationMs) <= MAX_DURATION_GAP_S }
        return best?.let(::toLyrics)
    }

    /** Free-text search, e.g. for browsing lyrics on the TV. */
    fun search(title: String, artist: String? = null): List<LrcLibTrack> {
        val params = if (artist != null) {
            listOf("track_name" to title, "artist_name" to artist)
        } else {
            listOf("q" to title)
        }
        val (code, body) = request("/api/search", params)
        if (code != 200) return emptyList()
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

    private fun request(path: String, params: List<Pair<String, String>>): Pair<Int, String> {
        val qs = params.joinToString("&") { (k, v) -> "$k=${encode(v)}" }
        return http.get("$baseUrl$path?$qs", mapOf("User-Agent" to userAgent, "Accept" to "application/json"))
    }

    private fun encode(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    companion object {
        const val SOURCE = "LRCLIB"
        private const val MAX_DURATION_GAP_S = 8.0
    }
}
