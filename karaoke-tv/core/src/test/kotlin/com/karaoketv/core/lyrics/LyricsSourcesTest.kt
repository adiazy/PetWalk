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
    fun lrcLibUsesCachedGetThenParsesSyncedLyrics() {
        val urls = mutableListOf<String>()
        val client = LrcLibClient(http = { url, headers ->
            urls += url
            assertTrue(headers.getValue("User-Agent").startsWith("KaraokeTV"))
            200 to """{"id":1,"trackName":"Song","artistName":"Band","duration":200,"syncedLyrics":"[00:01.00]Hi","extra":1}"""
        })
        val lyrics = assertNotNull(client.find(LyricsQuery("My Song", "The Band", durationMs = 200_400)))
        assertTrue(lyrics.synced)
        assertEquals("Hi", lyrics.lines.single().text)
        assertEquals("https://lrclib.net/api/get-cached?track_name=My%20Song&artist_name=The%20Band&duration=200", urls.single())
    }

    @Test
    fun lrcLibFallsBackToSearchPreferringSyncedAndCloseDuration() {
        val client = LrcLibClient(http = { url, _ ->
            when {
                "/api/get-cached" in url -> 404 to """{"code":404}"""
                else -> 200 to """[
                    {"id":1,"trackName":"t","artistName":"a","duration":100,"plainLyrics":"plain"},
                    {"id":2,"trackName":"t","artistName":"a","duration":300,"syncedLyrics":"[00:01.00]wrong length"},
                    {"id":3,"trackName":"t","artistName":"a","duration":181,"syncedLyrics":"[00:01.00]right"}
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
                "q=" in url -> 200 to """[{"id":9,"trackName":"Song","artistName":"Band","syncedLyrics":"[00:01.00]found"}]"""
                else -> 200 to "[]"
            }
        })
        assertEquals("found", client.find(LyricsQuery("Song", "Band"))?.lines?.single()?.text)
        assertEquals("https://lrclib.net/api/search?q=Band%20Song", urls.last())
        assertEquals(2, urls.size)
    }

    @Test
    fun repositoryReportsProviderErrors() {
        val client = LrcLibClient(http = { _, _ -> 503 to "busy" }, sleep = {})
        val report = LyricsRepository(listOf(client)).search(LyricsQuery("Song", "Band"))
        assertNull(report.lyrics)
        assertEquals(
            listOf("Online lyrics: LRCLIB's server is busy right now (HTTP 503). Try again in a minute."),
            report.problems,
        )
        assertEquals("Song", report.query.title)
    }

    @Test
    fun lrcLibRetriesWhenBusy() {
        var calls = 0
        val waits = mutableListOf<Long>()
        val client = LrcLibClient(
            http = { _, _ ->
                calls++
                if (calls < 3) 503 to "busy" else 200 to """[{"id":1,"trackName":"Song","syncedLyrics":"[00:01.00]ok"}]"""
            },
            sleep = { waits += it },
        )
        assertEquals("ok", client.find(LyricsQuery("Song"))?.lines?.single()?.text)
        assertEquals(3, calls)
        assertEquals(listOf(700L, 2_000L), waits)
    }

    @Test
    fun lyricsOvhBackup() {
        val urls = mutableListOf<String>()
        val ovh = LyricsOvhClient(http = { url, _ ->
            urls += url
            200 to """{"lyrics":"Paroles de la chanson Hello par Adele\r\nHello, it's me\r\nI was wondering"}"""
        })
        val lyrics = assertNotNull(ovh.find(LyricsQuery("Hello", "Adele")))
        assertEquals(false, lyrics.synced)
        assertEquals(listOf("Hello, it's me", "I was wondering"), lyrics.lines.map { it.text })
        assertEquals("https://api.lyrics.ovh/v1/Adele/Hello", urls.single())
        assertNull(ovh.find(LyricsQuery("Hello")))
        assertNull(LyricsOvhClient(http = { _, _ -> 404 to """{"error":"No lyrics found"}""" }).find(LyricsQuery("x", "y")))
    }

    @Test
    fun backupUsedWhenLrcLibIsBusy() {
        val repo = LyricsRepository(listOf(
            LrcLibClient(http = { _, _ -> 503 to "" }, sleep = {}),
            LyricsOvhClient(http = { _, _ -> 200 to """{"lyrics":"line"}""" }),
        ))
        val report = repo.search(LyricsQuery("Song", "Band"))
        assertEquals("lyrics.ovh", report.lyrics?.source)
    }

    @Test
    fun cleansTitlesForMatching() {
        assertEquals("Hey Ya!", LyricsQuery("Hey Ya! - Radio Edit").cleaned().title)
        assertEquals("Come Together", LyricsQuery("Come Together - Remastered 2009").cleaned().title)
        assertEquals("Stay", LyricsQuery("Stay (feat. Justin Bieber)", "The Kid LAROI").cleaned().title)
        assertEquals("Lose Yourself", LyricsQuery("Lose Yourself [Explicit]").cleaned().title)
        assertEquals("Shallow", LyricsQuery("Shallow (Radio Version)").cleaned().title)
        assertEquals("Remix Song (Remix)", LyricsQuery("Remix Song (Remix)").cleaned().title)
        assertEquals("Drake", LyricsQuery("x", "Drake feat. Rihanna").cleaned().artist)
        assertEquals("Simon", LyricsQuery("x", "Simon & Garfunkel").primaryArtist())
        assertNull(LyricsQuery("x", "Adele").primaryArtist())
    }

    @Test
    fun freeTextResultsMustBeTheRightSong() {
        val client = LrcLibClient(http = { url, _ ->
            when {
                "q=" in url -> 200 to """[
                    {"id":1,"trackName":"Yesterday Once More","artistName":"Carpenters","syncedLyrics":"[00:01.00]wrong"},
                    {"id":2,"trackName":"Yesterday","artistName":"Some Cover Band","syncedLyrics":"[00:01.00]cover"},
                    {"id":3,"trackName":"Yesterday - Remastered 2009","artistName":"The Beatles","syncedLyrics":"[00:01.00]right"}
                ]"""
                else -> 200 to "[]"
            }
        }, sleep = {})
        assertEquals("right", client.find(LyricsQuery("Yesterday", "Beatles"))?.lines?.single()?.text)
    }

    @Test
    fun onlineSourcesRunAtTheSameTime() {
        val slowBackup = LyricsProvider { Thread.sleep(400); Lyrics(listOf(LyricLine(0, "plain")), false, "backup") }
        val slowMain = LyricsProvider { Thread.sleep(400); null }
        val started = System.currentTimeMillis()
        val report = LyricsRepository(emptyList(), online = listOf(slowMain, slowBackup)).search(LyricsQuery("t", "a"))
        val took = System.currentTimeMillis() - started
        assertEquals("backup", report.lyrics?.source)
        assertTrue(took < 750, "took $took ms")
    }

    @Test
    fun localOnlySearchSkipsOnline() {
        var asked = false
        val repo = LyricsRepository(emptyList(), online = listOf(LyricsProvider { asked = true; null }))
        assertNull(repo.search(LyricsQuery("t"), includeOnline = false).lyrics)
        assertEquals(false, asked)
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
