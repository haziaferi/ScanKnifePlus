package com.paperknifeplus.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.AdaptiveIconDrawable
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import com.paperknifeplus.app.R
import java.io.File
import kotlin.math.pow
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * The launcher icon as Android draws it (Robolectric's native graphics): the generated vector drawables inflate, the scan beam
 * stays legible at launcher sizes, each part keeps its colour, and the themed icon cuts the beam out of the dart.
 * Points are given on the design's 108-unit artboard (docs/design/launcher), which is the whole visible icon.
 * The renders are also written to build/reports/launcher-icon/ for review.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LauncherIconTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val out = File("build/reports/launcher-icon").apply { mkdirs() }

    private fun icon() = ContextCompat.getDrawable(context, R.drawable.ic_launcher) as AdaptiveIconDrawable

    /** The icon as a launcher shows it, [px] pixels across; the adaptive icon maps its 72 dp safe zone onto the bounds. */
    private fun render(px: Int, name: String): Bitmap {
        val bitmap = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        icon().apply { setBounds(0, 0, px, px) }.draw(Canvas(bitmap))
        File(out, "$name-$px.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return bitmap
    }

    private fun Bitmap.at(x: Double, y: Double) = getPixel((x / 108 * width).toInt(), (y / 108 * height).toInt())

    private fun luminance(c: Int): Double {
        fun ch(v: Int) = (v / 255.0).let { if (it <= 0.03928) it / 12.92 else ((it + 0.055) / 1.055).pow(2.4) }
        return 0.2126 * ch(Color.red(c)) + 0.7152 * ch(Color.green(c)) + 0.0722 * ch(Color.blue(c))
    }

    private fun contrast(a: Int, b: Int): Double {
        val (hi, lo) = listOf(luminance(a), luminance(b)).sortedDescending()
        return (hi + 0.05) / (lo + 0.05)
    }

    @Test
    fun `the icon is an adaptive icon with background, foreground and themed layers`() {
        val icon = icon()
        assertNotNull(icon.background)
        assertNotNull(icon.foreground)
        assertNotNull(icon.monochrome)
        assertTrue(ContextCompat.getDrawable(context, R.drawable.ic_launcher_round) is AdaptiveIconDrawable)
    }

    @Test
    fun `the scan beam stands out from the tile at home-screen and small sizes`() {
        for (px in listOf(144, 48)) {
            val icon = render(px, "icon")
            // Where the beam crosses open tile, left of the dart, against the tile just above it.
            val beam = (0..8).map { icon.at(28.0, 51.0 + it * 0.75) }.maxBy { luminance(it) }
            val ratio = contrast(beam, icon.at(28.0, 49.0 - 54.0 / px))
            assertTrue("beam at $px px: ${"%.2f".format(ratio)}:1", ratio >= 3.0)
        }
    }

    @Test
    fun `the red half, the steel half and the tile keep their colours`() {
        val icon = render(432, "icon")
        val red = icon.at(54.0, 45.7)
        assertTrue("red half ${Integer.toHexString(red)}", Color.red(red) > Color.green(red) + 60 && Color.red(red) > Color.blue(red) + 40)
        val steel = icon.at(54.0, 62.3)
        val spread = listOf(Color.red(steel), Color.green(steel), Color.blue(steel)).let { it.max() - it.min() }
        assertTrue("steel half ${Integer.toHexString(steel)}", spread < 40 && luminance(steel) > 0.1)
        val tile = icon.at(54.0, 100.0)
        assertTrue("tile ${Integer.toHexString(tile)}", Color.blue(tile) > Color.red(tile) && Color.blue(tile) > Color.green(tile))
    }

    @Test
    fun `the themed icon cuts the beam out of the dart`() {
        val layer = icon().monochrome!!
        val px = 432
        val bitmap = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        layer.setBounds(0, 0, px, px)
        layer.draw(Canvas(bitmap))
        File(out, "monochrome-$px.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        // The layer is 108 dp with the artboard scaled by 72/108 about its centre.
        fun alpha(x: Double, y: Double) = Color.alpha(bitmap.at(54 + (x - 54) * 2 / 3, 54 + (y - 54) * 2 / 3))
        assertTrue("seam inside the dart should be cut", alpha(62.0, 54.0) < 64)
        assertTrue("top half should be solid", alpha(62.0, 49.0) > 200)
        assertTrue("bottom half should be solid", alpha(62.0, 59.0) > 200)
        assertTrue("beam outside the dart should be drawn", alpha(28.0, 54.0) > 200)
    }
}
