package com.karaoketv.core.lyrics

/**
 * File names used in the shared lyrics folder. Downloads are saved as `Artist - Title.lrc`;
 * when reading, a file named after the audio file (`01 Song.lrc`) is accepted too, so lyrics
 * dropped in by hand are found.
 */
object LyricsFileNames {
    private val illegal = Regex("""[\\/:*?"<>|\x00-\x1F]""")

    /** Base names (without extension), best first. */
    fun candidates(artist: String?, title: String, audioFileName: String? = null): List<String> = buildList {
        val cleanTitle = sanitize(title)
        val cleanArtist = artist?.let(::sanitize)?.takeIf { it.isNotEmpty() }
        if (cleanTitle.isNotEmpty()) add(if (cleanArtist != null) "$cleanArtist - $cleanTitle" else cleanTitle)
        audioFileName?.substringBeforeLast('.')?.let(::sanitize)?.takeIf { it.isNotEmpty() }?.let(::add)
    }.distinct()

    fun sanitize(name: String): String =
        name.replace(illegal, " ").replace(Regex("""\s{2,}"""), " ").trim().trimEnd('.').trim().take(150)
}
