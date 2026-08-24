package com.example.armakeup.tracking.face

/** Four shared landmark indices used to measure a feature opening independently of face scale. */
data class FaceApertureTopology(
    val firstCornerIndex: Int,
    val secondCornerIndex: Int,
    val upperIndex: Int,
    val lowerIndex: Int,
) {
    init {
        require(firstCornerIndex >= 0)
        require(secondCornerIndex >= 0)
        require(upperIndex >= 0)
        require(lowerIndex >= 0)
    }

    internal val maximumIndex: Int
        get() = maxOf(firstCornerIndex, secondCornerIndex, upperIndex, lowerIndex)
}

/** Semantic regions in the 468-point topology shared by ARCore and MediaPipe Face Landmarker. */
object FaceMesh468RegionTopology {
    val leftEyeContour = intArrayOf(
        362, 382, 381, 380, 374, 373, 390, 249,
        263, 466, 388, 387, 386, 385, 384, 398,
    )
    val rightEyeContour = intArrayOf(
        33, 7, 163, 144, 145, 153, 154, 155,
        133, 173, 157, 158, 159, 160, 161, 246,
    )

    val mouthAperture = FaceApertureTopology(
        firstCornerIndex = 61,
        secondCornerIndex = 291,
        upperIndex = 13,
        lowerIndex = 14,
    )
    val leftEyeAperture = FaceApertureTopology(
        firstCornerIndex = 362,
        secondCornerIndex = 263,
        upperIndex = 386,
        lowerIndex = 374,
    )
    val rightEyeAperture = FaceApertureTopology(
        firstCornerIndex = 33,
        secondCornerIndex = 133,
        upperIndex = 159,
        lowerIndex = 145,
    )
}
