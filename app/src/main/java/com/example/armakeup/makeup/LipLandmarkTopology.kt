package com.example.armakeup.makeup

/** Ordered MediaPipe landmark loops with one-to-one outer/inner lip correspondence. */
object LipLandmarkTopology {
    val outerContour = intArrayOf(
        61, 146, 91, 181, 84, 17, 314, 405, 321, 375,
        291, 409, 270, 269, 267, 0, 37, 39, 40, 185,
    )

    val innerContour = intArrayOf(
        78, 95, 88, 178, 87, 14, 317, 402, 318, 324,
        308, 415, 310, 311, 312, 13, 82, 81, 80, 191,
    )

    init {
        require(outerContour.size == innerContour.size)
    }
}
