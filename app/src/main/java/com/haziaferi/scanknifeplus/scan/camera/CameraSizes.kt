package com.haziaferi.scanknifeplus.scan.camera

import kotlin.math.abs

/** A camera output size in sensor orientation; a plain pair so the size rules run on the JVM without android.util.Size. */
data class Dim(val width: Int, val height: Int) {
    val area: Long get() = width.toLong() * height
    val longEdge: Int get() = maxOf(width, height)
    val shortEdge: Int get() = minOf(width, height)

    /** Long edge over short edge, so the same shape compares equal in either orientation. */
    val aspect: Double get() = longEdge.toDouble() / shortEdge

    override fun toString() = "${width}x$height"
}

/**
 * Which output sizes the scan camera asks CameraX for. OpenScan opened every stream with ResolutionPreset.high, which camera_android_camerax maps
 * to a 1280x720 bound at 16:9 for the preview, the analysis stream and the still alike, so its photos were 720p. Here the still is the largest JPEG
 * the camera offers (the user's decision), and the preview and analysis streams take the still's shape, so the frame live detection sees and the
 * photo the quad is applied to cover the same field of view. Analysis keeps OpenScan's 720 px short edge: on a 4:3 sensor that is 960x720 instead
 * of 1280x720, and detection, which downsamples to a 320 px long edge anyway, runs at 320x240 instead of 320x180.
 */
object CameraSizes {
    /** Analysis bound: OpenScan's ResolutionPreset.high (1280x720), long edge by short edge. */
    val ANALYSIS_BOUND = Dim(1280, 720)

    /** Preview bound: 1080p, the largest PREVIEW-class stream every camera guarantees alongside a YUV stream and a maximum-size JPEG. */
    val PREVIEW_BOUND = Dim(1920, 1080)

    /** Two shapes count as the same within this tolerance on the long/short ratio (sizes are rounded to even or 16-px steps). */
    private const val ASPECT_TOLERANCE = 0.01

    /** The still size: the largest by area; null if there are none. */
    fun largest(sizes: List<Dim>): Dim? = sizes.maxByOrNull { it.area }

    /** [sizes] ordered largest first, for the still's ResolutionFilter. */
    fun stillPreference(sizes: List<Dim>): List<Dim> = sizes.sortedByDescending { it.area }

    fun sameShape(a: Dim, b: Dim): Boolean = abs(a.aspect - b.aspect) <= ASPECT_TOLERANCE * b.aspect

    /**
     * [sizes] ordered for a stream that must match [still]'s shape and stay within [bound] (compared long edge to long edge, short to short):
     * same-shape sizes within the bound, largest first; then same-shape sizes over the bound, smallest first; then every other size in its
     * original order, as a last resort that CameraX only reaches if nothing of the right shape can be configured.
     */
    fun streamPreference(sizes: List<Dim>, still: Dim, bound: Dim): List<Dim> {
        val (sameShape, others) = sizes.partition { sameShape(it, still) }
        val (within, over) = sameShape.partition { it.longEdge <= bound.longEdge && it.shortEdge <= bound.shortEdge }
        return within.sortedByDescending { it.area } + over.sortedBy { it.area } + others
    }
}
