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

    /** Circle with hour/minute hands — the spacer's clock widget. */
    val Clock: ImageVector by lazy {
        ImageVector.Builder(
            name = "Clock", defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply {
            path(
                stroke = SolidColor(Color.White), strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
            ) {
                // circle (cubic approximation, k = 0.5523 * r)
                moveTo(12f, 4f)
                curveTo(16.42f, 4f, 20f, 7.58f, 20f, 12f)
                curveTo(20f, 16.42f, 16.42f, 20f, 12f, 20f)
                curveTo(7.58f, 20f, 4f, 16.42f, 4f, 12f)
                curveTo(4f, 7.58f, 7.58f, 4f, 12f, 4f)
                close()
                // hands
                moveTo(12f, 7.5f); lineTo(12f, 12f); lineTo(15f, 14f)
            }
        }.build()
    }

    /** Rounded page with text lines — the spacer's memo widget. */
    val Memo: ImageVector by lazy {
        ImageVector.Builder(
            name = "Memo", defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply {
            path(
                stroke = SolidColor(Color.White), strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
            ) {
                spacerFrame()
                moveTo(8.5f, 10f); lineTo(15.5f, 10f)
                moveTo(8.5f, 13.5f); lineTo(13.5f, 13.5f)
            }
        }.build()
    }

    /** The same frame, empty — the spacer's pure-black (blank) widget. */
    val Blank: ImageVector by lazy {
        ImageVector.Builder(
            name = "Blank", defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply {
            path(
                stroke = SolidColor(Color.White), strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
            ) {
                spacerFrame()
            }
        }.build()
    }

    /** Outward diagonal arrows — restore the video app to full screen. */
    val Expand: ImageVector by lazy {
        ImageVector.Builder(
            name = "Expand", defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply {
            path(
                stroke = SolidColor(Color.White), strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
            ) {
                // top-right arrow
                moveTo(14.5f, 4.5f); lineTo(19.5f, 4.5f); lineTo(19.5f, 9.5f)
                moveTo(19.5f, 4.5f); lineTo(13.5f, 10.5f)
                // bottom-left arrow
                moveTo(9.5f, 19.5f); lineTo(4.5f, 19.5f); lineTo(4.5f, 14.5f)
                moveTo(4.5f, 19.5f); lineTo(10.5f, 13.5f)
            }
        }.build()
    }

    /** Shared rounded frame for the Memo/Blank pair so the two chips read as siblings. */
    private fun androidx.compose.ui.graphics.vector.PathBuilder.spacerFrame() {
        moveTo(7f, 4.5f)
        lineTo(17f, 4.5f)
        curveTo(18.1f, 4.5f, 19f, 5.4f, 19f, 6.5f)
        lineTo(19f, 17.5f)
        curveTo(19f, 18.6f, 18.1f, 19.5f, 17f, 19.5f)
        lineTo(7f, 19.5f)
        curveTo(5.9f, 19.5f, 5f, 18.6f, 5f, 17.5f)
        lineTo(5f, 6.5f)
        curveTo(5f, 5.4f, 5.9f, 4.5f, 7f, 4.5f)
        close()
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
