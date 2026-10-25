package com.karaoketv.media

import android.net.Uri
import com.karaoketv.core.lyrics.Lyrics
import com.karaoketv.core.lyrics.LyricsQuery

data class Song(
    val id: String,
    val title: String,
    val artist: String?,
    val album: String? = null,
    val durationMs: Long? = null,
    /** Audio to play; null means lyrics-only mode (sing along to music playing elsewhere). */
    val uri: Uri? = null,
    val path: String? = null,
    val preloadedLyrics: Lyrics? = null,
) {
    fun toQuery() = LyricsQuery(title, artist, album, durationMs, path)
}
