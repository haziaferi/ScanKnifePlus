package com.paperknifeplus.app.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import com.tom_roush.pdfbox.contentstream.operator.Operator
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSFloat
import com.tom_roush.pdfbox.cos.COSInteger
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSNumber
import com.tom_roush.pdfbox.cos.COSStream
import com.tom_roush.pdfbox.cos.COSString
import com.tom_roush.pdfbox.pdfparser.PDFStreamParser
import com.tom_roush.pdfbox.pdfwriter.ContentStreamWriter
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDStream
import com.tom_roush.pdfbox.pdmodel.common.function.PDFunction
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDInlineImage
import com.tom_roush.pdfbox.util.Matrix
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Collections
import java.util.IdentityHashMap
import java.util.zip.DeflaterOutputStream
import kotlin.math.roundToInt
import kotlin.math.sqrt

private const val TAG = "GrayscaleConverter"

/** Quality of re-encoded JPEG images: high enough that the gray copy shows no new artefacts. */
private const val JPEG_QUALITY = 90

/** Resolution of a page that falls back to an image. */
private const val FALLBACK_DPI = 150f

// A fallback bitmap is ARGB, 4 bytes a pixel: 16 MP is 64 MB, about A2 at 150 DPI. Larger pages render at a lower resolution.
private const val MAX_FALLBACK_PIXELS = 16_000_000f

/** Renders one page of the source PDF, for the pages [GrayscaleConverter] cannot convert. */
internal fun interface PageRasterizer {
    /** Page [pageIndex], as displayed (rotation applied), scaled to a [width] x [height] bitmap on white. The caller recycles it. Blocking. */
    fun render(pageIndex: Int, width: Int, height: Int): Bitmap
}

/**
 * Turns [document] gray in place, so text, links, form fields, outline and metadata all stay. Colour operators in every content stream
 * (pages, form XObjects, annotation appearances, tiling patterns, Type 3 glyphs) are rewritten as DeviceGray; shadings get a sampled gray
 * function; images are re-encoded as one-channel DeviceGray, JPEGs as gray JPEGs and everything else losslessly. A page using a colour the
 * converter cannot map is replaced by an image of itself from [rasterizer], gray and lossless. Shared objects are converted once. Blocking.
 */
internal class GrayscaleConverter(private val document: PDDocument, private val rasterizer: PageRasterizer) {
    private fun identitySet() = Collections.newSetFromMap(IdentityHashMap<COSBase, Boolean>())

    private val done = identitySet()
    private val inProgress = identitySet()
    private val failed = identitySet()
    private val grayPatternSpaces = IdentityHashMap<COSDictionary, COSName>()

    /** Converts every page, reporting progress as (page, total); returns how many pages fell back to an image. */
    fun convert(onProgress: (Int, Int) -> Unit = { _, _ -> }): Int {
        var fallbacks = 0
        val total = document.numberOfPages
        document.pages.forEachIndexed { index, page ->
            onProgress(index + 1, total)
            try {
                convertPage(page)
            } catch (e: Throwable) {
                // Running out of memory on one huge image is a reason to fall back too; any other error is not.
                if (e !is Exception && e !is OutOfMemoryError) throw e
                Log.w(TAG, "Page ${index + 1} falls back to an image", e)
                rasterize(index, page)
                fallbacks++
            }
            grayGroup(page.cosObject)
            // A thumbnail is a small colour picture of the page some viewers show; they make a new one when it is missing.
            page.cosObject.removeItem(COSName.THUMB)
            page.annotations.forEach { convertAnnotation(it.cosObject) }
        }
        convertFormFields()
        convertOutline()
        return fallbacks
    }

    private fun convertPage(page: PDPage) {
        if (!page.hasContents()) return
        val tokens = rewrite(parse(page.contents.use { it.readBytes() }), page.resources)
        val stream = PDStream(document)
        stream.createOutputStream(COSName.FLATE_DECODE).use { ContentStreamWriter(it).writeTokens(tokens) }
        page.setContents(stream)
    }

    /** Converts [obj] with [block] unless it already was; a failure is remembered, so every page using the object falls back alike. */
    private inline fun once(obj: COSBase, block: () -> Unit) {
        if (obj in done || obj in inProgress) return
        if (obj in failed) throw IOException("Already found unconvertible")
        inProgress += obj
        try {
            block()
            done += obj
        } catch (e: Throwable) {
            failed += obj
            throw e
        } finally {
            inProgress -= obj
        }
    }

    private fun parse(bytes: ByteArray): List<Any> = PDFStreamParser(bytes).run {
        parse()
        tokens
    }

    /** The colour operators of [tokens] rewritten as gray, converting every resource they use on the way. */
    private fun rewrite(tokens: List<Any>, resources: PDResources?): List<Any> {
        val out = ArrayList<Any>(tokens.size)
        val operands = ArrayList<COSBase>()
        var fill: ColorModel = ColorModel.Gray
        var stroke: ColorModel = ColorModel.Gray
        val saved = ArrayDeque<Pair<ColorModel, ColorModel>>()
        for (token in tokens) {
            if (token !is Operator) {
                if (token is COSBase) operands += token
                continue
            }
            val name = token.name
            val stroking = name.first().isUpperCase()
            val replacement: List<Any>? = when (name) {
                "q" -> null.also { saved.addLast(fill to stroke) }
                "Q" -> null.also { saved.removeLastOrNull()?.let { fill = it.first; stroke = it.second } }
                "g", "G" -> null.also { if (stroking) stroke = ColorModel.Gray else fill = ColorModel.Gray }
                "rg", "RG", "k", "K" -> {
                    val model = if (name.equals("rg", ignoreCase = true)) ColorModel.Rgb else ColorModel.Cmyk
                    if (stroking) stroke = model else fill = model
                    grayOperator(model.gray(numbers(operands, model.components)), stroking)
                }
                "cs", "CS" -> {
                    val model = colorModel(operands.singleOrNull(), resources)
                    if (stroking) stroke = model else fill = model
                    when {
                        model !is ColorModel.Pattern -> grayOperator(model.gray(model.initial), stroking)
                        model.base != null -> listOf(grayPatternSpace(resources), token)
                        else -> null
                    }
                }
                "sc", "scn", "SC", "SCN" -> {
                    val model = if (stroking) stroke else fill
                    if (model is ColorModel.Pattern) {
                        val pattern = operands.lastOrNull() as? COSName ?: throw IOException("$name without a pattern name")
                        convertPattern(resource(resources, COSName.PATTERN, pattern), resources)
                        val base = model.base
                        if (base == null) null else listOf(number(base.gray(numbers(operands.dropLast(1), base.components))), pattern, token)
                    } else {
                        grayOperator(model.gray(numbers(operands, model.components)), stroking)
                    }
                }
                "sh" -> null.also { convertShading(resource(resources, COSName.SHADING, operands.firstOrNull()), resources) }
                "Do" -> null.also { (resource(resources, COSName.XOBJECT, operands.firstOrNull()) as? COSStream)?.let { convertXObject(it, resources) } }
                "BI" -> null.also { convertInlineImage(token, resources) }
                "Tf" -> null.also { convertFont(resource(resources, COSName.FONT, operands.firstOrNull()), resources) }
                "gs" -> null.also { convertExtGState(resource(resources, COSName.EXT_G_STATE, operands.firstOrNull()), resources) }
                else -> null
            }
            if (replacement == null) {
                out.addAll(operands)
                out.add(token)
            } else {
                out.addAll(replacement)
            }
            operands.clear()
        }
        out.addAll(operands)
        return out
    }

    private fun grayOperator(gray: Float, stroking: Boolean): List<Any> = listOf(number(gray), Operator.getOperator(if (stroking) "G" else "g"))

    private fun numbers(operands: List<COSBase>, count: Int): FloatArray {
        if (operands.size != count) throw IOException("Expected $count colour components, found ${operands.size}")
        return FloatArray(count) { (operands[it] as? COSNumber)?.floatValue() ?: throw IOException("A colour component is not a number") }
    }

    private fun number(value: Float): COSBase = when (val scaled = (value.coerceIn(0f, 1f) * 10000).roundToInt()) {
        0 -> COSInteger.ZERO
        10000 -> COSInteger.ONE
        else -> COSFloat(scaled / 10000f)
    }

    private fun grayArray(value: Float) = COSArray().apply { add(number(value)) }

    private fun resource(resources: PDResources?, kind: COSName, name: COSBase?): COSBase? {
        if (name !is COSName) return null
        return (resources?.cosObject?.getDictionaryObject(kind) as? COSDictionary)?.getDictionaryObject(name)
    }

    /** A [/Pattern /DeviceGray] space added to [resources], for uncoloured patterns whose base space was colour. */
    private fun grayPatternSpace(resources: PDResources?): COSName {
        val dict = resources?.cosObject ?: throw IOException("Uncoloured pattern without resources")
        return grayPatternSpaces.getOrPut(dict) {
            val spaces = dict.getDictionaryObject(COSName.COLORSPACE) as? COSDictionary ?: COSDictionary().also { dict.setItem(COSName.COLORSPACE, it) }
            var i = 0
            var name: COSName
            do name = COSName.getPDFName("GrayPattern${i++}") while (spaces.containsKey(name))
            spaces.setItem(name, COSArray().apply { add(COSName.PATTERN); add(COSName.DEVICEGRAY) })
            name
        }
    }

    private fun rewriteStream(stream: COSStream, resources: PDResources?) {
        val tokens = rewrite(parse(stream.createInputStream().use { it.readBytes() }), resources)
        stream.createOutputStream(COSName.FLATE_DECODE).use { ContentStreamWriter(it).writeTokens(tokens) }
        stream.removeItem(COSName.DECODE_PARMS)
    }

    private fun ownResources(dict: COSDictionary, fallback: PDResources?): PDResources? =
        (dict.getDictionaryObject(COSName.RESOURCES) as? COSDictionary)?.let { PDResources(it) } ?: fallback

    /** A transparency group's blending space becomes DeviceGray. */
    private fun grayGroup(dict: COSDictionary) {
        val group = dict.getDictionaryObject(COSName.GROUP) as? COSDictionary ?: return
        if (group.containsKey(COSName.CS)) group.setItem(COSName.CS, COSName.DEVICEGRAY)
    }

    private fun convertXObject(stream: COSStream, resources: PDResources?) {
        when (stream.getCOSName(COSName.SUBTYPE)) {
            COSName.IMAGE -> convertImage(stream, resources)
            COSName.FORM -> convertForm(stream, resources)
        }
    }

    private fun convertForm(stream: COSStream, parentResources: PDResources?) = once(stream) {
        rewriteStream(stream, ownResources(stream, parentResources))
        grayGroup(stream)
    }

    private fun convertFont(font: COSBase?, resources: PDResources?) {
        if (font !is COSDictionary || font.getCOSName(COSName.SUBTYPE) != COSName.TYPE3) return
        once(font) {
            val glyphResources = ownResources(font, resources)
            val procs = font.getDictionaryObject(COSName.CHAR_PROCS) as? COSDictionary ?: return@once
            for (key in procs.keySet()) {
                val proc = procs.getDictionaryObject(key) as? COSStream ?: continue
                once(proc) { rewriteStream(proc, glyphResources) }
            }
        }
    }

    private fun convertExtGState(state: COSBase?, resources: PDResources?) {
        if (state !is COSDictionary) return
        once(state) {
            val mask = state.getDictionaryObject(COSName.SMASK) as? COSDictionary ?: return@once
            val group = mask.getDictionaryObject(COSName.G) as? COSStream ?: return@once
            // The backdrop colour is given in the mask group's space, so it is read before the group turns gray.
            val backdrop = (mask.getDictionaryObject(COSName.BC) as? COSArray)?.let { bc ->
                val space = (group.getDictionaryObject(COSName.GROUP) as? COSDictionary)?.getDictionaryObject(COSName.CS) ?: return@let null
                val model = colorModel(space, ownResources(group, resources))
                model.gray(bc.toFloatArray().copyOf(model.components))
            }
            convertForm(group, resources)
            if (backdrop != null) mask.setItem(COSName.BC, grayArray(backdrop))
        }
    }

    private fun convertPattern(pattern: COSBase?, resources: PDResources?) {
        when (pattern) {
            is COSStream -> once(pattern) { rewriteStream(pattern, ownResources(pattern, resources)) }
            is COSDictionary -> once(pattern) { convertShading(pattern.getDictionaryObject(COSName.SHADING), resources) }
        }
    }

    private fun convertShading(shading: COSBase?, resources: PDResources?) {
        if (shading !is COSDictionary) return
        once(shading) {
            val model = colorModel(shading.getDictionaryObject(COSName.COLORSPACE), resources)
            if (model !is ColorModel.Gray) {
                // Mesh shadings without a function carry their colours in packed vertex data, which is not rewritten.
                val function = shading.getDictionaryObject(COSName.FUNCTION) ?: throw IOException("Shading colours outside a function")
                val sampled = sampledGrayFunction(function, model)
                val background = (shading.getDictionaryObject(COSName.BACKGROUND) as? COSArray)?.let { model.gray(it.toFloatArray().copyOf(model.components)) }
                shading.setItem(COSName.FUNCTION, sampled)
                if (background != null) shading.setItem(COSName.BACKGROUND, grayArray(background))
            }
            shading.setItem(COSName.COLORSPACE, COSName.DEVICEGRAY)
        }
    }

    /** A Type 0 function sampling the gray of [function]'s colours (in [model]) over its domain: 256 samples, or 64 x 64 for two inputs. */
    private fun sampledGrayFunction(function: COSBase, model: ColorModel): COSStream {
        val functions = if (function is COSArray) List(function.size()) { PDFunction.create(function.getObject(it)) } else listOf(PDFunction.create(function))
        val domain = (functions[0].cosObject.getDictionaryObject(COSName.DOMAIN) as? COSArray)?.toFloatArray() ?: throw IOException("Function without a domain")
        val inputs = domain.size / 2
        if (inputs !in 1..2) throw IOException("Shading function with $inputs inputs")
        val size = if (inputs == 1) 256 else 64

        fun gray(input: FloatArray): Float {
            val colour = if (functions.size == 1) functions[0].eval(input) else FloatArray(functions.size) { functions[it].eval(input)[0] }
            if (colour.size < model.components) throw IOException("Shading function gives ${colour.size} values")
            return model.gray(colour)
        }

        fun at(axis: Int, step: Int) = domain[2 * axis] + (domain[2 * axis + 1] - domain[2 * axis]) * step / (size - 1)
        val stream = document.document.createCOSStream()
        stream.createOutputStream(COSName.FLATE_DECODE).use { out ->
            for (j in 0 until if (inputs == 2) size else 1) for (i in 0 until size) {
                val input = if (inputs == 1) floatArrayOf(at(0, i)) else floatArrayOf(at(0, i), at(1, j))
                val sample = (gray(input) * 65535).roundToInt()
                out.write(sample shr 8)
                out.write(sample and 0xFF)
            }
        }
        stream.setInt(COSName.FUNCTION_TYPE, 0)
        stream.setItem(COSName.DOMAIN, COSArray().apply { domain.forEach { add(COSFloat(it)) } })
        stream.setItem(COSName.RANGE, COSArray().apply { add(COSInteger.ZERO); add(COSInteger.ONE) })
        stream.setItem(COSName.SIZE, COSArray().apply { repeat(inputs) { add(COSInteger.get(size.toLong())) } })
        stream.setInt(COSName.BITS_PER_SAMPLE, 16)
        return stream
    }

    private fun convertImage(image: COSStream, resources: PDResources?) = once(image) {
        if (image.getBoolean(COSName.IMAGE_MASK, false)) return@once
        val space = image.getDictionaryObject(COSName.COLORSPACE)
        val filters = filterNames(image.getDictionaryObject(COSName.FILTER))
        if (space == null) {
            // Only JPEG 2000 may leave the space to its own data, and Android cannot decode that to check whether it is colour.
            if (COSName.JPX_DECODE.name in filters) throw IOException("JPEG 2000 image with its own colour space")
            return@once
        }
        val model = colorModel(space, resources)
        if (model is ColorModel.Gray) {
            image.setItem(COSName.COLORSPACE, COSName.DEVICEGRAY)
            return@once
        }
        if (COSName.JPX_DECODE.name in filters) throw IOException("JPEG 2000 image in colour")
        if (image.getDictionaryObject(COSName.MASK) is COSArray) throw IOException("Colour-key masked image")
        val bitsPerComponent = image.getInt(COSName.BITS_PER_COMPONENT, 8)
        val decode = decodeArray(image.getDictionaryObject(COSName.DECODE), model, bitsPerComponent)

        val scratch = document.document.createCOSStream()
        try {
            val jpeg = filters.lastOrNull() == COSName.DCT_DECODE.name
            if (jpeg) {
                val bitmap = PDStream(image).createInputStream(listOf(COSName.DCT_DECODE.name)).use(::decodeJpeg)
                try {
                    scratch.createRawOutputStream().use { writeGrayJpeg(bitmap, model, decode, it) }
                    image.setInt(COSName.WIDTH, bitmap.width)
                    image.setInt(COSName.HEIGHT, bitmap.height)
                } finally {
                    bitmap.recycle()
                }
            } else {
                val width = image.getInt(COSName.WIDTH)
                val height = image.getInt(COSName.HEIGHT)
                PDStream(image).createInputStream().use { input ->
                    scratch.createOutputStream(COSName.FLATE_DECODE).use { grayRows(input, it, width, height, bitsPerComponent, model, decode) }
                }
            }
            scratch.createRawInputStream().use { input -> image.createRawOutputStream().use { input.copyTo(it) } }
            image.setItem(COSName.FILTER, if (jpeg) COSName.DCT_DECODE else COSName.FLATE_DECODE)
        } finally {
            scratch.close()
        }
        listOf(COSName.DECODE_PARMS, COSName.DECODE, COSName.getPDFName("Alternates")).forEach(image::removeItem)
        image.setItem(COSName.COLORSPACE, COSName.DEVICEGRAY)
        image.setInt(COSName.BITS_PER_COMPONENT, 8)
        // A soft mask's Matte is the colour the image was pre-blended with, in the image's own space.
        val mask = image.getDictionaryObject(COSName.SMASK) as? COSStream
        val matte = mask?.getDictionaryObject(COSName.MATTE) as? COSArray
        if (matte != null && matte.size() == model.components) mask.setItem(COSName.MATTE, grayArray(model.gray(matte.toFloatArray())))
    }

    private fun convertInlineImage(operator: Operator, resources: PDResources?) {
        val params = operator.imageParameters ?: return
        if (params.getBoolean(COSName.IM, COSName.IMAGE_MASK, false)) return
        val model = colorModel(params.getDictionaryObject(COSName.CS, COSName.COLORSPACE), resources)
        if (model is ColorModel.Gray) {
            params.removeItem(COSName.COLORSPACE)
            params.setItem(COSName.CS, COSName.G)
            return
        }
        val image = PDInlineImage(params, operator.imageData, resources)
        val decode = decodeArray(image.decode, model, image.bitsPerComponent)
        val data = ByteArrayOutputStream()
        val jpeg = image.filters?.lastOrNull() in DCT_NAMES
        var width = image.width
        var height = image.height
        if (jpeg) {
            val bitmap = image.createInputStream(DCT_NAMES).use(::decodeJpeg)
            try {
                writeGrayJpeg(bitmap, model, decode, data)
                width = bitmap.width
                height = bitmap.height
            } finally {
                bitmap.recycle()
            }
        } else {
            image.createInputStream().use { input -> DeflaterOutputStream(data).use { grayRows(input, it, width, height, image.bitsPerComponent, model, decode) } }
        }
        operator.imageParameters = COSDictionary().apply {
            setInt(COSName.W, width)
            setInt(COSName.H, height)
            setInt(COSName.BPC, 8)
            setItem(COSName.CS, COSName.G)
            setItem(COSName.F, if (jpeg) COSName.DCT_DECODE else COSName.FLATE_DECODE)
            params.getDictionaryObject(COSName.I, COSName.INTERPOLATE)?.let { setItem(COSName.I, it) }
        }
        operator.imageData = data.toByteArray()
    }

    private fun convertAnnotation(annotation: COSDictionary) {
        grayColour(annotation, COSName.C)
        grayColour(annotation, COSName.IC)
        (annotation.getDictionaryObject(COSName.MK) as? COSDictionary)?.let {
            grayColour(it, COSName.BC)
            grayColour(it, COSName.BG)
        }
        convertDefaultAppearance(annotation)
        val appearances = annotation.getDictionaryObject(COSName.AP) as? COSDictionary ?: return
        for (key in listOf(COSName.N, COSName.R, COSName.D)) {
            when (val entry = appearances.getDictionaryObject(key)) {
                is COSStream -> convertAppearance(entry)
                is COSDictionary -> entry.keySet().forEach { state -> (entry.getDictionaryObject(state) as? COSStream)?.let(::convertAppearance) }
            }
        }
    }

    // An appearance that cannot be converted stays as it is: dropping it would hide the annotation, which is worse than its colour.
    private fun convertAppearance(stream: COSStream) = try {
        convertForm(stream, null)
    } catch (e: Exception) {
        Log.w(TAG, "Leaving an annotation appearance in colour", e)
    }

    /** An annotation colour array (/C, /IC, /MK's /BC and /BG) with 3 or 4 components becomes one gray component. */
    private fun grayColour(dict: COSDictionary, key: COSName) {
        val colour = dict.getDictionaryObject(key) as? COSArray ?: return
        val model = when (colour.size()) {
            3 -> ColorModel.Rgb
            4 -> ColorModel.Cmyk
            else -> return
        }
        dict.setItem(key, grayArray(model.gray(colour.toFloatArray())))
    }

    /** The /DA string form fields and free-text annotations are regenerated from, so a viewer that redraws them draws gray. */
    private fun convertDefaultAppearance(dict: COSDictionary) {
        val appearance = dict.getDictionaryObject(COSName.DA) as? COSString ?: return
        try {
            val out = ByteArrayOutputStream()
            ContentStreamWriter(out).writeTokens(rewrite(parse(appearance.bytes), null))
            dict.setItem(COSName.DA, COSString(out.toString("ISO-8859-1").trim().replace('\n', ' ')))
        } catch (e: Exception) {
            Log.w(TAG, "Leaving a default appearance in colour", e)
        }
    }

    private fun convertFormFields() {
        val form = document.documentCatalog.cosObject.getDictionaryObject(COSName.ACRO_FORM) as? COSDictionary ?: return
        convertDefaultAppearance(form)
        val seen = identitySet()
        fun walk(fields: COSArray?) {
            fields ?: return
            for (i in 0 until fields.size()) {
                val field = fields.getObject(i) as? COSDictionary ?: continue
                if (!seen.add(field)) continue
                convertDefaultAppearance(field)
                walk(field.getDictionaryObject(COSName.KIDS) as? COSArray)
            }
        }
        walk(form.getDictionaryObject(COSName.FIELDS) as? COSArray)
    }

    /** Bookmark text colours (/C, always RGB) become gray. */
    private fun convertOutline() {
        val outline = document.documentCatalog.cosObject.getDictionaryObject(COSName.OUTLINES) as? COSDictionary ?: return
        val seen = identitySet()
        fun walk(first: COSBase?) {
            var item = first as? COSDictionary
            while (item != null && seen.add(item)) {
                val colour = item.getDictionaryObject(COSName.C) as? COSArray
                if (colour != null && colour.size() == 3) {
                    val gray = number(ColorModel.Rgb.gray(colour.toFloatArray()))
                    item.setItem(COSName.C, COSArray().apply { repeat(3) { add(gray) } })
                }
                walk(item.getDictionaryObject(COSName.FIRST))
                item = item.getDictionaryObject(COSName.NEXT) as? COSDictionary
            }
        }
        walk(outline.getDictionaryObject(COSName.FIRST))
    }

    /** Replaces the content of [page] with a gray, lossless image of it; its annotations stay separate and are converted on their own. */
    private fun rasterize(pageIndex: Int, page: PDPage) {
        val crop = page.cropBox
        val rotation = ((page.rotation % 360) + 360) % 360
        val turned = rotation == 90 || rotation == 270
        val shownWidth = if (turned) crop.height else crop.width
        val shownHeight = if (turned) crop.width else crop.height
        var scale = FALLBACK_DPI / 72f
        val pixels = shownWidth * scale * shownHeight * scale
        if (pixels > MAX_FALLBACK_PIXELS) scale *= sqrt(MAX_FALLBACK_PIXELS / pixels)
        val width = (shownWidth * scale).roundToInt().coerceAtLeast(1)
        val height = (shownHeight * scale).roundToInt().coerceAtLeast(1)

        val stream = document.document.createCOSStream()
        val bitmap = rasterizer.render(pageIndex, width, height)
        try {
            val row = IntArray(bitmap.width)
            stream.createOutputStream(COSName.FLATE_DECODE).use { out ->
                val grayRow = ByteArray(bitmap.width)
                for (y in 0 until bitmap.height) {
                    bitmap.getPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
                    for (x in row.indices) grayRow[x] = grayByte(luminance(Color.red(row[x]) / 255f, Color.green(row[x]) / 255f, Color.blue(row[x]) / 255f))
                    out.write(grayRow)
                }
            }
            stream.setItem(COSName.TYPE, COSName.XOBJECT)
            stream.setItem(COSName.SUBTYPE, COSName.IMAGE)
            stream.setInt(COSName.WIDTH, bitmap.width)
            stream.setInt(COSName.HEIGHT, bitmap.height)
            stream.setInt(COSName.BITS_PER_COMPONENT, 8)
            stream.setItem(COSName.COLORSPACE, COSName.DEVICEGRAY)
        } finally {
            bitmap.recycle()
        }

        // Maps the upright image onto the crop box so that the page's /Rotate shows it upright again.
        val (x, y, w, h) = listOf(crop.lowerLeftX, crop.lowerLeftY, crop.width, crop.height)
        val placement = when (rotation) {
            90 -> Matrix(0f, h, -w, 0f, x + w, y)
            180 -> Matrix(-w, 0f, 0f, -h, x + w, y + h)
            270 -> Matrix(0f, -h, w, 0f, x, y + h)
            else -> Matrix(w, 0f, 0f, h, x, y)
        }
        page.resources = PDResources()
        PDPageContentStream(document, page).use {
            it.saveGraphicsState()
            it.transform(placement)
            it.drawImage(PDImageXObject(PDStream(stream), null), 0f, 0f, 1f, 1f)
            it.restoreGraphicsState()
        }
    }

    private companion object {
        val DCT_NAMES = listOf(COSName.DCT_DECODE.name, "DCT")

        fun filterNames(filter: COSBase?): List<String> = when (filter) {
            is COSName -> listOf(filter.name)
            is COSArray -> List(filter.size()) { (filter.getObject(it) as? COSName)?.name.orEmpty() }
            else -> emptyList()
        }

        fun decodeArray(decode: COSBase?, model: ColorModel, bitsPerComponent: Int): FloatArray =
            (decode as? COSArray)?.toFloatArray()?.takeIf { it.size == model.components * 2 } ?: model.defaultDecode(bitsPerComponent)

        fun grayByte(gray: Float): Byte = (gray.coerceIn(0f, 1f) * 255 + 0.5f).toInt().toByte()

        fun decodeJpeg(input: InputStream): Bitmap =
            BitmapFactory.decodeStream(input, null, BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 })
                ?: throw IOException("Cannot decode a JPEG image")

        /** Writes [bitmap], a decoded JPEG of [model] with 1 or 3 components, as a gray JPEG; [decode] maps its 0-255 samples. */
        fun writeGrayJpeg(bitmap: Bitmap, model: ColorModel, decode: FloatArray, out: OutputStream) {
            val n = model.components
            // The platform decoder turns the samples into RGB as if they were YCbCr or gray; that is only right for these spaces.
            if (n != 1 && n != 3 || model is ColorModel.Lab) throw IOException("JPEG image with $n components in ${model::class.simpleName}")
            val pixels = IntArray(bitmap.width)
            val colour = FloatArray(n)
            GrayJpegEncoder.encode(bitmap.width, bitmap.height, JPEG_QUALITY, out) { y, row ->
                bitmap.getPixels(pixels, 0, bitmap.width, 0, y, bitmap.width, 1)
                for (x in row.indices) {
                    val p = pixels[x]
                    for (k in 0 until n) {
                        val sample = when (k) { 0 -> Color.red(p); 1 -> Color.green(p); else -> Color.blue(p) }
                        colour[k] = decode[2 * k] + sample * (decode[2 * k + 1] - decode[2 * k]) / 255f
                    }
                    row[x] = grayByte(model.gray(colour))
                }
            }
        }

        /**
         * Reads [height] rows of [width] samples of [model] at [bitsPerComponent] from [input] and writes them to [out] as 8-bit gray, one
         * row at a time; a short stream is padded with zeros, as viewers do.
         */
        fun grayRows(input: InputStream, out: OutputStream, width: Int, height: Int, bitsPerComponent: Int, model: ColorModel, decode: FloatArray) {
            if (bitsPerComponent !in setOf(1, 2, 4, 8, 16)) throw IOException("Unsupported $bitsPerComponent bits per component")
            val n = model.components
            val pixelBits = n * bitsPerComponent
            val maxValue = ((1 shl bitsPerComponent) - 1).toFloat()
            val colour = FloatArray(n)
            fun gray(sample: (Int) -> Int): Byte {
                for (k in 0 until n) colour[k] = decode[2 * k] + sample(k) * (decode[2 * k + 1] - decode[2 * k]) / maxValue
                return grayByte(model.gray(colour))
            }
            val mask = (1 shl bitsPerComponent) - 1
            // Pixels of up to 16 bits go through a table, so tint transforms and palettes are evaluated once per value.
            val table = if (pixelBits <= 16) ByteArray(1 shl pixelBits) { key -> gray { k -> (key shr ((n - 1 - k) * bitsPerComponent)) and mask } } else null

            val row = ByteArray((width * pixelBits + 7) / 8)
            val grayRow = ByteArray(width)
            repeat(height) {
                var read = 0
                while (read < row.size) {
                    val count = input.read(row, read, row.size - read)
                    if (count < 0) break
                    read += count
                }
                row.fill(0, read, row.size)
                for (x in 0 until width) {
                    grayRow[x] = if (table != null) {
                        table[readBits(row, x * pixelBits, pixelBits)]
                    } else {
                        gray { k -> readBits(row, (x * n + k) * bitsPerComponent, bitsPerComponent) }
                    }
                }
                out.write(grayRow)
            }
        }

        /** [count] bits (at most 16) of [row] from bit [offset], most significant first. */
        fun readBits(row: ByteArray, offset: Int, count: Int): Int {
            if (offset and 7 == 0) {
                if (count == 8) return row[offset ushr 3].toInt() and 0xFF
                if (count == 16) return ((row[offset ushr 3].toInt() and 0xFF) shl 8) or (row[(offset ushr 3) + 1].toInt() and 0xFF)
            }
            var value = 0
            for (i in 0 until count) {
                val bit = offset + i
                value = (value shl 1) or ((row[bit ushr 3].toInt() shr (7 - (bit and 7))) and 1)
            }
            return value
        }
    }
}

/**
 * [PageRasterizer] on the platform renderer, which unlike pdfbox-android draws every colour space and JPEG 2000 correctly, opened on first
 * use. It cannot open encrypted files, so when [password] is set the source is decrypted to a cache copy first, deleted on [close].
 */
internal class NativePageRasterizer(private val context: Context, private val uri: Uri, private val password: String?) : PageRasterizer, Closeable {
    private var renderer: PdfRenderer? = null
    private var copy: File? = null

    override fun render(pageIndex: Int, width: Int, height: Int): Bitmap {
        val opened = renderer ?: open().also { renderer = it }
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE)
        opened.openPage(pageIndex).use { it.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY) }
        return bitmap
    }

    private fun open(): PdfRenderer {
        val descriptor = if (password != null) {
            val file = context.contentResolver.requireInputStream(uri).use { input ->
                PDDocument.load(input, password).use { document ->
                    document.isAllSecurityToBeRemoved = true
                    saveDecryptedCopy(document, context.cacheDir)
                }
            }
            copy = file
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        } else {
            context.contentResolver.openFileDescriptor(uri, "r") ?: throw IOException("Cannot read $uri")
        }
        // PdfRenderer closes the descriptor itself, but not when its constructor throws.
        return try {
            PdfRenderer(descriptor)
        } catch (e: Exception) {
            descriptor.close()
            throw e
        }
    }

    override fun close() {
        renderer?.close()
        copy?.delete()
    }
}
