package com.paperknifeplus.app.ui.components

import android.content.res.AssetManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.BackHandler
import com.paperknifeplus.app.R
import com.paperknifeplus.app.ui.theme.LocalIsDarkTheme
import com.paperknifeplus.app.ui.theme.PaperPink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutView(onBack: () -> Unit, loadNotices: suspend () -> List<NoticeSection> = assetNoticeLoader()) {
    var currentSubPage by remember { mutableStateOf("main") }
    // The arrow and system Back agree: sub-pages return to the main page, which leaves About.
    val handleBack: () -> Unit = {
        if (currentSubPage == "main") onBack() else currentSubPage = "main"
    }
    BackHandler(enabled = currentSubPage != "main", onBack = handleBack)

    Scaffold(
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = handleBack,
                    modifier = Modifier.background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f), CircleShape)
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(
                        text = when(currentSubPage) {
                            "libraries" -> "Open Source"
                            "license" -> "License"
                            else -> "About"
                        },
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = (-0.5).sp
                    )
                    Text("SECURE PDF ENGINE", fontSize = 8.sp, fontWeight = FontWeight.Black, color = PaperPink, letterSpacing = 1.sp)
                }
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when(currentSubPage) {
                "main" -> AboutMain(onNavigate = { currentSubPage = it })
                "libraries" -> LibrariesPage(loadNotices)
                "license" -> LicensePage()
            }
        }
    }
}

@Composable
fun AboutMain(onNavigate: (String) -> Unit) {
    val context = LocalContext.current
    val isDark = LocalIsDarkTheme.current
    val appName = stringResource(R.string.app_name)

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        item {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Logo(modifier = Modifier.size(64.dp), partColor = if (isDark) Color.White else Color.Black)
                Spacer(Modifier.height(16.dp))
                Text(appName, fontSize = 24.sp, fontWeight = FontWeight.Black)
                Text("NATIVE PDF WORKSHOP", fontSize = 9.sp, color = PaperPink, fontWeight = FontWeight.Black, letterSpacing = 1.5.sp)
            }
        }

        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)),
                shape = RoundedCornerShape(24.dp)
            ) {
                Column(Modifier.padding(20.dp)) {
                    Text("How it works", fontWeight = FontWeight.Black, fontSize = 14.sp)
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "$appName is built on a simple but powerful idea: your documents belong to you, and they should never have to leave your device to be modified.\n\nTraditional PDF tools often require you to upload your files to their servers. This means your private contracts, bank statements, or personal letters are being sent over the internet and processed on a computer you don't control. Even if they promise to delete the data, the risk remains.\n\nWe took a different path. We built a 'Native Engine' that brings the workshop to your phone. When you open a PDF in $appName, our app reads the file's structure directly from your storage. When you sign a document, merge files, or extract images, all the heavy lifting is done by your phone's processor. It's like having a professional printing press and a secure vault built right into your pocket.\n\nThis approach doesn't just protect your privacy; it makes the app incredibly fast and reliable. Because there's no uploading or downloading, tools work instantly. You can edit a document on a plane, in a remote area without a signal, or in a high-security environment with zero connectivity. Your data starts on your device and ends on your device—exactly where it belongs.",
                        fontSize = 13.sp, lineHeight = 20.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )

                    Spacer(Modifier.height(20.dp))
                    HorizontalDivider(color = Color.Gray.copy(alpha = 0.1f))
                    Spacer(Modifier.height(20.dp))

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Security, null, tint = PaperPink, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("PRIVACY BY DESIGN", fontWeight = FontWeight.Black, fontSize = 11.sp, color = PaperPink, letterSpacing = 1.sp)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "• 100% local processing — files never leave your device\n• No servers, no cloud, no uploads\n• No tracking, no analytics, no telemetry\n• Works completely offline\n• Your files, your processor, your device",
                        fontSize = 12.sp, lineHeight = 20.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )

                    Spacer(Modifier.height(16.dp))

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Code, null, tint = PaperPink, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("FULLY OPEN SOURCE", fontWeight = FontWeight.Black, fontSize = 11.sp, color = PaperPink, letterSpacing = 1.sp)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "• All code available on GitHub\n• Anyone can audit the source\n• No hidden tracking or data collection\n• Community-driven development\n• Security researchers can verify claims",
                        fontSize = 12.sp, lineHeight = 20.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
            }
        }

        item { LibrarySectionHeader(Icons.Filled.Favorite, "CREDITS", PaperPink, first = true) }
        item {
            LibraryRow(
                Icons.Filled.Description, "PaperKnife+", "GPL-3.0-or-later",
                "$appName is a fork of PaperKnife+ by potatameister.\nhttps://github.com/potatameister/PaperKnifePlus", PaperPink
            )
        }
        item {
            LibraryRow(
                Icons.Filled.DocumentScanner, "OpenScan", "BSD-3-Clause",
                "The document scanner is ported from OpenScan by Vijay T S and Vikram H.\nhttps://github.com/ethereal-developers/OpenScan", PaperPink
            )
        }

        item {
            AboutSection("LEGAL") {
                AboutMenuItem(Icons.Filled.Code, "Open Source Libraries", "The tech powering our engine") { onNavigate("libraries") }
                AboutMenuItem(Icons.Filled.Description, "GPL v3", "Read the legal terms") { onNavigate("license") }
            }
        }

        item {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("$appName V${appVersionName(context)}", fontSize = 8.sp, color = Color.Gray, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
            }
        }

        item { Spacer(Modifier.height(100.dp)) }
    }
}

@Composable
fun LibrariesPage(loadNotices: suspend () -> List<NoticeSection> = assetNoticeLoader()) {
    val violet = Color(0xFF8B5CF6)
    val green = Color(0xFF10B981)
    var notices by remember(loadNotices) { mutableStateOf(emptyList<NoticeSection>()) }
    LaunchedEffect(loadNotices) {
        notices = loadNotices()
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        item { LibrarySectionHeader(Icons.Filled.Settings, "CORE ENGINE", PaperPink, first = true) }
        item { LibraryRow(Icons.Filled.Description, "PDFBox-Android", "Apache 2.0", "The native heart of our engine", PaperPink) }
        item { LibraryRow(Icons.Filled.Bolt, "Kotlin Coroutines", "Apache 2.0", "Powering our multi-threaded rendering engine", PaperPink) }
        item {
            LibraryRow(
                Icons.Filled.DocumentScanner, "OpenScan", "BSD-3-Clause",
                "Document scanner, ported from OpenScan. Copyright (c) 2021, Vijay T S and Vikram H", PaperPink
            )
        }

        item { LibrarySectionHeader(Icons.Filled.Palette, "UI & DESIGN", violet) }
        item { LibraryRow(Icons.Filled.TouchApp, "Jetpack Compose", "Apache 2.0", "Modern UI toolkit for native Android", violet) }
        item { LibraryRow(Icons.Filled.Style, "Material 3", "Apache 2.0", "Modern design system implementation", violet) }

        item { LibrarySectionHeader(Icons.Filled.Image, "MEDIA", green) }
        item { LibraryRow(Icons.Filled.PhotoLibrary, "Coil", "Apache 2.0", "High-performance image loading engine", green) }
        item { LibraryRow(Icons.Filled.PhotoCamera, "CameraX", "Apache 2.0", "Camera capture for the document scanner", green) }

        item { LibrarySectionHeader(Icons.Filled.Gavel, "NOTICES", Color.Gray) }
        items(notices) { notice ->
            LibraryRow(Icons.Filled.Gavel, notice.title, "", notice.body, Color.Gray)
        }

        item { Spacer(Modifier.height(100.dp)) }
    }
}

/** One block of the bundled notices, as plain text: a NOTICE.md heading with the text under it, or one whole file from NOTICES/. */
data class NoticeSection(val title: String, val body: String)

internal const val NOTICE_ASSET = "NOTICE.md"
internal const val NOTICES_ASSET_DIR = "NOTICES"

/** The default notice source for the Libraries page: [readNotices] from this app's assets, run on the IO dispatcher. */
@Composable
fun assetNoticeLoader(): suspend () -> List<NoticeSection> {
    val assets = LocalContext.current.assets
    return remember(assets) { { withContext(Dispatchers.IO) { readNotices(assets) } } }
}

/** Reads NOTICE.md and NOTICES/ from the APK assets (copies of the repo files, kept in sync by NoticeAssetTest). Blocking; empty if unreadable. */
internal fun readNotices(assets: AssetManager): List<NoticeSection> = try {
    val notice = assets.open(NOTICE_ASSET).bufferedReader().use { it.readText() }
    val files = assets.list(NOTICES_ASSET_DIR).orEmpty().sorted().map { name ->
        NoticeSection(name, assets.open("$NOTICES_ASSET_DIR/$name").bufferedReader().use { it.readText() }.trim())
    }
    noticeSections(notice) + files
} catch (e: IOException) {
    emptyList()
}

/**
 * Splits Markdown at its headings, so each section becomes one row, and turns each line into plain text with [plainMarkdownLine].
 * Lines inside ``` code blocks (licence texts) stay verbatim, heading-like ones included.
 */
internal fun noticeSections(markdown: String): List<NoticeSection> {
    val sections = mutableListOf<NoticeSection>()
    var title = ""
    val body = StringBuilder()
    var inFence = false
    fun flush() {
        if (title.isNotEmpty() || body.isNotBlank()) sections += NoticeSection(title, body.toString().trim())
        body.clear()
    }
    for (line in markdown.lines()) {
        when {
            line.startsWith("```") -> inFence = !inFence
            inFence -> body.appendLine(line)
            line.startsWith("#") -> { flush(); title = plainMarkdownLine(line.trimStart('#').trim()) }
            else -> body.appendLine(plainMarkdownLine(line))
        }
    }
    flush()
    return sections
}

private val markdownLink = Regex("""\[([^\]]+)]\(([^)\s]+)\)""")
private val markdownStrong = Regex("""(\*\*|__)(.+?)\1""")
private val markdownEmphasis = Regex("""(?<![\w*])\*(?![\s*])(.+?)(?<![\s*])\*(?![\w*])""")

/**
 * One Markdown line as plain text: [text](url) becomes "text (url)", ** / __ / * emphasis markers go, and inline code loses its backticks
 * with its content kept as written. Single _ emphasis is left alone, since the notices use underscores in file and package names.
 */
internal fun plainMarkdownLine(line: String): String {
    fun plain(text: String) = text.replace(markdownLink, "$1 ($2)").replace(markdownStrong, "$2").replace(markdownEmphasis, "$1")
    val parts = line.split('`')
    if (parts.size % 2 == 0) return plain(line)
    return parts.mapIndexed { i, part -> if (i % 2 == 1) part else plain(part) }.joinToString("")
}

@Composable
private fun LibrarySectionHeader(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, color: Color, first: Boolean = false) {
    Column {
        if (!first) Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = color, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(6.dp))
            Text(title, fontSize = 10.sp, fontWeight = FontWeight.Black, color = color, letterSpacing = 1.2.sp)
        }
    }
}

@Composable
private fun LibraryRow(icon: androidx.compose.ui.graphics.vector.ImageVector, name: String, license: String, description: String, color: Color) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(0.3f),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
            Box(
                modifier = Modifier.size(36.dp).background(color.copy(alpha = 0.1f), RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, null, tint = color, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(name, fontWeight = FontWeight.Black, fontSize = 14.sp)
                    if (license.isNotEmpty()) {
                        Spacer(Modifier.width(8.dp))
                        Text("[$license]", fontSize = 9.sp, color = color, fontWeight = FontWeight.Bold)
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(description, fontSize = 11.sp, color = Color.Gray)
            }
        }
    }
}

@Composable
fun LicensePage() {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(24.dp)
    ) {
        item {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(0.3f),
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    """
                    GNU GENERAL PUBLIC LICENSE
                    Version 3, 29 June 2007

                    Copyright (C) 2007 Free Software Foundation, Inc.
                    https://www.gnu.org/licenses/gpl-3.0.html

                    This program is free software: you can redistribute it
                    and/or modify it under the terms of the GNU General Public
                    License as published by the Free Software Foundation,
                    either version 3 of the License
                    any later version.

                    This program is distributed in the hope that it will be
                    useful, but WITHOUT ANY WARRANTY; without even the implied
                    warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR
                    PURPOSE. See the GNU General Public License for more details.

                    You should have received a copy of the GNU General Public
                    License along with this program. If not, see
                    https://www.gnu.org/licenses/.
                    """.trimIndent(),
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    modifier = Modifier.padding(20.dp),
                    color = Color.Gray
                )
            }
        }
        item { Spacer(Modifier.height(100.dp)) }
    }
}

@Composable
fun AboutSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column {
        Text(title, fontSize = 10.sp, fontWeight = FontWeight.Black, color = Color.Gray, letterSpacing = 1.2.sp, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(0.2f),
            shape = RoundedCornerShape(24.dp),
            border = BorderStroke(1.dp, Color.Gray.copy(0.1f))
        ) {
            Column { content() }
        }
    }
}

@Composable
fun AboutMenuItem(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable { onClick() }.padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.size(36.dp).background(PaperPink.copy(0.1f), CircleShape), contentAlignment = Alignment.Center) {
            Icon(icon, null, modifier = Modifier.size(18.dp), tint = PaperPink)
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Text(subtitle, fontSize = 11.sp, color = Color.Gray)
        }
        Icon(Icons.Filled.ChevronRight, null, modifier = Modifier.size(16.dp), tint = Color.Gray.copy(0.5f))
    }
}
