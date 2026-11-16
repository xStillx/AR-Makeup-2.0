package com.example.armakeup.makeup

/** Local artistic presets for appearance testing, not calibrated commercial products. */
internal enum class HighlighterFinish(
    val title: String,
    val roughness: Float,
    val sheen: Float,
    val specular: Float,
    val tint: Float,
    val flakes: Float,
    val flakeSize: Float,
    val flakeDensity: Float,
    val duo: Float = 0f,
) {
    SATIN("Сатиновое свечение", .58f, .34f, .42f, .45f, 0f, .0002f, 0f),
    WET("Влажный блеск", .29f, .12f, .95f, .20f, 0f, .0002f, 0f),
    METALLIC("Металлический перелив", .38f, .30f, .90f, 1f, .04f, .00015f, .50f),
    SPARKLE("Заметные искры", .40f, .12f, .48f, .65f, 1f, .00048f, .16f),
    DUOCHROME("Дуохромный перелив", .40f, .25f, .76f, .95f, .08f, .00022f, .35f, 1f),
    SHIMMER("Сверкающий финиш", .48f, .24f, .56f, .65f, .40f, .00018f, .65f),
}

internal object HighlighterCatalog {
    data class Shade(
        val id: String, val name: String, val color: LipstickColor,
        val finish: HighlighterFinish, val shift: LipstickColor = color,
    )

    val shades = listOf(
        Shade("satin_pearl", "Жемчужный", LipstickColor(0xF3E8DC), HighlighterFinish.SATIN),
        Shade("satin_champagne", "Шампань", LipstickColor(0xEED8AF), HighlighterFinish.SATIN),
        Shade("wet_crystal", "Хрустальный", LipstickColor(0xF2ECE6), HighlighterFinish.WET),
        Shade("wet_rose", "Розовый кварц", LipstickColor(0xF1D5D2), HighlighterFinish.WET),
        Shade("metal_gold", "Жидкое золото", LipstickColor(0xE8BE77), HighlighterFinish.METALLIC),
        Shade("metal_bronze", "Солнечная бронза", LipstickColor(0xCD945F), HighlighterFinish.METALLIC),
        Shade("sparkle_silver", "Серебряные искры", LipstickColor(0xE9E9F2), HighlighterFinish.SPARKLE),
        Shade("sparkle_gold", "Золотые искры", LipstickColor(0xF1D397), HighlighterFinish.SPARKLE),
        Shade("duo_opal", "Опал · розовый / золотой", LipstickColor(0xF0CBDD), HighlighterFinish.DUOCHROME, LipstickColor(0xE9D69B)),
        Shade("duo_aurora", "Аврора · лиловый / мятный", LipstickColor(0xD5BDEB), HighlighterFinish.DUOCHROME, LipstickColor(0xBCE3D0)),
        Shade("shimmer_pearl", "Жемчужная пыль", LipstickColor(0xEFE1D9), HighlighterFinish.SHIMMER),
        Shade("shimmer_peach", "Персиковое сияние", LipstickColor(0xF0C9AD), HighlighterFinish.SHIMMER),
    )

    val defaultShade: Shade get() = shades.first()
}

internal data class HighlighterStyle(
    val enabled: Boolean = false,
    val shade: HighlighterCatalog.Shade = HighlighterCatalog.defaultShade,
    val intensity: Float = DEFAULT_INTENSITY,
    val cheeks: Boolean = true,
    val nose: Boolean = false,
    val brows: Boolean = false,
    val cupid: Boolean = false,
) {
    companion object {
        const val DEFAULT_INTENSITY = .55f
        const val MAX_INTENSITY = 1.5f
    }
}
