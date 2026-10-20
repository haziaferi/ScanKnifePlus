package com.paperknifeplus.app.ui.components

import android.content.Context
import android.graphics.drawable.VectorDrawable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import com.paperknifeplus.app.R
import com.paperknifeplus.app.ui.theme.LocalIsDarkTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The wordmark reads "ScanKnife+" with Scan light, Knife heavy and the "+" in brand red for each theme; both marks inflate. */
@RunWith(RobolectricTestRunner::class)
class WordmarkTest {
    @get:Rule
    val rule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun spans(dark: Boolean): List<Triple<String, FontWeight?, Color>> {
        rule.setContent { CompositionLocalProvider(LocalIsDarkTheme provides dark) { Wordmark(fontSize = 22.sp) } }
        val text = rule.onNodeWithText("ScanKnife+").fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text)!!.single()
        return text.spanStyles.map { Triple(text.text.substring(it.start, it.end), it.item.fontWeight, it.item.color) }
    }

    @Test
    fun `light theme - Scan medium, Knife extra bold, a deep red plus`() {
        val spans = spans(dark = false)
        assertEquals(listOf("Scan", "Knife", "+"), spans.map { it.first })
        assertEquals(listOf(FontWeight.Medium, FontWeight.ExtraBold, FontWeight.ExtraBold), spans.map { it.second })
        assertEquals(Color(0xFFC41E3A), spans[2].third)
    }

    @Test
    fun `OLED theme - the plus takes the brighter red`() {
        assertEquals(Color(0xFFF04A66), spans(dark = true)[2].third)
    }

    @Test
    fun `both marks inflate at the mark's 66 by 86 proportions`() {
        for (id in listOf(R.drawable.brand_mark_light, R.drawable.brand_mark_dark)) {
            val mark = ContextCompat.getDrawable(context, id)
            assertTrue(mark is VectorDrawable)
            assertEquals(66f / 86f, mark!!.intrinsicWidth.toFloat() / mark.intrinsicHeight, 0.02f)
        }
    }
}
