package com.karaoketv.core.library

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SmbLocationTest {
    @Test
    fun parsesWindowsAndUrlForms() {
        val expected = SmbLocation("192.168.1.99", "Public", "Shared Music")
        assertEquals(expected, SmbLocation.parse("""\\192.168.1.99\Public\Shared Music"""))
        assertEquals(expected, SmbLocation.parse("smb://192.168.1.99/Public/Shared Music/"))
        assertEquals(expected, SmbLocation.parse("//192.168.1.99/Public/Shared Music"))
        assertEquals(SmbLocation("nas", "Music"), SmbLocation.parse("""\\nas\Music"""))
        assertEquals("""\\192.168.1.99\Public\Shared Music""", expected.toString())
        assertEquals("""Shared Music\a.mp3""", expected.child("a.mp3"))
        assertNull(SmbLocation.parse("""\\onlyhost"""))
    }

    @Test
    fun guessesArtistAndTitleFromFileNames() {
        assertEquals(TrackName.Parsed("Queen", "Bohemian Rhapsody"), TrackName.parse("01 - Queen - Bohemian Rhapsody.mp3"))
        assertEquals(TrackName.Parsed(null, "Yesterday"), TrackName.parse("03. Yesterday.flac"))
        assertEquals(TrackName.Parsed(null, "Hey Jude"), TrackName.parse("07 Hey Jude.m4a"))
        assertEquals(TrackName.Parsed("50 Cent", "In Da Club"), TrackName.parse("50 Cent - In Da Club.mp3"))
        assertEquals(TrackName.Parsed(null, "My Song"), TrackName.parse("My_Song.mp3"))
    }

    @Test
    fun takesArtistFromFoldersWhenFileNameHasNone() {
        assertEquals(
            TrackName.Parsed("Queen", "Bohemian Rhapsody"),
            TrackName.parse("01 Bohemian Rhapsody.mp3", listOf("Queen", "A Night at the Opera")),
        )
        assertEquals(TrackName.Parsed("Adele", "Hello"), TrackName.parse("Hello.mp3", listOf("Adele")))
        assertEquals(
            TrackName.Parsed("Band", "Song"),
            TrackName.parse("Band - Song.mp3", listOf("Other", "Album")),
        )
        assertEquals(TrackName.Parsed(null, "Song"), TrackName.parse("Song.mp3", listOf("Various Artists", "Hits")))
        assertEquals(TrackName.Parsed(null, "Song"), TrackName.parse("Song.mp3", emptyList()))
    }
}
