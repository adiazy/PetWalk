package com.karaoketv.media

import android.net.Uri
import com.karaoketv.core.library.TrackInfo
import com.karaoketv.core.library.TrackTags
import com.karaoketv.core.lyrics.Lyrics
import com.karaoketv.core.lyrics.LyricsQuery

data class Song(
    val id: String,
    val title: String,
    /** The song's own artist (used for lyrics). */
    val artist: String?,
    val album: String? = null,
    val durationMs: Long? = null,
    /** Audio to play; null means lyrics-only mode (sing along to music playing elsewhere). */
    val uri: Uri? = null,
    val path: String? = null,
    val preloadedLyrics: Lyrics? = null,
    /** A lyrics file exists for this song (next to it or in the lyrics folder). */
    val hasLyricsFile: Boolean = false,
    /** The artist it's listed under in My music: album artist tag, artist folder, then artist tag. */
    val libraryArtist: String? = artist,
    val track: Int? = null,
    val disc: Int? = null,
    val year: Int? = null,
    /** File size and modification time, to tell whether stored tags are still valid. */
    val sizeBytes: Long? = null,
    val modifiedMs: Long? = null,
    /** "Artist - Title" from the file name, so lyrics saved under that name are still found. */
    val nameGuess: String? = null,
    /** Tags were read (or the file has none), so there's nothing left to read. */
    val tagsRead: Boolean = false,
) {
    fun toQuery() = LyricsQuery(title, artist, album, durationMs, path, extraFileNames = listOfNotNull(nameGuess))

    fun treeInfo() = TrackInfo(libraryArtist, album, title, track, disc, year)

    /** Tags win over guesses from file and folder names. */
    fun applyTags(tags: TrackTags?): Song {
        if (tags == null) return copy(tagsRead = true)
        return copy(
            title = tags.title ?: title,
            artist = tags.artist ?: artist,
            libraryArtist = tags.albumArtist ?: libraryArtist ?: tags.artist,
            album = tags.album ?: album,
            track = tags.track ?: track,
            disc = tags.disc ?: disc,
            year = tags.year ?: year,
            durationMs = tags.durationMs ?: durationMs,
            tagsRead = true,
        )
    }
}
