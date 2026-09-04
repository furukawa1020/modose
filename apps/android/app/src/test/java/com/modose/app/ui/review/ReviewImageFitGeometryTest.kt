package com.modose.app.ui.review

import com.modose.app.network.baseline.NormalizedBoundingBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReviewImageFitGeometryTest {
    @Test
    fun landscapeImageIncludesVerticalLetterboxOffset() {
        val transform = ReviewImageFitGeometry.calculate(
            sourceWidthPx = 400,
            sourceHeightPx = 300,
            viewportWidthPx = 1_000f,
            viewportHeightPx = 1_000f,
        )!!

        assertRect(
            expectedLeft = 0f,
            expectedTop = 125f,
            expectedRight = 1_000f,
            expectedBottom = 875f,
            actual = transform.map(NormalizedBoundingBox(0, 0, 1000, 1000)),
        )
    }

    @Test
    fun normalizedBoxMapsInsideFittedImage() {
        val transform = ReviewImageFitGeometry.calculate(
            sourceWidthPx = 400,
            sourceHeightPx = 300,
            viewportWidthPx = 1_000f,
            viewportHeightPx = 1_000f,
        )!!

        assertRect(
            expectedLeft = 250f,
            expectedTop = 312.5f,
            expectedRight = 750f,
            expectedBottom = 687.5f,
            actual = transform.map(NormalizedBoundingBox(250, 250, 750, 750)),
        )
    }

    @Test
    fun portraitImageIncludesHorizontalLetterboxOffset() {
        val transform = ReviewImageFitGeometry.calculate(
            sourceWidthPx = 300,
            sourceHeightPx = 600,
            viewportWidthPx = 1_000f,
            viewportHeightPx = 600f,
        )!!

        assertRect(
            expectedLeft = 350f,
            expectedTop = 0f,
            expectedRight = 650f,
            expectedBottom = 600f,
            actual = transform.map(NormalizedBoundingBox(0, 0, 1000, 1000)),
        )
    }

    @Test
    fun invalidDimensionsDoNotProduceTransform() {
        assertNull(ReviewImageFitGeometry.calculate(0, 300, 1_000f, 1_000f))
        assertNull(ReviewImageFitGeometry.calculate(400, 300, 0f, 1_000f))
    }

    private fun assertRect(
        expectedLeft: Float,
        expectedTop: Float,
        expectedRight: Float,
        expectedBottom: Float,
        actual: androidx.compose.ui.geometry.Rect,
    ) {
        assertEquals(expectedLeft, actual.left, 0.01f)
        assertEquals(expectedTop, actual.top, 0.01f)
        assertEquals(expectedRight, actual.right, 0.01f)
        assertEquals(expectedBottom, actual.bottom, 0.01f)
    }
}
