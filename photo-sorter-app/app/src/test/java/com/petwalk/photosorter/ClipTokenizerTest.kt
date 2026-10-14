package com.petwalk.photosorter

import org.junit.Assert.assertArrayEquals
import org.junit.Test
import java.io.File

class ClipTokenizerTest {
    private val tokenizer = File("src/main/assets/clip_bpe_merges.gzip").inputStream().use { ClipTokenizer(it) }

    // Expected ids come from OpenAI's reference tokenizer (open_clip SimpleTokenizer).
    private fun check(text: String, vararg expected: Long) = assertArrayEquals(text, expected, tokenizer.encode(text))

    @Test fun simple() = check("a photo of dogs", 49406, 320, 1125, 539, 3255, 49407)

    @Test fun longWord() = check("a photo of screenshots", 49406, 320, 1125, 539, 26783, 49407)

    @Test fun contractionsAndPunctuation() =
        check("a photo of my cat's toys!", 49406, 320, 1125, 539, 607, 2368, 568, 7162, 256, 49407)

    @Test fun accentsDigitsCaseAndSpaces() =
        check("A photo of Café   2024", 49406, 320, 1125, 539, 15304, 273, 271, 273, 275, 49407)

    @Test fun multipleWords() =
        check("a photo of receipts and documents", 49406, 320, 1125, 539, 41181, 537, 14105, 49407)
}
