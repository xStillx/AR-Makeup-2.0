package com.example.armakeup.makeup

/** Source RGB catalogue from iOS dev cdc2366, independent of lipstick selection. */
internal object LipLinerCatalog {
    data class Shade(val id: String, val product: String, val name: String, val color: LipstickColor)

    val shades = listOf(
        Shade("VM-LP-1001-RW", "Divage Lip Pencil", "09 Терракотовый", LipstickColor(0x843F33)),
        Shade("VM-LP-1001-WN", "Divage Lip Pencil", "Тёплый нюд", LipstickColor(0x941B2B)),
        Shade("VM-LP-1002-TR", "Vivienne Sabo гелевый", "03 Холодный нюд", LipstickColor(0xC58394)),
    )
}

internal data class LipLinerStyle(
    val enabled: Boolean = false,
    val color: LipstickColor = LipLinerCatalog.shades.first().color,
    val opacity: Float = 0.76f,
    val thickness: Float = 2f,
    val blur: Float = 2f,
)
