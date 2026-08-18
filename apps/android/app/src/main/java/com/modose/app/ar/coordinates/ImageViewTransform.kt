package com.modose.app.ar.coordinates

import kotlin.math.abs

data class ImagePixelPoint(
    val x: Float,
    val y: Float,
)

data class ViewPixelPoint(
    val x: Float,
    val y: Float,
)

sealed interface CoordinateTransformResult<out T> {
    data class Transformed<T>(val value: T) : CoordinateTransformResult<T>
    data class Rejected(val reason: CoordinateTransformFailureReason) : CoordinateTransformResult<Nothing>
}

enum class CoordinateTransformFailureReason {
    NonFiniteValue,
    NonInvertible,
    OutOfBounds,
    InvalidDimensions,
    TrackingUnavailable,
}

class ImageViewTransform private constructor(
    private val m00: Float,
    private val m01: Float,
    private val m10: Float,
    private val m11: Float,
    private val translateX: Float,
    private val translateY: Float,
    private val determinant: Float,
) {
    fun imageToView(point: ImagePixelPoint): CoordinateTransformResult<ViewPixelPoint> {
        if (!point.x.isFinite() || !point.y.isFinite()) {
            return CoordinateTransformResult.Rejected(
                CoordinateTransformFailureReason.NonFiniteValue,
            )
        }
        return transformed(
            ViewPixelPoint(
                x = m00 * point.x + m01 * point.y + translateX,
                y = m10 * point.x + m11 * point.y + translateY,
            ),
        )
    }

    fun viewToImage(point: ViewPixelPoint): CoordinateTransformResult<ImagePixelPoint> {
        if (!point.x.isFinite() || !point.y.isFinite()) {
            return CoordinateTransformResult.Rejected(
                CoordinateTransformFailureReason.NonFiniteValue,
            )
        }
        val translatedX = point.x - translateX
        val translatedY = point.y - translateY
        return transformed(
            ImagePixelPoint(
                x = (m11 * translatedX - m01 * translatedY) / determinant,
                y = (-m10 * translatedX + m00 * translatedY) / determinant,
            ),
        )
    }

    private fun <T> transformed(value: T): CoordinateTransformResult<T> =
        CoordinateTransformResult.Transformed(value)

    companion object {
        private const val MIN_ABSOLUTE_DETERMINANT = 1e-8f

        fun create(
            m00: Float,
            m01: Float,
            m10: Float,
            m11: Float,
            translateX: Float,
            translateY: Float,
        ): CoordinateTransformResult<ImageViewTransform> {
            val values = floatArrayOf(m00, m01, m10, m11, translateX, translateY)
            if (values.any { value -> !value.isFinite() }) {
                return CoordinateTransformResult.Rejected(
                    CoordinateTransformFailureReason.NonFiniteValue,
                )
            }
            val determinant = m00 * m11 - m01 * m10
            if (!determinant.isFinite() || abs(determinant) < MIN_ABSOLUTE_DETERMINANT) {
                return CoordinateTransformResult.Rejected(
                    CoordinateTransformFailureReason.NonInvertible,
                )
            }
            return CoordinateTransformResult.Transformed(
                ImageViewTransform(
                    m00 = m00,
                    m01 = m01,
                    m10 = m10,
                    m11 = m11,
                    translateX = translateX,
                    translateY = translateY,
                    determinant = determinant,
                ),
            )
        }
    }
}
