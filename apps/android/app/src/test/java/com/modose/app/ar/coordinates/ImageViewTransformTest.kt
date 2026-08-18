package com.modose.app.ar.coordinates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ImageViewTransformTest {
    @Test
    fun `transforms image pixels to view pixels and back`() {
        val transform = transform(
            m00 = 0f,
            m01 = -0.5f,
            m10 = 0.5f,
            m11 = 0f,
            translateX = 240f,
            translateY = 0f,
        )
        val imagePoint = ImagePixelPoint(120f, 80f)

        val viewPoint = transform.imageToView(imagePoint).value()
        val roundTrip = transform.viewToImage(viewPoint).value()

        assertEquals(ViewPixelPoint(200f, 60f), viewPoint)
        assertTrue(abs(roundTrip.x - imagePoint.x) <= 0.001f)
        assertTrue(abs(roundTrip.y - imagePoint.y) <= 0.001f)
    }

    @Test
    fun `rejects non finite and non invertible transforms`() {
        assertEquals(
            CoordinateTransformFailureReason.NonFiniteValue,
            (ImageViewTransform.create(Float.NaN, 0f, 0f, 1f, 0f, 0f) as
                CoordinateTransformResult.Rejected).reason,
        )
        assertEquals(
            CoordinateTransformFailureReason.NonInvertible,
            (ImageViewTransform.create(1f, 2f, 2f, 4f, 0f, 0f) as
                CoordinateTransformResult.Rejected).reason,
        )
    }

    @Test
    fun `rejects non finite input points`() {
        val result = transform().imageToView(ImagePixelPoint(Float.POSITIVE_INFINITY, 1f))

        assertEquals(
            CoordinateTransformFailureReason.NonFiniteValue,
            (result as CoordinateTransformResult.Rejected).reason,
        )
    }

    private fun transform(
        m00: Float = 1f,
        m01: Float = 0f,
        m10: Float = 0f,
        m11: Float = 1f,
        translateX: Float = 0f,
        translateY: Float = 0f,
    ) = (ImageViewTransform.create(m00, m01, m10, m11, translateX, translateY) as
        CoordinateTransformResult.Transformed).value

    private fun <T> CoordinateTransformResult<T>.value(): T =
        (this as CoordinateTransformResult.Transformed).value
}
