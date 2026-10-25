package com.karaoketv.core.lyrics

import java.io.File
import java.security.MessageDigest

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

/** Disk cache around a (network) provider so songs work offline after the first play. */
class CachedLyricsProvider(
    private val dir: File,
    private val delegate: LyricsProvider,
) : LyricsProvider {
    override fun find(query: LyricsQuery): Lyrics? {
        val key = key(query)
        val synced = File(dir, "$key.lrc")
        val plain = File(dir, "$key.txt")
        val cached = if (synced.exists()) synced else plain.takeIf { it.exists() }
        if (cached != null) {
            val lines = cached.readLines()
            val header = lines.firstOrNull()?.takeIf { it.startsWith(SOURCE_HEADER) }
            val body = if (header != null) lines.drop(1) else lines
            return LrcParser.parse(body.joinToString("\n"), header?.removePrefix(SOURCE_HEADER) ?: "Cache")
        }

        val result = delegate.find(query) ?: return null
        runCatching {
            dir.mkdirs()
            (if (result.synced) synced else plain).writeText("$SOURCE_HEADER${result.source}\n" + LrcWriter.write(result))
        }
        return result
    }

    private fun key(q: LyricsQuery): String {
        val raw = "${q.artist.orEmpty()}|${q.title}|${q.durationMs?.div(1000) ?: ""}".lowercase()
        return MessageDigest.getInstance("SHA-1").digest(raw.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val SOURCE_HEADER = "#source:"
    }
}
