package com.example.armakeup.makeup

/** Product names and source RGB supplied by the user; no physical shade calibration. */
internal object FoundationCatalog {
    data class Shade(
        val id: String,
        val brand: String,
        val product: String,
        val name: String,
        val color: LipstickColor,
    )

    val shades = listOf(
        Shade("guerlain_parure_gold_cushion_00n", "Guerlain", "Parure Gold Cushion", "00N Бежевый", LipstickColor(0xE1E1C7)),
        Shade("guerlain_parure_gold_cushion_01n", "Guerlain", "Parure Gold Cushion", "01N", LipstickColor(0xE1E4C9)),
        Shade("erborian_natural_beige", "Erborian", "Натурально-бежевый", "Натурально-бежевый", LipstickColor(0xC19E87)),
        Shade("erborian_light", "Erborian", "Тон светлый", "Тон светлый", LipstickColor(0xD3AD8C)),
        Shade("missha_m_perfect_21", "Missha", "M Perfect", "21 Light Beige", LipstickColor(0xEAB699)),
        Shade("luxvisage_active_complex_10", "LuxVisage", "Active Complex", "10 fair", LipstickColor(0xE8D8BD)),
        Shade("manly_pro_enchanted_skin_31", "Manly PRO", "Enchanted Skin", "31", LipstickColor(0xD0B092)),
        Shade("estee_lauder_futurist_aqua_brilliance_1c1", "Estee Lauder", "Futurist Aqua Brilliance", "1C1", LipstickColor(0xE5B99F)),
        Shade("clarins_skin_illusion_velvet_105n", "Clarins", "Skin Illusion Velvet", "105N", LipstickColor(0xEDB886)),
        Shade("seven7een_time_plus_07", "Seven7een", "Time Plus Longlasting Make Up", "07 Летний Загар", LipstickColor(0xB77F53)),
        Shade("seven7een_time_plus_01", "Seven7een", "Time Plus Longlasting Make Up", "01 Фарфор", LipstickColor(0xF3DFCD)),
        Shade("pmkl_matte_velvet_01", "PMKL", "MATTE VELVET", "01 Фарфоровый", LipstickColor(0xF3DDCD)),
    )

    val defaultShade: Shade get() = shades.first()
}

internal data class FoundationStyle(
    val enabled: Boolean = false,
    val color: LipstickColor = FoundationCatalog.defaultShade.color,
    val coverage: Float = DEFAULT_COVERAGE,
    val retouch: Float = DEFAULT_RETOUCH,
    val leftHalfOnly: Boolean = true,
) {
    companion object {
        const val DEFAULT_COVERAGE = 0.15f
        const val DEFAULT_RETOUCH = 2f
        const val MAX_RETOUCH = 5f
    }
}
