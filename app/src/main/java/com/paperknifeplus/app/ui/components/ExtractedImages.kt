package com.paperknifeplus.app.ui.components

import android.graphics.Bitmap
import android.util.Log
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSNumber
import com.tom_roush.pdfbox.cos.COSStream
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

private const val TAG = "ExtractedImages"

/** What Extract Images can tell about an image XObject without decoding it. */
internal data class ImageTraits(
    /** Its /Filter chain, first to last, e.g. [FlateDecode, DCTDecode]. */
    val filters: List<String>,
    /** DeviceGray or DeviceRGB, also for an ICC-based space with 1 or 3 components; otherwise the colour space's name, or null. */
    val colorSpace: String?,
    /** A /Decode array other than the identity, which would invert or remap the stored samples. */
    val remappingDecode: Boolean,
    /** An /SMask or /Mask, i.e. transparency that lives outside the image data. */
    val masked: Boolean,
    /** An /ImageMask stencil. */
    val stencil: Boolean
)

/** How one image is written into the ZIP. */
internal enum class ImageOutput(val extension: String) {
    /** The stored JPEG data, copied as is. */
    RAW_JPEG("jpg"),

    /** The stored JPEG 2000 data, copied as is. */
    RAW_JPX("jp2"),

    /** Decoded and written losslessly. */
    PNG("png"),

    /** Decoded and re-encoded as JPEG at quality 90. */
    JPEG("jpg"),

    /** Not written: it cannot be decoded and its stored data is no image file. */
    SKIP("")
}

/**
 * The output that needs no decoding, if any. A JPEG whose stored data already shows the image as the PDF does (gray or RGB, no remapping,
 * no mask) is copied losslessly; JPEG 2000 is always copied, as Android cannot decode it.
 */
internal fun rawOutput(traits: ImageTraits): ImageOutput? = when (traits.filters.lastOrNull()) {
    COSName.DCT_DECODE.name -> ImageOutput.RAW_JPEG.takeIf {
        traits.colorSpace in setOf(COSName.DEVICEGRAY.name, COSName.DEVICERGB.name) && !traits.remappingDecode && !traits.masked && !traits.stencil
    }
    COSName.JPX_DECODE.name -> ImageOutput.RAW_JPX
    else -> null
}

/**
 * The output for an image that is not copied raw, given whether it [decoded]. Transparent, stencil and losslessly stored images become PNG;
 * only JPEG data that could not be copied (e.g. CMYK) is re-encoded as JPEG.
 */
internal fun decodedOutput(traits: ImageTraits, decoded: Boolean): ImageOutput = when {
    !decoded -> ImageOutput.SKIP
    traits.masked || traits.stencil -> ImageOutput.PNG
    traits.filters.lastOrNull() == COSName.DCT_DECODE.name -> ImageOutput.JPEG
    else -> ImageOutput.PNG
}

internal fun imageTraits(image: PDImageXObject): ImageTraits {
    val dict = image.cosObject
    val filters = when (val filter = dict.getDictionaryObject(COSName.FILTER)) {
        is COSName -> listOf(filter.name)
        is COSArray -> filter.mapNotNull { (it as? COSName)?.name }
        else -> emptyList()
    }
    val decode = dict.getDictionaryObject(COSName.DECODE) as? COSArray
    val remapping = decode != null && decode.toFloatArray().withIndex().any { (i, v) -> v != (if (i % 2 == 0) 0f else 1f) }
    val masked = dict.containsKey(COSName.SMASK) || dict.containsKey(COSName.MASK)
    return ImageTraits(filters, colorSpaceName(dict.getDictionaryObject(COSName.COLORSPACE)), remapping, masked, image.isStencil)
}

/** The family of a colour space entry, read from the COS objects so an ICC profile is never parsed. */
private fun colorSpaceName(space: COSBase?): String? = when (space) {
    is COSName -> space.name
    is COSArray -> {
        val family = space.getObject(0) as? COSName
        val components = ((space.getObject(1) as? COSStream)?.getDictionaryObject(COSName.N) as? COSNumber)?.intValue()
        when {
            family == COSName.ICCBASED && components == 1 -> COSName.DEVICEGRAY.name
            family == COSName.ICCBASED && components == 3 -> COSName.DEVICERGB.name
            else -> family?.name
        }
    }
    else -> null
}

/** Images written, images left out because they could not be decoded, and images [writeImages] filtered out as too small. */
internal data class ExtractCounts(val written: Int, val skipped: Int, val filtered: Int = 0) {
    /** The result line: what was saved and, when some images could not be decoded or were too small, how many were left out. */
    val summary: String
        get() {
            val saved = if (skipped == 0) {
                "Found $written assets in document"
            } else {
                "Saved $written of ${written + skipped} images; ${images(skipped)} could not be read"
            }
            return if (filtered == 0) saved else "$saved; ${images(filtered)} below the minimum size left out"
        }

    /** Why the ZIP is empty, or null when it is not: the PDF has no images, none of them could be decoded, or all were too small. */
    val emptyReason: String?
        get() = when {
            written > 0 -> null
            skipped > 0 -> "The PDF has ${images(skipped)}, but none could be read"
            filtered > 0 -> "All ${images(filtered)} of the PDF are below the minimum size"
            else -> "No images found in PDF"
        }

    private fun images(count: Int) = if (count == 1) "1 image" else "$count images"
}

/**
 * Writes every image of [document] (each XObject once, also from inside form XObjects) into [zip] as `asset_<n>_p<page>.<ext>`, in the
 * format [rawOutput] or [decodedOutput] picks. An image narrower or shorter than [minSize] pixels is left out and counted as filtered; 0
 * keeps every image. [onWritten] gets the running count. Blocking.
 */
internal fun writeImages(document: PDDocument, zip: ZipOutputStream, minSize: Int = 0, onWritten: (Int) -> Unit = {}): ExtractCounts {
    var written = 0
    var skipped = 0
    var filtered = 0
    val seen = mutableSetOf<String>()

    fun writeEntry(pageIdx: Int, output: ImageOutput, write: (ZipOutputStream) -> Unit) {
        written++
        zip.putNextEntry(ZipEntry("asset_${written}_p${pageIdx + 1}.${output.extension}"))
        write(zip)
        zip.closeEntry()
        onWritten(written)
    }

    fun extract(image: PDImageXObject, pageIdx: Int) {
        // The size comes from the image dictionary, so a filtered image is never decoded.
        if (image.width < minSize || image.height < minSize) {
            filtered++
            return
        }
        val traits = imageTraits(image)
        val raw = rawOutput(traits)
        if (raw != null) {
            val stopAt = if (raw == ImageOutput.RAW_JPEG) COSName.DCT_DECODE else COSName.JPX_DECODE
            // Decodes any filters before the JPEG one (e.g. Flate) and stops there, leaving the JPEG data itself.
            image.createInputStream(listOf(stopAt.name)).use { data -> writeEntry(pageIdx, raw) { data.copyTo(it) } }
            return
        }
        val bitmap: Bitmap? = try {
            image.image
        } catch (e: Exception) {
            Log.w(TAG, "Cannot decode an image on page ${pageIdx + 1}", e)
            null
        }
        when (val output = decodedOutput(traits, bitmap != null)) {
            ImageOutput.SKIP -> skipped++
            else -> {
                val format = if (output == ImageOutput.PNG) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
                writeEntry(pageIdx, output) { bitmap!!.compress(format, 90, it) }
                bitmap!!.recycle()
            }
        }
    }

    fun extractFrom(resources: PDResources?, pageIdx: Int) {
        if (resources == null) return
        for (name in resources.xObjectNames) {
            try {
                val xobject = resources.getXObject(name)
                if (!seen.add(xobject.cosObject.toString())) continue
                when (xobject) {
                    is PDImageXObject -> extract(xobject, pageIdx)
                    is PDFormXObject -> extractFrom(xobject.resources, pageIdx)
                }
            } catch (e: Exception) {
                // One broken XObject does not stop the others.
                Log.w(TAG, "Cannot read XObject $name on page ${pageIdx + 1}", e)
            }
        }
    }

    document.pages.forEachIndexed { pageIdx, page -> extractFrom(page.resources, pageIdx) }
    return ExtractCounts(written, skipped, filtered)
}
