package com.karaoketv.core.lyrics

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException
import java.net.URLEncoder

/**
 * Backup source: lyrics.ovh (https://lyricsovh.docs.apiary.io), free and keyless.
 * Plain text only (no timing), so lyrics from here scroll instead of highlighting line by line.
 * Needs both artist and title.
 */
class LyricsOvhClient(
    private val baseUrl: String = "https://api.lyrics.ovh",
    private val http: HttpGet = UrlConnectionHttpGet(timeoutMs = 5_000),
) : LyricsProvider {
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class Response(val lyrics: String? = null)

    override fun find(query: LyricsQuery): Lyrics? {
        val artist = query.artist ?: return null
        val (code, body) = http.get("$baseUrl/v1/${encode(artist)}/${encode(query.title)}", mapOf("Accept" to "application/json"))
        if (code == 404) return null
        if (code != 200) throw IOException("lyrics.ovh answered HTTP $code")
        val text = json.decodeFromString<Response>(body).lyrics
            ?.replace("\r\n", "\n")
            ?.lines()
            // The service sometimes prefixes a French "Paroles de la chanson X par Y" header.
            ?.dropWhile { it.isBlank() || it.startsWith("Paroles de la chanson") }
            ?.joinToString("\n")
            ?.takeIf { it.isNotBlank() }
            ?: return null
        return LrcParser.parse(text, SOURCE).copy(title = query.title, artist = artist)
    }

    private fun encode(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    companion object {
        const val SOURCE = "lyrics.ovh"
    }
}
