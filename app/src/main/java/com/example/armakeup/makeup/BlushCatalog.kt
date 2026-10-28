package com.example.armakeup.makeup

/** Standalone colour presets from iOS dev cdc2366; not calibrated product SKUs. */
internal object BlushCatalog {
    data class Shade(val id: String, val name: String, val color: LipstickColor)

    val shades = listOf(
        Shade("soft_pink", "Нежный розовый", LipstickColor(0xF26B80)),
        Shade("peach", "Персиковый", LipstickColor(0xF58061)),
        Shade("dusty_rose", "Пыльная роза", LipstickColor(0xBF5766)),
        Shade("coral", "Коралловый", LipstickColor(0xED5C52)),
    )
}

internal data class BlushStyle(
    val enabled: Boolean = false,
    val color: LipstickColor = BlushCatalog.shades.first().color,
    val opacity: Float = DEFAULT_OPACITY,
) {
    companion object {
        const val DEFAULT_OPACITY = 1.3f
        const val MAX_OPACITY = 1.5f
    }
}
