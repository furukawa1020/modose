package com.modose.app.ui.review

import androidx.compose.ui.geometry.Rect
import com.modose.app.network.baseline.NormalizedBoundingBox
import kotlin.math.min

data class ImageFitTransform(
    val contentRect: Rect,
) {
    fun map(box: NormalizedBoundingBox): Rect = Rect(
        left = contentRect.left + contentRect.width * (box.xMin / NORMALIZED_EDGE),
        top = contentRect.top + contentRect.height * (box.yMin / NORMALIZED_EDGE),
        right = contentRect.left + contentRect.width * (box.xMax / NORMALIZED_EDGE),
        bottom = contentRect.top + contentRect.height * (box.yMax / NORMALIZED_EDGE),
    )

    private companion object {
        const val NORMALIZED_EDGE = 1000f
    }
}

object ReviewImageFitGeometry {
    fun calculate(
        sourceWidthPx: Int,
        sourceHeightPx: Int,
        viewportWidthPx: Float,
        viewportHeightPx: Float,
    ): ImageFitTransform? {
        if (
            sourceWidthPx <= 0 ||
            sourceHeightPx <= 0 ||
            viewportWidthPx <= 0f ||
            viewportHeightPx <= 0f
        ) {
            return null
        }

        val scale = min(
            viewportWidthPx / sourceWidthPx,
            viewportHeightPx / sourceHeightPx,
        )
        val contentWidth = sourceWidthPx * scale
        val contentHeight = sourceHeightPx * scale
        val left = (viewportWidthPx - contentWidth) / 2f
        val top = (viewportHeightPx - contentHeight) / 2f
        return ImageFitTransform(
            contentRect = Rect(
                left = left,
                top = top,
                right = left + contentWidth,
                bottom = top + contentHeight,
            ),
        )
    }
}
