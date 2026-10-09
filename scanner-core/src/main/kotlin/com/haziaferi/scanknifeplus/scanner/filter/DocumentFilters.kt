// Ported from OpenScan lib/core/image_filter/filters/document_filters.dart and filters.dart.
// Copyright (c) 2021, Vijay T S and Vikram H, BSD-3-Clause. See NOTICE.md.
package com.haziaferi.scanknifeplus.scanner.filter

import com.haziaferi.scanknifeplus.scanner.cv.EdgeDetection
import com.haziaferi.scanknifeplus.scanner.dartRound
import com.haziaferi.scanknifeplus.scanner.setU8
import com.haziaferi.scanknifeplus.scanner.u8
import kotlin.math.max
import kotlin.math.min

/**
 * A colour mode that can be applied to a scanned page. [name] is the filter's stable id (cache key and stored value), so it is not localized;
 * the label the user sees is resolved separately.
 */
abstract class Filter(val name: String) {
    /** Rewrites [pixels], an RGBA buffer of stride 4, in place. */
    abstract fun apply(pixels: ByteArray, width: Int, height: Int)
}

/**
 * The scanner's colour modes, in picker order. These are document modes, not photo looks, mirroring what mainstream scanner apps ship.
 */
object DocumentFilters {
    val all: List<Filter> = listOf(Original, Auto, Lighten, Grayscale, BlackAndWhite, Whiteboard)

    /** The filter a page has when nothing has been applied to it. */
    val default: Filter get() = all.first()

    /** Looks a filter up by its stored name, falling back to [default] for an unknown or missing value. */
    fun byName(name: String?): Filter = if (name == null) default else all.firstOrNull { it.name == name } ?: default

    /** Fraction of the darkest/brightest pixels ignored when auto-levelling. */
    private const val CLIP_FRACTION = 0.005

    /** Leaves the capture exactly as it was shot. */
    object Original : Filter("Original") {
        override fun apply(pixels: ByteArray, width: Int, height: Int) {
            // Nothing to do: this is what "no filter" means.
        }
    }

    /**
     * Auto colour: per-channel auto-levels plus a touch of contrast. Stretching each channel independently also neutralises the colour cast of
     * the light the page was shot under, so the paper goes white and the ink saturates without picking a white balance.
     */
    object Auto : Filter("Auto") {
        override fun apply(pixels: ByteArray, width: Int, height: Int) {
            for (channel in 0 until 3) {
                val bounds = DocumentFilterUtils.percentileBounds(DocumentFilterUtils.channelHistogram(pixels, channel), CLIP_FRACTION, CLIP_FRACTION)
                DocumentFilterUtils.applyLutToChannel(pixels, channel, DocumentFilterUtils.stretchLut(bounds[0], bounds[1]))
            }
            ImageFilterUtils.contrast(pixels, 0.08)
        }
    }

    /**
     * Lighten / save ink: keeps the colours but drives the paper to white. The same scale is applied to all three channels so hues are preserved;
     * above [KNEE_START] the pixel is rolled smoothly towards white. Both halves key off luminance, not individual channels, so no tinted seam
     * or visible contour appears where the paper crosses the cutoff.
     */
    object Lighten : Filter("Lighten") {
        /** Luminance at which a pixel starts being treated as background. */
        private const val KNEE_START = 200

        override fun apply(pixels: ByteArray, width: Int, height: Int) {
            val gray = EdgeDetection.rgbaToGrayscale(pixels, width, height)
            // Clip harder at the top than Auto does: the goal is to find the paper, not the brightest surviving pixel.
            val bounds = DocumentFilterUtils.percentileBounds(DocumentFilterUtils.grayHistogram(gray), 0.001, 0.05)
            val scaleLut = DocumentFilterUtils.stretchLut(0, bounds[1])

            // How far a pixel of each original luminance ends up into the background band, as a 0-255 blend towards white; smoothstepped so the
            // transition has no edge to see.
            val blendLut = IntArray(256)
            for (v in 0 until 256) {
                val scaled = scaleLut.u8(v)
                if (scaled <= KNEE_START) continue
                val t = (scaled - KNEE_START).toDouble() / (255 - KNEE_START)
                blendLut[v] = dartRound(t * t * (3 - 2 * t) * 255) and 0xFF
            }

            var i = 0
            var pixel = 0
            while (i < pixels.size) {
                val blend = blendLut[gray.u8(pixel)]
                for (channel in 0 until 3) {
                    val scaled = scaleLut.u8(pixels.u8(i + channel))
                    pixels.setU8(i + channel, scaled + ((255 - scaled) * blend) / 255)
                }
                i += 4
                pixel++
            }
        }
    }

    /** Luminance-only, auto-levelled so text stays readable after the colour information is gone. */
    object Grayscale : Filter("Grayscale") {
        override fun apply(pixels: ByteArray, width: Int, height: Int) {
            ImageFilterUtils.grayscale(pixels)
            val bounds = DocumentFilterUtils.percentileBounds(DocumentFilterUtils.channelHistogram(pixels, 0), CLIP_FRACTION, CLIP_FRACTION)
            DocumentFilterUtils.applyLutToRgb(pixels, DocumentFilterUtils.stretchLut(bounds[0], bounds[1]))
        }
    }

    /**
     * Pure black and white via Bradley-Roth adaptive thresholding: each pixel is compared against the mean of a window around it, so a shadowed
     * corner binarizes on its own terms. Also by far the smallest mode to store in a PDF.
     */
    object BlackAndWhite : Filter("B&W") {
        /** How far below the local mean a pixel must sit to count as ink (Bradley-Roth's 15%). */
        private const val BIAS = 0.15

        override fun apply(pixels: ByteArray, width: Int, height: Int) {
            val gray = EdgeDetection.rgbaToGrayscale(pixels, width, height)
            val mean = localMeanField(gray, width, height)

            for (y in 0 until height) {
                val meanRow = (y * mean.height / height) * mean.width
                val row = y * width
                for (x in 0 until width) {
                    val local = mean.values.u8(meanRow + (x * mean.width / width))
                    val ink = gray.u8(row + x) < local * (1 - BIAS)
                    val v = if (ink) 0 else 255
                    val i = (row + x) * 4
                    pixels.setU8(i, v)
                    pixels.setU8(i + 1, v)
                    pixels.setU8(i + 2, v)
                }
            }
        }
    }

    /**
     * Whiteboard: divides out the illumination so shadows and glare flatten, then pushes saturation and contrast so the marker strokes pop.
     * The illumination estimate is a heavily blurred copy of the image.
     */
    object Whiteboard : Filter("Whiteboard") {
        override fun apply(pixels: ByteArray, width: Int, height: Int) {
            val gray = EdgeDetection.rgbaToGrayscale(pixels, width, height)
            val illumination = illuminationField(gray, width, height)

            for (y in 0 until height) {
                val fieldRow = (y * illumination.height / height) * illumination.width
                val row = y * width
                for (x in 0 until width) {
                    val level = illumination.values.u8(fieldRow + (x * illumination.width / width))
                    // Guard the divisor: a genuinely black region would otherwise blow up to pure white.
                    val scale = 255.0 / max(level, 16)
                    val i = (row + x) * 4
                    pixels.setU8(i, ImageFilterUtils.clampPixel(dartRound(pixels.u8(i) * scale)))
                    pixels.setU8(i + 1, ImageFilterUtils.clampPixel(dartRound(pixels.u8(i + 1) * scale)))
                    pixels.setU8(i + 2, ImageFilterUtils.clampPixel(dartRound(pixels.u8(i + 2) * scale)))
                }
            }

            ImageFilterUtils.saturation(pixels, 0.35)
            ImageFilterUtils.contrast(pixels, 0.15)
        }
    }

    /** A low-frequency single-channel field sampled back up to image size by the filter that asked for it. */
    private class Field(val values: ByteArray, val width: Int, val height: Int)

    /** Longest edge the mean/illumination fields are computed at; both are smooth by construction, so downscaling costs no visible quality. */
    private const val FIELD_MAX_EDGE = 640

    private fun downscaledGray(gray: ByteArray, width: Int, height: Int): Field {
        val longest = max(width, height)
        if (longest <= FIELD_MAX_EDGE) return Field(gray, width, height)
        val scale = FIELD_MAX_EDGE.toDouble() / longest
        val w = max(1, dartRound(width * scale))
        val h = max(1, dartRound(height * scale))
        return Field(DocumentFilterUtils.downscaleGray(gray, width, height, w, h), w, h)
    }

    /** Mean of a window roughly an eighth of the page wide around each pixel: the window size Bradley-Roth recommends for text. */
    private fun localMeanField(gray: ByteArray, width: Int, height: Int): Field {
        val small = downscaledGray(gray, width, height)
        val radius = max(2, small.width / 16)
        return Field(DocumentFilterUtils.boxBlur(small.values, small.width, small.height, radius), small.width, small.height)
    }

    /** Much wider window than [localMeanField]: here the strokes have to average away entirely, leaving only the lighting. */
    private fun illuminationField(gray: ByteArray, width: Int, height: Int): Field {
        val small = downscaledGray(gray, width, height)
        val radius = max(4, min(small.width, small.height) / 6)
        return Field(DocumentFilterUtils.boxBlur(small.values, small.width, small.height, radius), small.width, small.height)
    }
}
