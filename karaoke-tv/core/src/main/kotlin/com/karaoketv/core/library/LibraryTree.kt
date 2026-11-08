package com.karaoketv.core.library

import java.text.Normalizer

/** What grouping needs to know about a song. */
data class TrackInfo(
    val artist: String?,
    val album: String?,
    val title: String,
    val track: Int? = null,
    val disc: Int? = null,
    val year: Int? = null,
)

data class AlbumNode<T>(val name: String, val year: Int?, val songs: List<T>)

data class ArtistNode<T>(val name: String, val albums: List<AlbumNode<T>>) {
    val songCount get() = albums.sumOf { it.songs.size }
}

/**
 * Builds the Artist → Album → Song menu.
 * - Artists A–Z ("Unknown artist" last). Spelling variants ("&"/"and", case, accents,
 *   punctuation, a leading "The") are one artist, shown with the most common spelling.
 * - Albums by year, then name; songs without an album go in "Other songs" at the end.
 * - Songs by disc, track number, then title.
 */
object LibraryTree {
    const val UNKNOWN_ARTIST = "Unknown artist"
    const val NO_ALBUM = "Other songs"

    fun <T> build(items: List<T>, info: (T) -> TrackInfo): List<ArtistNode<T>> {
        val withInfo = items.map { it to info(it) }
        return withInfo
            .groupBy { (_, i) -> key(i.artist) }
            .map { (artistKey, group) ->
                val name = if (artistKey.isEmpty()) UNKNOWN_ARTIST else mostCommon(group.mapNotNull { it.second.artist?.trim() })
                val albums = group
                    .groupBy { (_, i) -> key(i.album) }
                    .map { (albumKey, songs) ->
                        val albumName = if (albumKey.isEmpty()) NO_ALBUM else mostCommon(songs.mapNotNull { it.second.album?.trim() })
                        val sorted = songs.sortedWith(
                            compareBy<Pair<T, TrackInfo>>(
                                { it.second.disc ?: 1 },
                                { it.second.track ?: Int.MAX_VALUE },
                                { it.second.title.lowercase() },
                            ),
                        )
                        AlbumNode(albumName, songs.mapNotNull { it.second.year }.minOrNull(), sorted.map { it.first }) to albumKey.isEmpty()
                    }
                    .sortedWith(
                        compareBy<Pair<AlbumNode<T>, Boolean>>(
                            { it.second },
                            { it.first.year ?: Int.MAX_VALUE },
                            { it.first.name.lowercase() },
                        ),
                    )
                    .map { it.first }
                ArtistNode(name, albums) to artistKey.isEmpty()
            }
            .sortedWith(compareBy<Pair<ArtistNode<T>, Boolean>>({ it.second }, { sortName(it.first.name) }))
            .map { it.first }
    }

    /** Grouping key: case, accents, punctuation, "&"/"and" and a leading "The" don't matter. */
    fun key(name: String?): String {
        val trimmed = name?.trim().orEmpty()
        if (trimmed.isEmpty() || trimmed.equals("<unknown>", ignoreCase = true) ||
            trimmed.equals("unknown artist", ignoreCase = true) || trimmed.equals("unknown album", ignoreCase = true)
        ) {
            return ""
        }
        return Normalizer.normalize(trimmed.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("""\p{M}+"""), "")
            .replace("&", " and ")
            .replace("+", " and ")
            .replace(Regex("""^the\s+"""), "")
            .replace(Regex("""[^\p{L}\p{N}]+"""), "")
    }

    private fun sortName(name: String) = name.lowercase().removePrefix("the ").trim()

    private fun mostCommon(names: List<String>): String =
        names.groupingBy { it }.eachCount().maxWithOrNull(compareBy<Map.Entry<String, Int>> { it.value }.thenBy { it.key })?.key
            ?: names.firstOrNull().orEmpty()
}
