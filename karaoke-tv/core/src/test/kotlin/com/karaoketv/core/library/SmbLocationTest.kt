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
        assertEquals(TrackName.Parsed("Simply Red", "Sunrise"), TrackName.parse("Simply Red - 01 - Sunrise.flac"))
        assertEquals(TrackName.Parsed("Blink", "182 Days"), TrackName.parse("Blink - 182 Days.mp3"))
    }

    @Test
    fun takesArtistFromFoldersWhenFileNameHasNone() {
        assertEquals(
            TrackName.Parsed("Queen", "Bohemian Rhapsody"),
            TrackName.parse("01 Bohemian Rhapsody.mp3", listOf("Queen", "A Night at the Opera")),
        )
        assertEquals(TrackName.Parsed("Adele", "Hello"), TrackName.parse("Hello.mp3", listOf("Adele")))
        assertEquals(TrackName.Parsed(null, "Song"), TrackName.parse("Song.mp3", listOf("Various Artists", "Hits")))
        assertEquals(TrackName.Parsed(null, "Song"), TrackName.parse("Song.mp3", emptyList()))
    }

    @Test
    fun artistFolderWinsOverFileName() {
        // Spelling differences in file names don't split one artist into several
        for (fileName in listOf(
            "Huey Lewis And The News - 01 - The Heart of Rock and Roll.flac",
            "Huey Lewis, Gwyneth Paltrow - 15 - Cruisin'.flac",
        )) {
            assertEquals("Huey Lewis & The News", TrackName.parse(fileName, listOf("!flac", "Huey Lewis & The News", "Sports")).artist)
        }
        assertEquals(
            TrackName.Parsed("Jose Jose", "El Triste"),
            TrackName.parse("José José - 03 - El Triste.flac", listOf("!flac", "Jose Jose", "Grandes Exitos")),
        )
        // Deep albums (Album\Disc) still belong to the artist folder, not the album
        assertEquals(
            "Cristian Castro",
            TrackName.parse("01 - Enamorados.flac", listOf("!flac", "Cristian Castro", "En Primera Fila", "Dia 1")).artist,
        )
        // No artist folder: compilations, songs in the root, album-named first folder
        assertEquals(
            TrackName.Parsed("Ammonia", "Hymn"),
            TrackName.parse("Ammonia - 26 - Hymn.mp3", listOf("Compilations", "New Wave Pop Classics")),
        )
        assertEquals(TrackName.Parsed("Band", "Song"), TrackName.parse("Band - Song.mp3", emptyList()))
        assertEquals(
            TrackName.Parsed("Queen", "Bohemian Rhapsody"),
            TrackName.parse("Queen - 11 - Bohemian Rhapsody.mp3", listOf("1975 - A Night at the Opera")),
        )
    }

    @Test
    fun dropsStrayDashFromEmptyTrackNumber() {
        assertEquals(
            TrackName.Parsed("Ella Fitzgerald", "Lady Is A Tramp"),
            TrackName.parse("Ella Fitzgerald -  - Lady Is A Tramp.mp3"),
        )
        assertEquals(
            TrackName.Parsed("Pet Shop Boys", "Go West"),
            TrackName.parse("Pet Shop Boys - 03 - 03 - Go West.mp3"),
        )
        assertEquals(TrackName.Parsed("Prince", "1999"), TrackName.parse("Prince - 01 - 1999.mp3"))
    }

    @Test
    fun skipsFormatFolders() {
        // Artist\song directly under a format folder: the artist, not "!flac"
        assertEquals(
            TrackName.Parsed("Simply Red", "Holding Back the Years"),
            TrackName.parse("Holding_Back_the_Years.flac", listOf("!flac", "Simply Red")),
        )
        assertEquals(
            TrackName.Parsed("Fey", "Muevelo"),
            TrackName.parse("01 Muevelo.mp3", listOf("mp3", "Fey")),
        )
        assertEquals(
            TrackName.Parsed("Ratt", "Lay It Down"),
            TrackName.parse("05 - Lay It Down.flac", listOf("FLAC 24bit", "Ratt", "Invasion of Your Privacy")),
        )
        // A song loose in the format folder itself has no folder artist
        assertEquals(TrackName.Parsed(null, "Song"), TrackName.parse("Song.mp3", listOf("mp3")))
    }

    @Test
    fun ignoresFormatNameUsedAsArtist() {
        assertEquals(
            TrackName.Parsed("Guns N' Roses", "Welcome To The Jungle"),
            TrackName.parse("!Flac - 01 - Welcome To The Jungle.flac", listOf("!flac", "Guns N' Roses", "Greatest Hits")),
        )
        assertEquals(
            TrackName.Parsed("Gloria Estefan", "Mi Tierra"),
            TrackName.parse("Mp3 - 02 - Mi Tierra.mp3", listOf("mp3", "Gloria Estefan")),
        )
        // Real artists that merely contain a format word are kept
        assertEquals(TrackName.Parsed("Mp3 Kings", "Song"), TrackName.parse("Mp3 Kings - Song.mp3", listOf("mp3")))
    }
}
