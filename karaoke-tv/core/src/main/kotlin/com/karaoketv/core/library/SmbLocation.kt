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
        return if (parts.size == 2) Parsed(parts[0].trim(), parts[1].trim()) else Parsed(null, withoutNumber)
    }

    /**
     * Like [parse], but when the file name has no artist, takes it from the folders below the
     * music root: `Artist\Album\song.mp3` or `Artist\song.mp3`.
     */
    fun parse(fileName: String, foldersBelowRoot: List<String>): Parsed {
        val parsed = parse(fileName)
        if (parsed.artist != null || foldersBelowRoot.isEmpty()) return parsed
        val folder = if (foldersBelowRoot.size >= 2) foldersBelowRoot[foldersBelowRoot.size - 2] else foldersBelowRoot[0]
        return if (folder.matches(GENERIC_FOLDER)) parsed else parsed.copy(artist = folder.trim())
    }

    private val GENERIC_FOLDER = Regex(
        """(?i)\s*(various( artists)?|va|compilations?|soundtracks?|ost|karaoke|misc(ellaneous)?|music|mp3s?|songs|singles|unknown( artist)?|new folder.*|\d+)\s*""",
    )
}
