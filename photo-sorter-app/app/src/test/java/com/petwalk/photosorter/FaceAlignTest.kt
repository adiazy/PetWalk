package com.petwalk.photosorter

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class FaceAlignTest {
    // Expected matrices from scikit-image's SimilarityTransform.estimate on the same points.
    @Test fun frontalFace() = assertArrayEquals(
        doubleArrayOf(0.612148, -0.011989, -22.227708, 0.011989, 0.612148, -22.346631),
        FaceAlign.similarity(doubleArrayOf(100.0, 120.0, 160.0, 118.0, 131.0, 150.0, 108.0, 185.0, 155.0, 184.0)),
        1e-5,
    )

    @Test fun tiltedFace() = assertArrayEquals(
        doubleArrayOf(0.441037, -0.146313, -40.663246, 0.146313, 0.441037, -169.596566),
        FaceAlign.similarity(doubleArrayOf(310.5, 402.2, 388.1, 371.9, 361.0, 431.7, 343.3, 478.4, 402.8, 454.6)),
        1e-5,
    )

    @Test fun ordersLandmarksLeftToRight() = assertArrayEquals(
        doubleArrayOf(100.0, 120.0, 160.0, 118.0, 131.0, 150.0, 108.0, 185.0, 155.0, 184.0),
        FaceAlign.orderedPoints(
            eyes = listOf(160f to 118f, 100f to 120f),
            nose = 131f to 150f,
            mouth = listOf(155f to 184f, 108f to 185f),
        ),
        0.0,
    )
}
