package com.karaoketv.core.lyrics

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LyricsSourcesTest {
    @Test
    fun cleansFileStyleTitles() {
        val q = LyricsQuery("Queen - Bohemian Rhapsody (Official Video)", artist = "<unknown>").cleaned()
        assertEquals("Queen", q.artist)
        assertEquals("Bohemian Rhapsody", q.title)
    }

    @Test
    fun lrcLibUsesGetThenParsesSyncedLyrics() {
        val urls = mutableListOf<String>()
        val client = LrcLibClient(http = { url, headers ->
            urls += url
            assertTrue(headers.getValue("User-Agent").startsWith("KaraokeTV"))
            200 to """{"id":1,"trackName":"Song","artistName":"Band","duration":200,"syncedLyrics":"[00:01.00]Hi","extra":1}"""
        })
        val lyrics = assertNotNull(client.find(LyricsQuery("My Song", "The Band", durationMs = 200_400)))
        assertTrue(lyrics.synced)
        assertEquals("Hi", lyrics.lines.single().text)
        assertEquals("https://lrclib.net/api/get?track_name=My%20Song&artist_name=The%20Band&duration=200", urls.single())
    }

    @Test
    fun lrcLibFallsBackToSearchPreferringSyncedAndCloseDuration() {
        val client = LrcLibClient(http = { url, _ ->
            when {
                "/api/get" in url -> 404 to """{"code":404}"""
                else -> 200 to """[
                    {"id":1,"duration":100,"plainLyrics":"plain"},
                    {"id":2,"duration":300,"syncedLyrics":"[00:01.00]wrong length"},
                    {"id":3,"duration":181,"syncedLyrics":"[00:01.00]right"}
                ]"""
            }
        })
        val lyrics = assertNotNull(client.find(LyricsQuery("t", "a", durationMs = 180_000)))
        assertEquals("right", lyrics.lines.single().text)
    }

    @Test
    fun lrcLibFallsBackToFreeTextSearch() {
        val urls = mutableListOf<String>()
        val client = LrcLibClient(http = { url, _ ->
            urls += url
            when {
                "/api/get" in url -> 404 to "{}"
                "q=" in url -> 200 to """[{"id":9,"syncedLyrics":"[00:01.00]found"}]"""
                else -> 200 to "[]"
            }
        })
        assertEquals("found", client.find(LyricsQuery("Song", "Band"))?.lines?.single()?.text)
        assertEquals("https://lrclib.net/api/search?q=Band%20Song", urls.last())
    }

    @Test
    fun repositoryReportsProviderErrors() {
        val client = LrcLibClient(http = { _, _ -> 503 to "busy" })
        val report = LyricsRepository(listOf(client)).search(LyricsQuery("Song", "Band"))
        assertNull(report.lyrics)
        assertEquals(listOf("LrcLibClient: LRCLIB answered HTTP 503"), report.problems)
        assertEquals("Song", report.query.title)
    }

    @Test
    fun repositoryPrefersSyncedAndSurvivesFailures() {
        val plain = Lyrics(listOf(LyricLine(0, "p")), synced = false, "A")
        val synced = Lyrics(listOf(LyricLine(0, "s")), synced = true, "B")
        val repo = LyricsRepository(listOf(
            LyricsProvider { error("network down") },
            LyricsProvider { plain },
            LyricsProvider { synced },
        ))
        assertEquals(synced, repo.find(LyricsQuery("x")))
    }

    @Test
    fun sidecar() {
        val dir = Files.createTempDirectory("karaoke").toFile()
        val audio = dir.resolve("track.mp3").apply { writeText("") }
        dir.resolve("track.lrc").writeText("[00:02.00]Sidecar")
        val sidecar = assertNotNull(SidecarLrcProvider().find(LyricsQuery("t", localPath = audio.path)))
        assertEquals("Sidecar", sidecar.lines.single().text)
        assertNull(SidecarLrcProvider().find(LyricsQuery("t", localPath = dir.resolve("other.mp3").path)))
        dir.deleteRecursively()
    }
}
