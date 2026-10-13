package com.paperknifeplus.app.ui.components

import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSNumber
import com.tom_roush.pdfbox.cos.COSObject
import com.tom_roush.pdfbox.cos.COSStream
import com.tom_roush.pdfbox.cos.COSString
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.function.PDFunction
import java.io.IOException

/**
 * The gray level of an RGB colour, with the weights of ColorMatrix.setSaturation(0), so the output matches the tool's live preview
 * (GrayscaleColorFilter).
 */
internal fun luminance(r: Float, g: Float, b: Float): Float = (0.213f * r + 0.715f * g + 0.072f * b).coerceIn(0f, 1f)

/**
 * A PDF colour space reduced to what grayscale conversion needs: how many components a colour has, how image samples map to them, and the
 * gray level (0 black, 1 white) of any colour. Built from the COS objects, since pdfbox-android itself treats every colour space other than
 * DeviceGray and DeviceRGB as DeviceRGB.
 */
internal sealed class ColorModel {
    abstract val components: Int

    /** The gray level of a colour given as [components] values. */
    abstract fun gray(c: FloatArray): Float

    /** What a sample of [bitsPerComponent] bits maps to when an image has no /Decode array: min and max per component. */
    open fun defaultDecode(bitsPerComponent: Int): FloatArray = FloatArray(components * 2) { if (it % 2 == 0) 0f else 1f }

    /** The colour cs and CS select along with the space. */
    open val initial: FloatArray get() = FloatArray(components)

    /** DeviceGray, CalGray, or an ICC profile with one component. */
    object Gray : ColorModel() {
        override val components = 1
        override fun gray(c: FloatArray) = c[0].coerceIn(0f, 1f)
    }

    /** DeviceRGB, CalRGB, or an ICC profile with three components. */
    object Rgb : ColorModel() {
        override val components = 3
        override fun gray(c: FloatArray) = luminance(c[0], c[1], c[2])
    }

    /** DeviceCMYK, or an ICC profile with four components; converted with the naive formula of PDF 32000 section 10.4. */
    object Cmyk : ColorModel() {
        override val components = 4
        override val initial get() = floatArrayOf(0f, 0f, 0f, 1f)
        override fun gray(c: FloatArray): Float {
            val k = 1 - c[3].coerceIn(0f, 1f)
            return luminance((1 - c[0].coerceIn(0f, 1f)) * k, (1 - c[1].coerceIn(0f, 1f)) * k, (1 - c[2].coerceIn(0f, 1f)) * k)
        }
    }

    /** CIE L*a*b*; the gray level is the lightness L* / 100. */
    class Lab(private val range: FloatArray) : ColorModel() {
        override val components = 3
        override fun gray(c: FloatArray) = (c[0] / 100f).coerceIn(0f, 1f)
        override fun defaultDecode(bitsPerComponent: Int) = floatArrayOf(0f, 100f, range[0], range[1], range[2], range[3])
    }

    /** A palette over [base]; [lookup] holds hival + 1 colours of base.components bytes each. */
    class Indexed(private val base: ColorModel, private val hival: Int, private val lookup: ByteArray) : ColorModel() {
        override val components = 1
        override fun defaultDecode(bitsPerComponent: Int) = floatArrayOf(0f, ((1 shl bitsPerComponent) - 1).toFloat())
        override fun gray(c: FloatArray): Float {
            val n = base.components
            val index = Math.round(c[0]).coerceIn(0, hival)
            val ranges = base.defaultDecode(8)
            val entry = FloatArray(n) { i ->
                val byte = if (index * n + i < lookup.size) lookup[index * n + i].toInt() and 0xFF else 0
                ranges[2 * i] + byte * (ranges[2 * i + 1] - ranges[2 * i]) / 255f
            }
            return base.gray(entry)
        }
    }

    /** Separation or DeviceN: [components] tints shown through [alternate] by the tint transform [function]. */
    class Tinted(override val components: Int, private val alternate: ColorModel, private val function: PDFunction) : ColorModel() {
        override val initial get() = FloatArray(components) { 1f }
        override fun gray(c: FloatArray): Float {
            val out = function.eval(c)
            if (out.size < alternate.components) throw IOException("Tint transform gives ${out.size} values, needs ${alternate.components}")
            return alternate.gray(out)
        }
    }

    /** A Pattern space; [base] is set for uncoloured patterns, whose colour is given with each scn. */
    class Pattern(val base: ColorModel?) : ColorModel() {
        override val components = base?.components ?: 0
        override fun gray(c: FloatArray) = base?.gray(c) ?: throw IOException("A coloured pattern has no colour components")
    }
}

/**
 * The [ColorModel] of a colour space entry, resolving names through [resources]. Throws [IOException] for a space it cannot map, so the
 * caller can fall back to rendering.
 */
internal fun colorModel(space: COSBase?, resources: PDResources?, depth: Int = 0): ColorModel {
    if (depth > 8) throw IOException("Colour space nesting too deep")
    return when (val s = if (space is COSObject) space.getObject() else space) {
        is COSName -> when (s.name) {
            "DeviceGray", "G", "CalGray" -> ColorModel.Gray
            "DeviceRGB", "RGB", "CalRGB" -> ColorModel.Rgb
            "DeviceCMYK", "CMYK" -> ColorModel.Cmyk
            "Pattern" -> ColorModel.Pattern(null)
            else -> {
                val named = (resources?.cosObject?.getDictionaryObject(COSName.COLORSPACE) as? COSDictionary)?.getDictionaryObject(s)
                    ?: throw IOException("Unknown colour space ${s.name}")
                colorModel(named, resources, depth + 1)
            }
        }
        is COSArray -> arrayModel(s, resources, depth)
        else -> throw IOException("Unreadable colour space $s")
    }
}

private fun arrayModel(space: COSArray, resources: PDResources?, depth: Int): ColorModel {
    val family = space.getObject(0) as? COSName ?: throw IOException("Colour space without a family")
    return when (family.name) {
        "DeviceGray", "G", "CalGray" -> ColorModel.Gray
        "DeviceRGB", "RGB", "CalRGB" -> ColorModel.Rgb
        "DeviceCMYK", "CMYK" -> ColorModel.Cmyk
        "Lab" -> {
            val range = ((space.getObject(1) as? COSDictionary)?.getDictionaryObject(COSName.RANGE) as? COSArray)?.toFloatArray()
            ColorModel.Lab(if (range != null && range.size == 4) range else floatArrayOf(-100f, 100f, -100f, 100f))
        }
        "ICCBased" -> when (((space.getObject(1) as? COSStream)?.getDictionaryObject(COSName.N) as? COSNumber)?.intValue()) {
            1 -> ColorModel.Gray
            3 -> ColorModel.Rgb
            4 -> ColorModel.Cmyk
            else -> throw IOException("ICC profile with an unsupported component count")
        }
        "Indexed", "I" -> {
            val base = colorModel(space.getObject(1), resources, depth + 1)
            if (base is ColorModel.Pattern || base is ColorModel.Indexed) throw IOException("Indexed over ${family.name}")
            val hival = (space.getObject(2) as? COSNumber)?.intValue() ?: throw IOException("Indexed without hival")
            val lookup = when (val table = space.getObject(3)) {
                is COSString -> table.bytes
                is COSStream -> table.createInputStream().use { it.readBytes() }
                else -> throw IOException("Indexed without a lookup table")
            }
            ColorModel.Indexed(base, hival.coerceIn(0, 255), lookup)
        }
        "Separation", "DeviceN" -> {
            val count = if (family.name == "Separation") 1 else (space.getObject(1) as? COSArray)?.size() ?: throw IOException("DeviceN without names")
            val alternate = colorModel(space.getObject(2), resources, depth + 1)
            if (alternate is ColorModel.Pattern || alternate is ColorModel.Indexed) throw IOException("${family.name} over an unusable alternate")
            ColorModel.Tinted(count, alternate, PDFunction.create(space.getObject(3)))
        }
        "Pattern" -> ColorModel.Pattern(if (space.size() > 1) colorModel(space.getObject(1), resources, depth + 1) else null)
        else -> throw IOException("Unsupported colour space ${family.name}")
    }
}
