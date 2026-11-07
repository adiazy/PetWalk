package com.karaoketv.core.library

/**
 * A folder on a Windows/NAS network share, e.g. `\\192.168.1.99\Public\Shared Music`.
 * [path] is relative to the share, backslash-separated, without leading/trailing slashes.
 */
data class SmbLocation(val host: String, val share: String, val path: String = "") {
    fun child(name: String) = if (path.isEmpty()) name else "$path\\$name"

    override fun toString() = "\\\\$host\\$share" + if (path.isEmpty()) "" else "\\$path"

    companion object {
        /** Accepts `\\host\share\path`, `//host/share/path` and `smb://host/share/path`. */
        fun parse(input: String): SmbLocation? {
            var s = input.trim().replace('/', '\\')
            if (s.startsWith("smb:", ignoreCase = true)) s = s.substring(4)
            val parts = s.split('\\').filter { it.isNotBlank() }.map { it.trim() }
            if (parts.size < 2) return null
            return SmbLocation(parts[0], parts[1], parts.drop(2).joinToString("\\"))
        }
    }
}

/** Guesses artist and title from a file name like `01 - Queen - Bohemian Rhapsody.mp3`. */
object TrackName {
    private val trackNumber = Regex("""^\d{1,3}\s*[-.)_]\s*(?=\D)|^0\d\s+""")
    private val separator = Regex("""\s+[-–—]\s+""")

    data class Parsed(val artist: String?, val title: String)

    fun parse(fileName: String): Parsed {
        val base = fileName.substringBeforeLast('.').replace('_', ' ').trim()
        val withoutNumber = trackNumber.replaceFirst(base, "").trim().ifEmpty { base }
        val parts = withoutNumber.split(separator, limit = 2)
        if (parts.size < 2) return Parsed(null, withoutNumber)
        // "Artist - 07 - Title": the track number can also come after the artist.
        // "Artist -  - Title" (empty track number) leaves a stray dash in front of the title.
        // "Artist - 03 - 03 - Title": a title tag that already had the number repeats it.
        var title = parts[1].trim()
        repeat(2) { title = trackNumber.replaceFirst(title, "").trimStart('-', '–', '—', ' ').trim() }
        return Parsed(parts[0].trim(), title.ifEmpty { parts[1].trim() })
    }

    /**
     * Like [parse], but the artist folder wins: in `Artist\Album\song.mp3` or `Artist\song.mp3`
     * (the first folder below the music root) every song belongs to `Artist`, even when file
     * names spell it differently ("Huey Lewis & The News" / "Huey Lewis And The News", accents,
     * duets). The artist from the file name is used only when there's no artist folder:
     * songs directly in the root, in a "Compilations"/"Various Artists"-type folder, or when the
     * first folder looks like an album ("1985 - Sports"). Format folders (`mp3\`, `!flac\`) are
     * skipped, and a format name is never taken as the artist.
     */
    fun parse(fileName: String, foldersBelowRoot: List<String>): Parsed {
        val fromName = parse(fileName)
        // "Mp3 - 01 - Title.mp3": a renaming tool used the format folder as the artist
        val nameArtist = fromName.artist?.takeUnless { it.matches(FORMAT_FOLDER) }
        val artistFolder = foldersBelowRoot
            .dropWhile { it.matches(FORMAT_FOLDER) }
            .firstOrNull()
            ?.trim()
            ?.takeUnless { it.isEmpty() || it.matches(GENERIC_FOLDER) || it.contains(ALBUM_FOLDER) }
        return Parsed(artistFolder ?: nameArtist, fromName.title)
    }

    /** `1985 - Sports`, `2004 Live`: an album, not an artist. */
    private val ALBUM_FOLDER = Regex("""^\s*(19|20)\d{2}\b""")

    /** `mp3`, `!flac`, `FLAC 24bit`, `mp3 320kbps`… */
    private val FORMAT_FOLDER = Regex(
        """(?i)[!_\-\s]*(mp3|flac|m4a|aac|ogg|opus|wav|wma)([\s_\-]*(\d+\s*(bit|khz|kbps)?|hi-?res|lossless))*\s*""",
    )

    private val GENERIC_FOLDER = Regex(
        """(?i)\s*(various( artists)?|va|compilations?|soundtracks?|ost|karaoke|misc(ellaneous)?|music|mp3s?|songs|singles|unknown( artist)?|new folder.*|\d+)\s*""",
    )
}
