package com.petwalk.photosorter

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import java.io.Closeable
import java.nio.FloatBuffer
import kotlin.math.sqrt

/**
 * Finds faces (ML Kit) and turns each into a 128-number "face fingerprint" (OpenCV's SFace model).
 * Two fingerprints of the same person point in a similar direction; compare them with [similarity].
 */
class FaceEngine(context: Context) : Closeable {
    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setMinFaceSize(0.05f)
            .build()
    )
    private val env = OrtEnvironment.getEnvironment()
    private val session = env.createSession(
        context.assets.open(MODEL_ASSET).use { it.readBytes() },
        OrtSession.SessionOptions(),
    )
    // The model also lists its weights as inputs; the image goes into "data".
    private val inputName = session.inputNames.firstOrNull { it == "data" } ?: session.inputNames.first()

    /** Fingerprints of the clear faces in [image], largest face first. */
    fun faces(image: Bitmap): List<FloatArray> {
        val bitmap = if (image.config == Bitmap.Config.ARGB_8888) image else image.copy(Bitmap.Config.ARGB_8888, false)
        val faces = try {
            Tasks.await(detector.process(InputImage.fromBitmap(bitmap, 0)))
        } catch (e: Exception) {
            return emptyList()
        }
        return faces
            .filter { it.boundingBox.width() >= MIN_FACE_PX }
            .sortedByDescending { it.boundingBox.width() * it.boundingBox.height() }
            .mapNotNull { face -> landmarks(face)?.let { embed(bitmap, it) } }
    }

    override fun close() {
        detector.close()
        session.close()
    }

    private fun landmarks(face: Face): DoubleArray? {
        fun point(type: Int) = face.getLandmark(type)?.position?.let { it.x to it.y }
        val leftEye = point(FaceLandmark.LEFT_EYE) ?: return null
        val rightEye = point(FaceLandmark.RIGHT_EYE) ?: return null
        val nose = point(FaceLandmark.NOSE_BASE) ?: return null
        val mouthLeft = point(FaceLandmark.MOUTH_LEFT) ?: return null
        val mouthRight = point(FaceLandmark.MOUTH_RIGHT) ?: return null
        return FaceAlign.orderedPoints(listOf(leftEye, rightEye), nose, listOf(mouthLeft, mouthRight))
    }

    private fun embed(bitmap: Bitmap, points: DoubleArray): FloatArray {
        val t = FaceAlign.similarity(points)
        val matrix = Matrix().apply {
            setValues(floatArrayOf(t[0].toFloat(), t[1].toFloat(), t[2].toFloat(), t[3].toFloat(), t[4].toFloat(), t[5].toFloat(), 0f, 0f, 1f))
        }
        val size = FaceAlign.SIZE
        val aligned = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        Canvas(aligned).drawBitmap(bitmap, matrix, Paint(Paint.FILTER_BITMAP_FLAG))
        val pixels = IntArray(size * size)
        aligned.getPixels(pixels, 0, size, 0, 0, size, size)
        // SFace takes raw 0-255 RGB values, channels first.
        val plane = size * size
        val buffer = FloatBuffer.allocate(3 * plane)
        for (i in 0 until plane) {
            val p = pixels[i]
            buffer.put(i, ((p shr 16) and 0xff).toFloat())
            buffer.put(plane + i, ((p shr 8) and 0xff).toFloat())
            buffer.put(2 * plane + i, (p and 0xff).toFloat())
        }
        return OnnxTensor.createTensor(env, buffer, longArrayOf(1, 3, size.toLong(), size.toLong())).use { tensor ->
            session.run(mapOf(inputName to tensor)).use { result ->
                @Suppress("UNCHECKED_CAST")
                normalize((result.get(0).value as Array<FloatArray>)[0])
            }
        }
    }

    companion object {
        const val MODEL_ASSET = "face/face_recognition_sface_2021dec.onnx"
        private const val MIN_FACE_PX = 40

        /** Cosine similarity of two fingerprints; OpenCV suggests 0.363 as the same-person threshold. */
        fun similarity(a: FloatArray, b: FloatArray): Float {
            var sum = 0f
            for (i in a.indices) sum += a[i] * b[i]
            return sum
        }

        private fun normalize(v: FloatArray): FloatArray {
            val norm = sqrt(v.fold(0f) { acc, x -> acc + x * x }).coerceAtLeast(1e-12f)
            return FloatArray(v.size) { v[it] / norm }
        }
    }
}
