package com.modose.app.ui.review

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.max

@Composable
fun BaselineBoundingBoxOverlay(
    state: BaselineReviewState,
    sourceWidthPx: Int,
    sourceHeightPx: Int,
    modifier: Modifier = Modifier,
) {
    val accessibilityDescription = state.entries.joinToString(separator = "、") { entry ->
        val selection = if (entry.selected) "対象" else "除外"
        "${entry.number}番 ${entry.objectValue.displayName} $selection"
    }
    Canvas(
        modifier = modifier.semantics {
            contentDescription = accessibilityDescription
        },
    ) {
        val transform = ReviewImageFitGeometry.calculate(
            sourceWidthPx = sourceWidthPx,
            sourceHeightPx = sourceHeightPx,
            viewportWidthPx = size.width,
            viewportHeightPx = size.height,
        ) ?: return@Canvas

        val strokeWidth = 3.dp.toPx()
        val badgeRadius = 13.dp.toPx()
        state.entries.forEach { entry ->
            val rect = transform.map(entry.objectValue.boundingBox)
            val color = entry.overlayColor()
            drawRect(
                color = color,
                topLeft = rect.topLeft,
                size = rect.size,
                alpha = if (entry.selected) 1f else 0.55f,
                style = Stroke(width = strokeWidth),
            )

            val badgeCenter = Offset(
                x = max(badgeRadius, rect.left + badgeRadius),
                y = max(badgeRadius, rect.top + badgeRadius),
            )
            drawCircle(
                color = color,
                radius = badgeRadius,
                center = badgeCenter,
                alpha = if (entry.selected) 1f else 0.7f,
            )
            drawContext.canvas.nativeCanvas.drawText(
                entry.number.toString(),
                badgeCenter.x,
                badgeCenter.y - (numberPaint.ascent() + numberPaint.descent()) / 2f,
                numberPaint.apply { textSize = badgeRadius * 1.15f },
            )
        }
    }
}

private fun BaselineReviewEntry.overlayColor(): Color = when {
    !selected -> Color(0xFF5F6662)
    source == ReviewObjectSource.Manual -> Color(0xFFFFB000)
    else -> Color(0xFF00A884)
}

private val numberPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = android.graphics.Color.WHITE
    textAlign = Paint.Align.CENTER
    typeface = android.graphics.Typeface.DEFAULT_BOLD
}
