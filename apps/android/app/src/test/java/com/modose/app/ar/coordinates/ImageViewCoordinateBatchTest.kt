package com.modose.app.ar.coordinates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageViewCoordinateBatchTest {
    @Test
    fun `round trips fixtures for all display rotations within half a pixel`() {
        rotationFixtures().forEach { fixture ->
            val snapshot = snapshot(fixture)
            val error = ImageViewCoordinateBatch.maximumImageRoundTripErrorPx(
                snapshot,
                imageFixturePoints(),
            ).value()

            assertTrue("${fixture.name}: $error", error <= 0.5f)
        }
    }

    @Test
    fun `transforms batches without returning partial results`() {
        val result = ImageViewCoordinateBatch.imageToView(
            snapshot(rotationFixtures().first()),
            listOf(ImagePixelPoint(10f, 10f), ImagePixelPoint(641f, 10f)),
        )

        assertEquals(
            CoordinateTransformFailureReason.OutOfBounds,
            (result as CoordinateTransformResult.Rejected).reason,
        )
    }

    @Test
    fun `rejects view points outside the current view`() {
        val result = ImageViewCoordinateBatch.viewToImage(
            snapshot(rotationFixtures().first()),
            listOf(ViewPixelPoint(-1f, 0f)),
        )

        assertEquals(
            CoordinateTransformFailureReason.OutOfBounds,
            (result as CoordinateTransformResult.Rejected).reason,
        )
    }

    private fun snapshot(fixture: RotationFixture): ImageViewTransformSnapshot =
        (ImageViewTransformSnapshotFactory.fromBasis(
            frameTimestampNanos = 1_000L,
            imageWidthPx = 640,
            imageHeightPx = 480,
            viewWidthPx = fixture.viewWidth,
            viewHeightPx = fixture.viewHeight,
            viewOrigin = fixture.origin,
            viewImageXAxisEnd = fixture.xAxisEnd,
            viewImageYAxisEnd = fixture.yAxisEnd,
        ) as ImageViewTransformFrameResult.Available).snapshot

    private fun imageFixturePoints() = listOf(
        ImagePixelPoint(0f, 0f),
        ImagePixelPoint(640f, 0f),
        ImagePixelPoint(0f, 480f),
        ImagePixelPoint(640f, 480f),
        ImagePixelPoint(317.25f, 201.75f),
    )

    private fun rotationFixtures() = listOf(
        RotationFixture("0", 640, 480, point(0, 0), point(640, 0), point(0, 480)),
        RotationFixture("90", 480, 640, point(480, 0), point(480, 640), point(0, 0)),
        RotationFixture("180", 640, 480, point(640, 480), point(0, 480), point(640, 0)),
        RotationFixture("270", 480, 640, point(0, 640), point(0, 0), point(480, 640)),
    )

    private fun point(x: Int, y: Int) = ViewPixelPoint(x.toFloat(), y.toFloat())

    private data class RotationFixture(
        val name: String,
        val viewWidth: Int,
        val viewHeight: Int,
        val origin: ViewPixelPoint,
        val xAxisEnd: ViewPixelPoint,
        val yAxisEnd: ViewPixelPoint,
    )

    private fun <T> CoordinateTransformResult<T>.value(): T =
        (this as CoordinateTransformResult.Transformed).value
}
