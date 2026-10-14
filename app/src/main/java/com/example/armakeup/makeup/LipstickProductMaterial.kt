package com.example.armakeup.makeup

/** Runtime-selectable color/material interpretation used by the ARCore tuning screen. */
internal enum class LipColorRenderingMode {
    IOS_REFERENCE,
    UNIFIED,
}

/** Density independent of RGB/finish: original iOS coverage and editable UNIFIED starting intensity. */
internal enum class LipstickDensity(
    val displayName: String,
    val pigmentCoverage: Float,
    val unifiedPigmentOpacity: Float,
) {
    LOW("Низкая", 0.54f, 0.30f),
    MEDIUM("Средняя", 0.78f, 0.65f),
    HIGH("Высокая", 0.92f, 1.00f);

    /** Initial colour mixing only. iOS already applies its own density in the compositor. */
    fun initialPigmentOpacity(renderingMode: LipColorRenderingMode): Float = when (renderingMode) {
        LipColorRenderingMode.UNIFIED -> unifiedPigmentOpacity
        LipColorRenderingMode.IOS_REFERENCE -> 1f
    }
}

internal enum class LipstickTexture(
    val displayName: String,
    val detailResponse: Float,
    val highlightResponse: Float,
    val highlightLimitResponse: Float,
    val highlightConcentration: Float,
) {
    CREAMY("Кремовая", 0.92f, 1.32f, 1.00f, 1.15f),
    MOUSSE("Муссовая", 0.80f, 0.40f, 0.56f, 1.35f),
    LIQUID("Жидкая", 0.94f, 1.65f, 1.25f, 1.85f),
}
