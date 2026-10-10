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
 * to a 1280x720 bound at 16:9 for the preview, the analysis stream and the still alike, so its photos were 720p crops of the sensor.
 *
 * Here every stream takes the shape of the sensor's active array (4:3 on most phones), so the still covers the sensor's whole field of view and
 * the frame live detection sees covers the same view as the photo the quad is applied to. The still is the largest size of that shape CameraX
 * offers. That is not always the largest size the camera lists: CameraX leaves out sizes known to fail on a device (on the OnePlus 6T back
 * camera its ExcludedSupportedSizesQuirk drops the 4000x3000 and 4160x3120 JPEGs, leaving 3264x2448), and a larger size of another shape would
 * be a crop. The sensor's shape is snapped to the nearest standard ratio first ([standardShape]), because active arrays are often a little off
 * the ratio of the sizes they output (4208x3120 is 1.349 but outputs 4:3).
 * Analysis keeps OpenScan's 720 px short edge as a bound: 960x720 (or the next smaller 4:3 size) instead of 1280x720, and detection, which
 * downsamples to a 320 px long edge anyway, runs at 320x240 instead of 320x180.
 */
object CameraSizes {
    /** Analysis bound: OpenScan's ResolutionPreset.high (1280x720), long edge by short edge. */
    val ANALYSIS_BOUND = Dim(1280, 720)

    /** Preview bound: 1080p, the largest PREVIEW-class stream every camera guarantees alongside a YUV stream and a maximum-size JPEG. */
    val PREVIEW_BOUND = Dim(1920, 1080)

    /** Two shapes count as the same within this tolerance on the long/short ratio (sizes are rounded to even or 16-px steps). */
    private const val ASPECT_TOLERANCE = 0.01

    /** How far a sensor's active array may be from a standard ratio and still be snapped to it. */
    private const val SNAP_TOLERANCE = 0.02

    /** The ratios camera outputs come in, long edge by short edge. */
    val STANDARD_SHAPES = listOf(Dim(4, 3), Dim(16, 9), Dim(1, 1), Dim(3, 2))

    /** [sensor] snapped to the nearest of [STANDARD_SHAPES] when within 2% of it (keeping its orientation), otherwise unchanged. */
    fun standardShape(sensor: Dim): Dim {
        val nearest = STANDARD_SHAPES.minByOrNull { abs(it.aspect - sensor.aspect) } ?: return sensor
        if (abs(nearest.aspect - sensor.aspect) > SNAP_TOLERANCE * nearest.aspect) return sensor
        return if (sensor.width >= sensor.height) Dim(nearest.longEdge, nearest.shortEdge) else Dim(nearest.shortEdge, nearest.longEdge)
    }

    fun sameShape(a: Dim, b: Dim): Boolean = abs(a.aspect - b.aspect) <= ASPECT_TOLERANCE * b.aspect

    /**
     * [sizes] ordered for the still: sizes of [shape] largest first, then every other size largest first (only reached if no size of the
     * sensor's shape can be configured). With [shape] unknown, simply largest first.
     */
    fun stillPreference(sizes: List<Dim>, shape: Dim?): List<Dim> {
        val (sameShape, others) = sizes.partition { shape != null && sameShape(it, shape) }
        return sameShape.sortedByDescending { it.area } + others.sortedByDescending { it.area }
    }

    /**
     * [sizes] ordered for a stream that must match [shape] and stay within [bound] (compared long edge to long edge, short to short): same-shape
     * sizes within the bound, largest first; then same-shape sizes over the bound, smallest first; then every other size in its original order,
     * as a last resort that CameraX only reaches if nothing of the right shape can be configured.
     */
    fun streamPreference(sizes: List<Dim>, shape: Dim, bound: Dim): List<Dim> {
        val (sameShape, others) = sizes.partition { sameShape(it, shape) }
        val (within, over) = sameShape.partition { it.longEdge <= bound.longEdge && it.shortEdge <= bound.shortEdge }
        return within.sortedByDescending { it.area } + over.sortedBy { it.area } + others
    }
}
