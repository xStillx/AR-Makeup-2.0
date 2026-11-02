package com.example.armakeup.makeup

/** Names and exact source sRGB supplied by the user; not measured product calibration. */
internal object ConcealerCatalog {
    data class Shade(val id: String, val brand: String, val product: String, val name: String, val color: LipstickColor)
    val shades = listOf(
        Shade("luxvisage_aqua_veil_104", "LuxVisage", "Aqua Veil Hyaluron Complex", "104", LipstickColor(0xF6E0C9)),
        Shade("luxvisage_ultra_hd_10", "LuxVisage", "Ultra HD soft focus", "10 fair", LipstickColor(0xE0DCD0)),
        Shade("dior_forever_skin_correct_1w", "Dior", "Forever Skin Correct", "1W Теплый", LipstickColor(0xEEC8A4)),
        Shade("jane_iredale_purematch_8n", "Jane Iredale", "PureMatch Perfecting Concealer", "8N Medium", LipstickColor(0xD6B084)),
        Shade("holika_holika_cover_hiding_01", "Holika Holika", "Cover & Hiding Liquid Concealer", "01 Светло-бежевый", LipstickColor(0xF0D7C9)),
    )
}

internal data class ConcealerStyle(
    val enabled: Boolean = false,
    val color: LipstickColor = ConcealerCatalog.shades.first().color,
    val coverage: Float = DEFAULT_COVERAGE,
    val retouch: Float = DEFAULT_RETOUCH,
) {
    companion object {
        const val DEFAULT_COVERAGE = 0.25f
        const val DEFAULT_RETOUCH = 0.2f
        const val MAX_RETOUCH = 1f
    }
}
