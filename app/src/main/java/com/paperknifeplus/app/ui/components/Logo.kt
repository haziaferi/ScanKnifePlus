package com.paperknifeplus.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import com.paperknifeplus.app.ui.theme.PaperPink

@Composable
fun Logo(
    modifier: Modifier = Modifier,
    partColor: Color = Color.Black
) {
    Canvas(modifier = modifier) {
        val width = size.width
        val scale = width / 24f

        val topPath = Path().apply {
            moveTo(4f * scale, 4f * scale)
            lineTo(21f * scale, 12f * scale)
            lineTo(9f * scale, 12f * scale)
            close()
        }
        drawPath(topPath, SolidColor(PaperPink))

        val bottomPath = Path().apply {
            moveTo(4f * scale, 20f * scale)
            lineTo(21f * scale, 12f * scale)
            lineTo(9f * scale, 12f * scale)
            close()
        }
        drawPath(bottomPath, SolidColor(partColor))
    }
}
