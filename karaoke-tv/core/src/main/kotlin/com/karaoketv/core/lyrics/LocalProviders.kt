package com.karaoketv.core.lyrics

import java.io.File

/** Finds `song.lrc` (or `song.txt`) next to `song.mp3`. */
class SidecarLrcProvider : LyricsProvider {
    override fun find(query: LyricsQuery): Lyrics? {
        val audio = query.localPath?.let(::File) ?: return null
        val dir = audio.parentFile ?: return null
        val base = audio.nameWithoutExtension
        val candidates = dir.listFiles { f -> f.isFile && f.nameWithoutExtension == base } ?: return null
        val file = candidates.firstOrNull { it.extension.equals("lrc", true) }
            ?: candidates.firstOrNull { it.extension.equals("txt", true) }
            ?: return null
        return LrcParser.parse(file.readText(), source = "Local .${file.extension.lowercase()}")
            .takeIf { it.lines.isNotEmpty() }
    }
}
