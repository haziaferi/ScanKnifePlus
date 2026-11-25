package com.paperknifeplus.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.paperknifeplus.app.R
import com.paperknifeplus.app.ui.theme.TargetStep
import java.text.Normalizer
import java.util.Locale

/** Accents and other combining marks, once a text is decomposed. */
private val COMBINING_MARKS = Regex("""\p{Mn}+""")

/** What separates words: anything but a letter or a digit (spaces, apostrophes, hyphens). */
private val NON_WORD = Regex("""[^\p{L}\p{Nd}]+""")

/** The words of [text] as search compares them: lower case, accents removed (NFD, combining marks dropped). */
internal fun searchWords(text: String): List<String> =
    COMBINING_MARKS.replace(Normalizer.normalize(text, Normalizer.Form.NFD), "").lowercase(Locale.ROOT)
        .split(NON_WORD).filter { it.isNotEmpty() }

/**
 * Whether a tool called [name], described by [description], matches [query] (decision 4): every word typed starts some word of the two,
 * ignoring case and accents. A typed word of [FORGIVING_LENGTH] letters or more need not match its last letter, so a singular finds the
 * plural ("pagina" finds "pagine", "immagine" finds "immagini"); shorter ones match in full, so "file" does not find "Filigrana". A blank
 * query matches every tool.
 */
internal fun toolMatches(query: String, name: String, description: String): Boolean {
    val words = searchWords("$name $description")
    return searchWords(query).all { typed ->
        val start = if (typed.length >= FORGIVING_LENGTH) typed.dropLast(1) else typed
        words.any { it.startsWith(start) }
    }
}

/** From this many letters a typed word may differ in its last one (user's choice, 2026-11-25). */
private const val FORGIVING_LENGTH = 5

/**
 * Every [ToolCatalog] tool that matches [query] ([toolMatches], in the app's language), grouped under its [ToolCategory]'s heading; a
 * category with no match is left out, and when nothing matches one line says so. Tapping a tool reports its id. [header] scrolls with the
 * list.
 */
@Composable
fun ToolsView(
    onToolClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    query: String = "",
    contentPadding: PaddingValues = PaddingValues(bottom = 16.dp),
    header: LazyListScope.() -> Unit = {}
) {
    val shown = ToolCatalog.tools.filter { toolMatches(query, stringResource(it.name), stringResource(it.description)) }
    val groups = shown.groupBy { it.category }
    val nothing = stringResource(R.string.tools_search_empty, query.trim())
    LazyColumn(modifier = modifier.fillMaxWidth(), contentPadding = contentPadding) {
        header()
        if (shown.isEmpty()) {
            item(key = "empty") {
                Text(
                    nothing,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)
                )
            }
        }
        ToolCategory.entries.forEach { category ->
            val tools = groups[category].orEmpty()
            if (tools.isEmpty()) return@forEach
            item(key = "header_${category.name}") {
                Text(
                    text = stringResource(category.label),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 4.dp).semantics { heading() }
                )
            }
            items(tools, key = { it.id }) { tool -> ToolRow(tool, onClick = { onToolClick(tool.id) }) }
        }
    }
}

/** One tool: icon tile, name and description in a row (T2). Accent-free (3d): the accent is kept for Scan. */
@Composable
private fun ToolRow(tool: Tool, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = TargetStep.ACT.drawn)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Box(
            modifier = Modifier.size(40.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(tool.icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(22.dp))
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(tool.name), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
            Text(stringResource(tool.description), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
