package com.haziaferi.scanknifeplus.ui.scan

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import com.paperknifeplus.app.ui.theme.AppPalette
import com.paperknifeplus.app.ui.theme.CameraColors
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Rule 16 over the worst camera pixel, a white page: every text and icon the Scanner draws over the picture keeps 4.5:1 against its backing
 * (offBg, the token the screen draws them on) composited over white, in every palette; the selected mode keeps 4.5:1 on its fill, and the
 * shutter's ring 3:1 against the solid footer. The figures are printed for the report.
 */
class CameraContrastTest {
    private val white = Color(0xFFFFFFFF)

    private fun channel(c: Int): Double {
        val v = c / 255.0
        return if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
    }

    /** WCAG relative luminance of [color] as drawn: opaque, in 8-bit channels. */
    private fun luminance(color: Color): Double {
        val argb = color.toArgb()
        return 0.2126 * channel(argb shr 16 and 0xFF) + 0.7152 * channel(argb shr 8 and 0xFF) + 0.0722 * channel(argb and 0xFF)
    }

    private fun contrast(a: Color, b: Color): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    private class Check(val what: String, val need: Double, val ratio: (CameraColors) -> Double)

    private val checks = listOf(
        Check("modes, close, Auto off, torch off, hold steady, low light, page labels: onCam on offBg over white", 4.5) {
            contrast(it.onCam, it.offBg.compositeOver(white))
        },
        Check("selected mode (and Auto on): onFg on onBg", 4.5) { contrast(it.onFg, it.onBg) },
        Check("shutter ring: outline on the solid footer (cam)", 3.0) { contrast(it.outline, it.cam) },
    )

    private val palettes = AppPalette.entries.flatMap { listOf("${it.name} light" to it.light.camera, "${it.name} oled" to it.oled.camera) }

    @Test
    fun `everything over the picture and the shutter's ring keep their contrast in every palette`() {
        val failures = mutableListOf<String>()
        for ((name, k) in palettes) {
            for (check in checks) {
                val ratio = check.ratio(k)
                println("$name  %5.2f:1  ${check.what}".format(ratio))
                if (ratio < check.need) failures += "$name ${check.what} = %.2f < ${check.need}".format(ratio)
            }
            // Reported, not held to 4.5:1: a locked mode is disabled (exempt from 1.4.3), and the ring's track differs from it by hue.
            val band = k.offBg.compositeOver(white)
            println("$name  %5.2f:1  (report) locked mode label: onCam at $LOCKED_MODE_ALPHA on offBg over white".format(
                contrast(k.onCam.copy(alpha = LOCKED_MODE_ALPHA).compositeOver(band), band)
            ))
            println("$name  %5.2f:1  (report) ring on its track: outline on shutter".format(contrast(k.outline, k.shutter)))
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }
}
