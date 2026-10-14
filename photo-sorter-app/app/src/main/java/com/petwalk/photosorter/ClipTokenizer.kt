package com.petwalk.photosorter

import java.io.InputStream
import java.util.zip.GZIPInputStream

/**
 * OpenAI CLIP's byte-level BPE tokenizer, ported from CLIP's simple_tokenizer.py.
 * [bpeGz] is the gzipped merges file shipped with CLIP (bpe_simple_vocab_16e6.txt.gz).
 */
class ClipTokenizer(bpeGz: InputStream) {

    private val byteEncoder: List<Pair<Int, Char>> = bytesToUnicode()
    private val byteToChar = CharArray(256).also { arr -> byteEncoder.forEach { (b, c) -> arr[b] = c } }
    private val encoder = HashMap<String, Int>()
    private val ranks = HashMap<String, Int>() // "first second" -> rank
    private val cache = HashMap<String, List<String>>()

    init {
        val lines = GZIPInputStream(bpeGz).bufferedReader(Charsets.UTF_8).use { it.readLines() }
        val merges = lines.subList(1, 49152 - 256 - 2 + 1).map { it.split(" ") }
        val vocab = ArrayList<String>(49408)
        byteEncoder.forEach { vocab += it.second.toString() }
        byteEncoder.forEach { vocab += it.second.toString() + "</w>" }
        merges.forEach { vocab += it[0] + it[1] }
        vocab += SOT
        vocab += EOT
        vocab.forEachIndexed { i, token -> encoder[token] = i }
        merges.forEachIndexed { i, m -> ranks[m[0] + " " + m[1]] = i }
    }

    /** Token ids for [text], wrapped in start/end tokens and cut to CLIP's 77-token context. */
    fun encode(text: String): LongArray {
        val clean = WHITESPACE.replace(text, " ").trim().lowercase()
        val ids = mutableListOf(encoder.getValue(SOT))
        for (match in PATTERN.findAll(clean)) {
            val mapped = buildString {
                match.value.toByteArray(Charsets.UTF_8).forEach { append(byteToChar[it.toInt() and 0xff]) }
            }
            bpe(mapped).forEach { ids += encoder.getValue(it) }
        }
        val capped = if (ids.size > CONTEXT - 1) ids.subList(0, CONTEXT - 1) else ids
        return (capped + encoder.getValue(EOT)).map { it.toLong() }.toLongArray()
    }

    private fun bpe(token: String): List<String> = cache.getOrPut(token) {
        var word: List<String> = token.dropLast(1).map { it.toString() } + (token.last().toString() + "</w>")
        while (word.size > 1) {
            var best: Int? = null
            var bestRank = Int.MAX_VALUE
            for (i in 0 until word.size - 1) {
                val rank = ranks[word[i] + " " + word[i + 1]] ?: continue
                if (rank < bestRank) {
                    bestRank = rank
                    best = i
                }
            }
            if (best == null) break
            val first = word[best]
            val second = word[best + 1]
            // Merge every occurrence of the best pair, left to right.
            val merged = ArrayList<String>(word.size)
            var i = 0
            while (i < word.size) {
                if (i < word.size - 1 && word[i] == first && word[i + 1] == second) {
                    merged += first + second
                    i += 2
                } else {
                    merged += word[i]
                    i += 1
                }
            }
            word = merged
        }
        word
    }

    companion object {
        const val CONTEXT = 77
        private const val SOT = "<|startoftext|>"
        private const val EOT = "<|endoftext|>"
        private val WHITESPACE = Regex("\\s+")
        private val PATTERN = Regex(
            "<\\|startoftext\\|>|<\\|endoftext\\|>|'s|'t|'re|'ve|'m|'ll|'d|[\\p{L}]+|[\\p{N}]|[^\\s\\p{L}\\p{N}]+",
            RegexOption.IGNORE_CASE,
        )

        /** CLIP's reversible byte -> printable-character table, in CLIP's order. */
        private fun bytesToUnicode(): List<Pair<Int, Char>> {
            val bs = (('!'.code..'~'.code) + ('¡'.code..'¬'.code) + ('®'.code..'ÿ'.code)).toMutableList()
            val cs = bs.toMutableList()
            var n = 0
            for (b in 0 until 256) {
                if (b !in bs) {
                    bs += b
                    cs += 256 + n
                    n++
                }
            }
            return bs.zip(cs) { b, c -> b to c.toChar() }
        }
    }
}
