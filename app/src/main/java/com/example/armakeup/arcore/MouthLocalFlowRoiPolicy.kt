package com.example.armakeup.arcore

import com.example.armakeup.render.VulkanTemporalTrackingRoi
import com.example.armakeup.tracking.face.FaceRegion
import com.example.armakeup.tracking.face.FaceRegionGeometry

/** Builds a compact display-space ROI for camera-synchronous lip-expression optical flow. */
internal object MouthLocalFlowRoiPolicy {
    fun from(outerLip: FaceRegionGeometry?): VulkanTemporalTrackingRoi {
        if (outerLip == null || outerLip.region != FaceRegion.LIPS_OUTER) {
            return VulkanTemporalTrackingRoi.INVALID
        }
        var minimumX = Float.POSITIVE_INFINITY
        var minimumY = Float.POSITIVE_INFINITY
        var maximumX = Float.NEGATIVE_INFINITY
        var maximumY = Float.NEGATIVE_INFINITY
        repeat(outerLip.pointCount) { index ->
            minimumX = minOf(minimumX, outerLip.x(index))
            minimumY = minOf(minimumY, outerLip.y(index))
            maximumX = maxOf(maximumX, outerLip.x(index))
            maximumY = maxOf(maximumY, outerLip.y(index))
        }
        val width = maximumX - minimumX
        val height = maximumY - minimumY
        if (!width.isFinite() || !height.isFinite() ||
            width < MINIMUM_MOUTH_WIDTH || height <= 0f
        ) {
            return VulkanTemporalTrackingRoi.INVALID
        }
        val horizontalMargin = width * HORIZONTAL_MARGIN_FRACTION
        val verticalMargin = maxOf(
            height * VERTICAL_MARGIN_FRACTION,
            width * MINIMUM_VERTICAL_MARGIN_OF_WIDTH,
        )
        return VulkanTemporalTrackingRoi(
            left = (minimumX - horizontalMargin).coerceIn(0f, 1f),
            top = (minimumY - verticalMargin).coerceIn(0f, 1f),
            right = (maximumX + horizontalMargin).coerceIn(0f, 1f),
            bottom = (maximumY + verticalMargin).coerceIn(0f, 1f),
        ).takeIf { it.isValid } ?: VulkanTemporalTrackingRoi.INVALID
    }

    private const val MINIMUM_MOUTH_WIDTH = 0.01f
    private const val HORIZONTAL_MARGIN_FRACTION = 0.25f
    private const val VERTICAL_MARGIN_FRACTION = 0.75f
    private const val MINIMUM_VERTICAL_MARGIN_OF_WIDTH = 0.12f
}
