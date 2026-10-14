package com.example.armakeup.makeup

/** An exact source-sRGB colour. HEX and RGB encode the same bytes; no shade correction is applied. */
internal data class LipstickColor(val sourceSrgbHex: Int) {
    init {
        require(sourceSrgbHex in 0..0xFFFFFF)
    }

    val red8Bit: Int get() = (sourceSrgbHex ushr 16) and 0xFF
    val green8Bit: Int get() = (sourceSrgbHex ushr 8) and 0xFF
    val blue8Bit: Int get() = sourceSrgbHex and 0xFF
    val red: Float get() = red8Bit / 255f
    val green: Float get() = green8Bit / 255f
    val blue: Float get() = blue8Bit / 255f
}

internal data class LipColorShade(
    val productId: String,
    val name: String,
    val color: LipstickColor,
)

internal data class LipColorProduct(
    val id: String,
    val name: String,
    val density: LipstickDensity,
    val finish: LipProductFinish,
    val texture: LipstickTexture,
    val shades: List<LipColorShade>,
)

/** Keep source types intact; oil/tint do not yet have independent renderer formulas. */
internal enum class LipProductFinish(val renderingFinish: LipstickFinish?) {
    MATTE(LipstickFinish.MATTE),
    SATIN(LipstickFinish.SATIN),
    GLOSS(LipstickFinish.GLOSS),
    OIL(null),
    TINT(null),
}

internal data class LipColorSwatch(val product: LipColorProduct, val shade: LipColorShade) {
    // Source productID is not unique: variants within MAC and shades across brands reuse it.
    val key: String = "${product.id}:${shade.productId}:${shade.color.sourceSrgbHex.toString(16)}"
}

/** Exact user-supplied product metadata and source-sRGB colours. */
internal object LipstickColorCatalog {
    private fun shade(productId: String, name: String, hex: Int) =
        LipColorShade(productId, name, LipstickColor(hex))

    val products: List<LipColorProduct> = listOf(
        LipColorProduct("guerlain-rouge-g-satin", "Guerlain Rouge G Satin", LipstickDensity.MEDIUM, LipProductFinish.SATIN, LipstickTexture.CREAMY, listOf(
            shade("VM-1001-131", "131", 0xD07266),
            shade("VM-1001-518", "518", 0xE16468),
            shade("VM-1001-006", "06", 0xBC4F52),
        )),
        LipColorProduct("dior-rouge-dior-satin", "Dior Rouge Dior Satin", LipstickDensity.HIGH, LipProductFinish.SATIN, LipstickTexture.CREAMY, listOf(
            shade("VM-1002-999", "999", 0xB8202D),
        )),
        LipColorProduct("guerlain-rouge-g-velvet", "Guerlain Rouge G Velvet", LipstickDensity.HIGH, LipProductFinish.MATTE, LipstickTexture.CREAMY, listOf(
            shade("VM-1003-770", "770", 0xB90E2E),
            shade("VM-1003-360", "360", 0xAB5244),
        )),
        LipColorProduct("mac-powder-kiss", "MAC Powder Kiss", LipstickDensity.HIGH, LipProductFinish.MATTE, LipstickTexture.MOUSSE, listOf(
            shade("VM-1004-BL", "Burning Love", 0x7E1331),
            shade("VM-1004-BL", "Burning Love ЗЯ", 0x7A2B40),
            shade("VM-1004-MM", "Marrakeshmere", 0x8B302B),
            shade("VM-1004-MM", "Marrakeshmere ЗЯ", 0x853832),
        )),
        LipColorProduct("mac-satin-lipstick", "MAC Satin Lipstick", LipstickDensity.MEDIUM, LipProductFinish.SATIN, LipstickTexture.CREAMY, listOf(
            shade("VM-1005-BRV", "Brave", 0xB44255),
        )),
        LipColorProduct("bobby-brown-satin-lipstick", "Bobby Brown Satin Lipstick", LipstickDensity.MEDIUM, LipProductFinish.SATIN, LipstickTexture.CREAMY, listOf(
            shade("VM-1005-BB", "Brave", 0x87473F),
        )),
        LipColorProduct("nrav-lipstick", "Nrav Lipstick", LipstickDensity.HIGH, LipProductFinish.MATTE, LipstickTexture.CREAMY, listOf(
            shade("VM-1005-BB", "05 Бежевыый", 0xD68B79),
        )),
        LipColorProduct("estee-lauder-satin-lipstick", "Estee Lauder Satin Lipstick", LipstickDensity.HIGH, LipProductFinish.SATIN, LipstickTexture.CREAMY, listOf(
            shade("VM-1005-EL", "340", 0xBA273A),
        )),
        LipColorProduct("kilian-matte-lipstick", "Kilian Paris Matte Lipstick", LipstickDensity.HIGH, LipProductFinish.MATTE, LipstickTexture.CREAMY, listOf(
            shade("VM-1005-KP", "240", 0x8E212D),
        )),
        LipColorProduct("make-up-factory-satin-lipstick", "Make Up Factory Satin Lipstick", LipstickDensity.MEDIUM, LipProductFinish.SATIN, LipstickTexture.CREAMY, listOf(
            shade("VM-1005-MF", "100", 0xDC8367),
        )),
        LipColorProduct("clarins-satin-lipstick", "Clarins Satin Lipstick", LipstickDensity.MEDIUM, LipProductFinish.SATIN, LipstickTexture.CREAMY, listOf(
            shade("VM-1006-705S", "705S", 0xA34545),
        )),
        LipColorProduct("clarins-lip-gloss", "Clarins Lip Gloss", LipstickDensity.LOW, LipProductFinish.GLOSS, LipstickTexture.LIQUID, listOf(
            shade("VM-1007-001", "01", 0xE39294),
        )),
        LipColorProduct("dior-lip-gloss", "Dior Lip Gloss Addict", LipstickDensity.LOW, LipProductFinish.GLOSS, LipstickTexture.LIQUID, listOf(
            shade("VM-1008-006", "006", 0xA74C89),
            shade("VM-1008-012", "012", 0xBC6065),
        )),
        LipColorProduct("dior-lip-gloss-creamy", "Dior Lip Gloss", LipstickDensity.LOW, LipProductFinish.GLOSS, LipstickTexture.CREAMY, listOf(
            shade("VM-1008-040", "040", 0xA52D3C),
            shade("VM-1008-004", "004", 0xEB8571),
        )),
        LipColorProduct("shik-lip-gloss", "Shik Lip Gloss Care", LipstickDensity.LOW, LipProductFinish.GLOSS, LipstickTexture.LIQUID, listOf(
            shade("VM-1008-008", "08", 0x371A12),
        )),
        LipColorProduct("make-up-forever-lip-gloss", "Make Up Forever Lip Gloss", LipstickDensity.LOW, LipProductFinish.GLOSS, LipstickTexture.LIQUID, listOf(
            shade("VM-1008-006", "06", 0xBE7C6E),
        )),
        LipColorProduct("shik-lip-gloss-balm", "Shik Total repair Balm", LipstickDensity.LOW, LipProductFinish.GLOSS, LipstickTexture.LIQUID, listOf(
            shade("VM-1008-001", "01", 0xF7C8BE),
            shade("VM-1008-002", "02", 0xAF584F),
            shade("VM-1008-005", "05", 0x2E0201),
        )),
        LipColorProduct("love-generation-lip-gloss", "Love Generation Lip Gloss Wet Dream", LipstickDensity.LOW, LipProductFinish.GLOSS, LipstickTexture.LIQUID, listOf(
            shade("VM-1009-006", "06", 0xB0453D),
        )),
        LipColorProduct("influence-lip-balm", "Influence Lipstick Balm", LipstickDensity.MEDIUM, LipProductFinish.GLOSS, LipstickTexture.LIQUID, listOf(
            shade("VM-1009-014", "14", 0x90372B),
        )),
        LipColorProduct("guerlain-bee-glow", "GUERLAIN, kiss bee glow oil", LipstickDensity.LOW, LipProductFinish.OIL, LipstickTexture.LIQUID, listOf(
            shade("VM-1009-309", "309", 0xEE6563),
            shade("VM-1009-775", "775", 0xF50F14),
        )),
        LipColorProduct("ok-beauty-tint", "OK Beauty", LipstickDensity.MEDIUM, LipProductFinish.TINT, LipstickTexture.LIQUID, listOf(
            shade("VM-1009-234", "Valkyrie", 0x78293D),
            shade("VM-1009-235", "Safari", 0xD78385),
        )),
    )

    val swatches: List<LipColorSwatch> = products.flatMap { product ->
        product.shades.map { LipColorSwatch(product, it) }
    }
}
