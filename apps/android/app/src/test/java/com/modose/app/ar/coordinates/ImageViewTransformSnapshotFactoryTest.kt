package com.modose.app.ar.coordinates

import org.junit.Assert.assertEquals
import org.junit.Test

class ImageViewTransformSnapshotFactoryTest {
    @Test
    fun `derives affine transform from image basis points`() {
        val result = ImageViewTransformSnapshotFactory.fromBasis(
            frameTimestampNanos = 1_000L,
            imageWidthPx = 640,
            imageHeightPx = 480,
            viewWidthPx = 480,
            viewHeightPx = 640,
            viewOrigin = ViewPixelPoint(480f, 0f),
            viewImageXAxisEnd = ViewPixelPoint(480f, 640f),
            viewImageYAxisEnd = ViewPixelPoint(0f, 0f),
        ) as ImageViewTransformFrameResult.Available

        assertEquals(
            ViewPixelPoint(400f, 120f),
            result.snapshot.transform.imageToView(ImagePixelPoint(120f, 80f)).value(),
        )
    }

    @Test
    fun `rejects invalid dimensions and collapsed basis`() {
        assertEquals(
            CoordinateTransformFailureReason.InvalidDimensions,
            (factory(imageWidth = 0) as ImageViewTransformFrameResult.Rejected).reason,
        )
        assertEquals(
            CoordinateTransformFailureReason.NonInvertible,
            (factory(collapsed = true) as ImageViewTransformFrameResult.Rejected).reason,
        )
    }

    private fun factory(
        imageWidth: Int = 640,
        collapsed: Boolean = false,
    ) = ImageViewTransformSnapshotFactory.fromBasis(
        frameTimestampNanos = 1_000L,
        imageWidthPx = imageWidth,
        imageHeightPx = 480,
        viewWidthPx = 480,
        viewHeightPx = 640,
        viewOrigin = ViewPixelPoint(0f, 0f),
        viewImageXAxisEnd = ViewPixelPoint(640f, 0f),
        viewImageYAxisEnd = if (collapsed) ViewPixelPoint(320f, 0f) else ViewPixelPoint(0f, 480f),
    )

    private fun <T> CoordinateTransformResult<T>.value(): T =
        (this as CoordinateTransformResult.Transformed).value
}
