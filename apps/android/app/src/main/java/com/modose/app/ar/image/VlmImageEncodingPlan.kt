package com.modose.app.ar.image

import kotlin.math.max
import kotlin.math.roundToInt

data class PixelRoi(
    val left: Int,
    val top: Int,
    val rightExclusive: Int,
    val bottomExclusive: Int,
) {
    val width: Int
        get() = rightExclusive - left
    val height: Int
        get() = bottomExclusive - top
}

enum class UprightRotation(val degreesClockwise: Int) {
    Degrees0(0),
    Degrees90(90),
    Degrees180(180),
    Degrees270(270),
}

data class VlmImageEncodingPlan(
    val roi: PixelRoi,
    val rotation: UprightRotation,
    val outputWidthPx: Int,
    val outputHeightPx: Int,
    val jpegQuality: Int = JPEG_QUALITY,
) {
    companion object {
        const val JPEG_QUALITY = 82
        const val MAX_LONG_EDGE_PX = 1600
        const val MAX_JPEG_BYTES = 2 * 1024 * 1024
    }
}

sealed interface VlmImagePlanResult {
    data class Planned(val plan: VlmImageEncodingPlan) : VlmImagePlanResult
    data class Rejected(val reason: VlmImageFailureReason) : VlmImagePlanResult
}

enum class VlmImageFailureReason {
    InvalidDimensions,
    InvalidRoi,
    UnsupportedRotation,
    InvalidYuv,
    EncodeFailed,
    EmptyOutput,
    OutputTooLarge,
}

object VlmImageEncodingPlanner {
    fun create(
        sourceWidthPx: Int,
        sourceHeightPx: Int,
        roi: PixelRoi,
        rotationDegreesClockwise: Int,
    ): VlmImagePlanResult {
        if (sourceWidthPx <= 0 || sourceHeightPx <= 0) {
            return rejected(VlmImageFailureReason.InvalidDimensions)
        }
        if (
            roi.left < 0 || roi.top < 0 ||
            roi.rightExclusive > sourceWidthPx || roi.bottomExclusive > sourceHeightPx ||
            roi.width <= 0 || roi.height <= 0
        ) {
            return rejected(VlmImageFailureReason.InvalidRoi)
        }
        val rotation = UprightRotation.entries.firstOrNull { value ->
            value.degreesClockwise == rotationDegreesClockwise
        } ?: return rejected(VlmImageFailureReason.UnsupportedRotation)

        val uprightWidth = if (rotation.swapsAxes()) roi.height else roi.width
        val uprightHeight = if (rotation.swapsAxes()) roi.width else roi.height
        val scale = minOf(
            1f,
            VlmImageEncodingPlan.MAX_LONG_EDGE_PX.toFloat() / max(uprightWidth, uprightHeight),
        )
        return VlmImagePlanResult.Planned(
            VlmImageEncodingPlan(
                roi = roi,
                rotation = rotation,
                outputWidthPx = max(1, (uprightWidth * scale).roundToInt()),
                outputHeightPx = max(1, (uprightHeight * scale).roundToInt()),
            ),
        )
    }

    fun validateEncodedSize(byteCount: Int): VlmImageFailureReason? = when {
        byteCount <= 0 -> VlmImageFailureReason.EmptyOutput
        byteCount > VlmImageEncodingPlan.MAX_JPEG_BYTES -> VlmImageFailureReason.OutputTooLarge
        else -> null
    }

    private fun rejected(reason: VlmImageFailureReason) = VlmImagePlanResult.Rejected(reason)

    private fun UprightRotation.swapsAxes(): Boolean =
        this == UprightRotation.Degrees90 || this == UprightRotation.Degrees270
}
