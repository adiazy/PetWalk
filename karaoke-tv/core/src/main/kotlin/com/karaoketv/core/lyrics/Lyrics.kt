package com.karaoketv.core.lyrics

/** A word inside an "enhanced" LRC line (`<mm:ss.xx>word`). Times are absolute song positions. */
data class LyricWord(val startMs: Long, val text: String)

data class LyricLine(
    val startMs: Long,
    val text: String,
    val words: List<LyricWord> = emptyList(),
)

data class Lyrics(
    val lines: List<LyricLine>,
    /** True when lines carry real timestamps; false for plain text that can only be scrolled. */
    val synced: Boolean,
    /** Human-readable origin, e.g. "LRCLIB" or "Local .lrc". */
    val source: String,
    val title: String? = null,
    val artist: String? = null,
) {
    /** Index of the line being sung at [positionMs], or -1 before the first line. */
    fun lineIndexAt(positionMs: Long): Int {
        if (!synced) return -1
        var lo = 0
        var hi = lines.lastIndex
        var found = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (lines[mid].startMs <= positionMs) {
                found = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return found
    }

    /** When the line at [index] stops being current. */
    fun lineEndMs(index: Int, songDurationMs: Long? = null): Long {
        val start = lines[index].startMs
        return lines.getOrNull(index + 1)?.startMs
            ?: songDurationMs?.takeIf { it > start }
            ?: (start + DEFAULT_LAST_LINE_MS)
    }

    /** Fraction (0..1) of the line's characters that should be highlighted at [positionMs]. */
    fun progressAt(index: Int, positionMs: Long, songDurationMs: Long? = null): Float {
        val line = lines.getOrNull(index) ?: return 0f
        if (positionMs < line.startMs) return 0f
        val end = lineEndMs(index, songDurationMs)

        if (line.words.isNotEmpty()) {
            val total = line.words.sumOf { it.text.length }.coerceAtLeast(1)
            var done = 0
            for ((i, word) in line.words.withIndex()) {
                val wordEnd = line.words.getOrNull(i + 1)?.startMs ?: end
                if (positionMs < word.startMs) break
                if (positionMs >= wordEnd) {
                    done += word.text.length
                    continue
                }
                val f = (positionMs - word.startMs).toFloat() / (wordEnd - word.startMs).coerceAtLeast(1)
                return ((done + f * word.text.length) / total).coerceIn(0f, 1f)
            }
            return (done.toFloat() / total).coerceIn(0f, 1f)
        }

        // Without word timing, assume the line is sung at a natural pace rather than stretching it
        // across an instrumental gap before the next line.
        val singEnd = minOf(end, line.startMs + maxOf(MIN_LINE_MS, line.text.length * MS_PER_CHAR))
        val f = (positionMs - line.startMs).toFloat() / (singEnd - line.startMs).coerceAtLeast(1)
        return f.coerceIn(0f, 1f)
    }

    companion object {
        const val DEFAULT_LAST_LINE_MS = 5_000L
        private const val MIN_LINE_MS = 1_500L
        private const val MS_PER_CHAR = 180L
    }
}
