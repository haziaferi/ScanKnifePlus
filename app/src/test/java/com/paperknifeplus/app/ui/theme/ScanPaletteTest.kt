package com.paperknifeplus.app.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** The accessibility rules the approved palettes were scored on, checked for every palette x theme. */
class ScanPaletteTest {
    private val variants: List<Pair<String, ScanPalette>> =
        AppPalette.entries.flatMap { listOf("${it.name} light" to it.light, "${it.name} oled" to it.oled) }

    private fun channel(c: Int): Double {
        val v = c / 255.0
        return if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
    }

    private fun luminance(color: Color): Double {
        val argb = color.toArgb()
        return 0.2126 * channel(argb shr 16 and 0xFF) + 0.7152 * channel(argb shr 8 and 0xFF) + 0.0722 * channel(argb and 0xFF)
    }

    private fun contrast(a: Color, b: Color): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    /** HSL lightness in percent. */
    private fun hslLightness(color: Color): Double {
        val argb = color.toArgb()
        val channels = listOf(argb shr 16 and 0xFF, argb shr 8 and 0xFF, argb and 0xFF)
        return (channels.max() + channels.min()) / 2.0 / 255.0 * 100.0
    }

    private class Rule(val fg: String, val bg: String, val need: Double, val f: (ScanPalette) -> Color, val b: (ScanPalette) -> Color)

    private val rules: List<Rule> = buildList {
        val surfaces = listOf<Pair<String, (ScanPalette) -> Color>>("bg" to { it.bg }, "sf" to { it.sf }, "sf2" to { it.sf2 })
        val cardSurfaces = surfaces.take(2)
        for ((s, sv) in surfaces) {
            add(Rule("tx", s, 4.5, { it.tx }, sv))
            add(Rule("mu", s, 4.5, { it.mu }, sv))
            add(Rule("meta", s, 4.5, { it.meta }, sv))
            add(Rule("nav", s, 7.0, { it.nav }, sv))
        }
        for ((s, sv) in cardSurfaces) {
            add(Rule("ac", s, 4.5, { it.ac }, sv))
            add(Rule("danger", s, 4.5, { it.danger }, sv))
            add(Rule("bd", s, 3.0, { it.bd }, sv))
            add(Rule("ring", s, 3.0, { it.ring }, sv))
            add(Rule("ok", s, 3.0, { it.ok }, sv))
            add(Rule("acf", s, 3.0, { it.acf }, sv))
        }
        add(Rule("onAcf", "acf", 7.0, { it.onAcf }, { it.acf }))
        add(Rule("ac", "acSoft", 4.5, { it.ac }, { it.acSoft }))
        add(Rule("selFg", "selBg", 4.5, { it.selFg }, { it.selBg }))
        add(Rule("warnFg", "warnBg", 4.5, { it.warnFg }, { it.warnBg }))
        add(Rule("scanFg", "scanBg", 4.5, { it.scanFg }, { it.scanBg }))
        add(Rule("toolFg", "toolBg", 4.5, { it.toolFg }, { it.toolBg }))
        add(Rule("confFg", "confBg", 4.5, { it.confFg }, { it.confBg }))
        add(Rule("barMu", "bar", 4.5, { it.barMu }, { it.bar }))
        add(Rule("barOk", "bar", 3.0, { it.barOk }, { it.bar }))
        add(Rule("camera.onCam", "camera.cam", 4.5, { it.camera.onCam }, { it.camera.cam }))
        add(Rule("camera.shutter", "camera.cam", 3.0, { it.camera.shutter }, { it.camera.cam }))
        add(Rule("camera.onFg", "camera.onBg", 4.5, { it.camera.onFg }, { it.camera.onBg }))
        add(Rule("camera.torchFg", "camera.torchBg", 4.5, { it.camera.torchFg }, { it.camera.torchBg }))
        add(Rule("camera.doneFg", "camera.doneBg", 7.0, { it.camera.doneFg }, { it.camera.doneBg }))
        // The outline and the split line are drawn over whatever the camera sees: checked over a black and a mid-grey scene.
        for ((scene, colour) in listOf("black preview" to Color(0xFF000000), "mid-grey preview" to Color(0xFF777777))) {
            add(Rule("camera.outline", scene, 3.0, { it.camera.outline }, { colour }))
            add(Rule("camera.split", scene, 3.0, { it.camera.split }, { colour }))
        }
    }

    @Test
    fun `every palette and theme meets the contrast rules`() {
        val failures = variants.flatMap { (name, p) ->
            rules.mapNotNull { r ->
                val ratio = contrast(r.f(p), r.b(p))
                if (ratio < r.need) "$name ${r.fg}/${r.bg} = ${"%.2f".format(ratio)} < ${r.need}" else null
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    private fun tokens(p: ScanPalette): List<Color> = with(p) {
        listOf(
            bg, sf, sf2, tx, mu, meta, nav, bd, ac, acf, onAcf, acSoft, ok, ring, warnBg, warnFg, danger, selBg, selFg,
            scanBg, scanFg, toolBg, toolFg, confBg, confFg, bar, barMu, barOk
        ) + with(camera) { listOf(cam, onCam, shutter, ctrlOffBg, offBg, onBg, onFg, torchBg, torchFg, doneBg, doneFg) }
    }

    @Test
    fun `no token is pure black or pure white`() {
        for ((name, p) in variants) {
            for (color in tokens(p)) {
                val rgb = color.toArgb() and 0xFFFFFF
                assertTrue("$name has ${Integer.toHexString(color.toArgb())}", rgb != 0x000000 && rgb != 0xFFFFFF)
            }
        }
    }

    @Test
    fun `OLED surfaces are within the OLED floor`() {
        for (palette in AppPalette.entries) {
            for (surface in with(palette.oled) { listOf(bg, sf, sf2) }) {
                assertTrue("${palette.name} ${Integer.toHexString(surface.toArgb())}", hslLightness(surface) >= 5.0)
            }
        }
    }

    /** Every ColorScheme slot, read by reflection so a slot added by a Material3 update is caught too. */
    private fun slots(scheme: ColorScheme): Map<String, Color> =
        ColorScheme::class.java.methods
            .filter { it.parameterCount == 0 && it.returnType == Long::class.javaPrimitiveType && it.name.startsWith("get") }
            .associate { it.name.substringBefore('-') to Color((it.invoke(scheme) as Long).toULong()) }

    @Test
    fun `every Material3 slot comes from the palette's tokens`() {
        for ((name, p) in variants) {
            val scheme = p.toColorScheme()
            val slots = slots(scheme)
            assertEquals(48, slots.size)
            val allowed = tokens(p).toSet()
            for ((slot, color) in slots) assertTrue("$name $slot", color in allowed)
        }
    }

    @Test
    fun `the scheme maps the documented roles`() {
        val p = AppPalette.STILL_WATER.light
        val scheme = p.toColorScheme()
        assertEquals(p.acf, scheme.primary)
        assertEquals(p.onAcf, scheme.onPrimary)
        assertEquals(p.bg, scheme.background)
        assertEquals(p.sf, scheme.surface)
        assertEquals(p.sf2, scheme.surfaceVariant)
        assertEquals(p.tx, scheme.onSurface)
        assertEquals(p.mu, scheme.onSurfaceVariant)
        assertEquals(p.bd, scheme.outline)
        assertEquals(p.danger, scheme.error)
    }
}
