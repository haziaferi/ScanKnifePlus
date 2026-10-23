package com.haziaferi.scanknifeplus.scanner.cv

import com.haziaferi.scanknifeplus.scanner.dartRound
import java.util.Random
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The detector on synthetic photos: the cases OSS-DocumentScanner's candidates were ported for, and OpenScan's candidates as the fallback. */
class DocumentDetectorTest {
    private class Scene(val width: Int, val height: Int, val page: Quad, val rgba: ByteArray)

    /** A page of grey level [paper] over [background] (grey level per pixel), with seeded sensor noise. */
    private fun scene(width: Int, height: Int, page: Quad, paper: Int, seed: Long = 1, background: (Int, Int) -> Int): Scene {
        val r = Random(seed)
        val rgba = ByteArray(width * height * 4)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val v = ((if (inside(page, x + 0.5, y + 0.5)) paper else background(x, y)) + r.nextGaussian() * 3).toInt().coerceIn(0, 255)
                val i = (y * width + x) * 4
                rgba[i] = v.toByte()
                rgba[i + 1] = v.toByte()
                rgba[i + 2] = (v * 0.95).toInt().toByte()
                rgba[i + 3] = -1
            }
        }
        return Scene(width, height, page, rgba)
    }

    private fun inside(q: Quad, x: Double, y: Double): Boolean {
        val p = q.points
        var sign = 0
        for (i in 0 until 4) {
            val a = p[i]
            val b = p[(i + 1) % 4]
            val c = (b.x - a.x) * (y - a.y) - (b.y - a.y) * (x - a.x)
            val s = if (c > 0) 1 else if (c < 0) -1 else 0
            if (s != 0) {
                if (sign != 0 && s != sign) return false
                sign = s
            }
        }
        return true
    }

    /** Intersection over union of two quads, counted on the pixel grid of a [width] x [height] frame. */
    private fun iou(a: Quad, b: Quad, width: Int, height: Int): Double {
        var both = 0
        var either = 0
        for (y in 0 until height) {
            for (x in 0 until width) {
                val ia = inside(a, x + 0.5, y + 0.5)
                val ib = inside(b, x + 0.5, y + 0.5)
                if (ia && ib) both++
                if (ia || ib) either++
            }
        }
        return both.toDouble() / either
    }

    private fun rect(x0: Double, y0: Double, x1: Double, y1: Double) = Quad(Pt(x0, y0), Pt(x1, y0), Pt(x1, y1), Pt(x0, y1))

    private fun rotated(q: Quad, degrees: Double): Quad {
        val cx = q.points.sumOf { it.x } / 4
        val cy = q.points.sumOf { it.y } / 4
        val c = cos(Math.toRadians(degrees))
        val s = sin(Math.toRadians(degrees))
        val p = q.points.map { Pt(cx + (it.x - cx) * c - (it.y - cy) * s, cy + (it.x - cx) * s + (it.y - cy) * c) }
        return Quad(p[0], p[1], p[2], p[3])
    }

    private fun detect(s: Scene): Quad? = (DocumentDetector.detectFromRgba(s.rgba, s.width, s.height) as? DetectionResult.Success)?.quad

    private fun assertFinds(s: Scene, minIou: Double = 0.9) {
        val quad = detect(s)
        assertNotNull("no quad", quad)
        val score = iou(quad!!, s.page, s.width, s.height)
        assertTrue("IoU $score for $quad", score >= minIou)
    }

    /** The one-shot detector's working image: the photo shrunk to [DocumentDetector.DETECTION_MAX_DIMENSION], in grey. */
    private fun workingGray(s: Scene): Triple<Int, Int, ByteArray> {
        val longest = max(s.width, s.height).toDouble()
        val scale = if (longest > DocumentDetector.DETECTION_MAX_DIMENSION) DocumentDetector.DETECTION_MAX_DIMENSION / longest else 1.0
        val w = max(1, dartRound(s.width * scale))
        val h = max(1, dartRound(s.height * scale))
        return Triple(w, h, EdgeDetection.rgbaToGrayscale(DocumentDetector.downscaleRgba(s.rgba, s.width, s.height, w, h), w, h))
    }

    /** The detector before the port (OpenScan's candidates and ranking alone), for the fallback guarantee. */
    private fun oldDetect(gray: ByteArray, width: Int, height: Int): Quad? {
        val magnitude = EdgeDetection.sobelMagnitude(EdgeDetection.gaussianBlur3(gray, width, height), width, height)
        val base = EdgeDetection.otsuThreshold(magnitude)
        val candidates = doubleArrayOf(0.7, 1.0, 1.3).flatMap { m ->
            val binary = EdgeDetection.threshold(magnitude, dartRound(base * m).coerceIn(0, 255))
            Contours.findDocumentQuadCandidates(EdgeDetection.dilate(binary, width, height, 4), width, height)
        }
        return Contours.pickBestQuad(candidates, width, height)
    }

    private val cutOff = scene(600, 800, rect(150.0, 120.0, 600.0, 680.0), paper = 230) { _, _ -> 60 }
    private val onStripes = scene(600, 800, rotated(rect(110.0, 140.0, 490.0, 660.0), 6.0), paper = 225) { x, _ -> if ((x / 9) % 2 == 0) 85 else 145 }
    private val lowContrast = scene(600, 800, rotated(rect(100.0, 120.0, 500.0, 680.0), -4.0), paper = 200) { _, _ -> 168 }

    /** Under OSS-DocumentScanner's minimum area (10% of the frame), above OpenScan's (5%): only the fallback can find it. */
    private val small = scene(600, 800, rect(240.0, 300.0, 400.0, 520.0), paper = 230) { _, _ -> 60 }

    @Test
    fun `a page cut off by the frame is outlined along the frame edge`() = assertFinds(cutOff)

    @Test
    fun `a page on a striped background is found`() = assertFinds(onStripes)

    @Test
    fun `a low-contrast page is found`() = assertFinds(lowContrast)

    @Test
    fun `a page too small for the ported candidates still comes from OpenScan's`() {
        val (w, h, gray) = workingGray(small)
        assertEquals(oldDetect(gray, w, h)!!.scaled(small.width / w.toDouble(), small.height / h.toDouble()), detect(small))
        assertFinds(small, minIou = 0.85)
    }

    @Test
    fun `live frames go through the same detector`() {
        fun frame(s: Scene): ByteArray = EdgeDetection.rgbaToGrayscale(DocumentDetector.downscaleRgba(s.rgba, s.width, s.height, 240, 320), 240, 320)
        val scores = listOf(cutOff, onStripes, lowContrast).map { s ->
            val quad = DocumentDetector.detectQuadFromGrayscale(frame(s), 240, 320)
            quad?.let { iou(it.scaled(s.width / 240.0, s.height / 320.0), s.page, s.width, s.height) } ?: 0.0
        }
        assertTrue("IoU $scores", scores.all { it >= 0.9 })
        // Too small for the ported candidates: the fallback gives exactly what the old detector gave.
        assertEquals(oldDetect(frame(small), 240, 320), DocumentDetector.detectQuadFromGrayscale(frame(small), 240, 320))
    }

    @Test
    fun `a quad comes back whenever the old detector found one`() {
        val r = Random(7)
        val scenes = ArrayList(listOf(cutOff, onStripes, lowContrast, small))
        repeat(12) { i ->
            val x0 = 20.0 + r.nextInt(250)
            val y0 = 20.0 + r.nextInt(300)
            val page = rotated(rect(x0, y0, x0 + 60 + r.nextInt(300), y0 + 60 + r.nextInt(400)), r.nextDouble() * 30 - 15)
            val paper = 120 + r.nextInt(130)
            val bg = r.nextInt(200)
            val stripe = r.nextInt(3) * 30
            scenes += scene(400, 560, page, paper, seed = i.toLong()) { x, y -> bg + if (((x + y) / 7) % 2 == 0) stripe else 0 }
        }
        for ((i, s) in scenes.withIndex()) {
            val (w, h, gray) = workingGray(s)
            if (oldDetect(gray, w, h) != null) {
                assertNotNull("scene $i, one-shot", detect(s))
                assertNotNull("scene $i, live", DocumentDetector.detectQuadFromGrayscale(gray, w, h))
            }
        }
    }

    @Test
    fun `detectFromRgba reports bad input as Failure instead of throwing`() {
        val result = DocumentDetector.detectFromRgba(ByteArray(10), 100, 100)
        assertTrue(result is DetectionResult.Failure)
    }

    @Test
    fun `downscaleRgba returns the same buffer when the size is unchanged`() {
        val src = ByteArray(16)
        assertSame(src, DocumentDetector.downscaleRgba(src, 2, 2, 2, 2))
    }
}
