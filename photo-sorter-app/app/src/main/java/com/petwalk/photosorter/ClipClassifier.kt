package com.petwalk.photosorter

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The CLIP model files. Release builds ship them inside the APK (assets/clip/); they're unpacked
 * into private storage once, because ONNX Runtime loads models from files. Builds without the
 * bundled files download them from Hugging Face instead.
 */
object ClipFiles {
    // OpenAI CLIP ViT-B/32, exported to ONNX and int8-quantized (MIT license).
    private const val BASE = "https://huggingface.co/Xenova/clip-vit-base-patch32/resolve/main/onnx/"
    private const val ASSET_DIR = "clip"
    const val VISION = "vision_model_quantized.onnx"
    const val TEXT = "text_model_quantized.onnx"
    private val FILES = listOf(VISION, TEXT)

    fun dir(context: Context) = File(context.filesDir, "clip")

    fun isReady(context: Context) = FILES.all { File(dir(context), it).exists() }

    fun isBundled(context: Context) =
        context.assets.list(ASSET_DIR).orEmpty().toSet().containsAll(FILES)

    /** Unpacks (or, if not bundled, downloads) the missing files, reporting progress from 0 to 1. */
    fun prepare(context: Context, onProgress: (Float) -> Unit) {
        val dir = dir(context).apply { mkdirs() }
        val bundled = isBundled(context)
        FILES.forEachIndexed { index, name ->
            val target = File(dir, name)
            if (target.exists()) return@forEachIndexed
            val part = File(dir, "$name.part")
            if (bundled) {
                context.assets.openFd("$ASSET_DIR/$name").use { fd ->
                    fd.createInputStream().use { copy(it, part, fd.length, index, onProgress) }
                }
            } else {
                download(BASE + name, part, index, onProgress)
            }
            if (!part.renameTo(target)) throw IOException("Couldn't save the recognition model.")
        }
    }

    private fun download(url: String, part: File, index: Int, onProgress: (Float) -> Unit) {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 30_000
        connection.readTimeout = 60_000
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("Model download failed (HTTP ${connection.responseCode}).")
            }
            connection.inputStream.use { copy(it, part, connection.contentLengthLong, index, onProgress) }
        } finally {
            connection.disconnect()
        }
    }

    private fun copy(input: InputStream, part: File, total: Long, index: Int, onProgress: (Float) -> Unit) {
        part.outputStream().use { output ->
            val buffer = ByteArray(256 * 1024)
            var done = 0L
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                output.write(buffer, 0, n)
                done += n
                if (total > 0) onProgress((index + done.toFloat() / total) / FILES.size)
            }
        }
    }
}

/**
 * Zero-shot photo classification with CLIP, entirely on the phone: each photo goes to the
 * category whose description ("a photo of <category>") best matches the image.
 */
class ClipClassifier(
    context: Context,
    private val store: PhotoStore,
    private val labels: List<String>,
    private val minConfidence: Float,
) : Closeable {
    private val env = OrtEnvironment.getEnvironment()
    private val vision: OrtSession
    private val textEmbeds: List<FloatArray>

    init {
        val dir = ClipFiles.dir(context)
        val tokenizer = context.assets.open(BPE_ASSET).use { ClipTokenizer(it) }
        textEmbeds = env.createSession(File(dir, ClipFiles.TEXT).absolutePath, OrtSession.SessionOptions()).use { text ->
            labels.map { label -> embedText(text, tokenizer.encode("a photo of ${label.lowercase()}")) }
        }
        vision = env.createSession(File(dir, ClipFiles.VISION).absolutePath, OrtSession.SessionOptions())
    }

    fun classify(photo: Photo): String {
        val bitmap = store.thumbnail(photo.uri, SIZE) ?: return Folders.UNREADABLE
        val image = OnnxTensor.createTensor(env, preprocess(bitmap), longArrayOf(1, 3, SIZE.toLong(), SIZE.toLong())).use { tensor ->
            vision.run(mapOf(vision.inputNames.first() to tensor)).use { normalize(embedding(it)) }
        }
        // CLIP's learned logit scale is 100; softmax turns similarities into probabilities.
        val probs = softmax(textEmbeds.map { dot(it, image) * 100f })
        val best = probs.indices.maxBy { probs[it] }
        return if (probs[best] >= minConfidence) Folders.sanitize(labels[best]) else Folders.OTHER
    }

    override fun close() = vision.close()

    private fun embedText(session: OrtSession, ids: LongArray): FloatArray {
        val shape = longArrayOf(1, ids.size.toLong())
        val inputs = HashMap<String, OnnxTensor>()
        try {
            for (name in session.inputNames) {
                inputs[name] = when (name) {
                    "input_ids" -> OnnxTensor.createTensor(env, LongBuffer.wrap(ids), shape)
                    "attention_mask" -> OnnxTensor.createTensor(env, LongBuffer.wrap(LongArray(ids.size) { 1L }), shape)
                    else -> throw IllegalStateException("Unexpected text model input: $name")
                }
            }
            return session.run(inputs).use { normalize(embedding(it)) }
        } finally {
            inputs.values.forEach { it.close() }
        }
    }

    private fun embedding(result: OrtSession.Result): FloatArray {
        val value = result.firstOrNull { it.key.endsWith("embeds") }?.value ?: result.get(0)
        @Suppress("UNCHECKED_CAST")
        return (value.value as Array<FloatArray>)[0]
    }

    /** Resize the short side to 224, center-crop 224x224, normalize with CLIP's mean/std (CHW). */
    private fun preprocess(src: Bitmap): FloatBuffer {
        val scale = SIZE.toFloat() / min(src.width, src.height)
        val w = max(SIZE, (src.width * scale).roundToInt())
        val h = max(SIZE, (src.height * scale).roundToInt())
        val scaled = Bitmap.createScaledBitmap(src, w, h, true)
        val pixels = IntArray(SIZE * SIZE)
        scaled.getPixels(pixels, 0, SIZE, (w - SIZE) / 2, (h - SIZE) / 2, SIZE, SIZE)
        val plane = SIZE * SIZE
        val buffer = FloatBuffer.allocate(3 * plane)
        for (i in 0 until plane) {
            val p = pixels[i]
            buffer.put(i, (((p shr 16) and 0xff) / 255f - MEAN[0]) / STD[0])
            buffer.put(plane + i, (((p shr 8) and 0xff) / 255f - MEAN[1]) / STD[1])
            buffer.put(2 * plane + i, ((p and 0xff) / 255f - MEAN[2]) / STD[2])
        }
        return buffer
    }

    private companion object {
        const val SIZE = 224
        const val BPE_ASSET = "clip_bpe_simple_vocab_16e6.txt.gz"
        val MEAN = floatArrayOf(0.48145466f, 0.4578275f, 0.40821073f)
        val STD = floatArrayOf(0.26862954f, 0.26130258f, 0.27577711f)

        fun normalize(v: FloatArray): FloatArray {
            val norm = sqrt(v.fold(0f) { acc, x -> acc + x * x }).coerceAtLeast(1e-12f)
            return FloatArray(v.size) { v[it] / norm }
        }

        fun dot(a: FloatArray, b: FloatArray): Float {
            var sum = 0f
            for (i in a.indices) sum += a[i] * b[i]
            return sum
        }

        fun softmax(logits: List<Float>): List<Float> {
            val top = logits.max()
            val exps = logits.map { exp(it - top) }
            val total = exps.sum()
            return exps.map { it / total }
        }
    }
}
