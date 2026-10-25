package com.karaoketv.core.lyrics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LrcParserTest {
    @Test
    fun parsesTimestampsMetadataAndRepeatedLines() {
        val lyrics = LrcParser.parse(
            """
            [ti:Song]
            [ar:Band]
            [00:12.50]First line
            [00:20.00][01:05.5]Chorus
            [00:30.123]Third
            """.trimIndent(),
        )
        assertTrue(lyrics.synced)
        assertEquals("Song", lyrics.title)
        assertEquals("Band", lyrics.artist)
        assertEquals(listOf(12_500L, 20_000L, 30_123L, 65_500L), lyrics.lines.map { it.startMs })
        assertEquals(listOf("First line", "Chorus", "Third", "Chorus"), lyrics.lines.map { it.text })
    }

    @Test
    fun appliesOffset() {
        val lyrics = LrcParser.parse("[offset:+500]\n[00:10.00]Hi")
        assertEquals(9_500L, lyrics.lines.single().startMs)
    }

    @Test
    fun parsesEnhancedWordTiming() {
        val line = LrcParser.parse("[00:01.00]<00:01.00>Hello <00:01.50>big <00:02.00>world<00:03.00>").lines.single()
        assertEquals("Hello big world", line.text)
        assertEquals(listOf(1_000L, 1_500L, 2_000L, 3_000L), line.words.map { it.startMs })
        assertEquals("Hello big world", line.words.joinToString("") { it.text })
    }

    @Test
    fun plainTextIsUnsynced() {
        val lyrics = LrcParser.parse("Just some\nlyrics")
        assertFalse(lyrics.synced)
        assertEquals(2, lyrics.lines.size)
        assertEquals(-1, lyrics.lineIndexAt(10_000))
    }

    @Test
    fun lineIndexAndProgress() {
        val lyrics = LrcParser.parse("[00:01.00]aaaa\n[00:10.00]bbbb")
        assertEquals(-1, lyrics.lineIndexAt(500))
        assertEquals(0, lyrics.lineIndexAt(1_000))
        assertEquals(0, lyrics.lineIndexAt(9_999))
        assertEquals(1, lyrics.lineIndexAt(60_000))
        // Short plain line fills over the 1.5s minimum, not the whole 9s gap.
        assertEquals(0.5f, lyrics.progressAt(0, 1_750), 0.01f)
        assertEquals(1f, lyrics.progressAt(0, 5_000))
    }

    @Test
    fun wordProgressFollowsWordTimes() {
        val lyrics = LrcParser.parse("[00:00.00]<00:00.00>ab<00:01.00>cd<00:02.00>")
        assertEquals(0.25f, lyrics.progressAt(0, 500), 0.01f)
        assertEquals(0.5f, lyrics.progressAt(0, 1_000), 0.01f)
        assertEquals(1f, lyrics.progressAt(0, 2_500), 0.01f)
    }

    @Test
    fun writerRoundTrips() {
        val original = LrcParser.parse("[ti:T]\n[00:01.00]<00:01.00>Hello <00:01.50>world\n[01:02.30]Bye")
        assertEquals(original, LrcParser.parse(LrcWriter.write(original)))
    }
}
