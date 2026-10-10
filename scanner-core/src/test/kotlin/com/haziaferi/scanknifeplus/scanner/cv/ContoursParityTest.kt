package com.haziaferi.scanknifeplus.scanner.cv

import com.haziaferi.scanknifeplus.scanner.ParityCase
import com.haziaferi.scanknifeplus.scanner.ParityFixture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Compares contour finding, candidate scoring and the corner helpers with OpenScan's Dart output (fixture: parity/contours.tsv). */
class ContoursParityTest {
    private val cases = ParityFixture.load("contours.tsv")
    private val maskCases = cases.filter { it.has("mask") }
    private val geometryCases = cases.filter { it.name.startsWith("geometry_") }

    @Test
    fun `fixture is present`() {
        assertTrue(maskCases.size >= 8)
        assertTrue(geometryCases.size >= 40)
        // At least one case must actually produce a detection, or the comparisons below prove little.
        assertTrue(maskCases.count { it.raw("best") != "null" } >= 7)
    }

    @Test
    fun `candidates match OpenScan`() {
        for (c in maskCases) {
            val candidates = Contours.findDocumentQuadCandidates(c.bytes("mask"), c.int("width"), c.int("height"))
            assertEquals("$c: candidates", c.raw("candidates"), if (candidates.isEmpty()) "none" else candidates.joinToString(";") { it.format() })
        }
    }

    @Test
    fun `best quad matches OpenScan with and without a previous quad`() {
        for (c in maskCases) {
            val w = c.int("width")
            val h = c.int("height")
            val mask = c.bytes("mask")
            val candidates = Contours.findDocumentQuadCandidates(mask, w, h)
            assertEquals("$c: best", c.raw("best"), Contours.pickBestQuad(candidates, w, h).format())
            val previous = c.quad("previous")
            assertEquals("$c: best_with_previous", c.raw("best_with_previous"), Contours.pickBestQuad(candidates, w, h, previous).format())
            assertEquals("$c: find", c.raw("find"), Contours.findDocumentQuad(mask, w, h).format())
        }
    }

    @Test
    fun `corner helpers match OpenScan`() {
        for (c in geometryCases) {
            val q = c.quad("points")!!
            assertEquals("$c: sorted", c.raw("sorted"), Contours.sortCorners(q.points).format())
            val m = Contours.bestCornerAssignment(q.points, c.quad("reference")!!)
            assertEquals("$c: assigned", c.raw("assigned"), m.quad.format())
            assertEquals("$c: assigned_distance", c.double("assigned_distance"), m.totalDistance, 0.0)
            assertEquals("$c: plausible", c.raw("plausible").toBoolean(), Contours.isPlausibleQuad(q, 100, 80))
            assertEquals("$c: plausible_sorted", c.raw("plausible_sorted").toBoolean(), Contours.isPlausibleQuad(Contours.sortCorners(q.points), 100, 80))
        }
    }
}

/** Formats like the Dart generator's `quadToString`: 8 comma-separated doubles, or `null`. */
internal fun Quad?.format(): String = this?.points?.flatMap { listOf(it.x, it.y) }?.joinToString(",") { dartDoubleString(it) } ?: "null"

/** Dart prints whole doubles as `9.0` like Kotlin, but switches to exponent notation at 1e21 rather than 1e7. */
private fun dartDoubleString(d: Double): String {
    val s = d.toString()
    if (!s.contains('E')) return s
    return java.math.BigDecimal(d).stripTrailingZeros().let { if (it.scale() <= 0) it.toPlainString() + ".0" else it.toPlainString() }
}

/** Parses the generator's `quadToString` output, the inverse of [format]. */
internal fun parseQuad(v: String): Quad? = if (v == "null") null else quadOf(v.split(',').map { it.toDouble() }.toDoubleArray())

internal fun ParityCase.quad(key: String): Quad? = parseQuad(raw(key))
