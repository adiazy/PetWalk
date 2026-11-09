package com.karaoketv.core.library

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LibraryTreeTest {
    private data class S(val info: TrackInfo)

    private fun tree(vararg infos: TrackInfo) = LibraryTree.build(infos.map(::S)) { it.info }

    @Test
    fun groupsArtistAlbumSongInOrder() {
        val t = tree(
            TrackInfo("Queen", "News of the World", "We Will Rock You", track = 1, year = 1977),
            TrackInfo("Queen", "A Night at the Opera", "Love of My Life", track = 9, year = 1975),
            TrackInfo("Queen", "A Night at the Opera", "Bohemian Rhapsody", track = 11, year = 1975),
            TrackInfo("ABBA", "Arrival", "Dancing Queen", track = 2, year = 1976),
            TrackInfo(null, null, "Mystery"),
        )
        assertEquals(listOf("ABBA", "Queen", LibraryTree.UNKNOWN_ARTIST), t.map { it.name })
        val queen = t[1]
        assertEquals(listOf("A Night at the Opera", "News of the World"), queen.albums.map { it.name })
        assertEquals(listOf("Love of My Life", "Bohemian Rhapsody"), queen.albums[0].songs.map { it.info.title })
        assertEquals(3, queen.songCount)
        assertEquals(LibraryTree.NO_ALBUM, t[2].albums.single().name)
    }

    @Test
    fun mergesArtistSpellings() {
        val t = tree(
            TrackInfo("Huey Lewis & The News", "Sports", "a"),
            TrackInfo("Huey Lewis and the News", "Sports", "b"),
            TrackInfo("Huey Lewis & The News", "Fore!", "c"),
            TrackInfo("Beyoncé", "x", "d"),
            TrackInfo("BEYONCE", "x", "e"),
        )
        assertEquals(listOf("Beyoncé", "Huey Lewis & The News"), t.map { it.name })
        assertEquals(2, t[1].albums.first { it.name == "Sports" }.songs.size)
    }

    @Test
    fun discsAndUntrackedSongs() {
        val t = tree(
            TrackInfo("A", "Double", "Second disc opener", track = 1, disc = 2),
            TrackInfo("A", "Double", "No number"),
            TrackInfo("A", "Double", "First", track = 1, disc = 1),
        )
        assertEquals(listOf("First", "No number", "Second disc opener"), t[0].albums[0].songs.map { it.info.title })
    }

    @Test
    fun parsesTagNumbers() {
        assertEquals(3, TrackTags.parseNumber("03/12"))
        assertNull(TrackTags.parseNumber("x"))
        assertEquals(2004, TrackTags.parseYear("2004-05-01T00:00:00Z"))
        assertNull(TrackTags.parseYear("0"))
    }

    @Test
    fun tagCacheRemembersUnchangedFiles() {
        val dir = Files.createTempDirectory("tags").toFile()
        val file = dir.resolve("cache.json")
        TagCache.inFile(file).apply {
            put("a.mp3", 100, 5, TrackTags(title = "A"))
            put("gone.mp3", 1, 1, null)
            save(keep = setOf("a.mp3"))
        }
        val reloaded = TagCache.inFile(file)
        assertEquals("A", reloaded.get("a.mp3", 100, 5)?.tags?.title)
        assertNull(reloaded.get("a.mp3", 101, 5), "changed size means read again")
        assertNull(reloaded.get("gone.mp3", 1, 1))
        dir.deleteRecursively()
    }

    @Test
    fun tagCacheMergesWithOtherDevices() {
        var stored: String? = null
        val tvA = TagCache({ stored }, { stored = it })
        val tvB = TagCache({ stored }, { stored = it })
        tvA.put("a.mp3", 1, 1, TrackTags(title = "A"))
        tvA.save()
        tvB.put("b.mp3", 2, 2, TrackTags(title = "B"))
        tvB.save()
        val fresh = TagCache({ stored }, { stored = it })
        assertEquals("A", fresh.get("a.mp3", 1, 1)?.tags?.title)
        assertEquals("B", fresh.get("b.mp3", 2, 2)?.tags?.title)
    }

    @Test
    fun albumFolderGuess() {
        assertEquals("A Night at the Opera", TrackName.albumFolder(listOf("Queen", "A Night at the Opera")))
        assertEquals("Sports", TrackName.albumFolder(listOf("mp3", "Huey Lewis", "Sports")))
        assertEquals("1985 - Sports", TrackName.albumFolder(listOf("1985 - Sports")))
        assertNull(TrackName.albumFolder(listOf("Adele")))
        assertNull(TrackName.albumFolder(emptyList()))
    }

    @Test
    fun missLogWaitsBeforeRetrying() {
        var stored: String? = null
        var clock = 1_000L
        val log = MissLog({ stored }, { stored = it }, retryAfterMs = 100, now = { clock })
        log.missed("song")
        assertEquals(true, log.recentlyMissed("song"))
        log.save()
        clock += 50
        assertEquals(true, MissLog({ stored }, { stored = it }, 100, { clock }).recentlyMissed("song"))
        clock += 60
        assertEquals(false, log.recentlyMissed("song"))
        log.save()
        assertEquals("{}", stored, "expired entries are dropped")
    }
}
