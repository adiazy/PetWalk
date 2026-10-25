package com.karaoketv.core.lyrics

/**
 * Parses LRC lyrics, including multiple timestamps per line (`[00:10.00][01:20.00]chorus`),
 * `[offset:+/-ms]`, metadata tags and enhanced word timing (`<00:10.50>word`).
 * Text without any timestamps is returned as unsynced lyrics.
 */
object LrcParser {
    private val timeTag = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")
    private val metaTag = Regex("""^\[([A-Za-z#]+):(.*)]$""")
    private val wordTag = Regex("""<(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?>""")

    fun parse(text: String, source: String = "LRC"): Lyrics {
        var offsetMs = 0L
        var title: String? = null
        var artist: String? = null
        val timed = mutableListOf<Pair<Long, String>>()
        val plain = mutableListOf<String>()

        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue

            val stamps = mutableListOf<Long>()
            var rest = line
            while (true) {
                val m = timeTag.find(rest) ?: break
                if (m.range.first != 0) break
                stamps += toMs(m)
                rest = rest.substring(m.range.last + 1)
            }

            if (stamps.isEmpty()) {
                val meta = metaTag.find(line)
                if (meta != null) {
                    val value = meta.groupValues[2].trim()
                    when (meta.groupValues[1].lowercase()) {
                        "offset" -> offsetMs = value.removePrefix("+").toLongOrNull() ?: 0L
                        "ti" -> title = value.ifEmpty { null }
                        "ar" -> artist = value.ifEmpty { null }
                    }
                } else {
                    plain += line
                }
                continue
            }
            stamps.forEach { timed += it to rest }
        }

        if (timed.isEmpty()) {
            return Lyrics(plain.map { LyricLine(0, it) }, synced = false, source, title, artist)
        }

        // A positive LRC offset means lyrics should appear earlier.
        fun shift(t: Long) = (t - offsetMs).coerceAtLeast(0)
        val lines = timed.map { (start, body) ->
            val (lineText, words) = parseWords(body, ::shift)
            LyricLine(shift(start), lineText, words)
        }.sortedBy { it.startMs }

        return Lyrics(lines, synced = true, source, title, artist)
    }

    private fun parseWords(body: String, shift: (Long) -> Long): Pair<String, List<LyricWord>> {
        val tags = wordTag.findAll(body).toList()
        if (tags.isEmpty()) return body.trim() to emptyList()

        val words = mutableListOf<LyricWord>()
        val leading = body.substring(0, tags.first().range.first)
        for ((i, tag) in tags.withIndex()) {
            val textEnd = tags.getOrNull(i + 1)?.range?.first ?: body.length
            var word = body.substring(tag.range.last + 1, textEnd)
            if (i == 0) word = leading + word
            words += LyricWord(shift(toMs(tag)), word)
        }
        // Keep word texts consistent with the trimmed line text so highlight fractions line up.
        val first = words.first()
        words[0] = first.copy(text = first.text.trimStart())
        val last = words.last()
        words[words.lastIndex] = last.copy(text = last.text.trimEnd())
        return words.joinToString("") { it.text } to words
    }

    private fun toMs(m: MatchResult): Long {
        val min = m.groupValues[1].toLong()
        val sec = m.groupValues[2].toLong()
        val frac = m.groupValues[3]
        val fracMs = when (frac.length) {
            0 -> 0L
            1 -> frac.toLong() * 100
            2 -> frac.toLong() * 10
            else -> frac.toLong()
        }
        return min * 60_000 + sec * 1_000 + fracMs
    }
}
