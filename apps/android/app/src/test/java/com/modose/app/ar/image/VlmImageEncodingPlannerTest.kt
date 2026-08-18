package com.modose.app.ar.image

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VlmImageEncodingPlannerTest {
    @Test
    fun `crops rotates and limits the long edge`() {
        val result = VlmImageEncodingPlanner.create(
            sourceWidthPx = 4000,
            sourceHeightPx = 3000,
            roi = PixelRoi(0, 0, 4000, 2000),
            rotationDegreesClockwise = 90,
        ) as VlmImagePlanResult.Planned

        assertEquals(800, result.plan.outputWidthPx)
        assertEquals(1600, result.plan.outputHeightPx)
        assertEquals(82, result.plan.jpegQuality)
    }

    @Test
    fun `does not upscale a small crop`() {
        val result = VlmImageEncodingPlanner.create(
            sourceWidthPx = 640,
            sourceHeightPx = 480,
            roi = PixelRoi(10, 20, 210, 120),
            rotationDegreesClockwise = 0,
        ) as VlmImagePlanResult.Planned

        assertEquals(200, result.plan.outputWidthPx)
        assertEquals(100, result.plan.outputHeightPx)
    }

    @Test
    fun `rejects invalid roi dimensions and rotation`() {
        assertRejected(PixelRoi(10, 10, 10, 20), 0, VlmImageFailureReason.InvalidRoi)
        assertRejected(PixelRoi(0, 0, 641, 480), 0, VlmImageFailureReason.InvalidRoi)
        assertRejected(PixelRoi(0, 0, 640, 480), 45, VlmImageFailureReason.UnsupportedRotation)
        assertEquals(
            VlmImageFailureReason.InvalidDimensions,
            (VlmImageEncodingPlanner.create(0, 480, PixelRoi(0, 0, 1, 1), 0) as
                VlmImagePlanResult.Rejected).reason,
        )
    }

    @Test
    fun `enforces non empty two megabyte output`() {
        assertEquals(VlmImageFailureReason.EmptyOutput, VlmImageEncodingPlanner.validateEncodedSize(0))
        assertNull(VlmImageEncodingPlanner.validateEncodedSize(2 * 1024 * 1024))
        assertEquals(
            VlmImageFailureReason.OutputTooLarge,
            VlmImageEncodingPlanner.validateEncodedSize(2 * 1024 * 1024 + 1),
        )
    }

    private fun assertRejected(
        roi: PixelRoi,
        rotation: Int,
        expected: VlmImageFailureReason,
    ) {
        val result = VlmImageEncodingPlanner.create(640, 480, roi, rotation)
        assertEquals(expected, (result as VlmImagePlanResult.Rejected).reason)
    }
}
