package com.paperknifeplus.app.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.util.Log
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSObject
import com.tom_roush.pdfbox.cos.COSStream
import com.tom_roush.pdfbox.cos.COSString
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDTerminalField
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDTextField
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.text.Normalizer
import java.util.IdentityHashMap
import java.util.zip.ZipInputStream
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sqrt

private const val TAG = "Redaction"

/** The shortest phrase Redact searches for: a shorter one (a page number, a year suffix) would match far too much. */
internal const val MIN_REDACT_PHRASE = 4

/** Resolution of a redacted page's image. */
private const val REDACT_DPI = 300f

// The page bitmap is ARGB, 4 bytes a pixel: 16 MP is 64 MB, a little under A3 at 300 DPI. Larger pages render at a lower resolution.
private const val MAX_REDACT_PIXELS = 16_000_000f

private const val PHOTO_JPEG_QUALITY = 0.9f

/** A page whose samples show more distinct colours than this (at 5 bits a channel), and little paper, is a photo. */
private const val PHOTO_COLOURS = 4096

/** At most this much of an attachment, or of all members of a zipped one, is read to look for a phrase. */
private const val ATTACHMENT_TEXT_LIMIT = 64 * 1024 * 1024

private val THUMB = COSName.getPDFName("Thumb")
private val EMBEDDED_FILE = COSName.getPDFName("EmbeddedFile")
private val RICH_TEXT = COSName.getPDFName("RC")
private val SUBJECT = COSName.getPDFName("Subj")
private val FILE_ATTACHMENT = COSName.getPDFName("FileAttachment")
private val POPUP = COSName.getPDFName("Popup")
private val FILE_NAME_KEYS = listOf(COSName.F, COSName.UF, COSName.DESC) + listOf("DOS", "Mac", "Unix").map(COSName::getPDFName)

/** A PDF string token: a literal (one level of nested parentheses) or a hex string. */
private const val PDF_STRING = """\((?:\\.|[^\\()]|\((?:\\.|[^\\()])*\))*\)|<[0-9A-Fa-f\s]*>"""

/** Marked-content properties that carry text a reader or a screen reader shows. */
private val MARKED_TEXT = Regex("""(/(?:ActualText|Alt|E|T)\s*)($PDF_STRING)""", RegexOption.DOT_MATCHES_ALL)

private val ANY_STRING = Regex(PDF_STRING, RegexOption.DOT_MATCHES_ALL)

private val WHITESPACE = Regex("""\s+""")

/** How a redaction box is painted on the page image. */
enum class RedactFill(internal val color: Int) {
    BLACK(Color.BLACK),
    WHITE(Color.WHITE)
}

/** Where [redact] removed a phrase outside the redacted pages, counted per object changed or removed. */
internal data class ScrubCounts(
    val info: Int = 0,
    val xmp: Int = 0,
    val outline: Int = 0,
    val annotations: Int = 0,
    val fields: Int = 0,
    val attachments: Int = 0,
    val markedContent: Int = 0,
    val strings: Int = 0
) {
    val total: Int get() = info + xmp + outline + annotations + fields + attachments + markedContent + strings
}

/** What [redact] did: the indices of the pages it turned into images, and where it scrubbed the redacted text. */
internal data class RedactReport(val pages: List<Int>, val scrubbed: ScrubCounts)

/**
 * Redacts [document] in place. Each page with [boxes] (rectangles in the page's default user space, by page index) or with a match of
 * [phrase] (case-insensitive, at least [MIN_REDACT_PHRASE] characters, else IllegalArgumentException) is rendered by [rasterizer] at 300
 * DPI with the boxes burnt in with [fill], and its content replaced by that single image: no text, vector or original image survives. The
 * image is placed as Grayscale places its fallback pages ([uprightPlacement]), so the page keeps its /Rotate. Its thumbnail goes, and
 * so do annotations and form fields a box touches. The phrase, and every line of at least [MIN_REDACT_PHRASE] characters that a drawn box
 * covered (as whole words), are then scrubbed from the rest of the file ([SecretScrubber]). Other pages are not re-rendered. Blocking.
 */
internal fun redact(
    document: PDDocument,
    boxes: Map<Int, List<PDRectangle>>,
    phrase: String?,
    fill: RedactFill,
    rasterizer: PageRasterizer
): RedactReport {
    val needle = phrase?.trim()?.takeIf { it.isNotEmpty() }
    require(needle == null || needle.length >= MIN_REDACT_PHRASE) { "A phrase to redact needs at least $MIN_REDACT_PHRASE characters" }
    // Phrase -> whether only whole words match; a phrase searched for matches anywhere, text taken from under a box only as whole words.
    val secrets = linkedMapOf<String, Boolean>()
    needle?.let { secrets[it] = false }
    val changed = mutableListOf<Int>()
    for (index in 0 until document.numberOfPages) {
        val drawn = boxes[index].orEmpty()
        if (drawn.isEmpty() && needle == null) continue
        val page = document.getPage(index)
        // Both reading orders, so a phrase split across text chunks is found whichever order the content stream draws them in.
        val texts = listOf(PageText.read(document, index, sorted = true), PageText.read(document, index, sorted = false))
        val found = if (needle == null) emptyList() else texts.flatMap { it.find(needle, page.cropBox) }
        if (drawn.isEmpty() && found.isEmpty()) continue
        texts.first().linesIn(drawn, page.cropBox).filter { it.length >= MIN_REDACT_PHRASE }.forEach { secrets.putIfAbsent(it, true) }
        burn(document, rasterizer, index, page, drawn + found, fill)
        changed += index
    }
    val scrubbed = if (secrets.isEmpty()) ScrubCounts() else SecretScrubber(document, secrets).scrub()
    return RedactReport(changed, scrubbed)
}

/**
 * Writes [input], opened with [password], to [output] redacted as [redact] does, pages rendered by the platform renderer unless [rasterizer]
 * gives another. The copy is as protected as the source with [keepProtection] ([sameProtection]), else not encrypted; it is buffered in
 * temp files. Returns the report; throws on failure, including IllegalArgumentException for a phrase that is too short.
 */
internal suspend fun redactPdf(
    context: Context,
    input: Uri,
    output: Uri,
    password: String?,
    boxes: Map<Int, List<PDRectangle>>,
    phrase: String?,
    fill: RedactFill,
    keepProtection: Boolean = false,
    rasterizer: (PDDocument) -> PageRasterizer = { NativePageRasterizer(context, input, if (it.isEncrypted) password.orEmpty() else null) }
): RedactReport = withContext(Dispatchers.IO) {
    val memory = MemoryUsageSetting.setupTempFileOnly().setTempDir(context.cacheDir)
    context.contentResolver.requireInputStream(input).use { stream ->
        PDDocument.load(stream, password.orEmpty(), memory).use { document ->
            val protection = if (keepProtection) document.sameProtection(password.orEmpty()) else null
            if (protection == null && document.isEncrypted) document.isAllSecurityToBeRemoved = true
            val pages = rasterizer(document)
            val report = try {
                redact(document, boxes, phrase, fill, pages)
            } finally {
                (pages as? Closeable)?.close()
            }
            protection?.let(document::protect)
            saveAndFlush(context, document, output)
            report
        }
    }
}

/** The text of one page as characters, each with the glyph that drew it (null for the word and line breaks the stripper inserts). */
private class PageText(private val chars: String, private val owners: List<TextPosition?>) {
    /** The glyph boxes of every case-insensitive match of [phrase], whitespace runs matching any whitespace. */
    fun find(phrase: String, crop: PDRectangle): List<PDRectangle> {
        val (text, glyphs) = normalized()
        // Folded the way the page text is (compatibility forms, lower case one character at a time), so the two compare.
        val needle = Normalizer.normalize(phrase, Normalizer.Form.NFKC).map { Character.toLowerCase(it) }.joinToString("")
            .replace(WHITESPACE, " ").trim()
        // An empty needle would match at every index without end.
        if (needle.isEmpty()) return emptyList()
        val boxes = mutableListOf<PDRectangle>()
        var from = text.indexOf(needle)
        while (from >= 0) {
            (from until from + needle.length).mapNotNullTo(boxes) { glyphs[it]?.let { glyph -> glyphBox(glyph, crop) } }
            from = text.indexOf(needle, from + 1)
        }
        return boxes
    }

    /** The lines of text whose glyphs lie inside [boxes] (centre inside), trimmed; glyphs outside break the line into words. */
    fun linesIn(boxes: List<PDRectangle>, crop: PDRectangle): List<String> {
        if (boxes.isEmpty()) return emptyList()
        val lines = mutableListOf<String>()
        val line = StringBuilder()
        chars.forEachIndexed { i, c ->
            val glyph = owners[i]
            when {
                c == '\n' -> lines += line.toString().also { line.clear() }
                glyph == null -> line.append(' ')
                else -> {
                    val box = glyphBox(glyph, crop)
                    val x = (box.lowerLeftX + box.upperRightX) / 2
                    val y = (box.lowerLeftY + box.upperRightY) / 2
                    line.append(if (boxes.any { x in it.lowerLeftX..it.upperRightX && y in it.lowerLeftY..it.upperRightY }) c else ' ')
                }
            }
        }
        lines += line.toString()
        return lines.map { it.replace(WHITESPACE, " ").trim() }.filter { it.isNotEmpty() }
    }

    /** The text lower-cased with whitespace runs collapsed to one space, and the glyph of each of its characters. */
    private fun normalized(): Pair<String, List<TextPosition?>> {
        val text = StringBuilder()
        val glyphs = mutableListOf<TextPosition?>()
        chars.forEachIndexed { i, c ->
            if (Character.isWhitespace(c) || Character.isSpaceChar(c)) {
                if (text.isNotEmpty() && text.last() != ' ') {
                    text.append(' ')
                    glyphs += null
                }
            } else {
                text.append(Character.toLowerCase(c))
                glyphs += owners[i]
            }
        }
        return text.toString() to glyphs
    }

    companion object {
        /** The text of page [index] of [document], in visual order when [sorted], else in content stream order. Blocking. */
        fun read(document: PDDocument, index: Int, sorted: Boolean): PageText {
            val collector = Collector().apply {
                startPage = index + 1
                endPage = index + 1
                sortByPosition = sorted
            }
            collector.getText(document)
            return PageText(collector.chars.toString(), collector.owners)
        }
    }

    private class Collector : PDFTextStripper() {
        val chars = StringBuilder()
        val owners = mutableListOf<TextPosition?>()

        init {
            // Text drawn twice (a fake bold) is redacted wherever it is.
            suppressDuplicateOverlappingText = false
        }

        override fun writeString(text: String, textPositions: List<TextPosition>) {
            for (position in textPositions) {
                // Compatibility forms, so a ligature glyph matches the letters it stands for.
                for (c in Normalizer.normalize(position.unicode.orEmpty(), Normalizer.Form.NFKC)) {
                    chars.append(c)
                    owners += position
                }
            }
        }

        override fun writeWordSeparator() = separator(' ')

        override fun writeLineSeparator() = separator('\n')

        private fun separator(c: Char) {
            chars.append(c)
            owners += null
        }
    }
}

/**
 * The box [glyph] covers in the page's default user space: its advance width from just below the baseline to the top of the em square,
 * through the text rendering matrix, so rotated, skewed and scaled text gets the right box. pdfbox gives that matrix relative to [crop]'s
 * lower-left corner.
 */
private fun glyphBox(glyph: TextPosition, crop: PDRectangle): PDRectangle {
    val advance = try {
        glyph.characterCodes.sumOf { glyph.font.getDisplacement(it).x.toDouble() }.toFloat()
    } catch (e: Exception) {
        // A font that cannot tell the width gets a typical one; the box errs on the large side either way.
        0f
    }
    val width = if (advance > 0f) advance else 0.6f
    val m = glyph.textMatrix
    val corners = listOf(0f to -0.3f, width to -0.3f, 0f to 1f, width to 1f).map { (x, y) ->
        (m.scaleX * x + m.shearX * y + m.translateX + crop.lowerLeftX) to (m.shearY * x + m.scaleY * y + m.translateY + crop.lowerLeftY)
    }
    val xs = corners.map { it.first }
    val ys = corners.map { it.second }
    return PDRectangle(xs.min(), ys.min(), xs.max() - xs.min(), ys.max() - ys.min())
}

private fun PDRectangle.intersects(other: PDRectangle): Boolean =
    lowerLeftX < other.upperRightX && other.lowerLeftX < upperRightX && lowerLeftY < other.upperRightY && other.lowerLeftY < upperRightY

/**
 * Replaces page [index]'s content with an image of it as displayed, from [rasterizer], with [boxes] painted in [fill]; drops its thumbnail
 * and the annotations [boxes] touch.
 */
private fun burn(document: PDDocument, rasterizer: PageRasterizer, index: Int, page: PDPage, boxes: List<PDRectangle>, fill: RedactFill) {
    val annots = page.cosObject.getDictionaryObject(COSName.ANNOTS) as? COSArray
    val covered = annots?.let { array ->
        (0 until array.size()).mapNotNull { array.getObject(it) as? COSDictionary }.filter { annotation ->
            val rect = annotation.getDictionaryObject(COSName.RECT) as? COSArray
            rect != null && rect.size() == 4 && PDRectangle(rect).let { r -> boxes.any { it.intersects(r) } }
        }
    }.orEmpty()
    removeAnnotations(document, page, covered)
    page.cosObject.removeItem(THUMB)

    val crop = page.cropBox
    val rotation = ((page.rotation % 360) + 360) % 360
    val turned = rotation == 90 || rotation == 270
    var scale = REDACT_DPI / 72f
    val pixels = crop.width * scale * crop.height * scale
    if (pixels > MAX_REDACT_PIXELS) scale *= sqrt(MAX_REDACT_PIXELS / pixels)
    // Unrotated size in pixels; the bitmap is the page as displayed, so turned pages swap the two.
    val width = (crop.width * scale).roundToInt().coerceAtLeast(1)
    val height = (crop.height * scale).roundToInt().coerceAtLeast(1)
    val bitmap = if (turned) rasterizer.render(index, height, width) else rasterizer.render(index, width, height)
    val image = try {
        val paint = Paint().apply {
            color = fill.color
            style = Paint.Style.FILL
        }
        val canvas = Canvas(bitmap)
        for (box in boxes) {
            // Whole pixels outward in the unrotated image, so no partly covered pixel keeps a trace of what is under the box.
            val left = floor((box.lowerLeftX - crop.lowerLeftX) * width / crop.width)
            val top = floor((crop.upperRightY - box.upperRightY) * height / crop.height)
            val right = ceil((box.upperRightX - crop.lowerLeftX) * width / crop.width)
            val bottom = ceil((crop.upperRightY - box.lowerLeftY) * height / crop.height)
            // The same corners on the page as displayed, turned clockwise by its rotation.
            when (rotation) {
                90 -> canvas.drawRect(height - bottom, left, height - top, right, paint)
                180 -> canvas.drawRect(width - right, height - bottom, width - left, height - top, paint)
                270 -> canvas.drawRect(top, width - right, bottom, width - left, paint)
                else -> canvas.drawRect(left, top, right, bottom, paint)
            }
        }
        bitmap.setHasAlpha(false)
        encodePage(document, bitmap)
    } finally {
        bitmap.recycle()
    }
    page.resources = PDResources()
    PDPageContentStream(document, page).use {
        it.saveGraphicsState()
        it.transform(uprightPlacement(crop, rotation))
        it.drawImage(image, 0f, 0f, 1f, 1f)
        it.restoreGraphicsState()
    }
}

/** [bitmap] stored losslessly, or as JPEG when it is clearly a photo and the JPEG is smaller. */
private fun encodePage(document: PDDocument, bitmap: Bitmap): PDImageXObject {
    val lossless = LosslessFactory.createFromImage(document, bitmap)
    if (!looksLikePhoto(bitmap)) return lossless
    val jpeg = JPEGFactory.createFromImage(document, bitmap, PHOTO_JPEG_QUALITY)
    return if (jpeg.cosObject.length < lossless.cosObject.length) jpeg else lossless
}

/**
 * Whether [bitmap] is a photo rather than a document: on a 4-pixel grid, under half the samples are paper (light, all channels over 200)
 * and the samples show more than [PHOTO_COLOURS] colours. Text, line art and scanned pages are mostly paper.
 */
internal fun looksLikePhoto(bitmap: Bitmap): Boolean {
    val colours = HashSet<Int>()
    var paper = 0
    var samples = 0
    val row = IntArray(bitmap.width)
    for (y in 0 until bitmap.height step 4) {
        bitmap.getPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
        for (x in row.indices step 4) {
            val c = row[x]
            samples++
            if (Color.red(c) > 200 && Color.green(c) > 200 && Color.blue(c) > 200) paper++
            colours += (c shr 3) and 0x1F1F1F
        }
    }
    return paper * 2 < samples && colours.size > PHOTO_COLOURS
}

/**
 * Takes [annotations] off [page], with the pop-ups that belong to them, and widgets out of the form's field tree. What stays of each is an
 * empty annotation of its type, so one the structure tree still points at keeps none of its text, appearance or action.
 */
private fun removeAnnotations(document: PDDocument, page: PDPage, annotations: Collection<COSDictionary>) {
    if (annotations.isEmpty()) return
    val annots = page.cosObject.getDictionaryObject(COSName.ANNOTS) as? COSArray ?: return
    val removed = identitySetOf(annotations)
    for (i in 0 until annots.size()) {
        val annotation = annots.getObject(i) as? COSDictionary ?: continue
        val parent = annotation.getDictionaryObject(COSName.PARENT) as? COSDictionary
        if (annotation.getCOSName(COSName.SUBTYPE) == POPUP && parent != null && parent in removed) removed += annotation
    }
    for (i in annots.size() - 1 downTo 0) {
        val annotation = annots.getObject(i) as? COSDictionary
        if (annotation != null && annotation in removed) annots.remove(i)
    }
    for (annotation in removed) {
        if (annotation.getCOSName(COSName.SUBTYPE) == COSName.WIDGET) document.detachWidget(annotation)
        val subtype = annotation.getItem(COSName.SUBTYPE)
        val rect = annotation.getItem(COSName.RECT)
        annotation.clear()
        annotation.setItem(COSName.TYPE, COSName.ANNOT)
        subtype?.let { annotation.setItem(COSName.SUBTYPE, it) }
        rect?.let { annotation.setItem(COSName.RECT, it) }
    }
}

/** [value] itself, or the object it refers to. */
private fun resolve(value: COSBase?): COSBase? = if (value is COSObject) value.`object` else value

/** The decoded data of [stream], up to [ATTACHMENT_TEXT_LIMIT] bytes; empty when its filters cannot decode it (then nothing can show it). */
private fun streamBytes(stream: COSStream): ByteArray = try {
    stream.createInputStream().use { it.readUpTo(ATTACHMENT_TEXT_LIMIT) }
} catch (e: IOException) {
    Log.w(TAG, "Cannot decode a stream", e)
    ByteArray(0)
}

private fun InputStream.readUpTo(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(64 * 1024)
    while (out.size() < limit) {
        val n = read(buffer, 0, minOf(buffer.size, limit - out.size()))
        if (n < 0) break
        out.write(buffer, 0, n)
    }
    return out.toByteArray()
}

/** The text of one PDF string token as [PDF_STRING] matches it, given as Latin-1 characters: UTF-16 with a byte order mark, else Latin-1. */
internal fun pdfStringText(token: String): String {
    val bytes = if (token.startsWith("<")) {
        val hex = token.substring(1, token.length - 1).filterNot { it.isWhitespace() }.let { if (it.length % 2 == 1) it + "0" else it }
        ByteArray(hex.length / 2) { hex.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
    } else {
        val body = token.substring(1, token.length - 1)
        val out = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < body.length) {
            val c = body[i]
            if (c != '\\' || i + 1 >= body.length) {
                out.write(c.code and 0xFF)
                i++
                continue
            }
            val n = body[i + 1]
            val octal = body.substring(i + 1, minOf(i + 4, body.length)).takeWhile { it in '0'..'7' }
            when {
                n in ESCAPES -> {
                    out.write(ESCAPES.getValue(n))
                    i += 2
                }
                octal.isNotEmpty() -> {
                    out.write(octal.toInt(8) and 0xFF)
                    i += 1 + octal.length
                }
                n == '\r' || n == '\n' -> i += 2
                else -> {
                    out.write(n.code and 0xFF)
                    i += 2
                }
            }
        }
        out.toByteArray()
    }
    return if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
        String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
    } else {
        String(bytes, Charsets.ISO_8859_1)
    }
}

private val ESCAPES = mapOf('n' to 10, 'r' to 13, 't' to 9, 'b' to 8, 'f' to 12)

/** What an attachment says, as far as it can be read: its bytes as text, the members of a zip (.docx, .xlsx, .odt, .zip), a PDF's text. */
internal fun attachmentTexts(body: ByteArray): List<String> {
    val texts = mutableListOf(String(body, Charsets.UTF_8))
    // Any binary decodes to some UTF-16 text; only a marked UTF-16 file is text.
    if (body.size >= 2 && ((body[0] == 0xFF.toByte() && body[1] == 0xFE.toByte()) || (body[0] == 0xFE.toByte() && body[1] == 0xFF.toByte()))) {
        texts += String(body, Charsets.UTF_16)
    }
    if (body.size >= 2 && body[0] == 'P'.code.toByte() && body[1] == 'K'.code.toByte()) {
        try {
            ZipInputStream(body.inputStream()).use { zip ->
                var left = ATTACHMENT_TEXT_LIMIT
                while (left > 0) {
                    zip.nextEntry ?: break
                    val data = zip.readUpTo(left)
                    left -= data.size
                    texts += String(data, Charsets.UTF_8)
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Not a readable zip; its bytes were checked", e)
        }
    }
    if (body.size >= 5 && String(body, 0, 5, Charsets.ISO_8859_1) == "%PDF-") {
        try {
            PDDocument.load(body).use { inner ->
                texts += PDFTextStripper().getText(inner)
                val info = inner.documentInformation
                info.metadataKeys.mapNotNullTo(texts) { info.getCustomMetadataValue(it) }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Not a readable PDF; its bytes were checked", e)
        }
    }
    return texts
}

/**
 * Removes every redacted phrase from the places redacting the pages does not reach, a port of the PC app's `scrub_secrets`: document
 * information, XMP (any /Metadata stream that mentions it is dropped), bookmark titles, embedded files (by name, description or content),
 * annotations (by text or appearance), form fields (a text field's value is cut, other fields go), marked-content /ActualText, /Alt, /E
 * and /T in page and form content, and then every string object the saved file can reach. [secrets] maps each phrase to whether it
 * matches whole words only; matching ignores case, and a whitespace run matches any whitespace.
 */
private class SecretScrubber(private val document: PDDocument, secrets: Map<String, Boolean>) {
    private val patterns = secrets.map { (secret, wholeWords) ->
        val body = secret.trim().split(WHITESPACE).joinToString("""\s+""") { Regex.escape(it) }
        // Explicit classes, as \w differs between the JVM and Android's ICU regex.
        Regex(if (wholeWords) """(?<![\p{L}\p{N}_])$body(?![\p{L}\p{N}_])""" else body, RegexOption.IGNORE_CASE)
    }
    private var info = 0
    private var xmp = 0
    private var outline = 0
    private var annotations = 0
    private var fields = 0
    private var attachments = 0
    private var markedContent = 0
    private var strings = 0

    private fun hit(text: String?): Boolean = !text.isNullOrEmpty() && patterns.any { it.containsMatchIn(text) }

    private fun strip(text: String): String = patterns.fold(text) { t, p -> p.replace(t, "") }

    /** Cuts the phrases out of [string] in place; false when it has none. */
    private fun scrub(string: COSString): Boolean {
        if (!hit(string.string)) return false
        string.setValue(COSString(strip(string.string)).bytes)
        return true
    }

    private fun streamHits(stream: COSStream): Boolean = attachmentTexts(streamBytes(stream)).any(::hit)

    /** Whether a string or text stream value (as /RC can be) mentions a phrase. */
    private fun valueHits(value: COSBase?): Boolean = when (value) {
        is COSString -> hit(value.string)
        is COSStream -> streamHits(value)
        else -> false
    }

    /**
     * Whether the string tokens of an appearance stream of [annotation] (normal, rollover or down, any state) mention a phrase. They are
     * read joined, as a TJ array splits a word into kerned pieces and each line of a field may be a string of its own.
     */
    private fun appearanceHits(annotation: COSDictionary): Boolean {
        val appearances = annotation.getDictionaryObject(COSName.AP) as? COSDictionary ?: return false
        val streams = appearances.values.flatMap { entry ->
            when (val value = resolve(entry)) {
                is COSStream -> listOf(value)
                is COSDictionary -> value.values.mapNotNull { resolve(it) as? COSStream }
                else -> emptyList()
            }
        }
        return streams.any { stream ->
            val pieces = ANY_STRING.findAll(String(streamBytes(stream), Charsets.ISO_8859_1)).map { pdfStringText(it.value) }.toList()
            hit(pieces.joinToString("")) || hit(pieces.joinToString(" "))
        }
    }

    /** Whether a file specification (a name, or a dictionary with names, a description and embedded streams) mentions a phrase. */
    private fun fileHits(spec: COSBase?): Boolean = when (spec) {
        is COSString -> hit(spec.string)
        is COSDictionary -> FILE_NAME_KEYS.any { valueHits(spec.getDictionaryObject(it)) } || embeddedStreams(spec).any(::streamHits)
        else -> false
    }

    /** The embedded file streams of the file specification [spec]. */
    private fun embeddedStreams(spec: COSDictionary): List<COSStream> =
        (spec.getDictionaryObject(COSName.EF) as? COSDictionary)?.values?.mapNotNull { resolve(it) as? COSStream }.orEmpty()

    fun scrub(): ScrubCounts {
        val catalog = document.documentCatalog
        for (key in document.documentInformation.cosObject.keySet()) {
            val value = document.documentInformation.cosObject.getDictionaryObject(key) as? COSString ?: continue
            if (scrub(value)) info++
        }
        catalog.metadata?.let {
            if (streamHits(it.cosObject)) {
                catalog.metadata = null
                xmp++
            }
        }
        scrubOutline(catalog.cosObject.getDictionaryObject(COSName.OUTLINES) as? COSDictionary)
        scrubEmbeddedFiles()
        scrubAnnotations()
        scrubFields()
        scrubObjects()
        return ScrubCounts(info, xmp, outline, annotations, fields, attachments, markedContent, strings)
    }

    private fun scrubOutline(root: COSDictionary?) {
        val seen = identitySetOf<COSDictionary>()
        val pending = ArrayDeque<COSDictionary>()
        (root?.getDictionaryObject(COSName.FIRST) as? COSDictionary)?.let(pending::add)
        while (pending.isNotEmpty()) {
            val item = pending.removeLast()
            if (!seen.add(item)) continue
            (item.getDictionaryObject(COSName.TITLE) as? COSString)?.let { if (scrub(it)) outline++ }
            (item.getDictionaryObject(COSName.FIRST) as? COSDictionary)?.let(pending::add)
            (item.getDictionaryObject(COSName.NEXT) as? COSDictionary)?.let(pending::add)
        }
    }

    /** Drops each entry of the EmbeddedFiles name tree whose name or file mentions a phrase. */
    private fun scrubEmbeddedFiles() {
        val names = document.documentCatalog.cosObject.getDictionaryObject(COSName.NAMES) as? COSDictionary ?: return
        val seen = identitySetOf<COSDictionary>()
        val pending = ArrayDeque<COSDictionary>()
        (names.getDictionaryObject(COSName.EMBEDDED_FILES) as? COSDictionary)?.let(pending::add)
        while (pending.isNotEmpty()) {
            val node = pending.removeLast()
            if (!seen.add(node)) continue
            (node.getDictionaryObject(COSName.NAMES) as? COSArray)?.let { pairs ->
                for (i in (pairs.size() / 2 - 1) downTo 0) {
                    if (valueHits(pairs.getObject(2 * i)) || fileHits(pairs.getObject(2 * i + 1))) {
                        pairs.remove(2 * i + 1)
                        pairs.remove(2 * i)
                        attachments++
                    }
                }
            }
            (node.getDictionaryObject(COSName.KIDS) as? COSArray)?.forEach { kid -> (resolve(kid) as? COSDictionary)?.let(pending::add) }
        }
    }

    /** Removes file attachments and other annotations (not widgets) whose text, file or appearance mentions a phrase. */
    private fun scrubAnnotations() {
        for (page in document.pages) {
            val annots = page.cosObject.getDictionaryObject(COSName.ANNOTS) as? COSArray ?: continue
            val doomed = mutableListOf<COSDictionary>()
            for (i in 0 until annots.size()) {
                val annotation = annots.getObject(i) as? COSDictionary ?: continue
                val subtype = annotation.getCOSName(COSName.SUBTYPE)
                if (subtype == COSName.WIDGET) continue
                val texts = listOf(COSName.CONTENTS, COSName.T, SUBJECT, RICH_TEXT).any { valueHits(annotation.getDictionaryObject(it)) }
                if (subtype == FILE_ATTACHMENT && fileHits(annotation.getDictionaryObject(COSName.getPDFName("FS")))) {
                    doomed += annotation
                    attachments++
                } else if (texts || appearanceHits(annotation)) {
                    doomed += annotation
                    annotations++
                }
            }
            removeAnnotations(document, page, doomed)
        }
    }

    /**
     * Cuts the phrases out of text field values, regenerating their appearance (or, when that fails, leaving it to the viewer), and removes
     * other fields whose value or tooltip mentions one. A widget whose appearance still shows a phrase loses it.
     */
    private fun scrubFields() {
        val form = document.documentCatalog.getAcroForm(null) ?: return
        val pageOf = IdentityHashMap<COSDictionary, PDPage>()
        for (page in document.pages) {
            (page.cosObject.getDictionaryObject(COSName.ANNOTS) as? COSArray)?.forEach { item ->
                (resolve(item) as? COSDictionary)?.let { pageOf[it] = page }
            }
        }
        for (field in form.fieldTree.filterIsInstance<PDTerminalField>().toList()) {
            val widgets = field.widgets.map { it.cosObject }
            if (field is PDTextField) {
                val value = field.value
                if (hit(value)) {
                    try {
                        field.value = strip(value)
                    } catch (e: Exception) {
                        Log.w(TAG, "Cannot redraw a redacted text field", e)
                        field.cosObject.setString(COSName.V, strip(value))
                        widgets.forEach { it.removeItem(COSName.AP) }
                        form.needAppearances = true
                    }
                    fields++
                }
            } else if (hit(field.valueAsString) || hit(field.alternateFieldName)) {
                widgets.groupBy { pageOf[it] }.forEach { (page, onPage) ->
                    if (page != null) removeAnnotations(document, page, onPage) else onPage.forEach(document::detachWidget)
                }
                fields++
                continue
            }
            val stale = widgets.filter(::appearanceHits)
            if (stale.isNotEmpty()) {
                stale.forEach { it.removeItem(COSName.AP) }
                form.needAppearances = true
                fields++
            }
        }
    }

    /**
     * Walks every object the saved file can reach from the trailer: cuts the phrases out of each string, drops a /Metadata stream or
     * empties an embedded file that mentions one, and blanks marked-content text that does in page and form content streams.
     */
    private fun scrubObjects() {
        val contents = identitySetOf<COSStream>()
        for (page in document.pages) {
            when (val value = page.cosObject.getDictionaryObject(COSName.CONTENTS)) {
                is COSStream -> contents += value
                is COSArray -> value.forEach { (resolve(it) as? COSStream)?.let(contents::add) }
                else -> {}
            }
        }
        val seen = identitySetOf<COSBase>()
        val pending = ArrayDeque<COSBase>()
        pending += document.document.trailer
        while (pending.isNotEmpty()) {
            val value = resolve(pending.removeLast()) ?: continue
            if (!seen.add(value)) continue
            when (value) {
                is COSString -> if (scrub(value)) strings++
                is COSDictionary -> {
                    if (value is COSStream) scrubStream(value, value in contents)
                    // Embedded files are found through their file specification, as their /Type is optional.
                    if (value.containsKey(COSName.EF)) embeddedStreams(value).filter(seen::add).forEach(::scrubEmbeddedFile)
                    for (key in value.keySet().toList()) {
                        val child = value.getDictionaryObject(key)
                        if (key == COSName.METADATA && child is COSStream && streamHits(child)) {
                            value.removeItem(key)
                            xmp++
                            continue
                        }
                        value.getItem(key)?.let(pending::add)
                    }
                }
                is COSArray -> value.forEach { it?.let(pending::add) }
                else -> {}
            }
        }
    }

    /** Empties [stream], an embedded file still in the document, when it mentions a phrase. */
    private fun scrubEmbeddedFile(stream: COSStream) {
        if (!streamHits(stream)) return
        stream.createOutputStream().close()
        attachments++
    }

    private fun scrubStream(stream: COSStream, pageContent: Boolean) {
        if (stream.getCOSName(COSName.TYPE) == EMBEDDED_FILE) return scrubEmbeddedFile(stream)
        if (!pageContent && stream.getCOSName(COSName.SUBTYPE) != COSName.FORM) return
        val data = String(streamBytes(stream), Charsets.ISO_8859_1)
        var blanked = 0
        val result = MARKED_TEXT.replace(data) { match ->
            if (hit(pdfStringText(match.groupValues[2]))) {
                blanked++
                match.groupValues[1] + "()"
            } else {
                match.value
            }
        }
        if (blanked == 0) return
        stream.createOutputStream(COSName.FLATE_DECODE).use { it.write(result.toByteArray(Charsets.ISO_8859_1)) }
        markedContent += blanked
    }
}
