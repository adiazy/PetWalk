package com.petwalk.photosorter

/**
 * Face alignment for SFace: the similarity transform (rotation, uniform scale, translation) that
 * best maps five face landmarks onto OpenCV's standard 112x112 template, as FaceRecognizerSF does.
 */
object FaceAlign {
    const val SIZE = 112

    // Image-left eye, image-right eye, nose tip, image-left mouth corner, image-right mouth corner.
    private val TEMPLATE = doubleArrayOf(
        38.2946, 51.6963, 73.5318, 51.5014, 56.0252, 71.7366, 41.5493, 92.3655, 70.7299, 92.2041,
    )

    /** Puts landmarks in template order by where they are in the image, whatever the detector calls them. */
    fun orderedPoints(
        eyes: List<Pair<Float, Float>>,
        nose: Pair<Float, Float>,
        mouth: List<Pair<Float, Float>>,
    ): DoubleArray {
        val (eyeL, eyeR) = eyes.sortedBy { it.first }
        val (mouthL, mouthR) = mouth.sortedBy { it.first }
        return listOf(eyeL, eyeR, nose, mouthL, mouthR)
            .flatMap { listOf(it.first.toDouble(), it.second.toDouble()) }
            .toDoubleArray()
    }

    /**
     * Least-squares similarity transform from [points] (x0, y0, ... x4, y4, in template order) to the
     * template. Returns the affine matrix as [a, b, tx, c, d, ty]: x' = a*x + b*y + tx, y' = c*x + d*y + ty.
     */
    fun similarity(points: DoubleArray): DoubleArray {
        val n = 5
        var msx = 0.0
        var msy = 0.0
        var mdx = 0.0
        var mdy = 0.0
        for (i in 0 until n) {
            msx += points[2 * i]; msy += points[2 * i + 1]
            mdx += TEMPLATE[2 * i]; mdy += TEMPLATE[2 * i + 1]
        }
        msx /= n; msy /= n; mdx /= n; mdy /= n
        var a = 0.0
        var b = 0.0
        var norm = 0.0
        for (i in 0 until n) {
            val sx = points[2 * i] - msx
            val sy = points[2 * i + 1] - msy
            val dx = TEMPLATE[2 * i] - mdx
            val dy = TEMPLATE[2 * i + 1] - mdy
            a += sx * dx + sy * dy
            b += sx * dy - sy * dx
            norm += sx * sx + sy * sy
        }
        val cos = a / norm
        val sin = b / norm
        val tx = mdx - (cos * msx - sin * msy)
        val ty = mdy - (sin * msx + cos * msy)
        return doubleArrayOf(cos, -sin, tx, sin, cos, ty)
    }
}
