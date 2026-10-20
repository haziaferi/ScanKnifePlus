package com.paperknifeplus.app.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import com.paperknifeplus.app.R
import com.paperknifeplus.app.ui.theme.LocalIsDarkTheme
import com.paperknifeplus.app.ui.theme.PlusJakartaSans

// The brand red of the "+": the colour that recalls PaperKnife+, deep enough on the light themes to clear 4.5:1 at its small size.
private val PlusOnLight = Color(0xFFC41E3A)
private val PlusOnOled = Color(0xFFF04A66)

/** The mark (the dart, scan beam and crop marks) without its tile, [height] tall; drawn for the app's own theme, not the system's. */
@Composable
fun BrandMark(height: Dp, modifier: Modifier = Modifier) {
    val dark = LocalIsDarkTheme.current
    Image(
        painterResource(if (dark) R.drawable.brand_mark_dark else R.drawable.brand_mark_light),
        contentDescription = null, // the wordmark beside it names the app
        modifier = modifier.height(height).width(height * 66f / 86f)
    )
}

/** "ScanKnife+": Scan in Medium, Knife in ExtraBold, a smaller raised "+" in brand red; the letters take the theme's text colour. */
@Composable
fun Wordmark(fontSize: TextUnit, modifier: Modifier = Modifier) {
    val name = stringResource(R.string.app_name) // "ScanKnife+"
    val plus = if (LocalIsDarkTheme.current) PlusOnOled else PlusOnLight
    val text = buildAnnotatedString {
        withStyle(SpanStyle(fontWeight = FontWeight.Medium)) { append(name.substring(0, 4)) }
        withStyle(SpanStyle(fontWeight = FontWeight.ExtraBold)) { append(name.substring(4, 9)) }
        withStyle(SpanStyle(fontWeight = FontWeight.ExtraBold, color = plus, fontSize = 0.62.em, baselineShift = BaselineShift(0.48f))) {
            append(name.substring(9))
        }
    }
    Text(
        text,
        fontFamily = PlusJakartaSans,
        fontSize = fontSize,
        letterSpacing = (-0.015).em,
        color = MaterialTheme.colorScheme.onBackground,
        maxLines = 1,
        modifier = modifier
    )
}

/** The mark beside the wordmark, as the Home header shows it. */
@Composable
fun WordmarkRow(fontSize: TextUnit, markHeight: Dp, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        BrandMark(markHeight)
        Wordmark(fontSize)
    }
}

/** The mark above the wordmark, as the About page shows it. */
@Composable
fun WordmarkStack(fontSize: TextUnit, markHeight: Dp, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        BrandMark(markHeight)
        Wordmark(fontSize)
    }
}
