package com.displaysplitter.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** Hand-drawn 24dp glyphs so we don't ship the 4 MB extended icon pack. */
object AppIcons {

    /** Two opposing arrows — swap the video/spacer panes. */
    val Swap: ImageVector by lazy {
        ImageVector.Builder(
            name = "Swap", defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply {
            path(
                stroke = SolidColor(Color.White), strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
            ) {
                // up arrow (left half)
                moveTo(8f, 20f); lineTo(8f, 5f)
                moveTo(4.5f, 8.5f); lineTo(8f, 5f); lineTo(11.5f, 8.5f)
                // down arrow (right half)
                moveTo(16f, 4f); lineTo(16f, 19f)
                moveTo(12.5f, 15.5f); lineTo(16f, 19f); lineTo(19.5f, 15.5f)
            }
        }.build()
    }

    /** 16:9 frame with a smaller inner frame — aspect-ratio glyph for the bubble. */
    val Ratio: ImageVector by lazy {
        ImageVector.Builder(
            name = "Ratio", defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply {
            path(
                stroke = SolidColor(Color.White), strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
            ) {
                // outer rounded frame
                moveTo(5f, 4.5f)
                lineTo(19f, 4.5f)
                curveTo(20.1f, 4.5f, 21f, 5.4f, 21f, 6.5f)
                lineTo(21f, 17.5f)
                curveTo(21f, 18.6f, 20.1f, 19.5f, 19f, 19.5f)
                lineTo(5f, 19.5f)
                curveTo(3.9f, 19.5f, 3f, 18.6f, 3f, 17.5f)
                lineTo(3f, 6.5f)
                curveTo(3f, 5.4f, 3.9f, 4.5f, 5f, 4.5f)
                close()
                // divider line
                moveTo(3f, 15f); lineTo(21f, 15f)
            }
        }.build()
    }
}
