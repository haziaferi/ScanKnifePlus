package com.paperknifeplus.app.ui.components

import android.content.Context
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.paperknifeplus.app.R
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDDocumentInformation
import java.io.ByteArrayOutputStream
import java.util.Calendar
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.w3c.dom.Document
import org.w3c.dom.Element

@Composable
fun MetadataView(
    initialUri: Uri? = null,
    onBack: () -> Unit,
    onOpenPreview: (Uri, String, Int) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val outputs = rememberToolOutputs("metadata")
    val accentColor = MaterialTheme.colorScheme.primary

    var currentState by remember { mutableStateOf(ToolState.SELECTING) }
    var selectedUri by remember { mutableStateOf<Uri?>(null) }
    var outputUri by remember { mutableStateOf<Uri?>(null) }
    var unlockPassword by remember { mutableStateOf("") }

    var title by remember { mutableStateOf("") }
    var author by remember { mutableStateOf("") }
    var subject by remember { mutableStateOf("") }
    var keywords by remember { mutableStateOf("") }
    var creator by remember { mutableStateOf("") }
    var producer by remember { mutableStateOf("") }
    // As read from the file, so saving changes only the fields the user edited.
    var loaded by remember { mutableStateOf(PdfProperties("", "", "", "", "", "")) }

    var fileName by remember { mutableStateOf("") }
    var fileSize by remember { mutableStateOf("") }
    var pageCount by remember { mutableIntStateOf(0) }
    var isFileLoading by remember { mutableStateOf(false) }
    var processingTime by remember { mutableStateOf("") }
    var fileToUnlock by remember { mutableStateOf<String?>(null) }
    var unlockError by remember { mutableStateOf(false) }
    val showLoadingWarning = rememberLoadingWarning(isFileLoading || currentState == ToolState.PROCESSING)

    fun backToSelection() {
        selectedUri = null
        fileToUnlock = null
        currentState = ToolState.SELECTING
    }

    /** Reads [uri]'s properties with [password] and shows them, or goes back to file selection when they cannot be read. */
    suspend fun showProperties(uri: Uri, password: String?) {
        val count = getPageCount(context, uri, password)
        val properties = loadMetadata(context, uri, password)
        if (properties == null) {
            Toast.makeText(context, toolText(context, R.string.metadata_could_not_read_file_properties), Toast.LENGTH_LONG).show()
            backToSelection()
            return
        }
        loaded = properties
        title = properties.title
        author = properties.author
        subject = properties.subject
        keywords = properties.keywords
        creator = properties.creator
        producer = properties.producer
        pageCount = count
        currentState = ToolState.CONFIGURING
        fileToUnlock = null
    }

    fun handleFileSelection(uri: Uri) {
        selectedUri = uri
        val details = getUriDetails(context, uri)
        fileName = details.name
        fileSize = details.size
        unlockError = false
        isFileLoading = true
        scope.launch {
            when (inspectPdf(context, uri)) {
                PdfAccess.Unreadable -> {
                    Toast.makeText(context, toolText(context, R.string.tool_unreadable), Toast.LENGTH_LONG).show()
                    backToSelection()
                }
                PdfAccess.Encrypted -> fileToUnlock = fileName
                PdfAccess.Open -> showProperties(uri, null)
            }
            isFileLoading = false
        }
    }

    LaunchedEffect(initialUri) {
        initialUri?.let { handleFileSelection(it) }
    }

    val pickLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { handleFileSelection(it) }
    }

    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        val source = selectedUri
        if (uri == null || source == null) return@rememberLauncherForActivityResult
        currentState = ToolState.PROCESSING
        val startTime = System.currentTimeMillis()
        val edited = PdfProperties(title, author, subject, keywords, creator, producer)
        val original = loaded
        scope.launch(Dispatchers.IO) {
            try {
                saveProperties(context, source, unlockPassword, uri, original, edited)
                val timeStr = formatElapsed(startTime)
                val outputName = getUriDetails(context, uri).name
                withContext(Dispatchers.Main) {
                    processingTime = timeStr
                    outputUri = uri
                    fileName = outputName
                    outputs.saved(uri, fileName, toolText(context, R.string.metadata_edited_properties), Icons.Filled.Fingerprint, pageCount)
                    currentState = ToolState.SUCCESS
                }
            } catch (e: CancellationException) {
                deleteCreatedDocument(context, uri)
                throw e
            } catch (e: Exception) {
                Log.w("MetadataView", "Saving properties failed", e)
                deleteCreatedDocument(context, uri)
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, toolText(context, R.string.tool_error, e.message), Toast.LENGTH_LONG).show()
                    currentState = ToolState.CONFIGURING
                }
            }
        }
    }

    Scaffold(
        topBar = {
            ToolScreenBar(currentState, stringResource(R.string.tool_metadata), stringResource(R.string.metadata_edit_document_properties), processingTime, onBack)
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (isFileLoading) {
                LoadingStateView(showLoadingWarning, stringResource(R.string.metadata_reading_file_properties))
            } else {
                when (currentState) {
                    ToolState.SELECTING -> {
                        ToolEmptyState(
                            icon = Icons.Outlined.Fingerprint,
                            title = stringResource(R.string.metadata_edit_pdf_properties),
                            task = stringResource(R.string.tool_metadata_desc),
                            onChoose = { pickLauncher.launch("application/pdf") },
                            onOpenRecent = { handleFileSelection(it) }
                        )
                    }
                    ToolState.CONFIGURING -> {
                        ToolConfigLayout(
                            action = stringResource(R.string.action_save_pdf),
                            onAction = { saveLauncher.launch(outputFileName(pdfBaseName(fileName) + "-meta", "pdf")) }
                        ) {
                            ToolFileCard(
                                uri = selectedUri!!,
                                password = unlockPassword.ifEmpty { null },
                                name = fileName,
                                details = pluralStringResource(R.plurals.tool_file_details, pageCount, fileSize, pageCount),
                                onChange = { selectedUri = null; currentState = ToolState.SELECTING },
                                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 20.dp)
                            )
                            Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
                                MetadataGroup(stringResource(R.string.metadata_core_metadata)) {
                                    MetadataEditField(stringResource(R.string.tool_field_title), title, accentColor) { title = it }
                                    MetadataEditField(stringResource(R.string.metadata_author), author, accentColor) { author = it }
                                }

                                Spacer(Modifier.height(16.dp))

                                MetadataGroup(stringResource(R.string.metadata_additional_tags)) {
                                    MetadataEditField(stringResource(R.string.metadata_subject), subject, accentColor) { subject = it }
                                    MetadataEditField(stringResource(R.string.metadata_keywords), keywords, accentColor) { keywords = it }
                                }

                                Spacer(Modifier.height(16.dp))

                                MetadataGroup(stringResource(R.string.metadata_source_engine)) {
                                    MetadataEditField(stringResource(R.string.metadata_creator), creator, accentColor) { creator = it }
                                    MetadataEditField(stringResource(R.string.metadata_producer), producer, accentColor) { producer = it }
                                }
                            }
                        }
                    }
                    ToolState.PROCESSING -> {
                        ProcessingStateView(
                            uri = selectedUri,
                            password = unlockPassword.ifEmpty { null },
                            text = stringResource(R.string.metadata_updating_document_metadata),
                            current = 0,
                            total = 0,
                            showWarning = showLoadingWarning
                        )
                    }
                    ToolState.SUCCESS -> {
                        SuccessView(
                            message = stringResource(R.string.metadata_properties_updated),
                            subMessage = stringResource(R.string.metadata_document_metadata_saved),
                            result = outputUri?.let { ToolResult(it, fileName) },
                            onDone = onBack,
                            onProcessMore = {
                                selectedUri = null
                                unlockPassword = ""
                                currentState = ToolState.SELECTING
                            },
                            onOpen = { outputUri?.let { uri -> onOpenPreview(uri, fileName, pageCount) } }
                        )
                    }
                }
            }

            if (fileToUnlock != null) {
                LockedFilePrompt(
                    fileName = fileToUnlock!!,
                    onDismiss = { fileToUnlock = null; unlockError = false; selectedUri = null; currentState = ToolState.SELECTING },
                    onUnlocked = { pass ->
                        val source = selectedUri
                        if (source != null) {
                            isFileLoading = true
                            scope.launch {
                                if (getPageCount(context, source, pass) > 0) {
                                    unlockPassword = pass
                                    showProperties(source, pass)
                                } else {
                                    unlockError = true
                                }
                                isFileLoading = false
                            }
                        }
                    },
                    isError = unlockError,
                    accentColor = accentColor,
                    isLoading = isFileLoading
                )
            }
        }
    }
}

@Composable
fun MetadataGroup(title: String, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
            .padding(vertical = 8.dp)
    ) {
        Text(
            text = title,
            fontSize = 12.sp,
            fontWeight = FontWeight.Black,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            letterSpacing = 1.2.sp,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )
        content()
    }
}

@Composable
fun MetadataEditField(label: String, value: String, accentColor: Color, onValueChange: (String) -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = accentColor.copy(0.7f), letterSpacing = 0.5.sp)
        TextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            colors = TextFieldDefaults.colors(
                unfocusedContainerColor = Color.Transparent,
                focusedContainerColor = Color.Transparent,
                focusedIndicatorColor = accentColor,
                cursorColor = accentColor
            ),
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
            singleLine = true
        )
    }
}

/** A PDF's document information fields, blank where the file has none. */
internal data class PdfProperties(
    val title: String,
    val author: String,
    val subject: String,
    val keywords: String,
    val creator: String,
    val producer: String
)

/** [uri]'s document information, opened with [password]; null when the file cannot be read, so the screen can say so. */
internal suspend fun loadMetadata(context: Context, uri: Uri, password: String?): PdfProperties? = withContext(Dispatchers.IO) {
    try {
        context.contentResolver.requireInputStream(uri).use { input ->
            PDDocument.load(input, password.orEmpty()).use { document ->
                val info = document.documentInformation
                PdfProperties(
                    info.title.orEmpty(),
                    info.author.orEmpty(),
                    info.subject.orEmpty(),
                    info.keywords.orEmpty(),
                    info.creator.orEmpty(),
                    info.producer.orEmpty()
                )
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w("MetadataView", "Cannot read the properties of $uri", e)
        null
    }
}

/** A change to one of the document dates: [Keep] leaves it as it is, [Change] sets it, a null date removing it. */
internal sealed interface DateEdit {
    data object Keep : DateEdit

    data class Change(val date: Calendar?) : DateEdit
}

/** What the Metadata tool changes besides the [PdfProperties] fields: the creation and modification dates, and removing the XMP stream. */
internal data class MetadataExtras(val created: DateEdit = DateEdit.Keep, val modified: DateEdit = DateEdit.Keep, val removeXmp: Boolean = false)

/**
 * Writes [source], opened with [password], to [output] with the fields the user changed from [original] to [edited] and the [extras], as
 * the Metadata tool saves. No default creator, producer or author is added, so cleared fields stay cleared. Blocking; throws on failure.
 */
internal fun saveProperties(
    context: Context,
    source: Uri,
    password: String,
    output: Uri,
    original: PdfProperties,
    edited: PdfProperties,
    extras: MetadataExtras = MetadataExtras()
) = rewritePdf(context, source, password, output, applyDefaults = false) { applyProperties(it, original, edited, extras) }

/**
 * Sets each field of [document]'s information that differs between [original] and [edited] to its [edited] value, a blank one removing the
 * entry, and each date [extras] changes, and makes the XMP metadata say the same: dc:title, dc:creator, dc:description, pdf:Keywords,
 * xmp:CreatorTool, pdf:Producer, xmp:CreateDate or xmp:ModifyDate is rewritten, or removed for a blank field. Unchanged fields keep their Info
 * and XMP as they are. A document without XMP gets none; XMP that cannot be parsed is left as it is; [MetadataExtras.removeXmp] drops the
 * document's XMP stream instead.
 */
internal fun applyProperties(document: PDDocument, original: PdfProperties, edited: PdfProperties, extras: MetadataExtras = MetadataExtras()) {
    val values = edited.asList()
    val changed = values.indices.filter { values[it] != original.asList()[it] }
    // Reading the information creates an empty /Info, so a save that changes nothing returns first.
    if (changed.isEmpty() && extras == MetadataExtras()) return
    val info = document.documentInformation
    changed.forEach { INFO_SETTERS[it](info, values[it].ifBlank { null }) }
    val xmpChanges = changed.map { XMP_PROPERTIES[it] to values[it] }.toMutableList()
    (extras.created as? DateEdit.Change)?.let {
        info.creationDate = it.date
        xmpChanges += XMP_CREATE_DATE to it.date?.let(::xmpDate).orEmpty()
    }
    (extras.modified as? DateEdit.Change)?.let {
        info.modificationDate = it.date
        xmpChanges += XMP_MODIFY_DATE to it.date?.let(::xmpDate).orEmpty()
    }
    if (extras.removeXmp) {
        document.documentCatalog.metadata = null
        return
    }
    if (xmpChanges.isEmpty()) return

    val metadata = document.documentCatalog.metadata ?: return
    val xmp = try {
        parseXml(metadata.exportXMPMetadata().use { it.readBytes() })
    } catch (e: Exception) {
        Log.w("MetadataView", "Leaving XMP metadata that cannot be parsed as it is", e)
        return
    }
    val rdf = xmp.getElementsByTagNameNS(RDF_NS, "RDF").item(0) as? Element ?: return
    val descriptions = rdf.childElements().filter { it.namespaceURI == RDF_NS && it.localName == "Description" }
    val target = descriptions.firstOrNull() ?: xmp.createElementNS(RDF_NS, "${rdf.prefixFor(RDF_NS, "rdf")}:Description").also {
        it.setAttributeNS(RDF_NS, "${rdf.prefixFor(RDF_NS, "rdf")}:about", "")
        rdf.appendChild(it)
    }
    for ((property, value) in xmpChanges) {
        for (description in descriptions) {
            description.removeAttributeNS(property.namespace, property.name)
            description.childElements()
                .filter { it.namespaceURI == property.namespace && it.localName == property.name }
                .forEach { description.removeChild(it) }
        }
        if (value.isNotBlank()) target.appendChild(property.element(xmp, target, value))
    }
    metadata.importXMPMetadata(serializeXml(xmp))
}

/** Setters of the document information entries for [PdfProperties]' fields, in the order of [PdfProperties.asList]. */
private val INFO_SETTERS: List<(PDDocumentInformation, String?) -> Unit> = listOf(
    { info, value -> info.title = value },
    { info, value -> info.author = value },
    { info, value -> info.subject = value },
    { info, value -> info.keywords = value },
    { info, value -> info.creator = value },
    { info, value -> info.producer = value }
)

private const val RDF_NS = "http://www.w3.org/1999/02/22-rdf-syntax-ns#"
private const val XML_NS = "http://www.w3.org/XML/1998/namespace"
private const val XMLNS_NS = "http://www.w3.org/2000/xmlns/"

/** The prefix bound to [namespace] here, or [preferred] after declaring it on this element. */
private fun Element.prefixFor(namespace: String, preferred: String): String =
    lookupPrefix(namespace) ?: preferred.also { setAttributeNS(XMLNS_NS, "xmlns:$it", namespace) }

/** An XMP property: a plain value, or one in an rdf:Alt language alternative or rdf:Seq list as [container] says. */
private class XmpProperty(val namespace: String, val prefix: String, val name: String, val container: String?) {
    /** The property element holding [value], for [description] of [xmp]. */
    fun element(xmp: Document, description: Element, value: String): Element {
        val element = xmp.createElementNS(namespace, "${description.prefixFor(namespace, prefix)}:$name")
        if (container == null) {
            element.textContent = value
        } else {
            val rdf = description.prefixFor(RDF_NS, "rdf")
            val item = xmp.createElementNS(RDF_NS, "$rdf:li").apply { textContent = value }
            if (container == "Alt") item.setAttributeNS(XML_NS, "xml:lang", "x-default")
            element.appendChild(xmp.createElementNS(RDF_NS, "$rdf:$container").apply { appendChild(item) })
        }
        return element
    }
}

/** The XMP counterparts of [PdfProperties]' fields, in the order of [PdfProperties.asList]. */
private val XMP_PROPERTIES = listOf(
    XmpProperty("http://purl.org/dc/elements/1.1/", "dc", "title", "Alt"),
    XmpProperty("http://purl.org/dc/elements/1.1/", "dc", "creator", "Seq"),
    XmpProperty("http://purl.org/dc/elements/1.1/", "dc", "description", "Alt"),
    XmpProperty("http://ns.adobe.com/pdf/1.3/", "pdf", "Keywords", null),
    XmpProperty("http://ns.adobe.com/xap/1.0/", "xmp", "CreatorTool", null),
    XmpProperty("http://ns.adobe.com/pdf/1.3/", "pdf", "Producer", null)
)

private val XMP_CREATE_DATE = XmpProperty("http://ns.adobe.com/xap/1.0/", "xmp", "CreateDate", null)
private val XMP_MODIFY_DATE = XmpProperty("http://ns.adobe.com/xap/1.0/", "xmp", "ModifyDate", null)

/** [date] as an XMP (ISO 8601) date with its time zone offset, e.g. 2026-10-14T09:30:00+02:00. */
private fun xmpDate(date: Calendar): String {
    val offset = (date.get(Calendar.ZONE_OFFSET) + date.get(Calendar.DST_OFFSET)) / 60_000
    val zone = if (offset == 0) "Z" else String.format(Locale.ROOT, "%s%02d:%02d", if (offset < 0) "-" else "+", abs(offset) / 60, abs(offset) % 60)
    return String.format(
        Locale.ROOT, "%04d-%02d-%02dT%02d:%02d:%02d%s", date.get(Calendar.YEAR), date.get(Calendar.MONTH) + 1, date.get(Calendar.DAY_OF_MONTH),
        date.get(Calendar.HOUR_OF_DAY), date.get(Calendar.MINUTE), date.get(Calendar.SECOND), zone
    )
}

private fun PdfProperties.asList() = listOf(title, author, subject, keywords, creator, producer)

private fun Element.childElements(): List<Element> = (0 until childNodes.length).mapNotNull { childNodes.item(it) as? Element }

private fun parseXml(bytes: ByteArray): Document {
    val factory = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        isExpandEntityReferences = false
    }
    // XMP never needs a DTD; refuse one where the parser supports it (Android's ignores external entities anyway).
    try {
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
    } catch (e: Exception) {
        Log.d("MetadataView", "Parser cannot refuse DTDs", e)
    }
    return factory.newDocumentBuilder().parse(bytes.inputStream())
}

private fun serializeXml(xml: Document): ByteArray = ByteArrayOutputStream().also { out ->
    TransformerFactory.newInstance().newTransformer().apply {
        setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes")
        setOutputProperty(OutputKeys.ENCODING, "UTF-8")
    }.transform(DOMSource(xml), StreamResult(out))
}.toByteArray()
