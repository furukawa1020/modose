package com.modose.app.ui.review

import androidx.compose.ui.geometry.Offset
import com.modose.app.network.baseline.NormalizedBoundingBox
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class ManualBoxRejection {
    OutsideImage,
    EmptyBox,
}

sealed interface ManualBoxMappingResult {
    data class Mapped(val boundingBox: NormalizedBoundingBox) : ManualBoxMappingResult
    data class Rejected(val reason: ManualBoxRejection) : ManualBoxMappingResult
}

object ManualBoxGestureMapper {
    fun map(
        transform: ImageFitTransform,
        start: Offset,
        end: Offset,
    ): ManualBoxMappingResult {
        if (!transform.contentRect.contains(start) || !transform.contentRect.contains(end)) {
            return ManualBoxMappingResult.Rejected(ManualBoxRejection.OutsideImage)
        }

        val left = min(start.x, end.x)
        val top = min(start.y, end.y)
        val right = max(start.x, end.x)
        val bottom = max(start.y, end.y)
        val box = NormalizedBoundingBox(
            yMin = transform.normalizedY(top),
            xMin = transform.normalizedX(left),
            yMax = transform.normalizedY(bottom),
            xMax = transform.normalizedX(right),
        )
        if (box.yMin >= box.yMax || box.xMin >= box.xMax) {
            return ManualBoxMappingResult.Rejected(ManualBoxRejection.EmptyBox)
        }
        return ManualBoxMappingResult.Mapped(box)
    }

    private fun ImageFitTransform.normalizedX(x: Float): Int =
        (((x - contentRect.left) / contentRect.width) * NORMALIZED_EDGE)
            .roundToInt()
            .coerceIn(0, NORMALIZED_EDGE)

    private fun ImageFitTransform.normalizedY(y: Float): Int =
        (((y - contentRect.top) / contentRect.height) * NORMALIZED_EDGE)
            .roundToInt()
            .coerceIn(0, NORMALIZED_EDGE)

    private const val NORMALIZED_EDGE = 1000
}
