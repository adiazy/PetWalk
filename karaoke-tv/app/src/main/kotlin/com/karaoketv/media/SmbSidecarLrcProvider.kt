package com.karaoketv.media

import android.net.Uri
import com.karaoketv.core.lyrics.LrcParser
import com.karaoketv.core.lyrics.LrcWriter
import com.karaoketv.core.lyrics.Lyrics
import com.karaoketv.core.lyrics.LyricsFileNames
import com.karaoketv.core.lyrics.LyricsProvider
import com.karaoketv.core.lyrics.LyricsQuery

/**
 * Lyrics files on the network share: first the shared lyrics folder
 * (`Shared Music\.lrc\Artist - Title.lrc`), then `Song.lrc` next to `Song.mp3`.
 */
class SmbSidecarLrcProvider(
    private val connection: () -> SmbConnection?,
    /** Share-relative path of the shared lyrics folder, or null if none is usable. */
    private val lyricsDir: () -> String?,
) : LyricsProvider {
    override fun find(query: LyricsQuery): Lyrics? {
        val smb = connection() ?: return null

        lyricsDir()?.let { dir ->
            for (name in fileNames(query)) {
                val text = runCatching { smb.readText("$dir\\$name.lrc") }.getOrNull() ?: continue
                val lyrics = LrcParser.parse(text, source = FOLDER_SOURCE)
                if (lyrics.lines.isNotEmpty()) return lyrics
            }
        }

        val local = query.localPath?.takeIf { it.startsWith("smb://") } ?: return null
        val path = smb.pathOf(Uri.parse(local)) ?: return null
        val base = path.substringBeforeLast('.')
        for (ext in listOf("lrc", "LRC", "txt")) {
            val text = runCatching { smb.readText("$base.$ext") }.getOrNull() ?: continue
            val lyrics = LrcParser.parse(text, source = "Network .${ext.lowercase()}")
            if (lyrics.lines.isNotEmpty()) return lyrics
        }
        return null
    }

    /** Saves [lyrics] as `Artist - Title.lrc` in the lyrics folder (creating it). Blocking. */
    fun save(query: LyricsQuery, lyrics: Lyrics): Boolean {
        val smb = connection() ?: return false
        val dir = lyricsDir() ?: return false
        val name = fileNames(query).firstOrNull() ?: return false
        return runCatching {
            smb.ensureFolder(dir)
            smb.writeText("$dir\\$name.lrc", LrcWriter.write(lyrics))
        }.isSuccess
    }

    /** Whether the lyrics folder (listed as [folderFiles]) already has a file for [query]. */
    fun hasFile(query: LyricsQuery, folderFiles: Set<String>) =
        fileNames(query).any { "${it.lowercase()}.lrc" in folderFiles }

    private fun fileNames(query: LyricsQuery): List<String> {
        val audioName = query.localPath?.let {
            if (it.startsWith("smb://")) Uri.parse(it).lastPathSegment else it.substringAfterLast('/')
        }
        return LyricsFileNames.candidates(query.artist, query.title, audioName)
    }

    companion object {
        const val FOLDER_SOURCE = "Lyrics folder"

        /** Lyrics that came from a file on the share, so there's nothing to download. */
        fun isFromShare(lyrics: Lyrics) = lyrics.source == FOLDER_SOURCE || lyrics.source.startsWith("Network")
    }
}
