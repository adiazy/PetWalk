package com.karaoketv.media

import android.net.Uri
import com.karaoketv.core.lyrics.LrcParser
import com.karaoketv.core.lyrics.LrcWriter
import com.karaoketv.core.lyrics.Lyrics
import com.karaoketv.core.lyrics.LyricsProvider
import com.karaoketv.core.lyrics.LyricsQuery

/** Finds `Song.lrc` next to `Song.mp3` on the network share. */
class SmbSidecarLrcProvider(private val connection: () -> SmbConnection?) : LyricsProvider {
    override fun find(query: LyricsQuery): Lyrics? {
        val local = query.localPath?.takeIf { it.startsWith("smb://") } ?: return null
        val smb = connection() ?: return null
        val path = smb.pathOf(Uri.parse(local)) ?: return null
        val base = path.substringBeforeLast('.')
        for (ext in listOf("lrc", "LRC", "txt")) {
            val text = runCatching { smb.readText("$base.$ext") }.getOrNull() ?: continue
            val lyrics = LrcParser.parse(text, source = "Network .${ext.lowercase()}")
            if (lyrics.lines.isNotEmpty()) return lyrics
        }
        return null
    }

    /**
     * Saves online lyrics next to the song (`Song.lrc`, or `Song.txt` for unsynced text) so the
     * share builds up its own lyrics library. Returns false if the share is read-only.
     */
    fun save(songPath: String, lyrics: Lyrics): Boolean {
        val smb = connection() ?: return false
        val path = smb.pathOf(Uri.parse(songPath)) ?: return false
        val ext = if (lyrics.synced) "lrc" else "txt"
        return runCatching { smb.writeText("${path.substringBeforeLast('.')}.$ext", LrcWriter.write(lyrics)) }.isSuccess
    }
}
