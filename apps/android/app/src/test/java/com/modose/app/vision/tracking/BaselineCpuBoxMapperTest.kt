package com.modose.app.vision.tracking

import com.modose.app.ar.image.*
import com.modose.app.network.baseline.NormalizedBoundingBox
import org.junit.Assert.*
import org.junit.Test

class BaselineCpuBoxMapperTest {
    private val image = CpuCameraImage(400, 300, 123L, emptyList())
    private val roi = PixelRoi(20, 40, 220, 140)
    private val box = NormalizedBoundingBox(200, 100, 600, 400)

    private fun plan(rotation: Int = 0, region: PixelRoi = roi) =
        (VlmImageEncodingPlanner.create(400, 300, region, rotation) as VlmImagePlanResult.Planned).plan

    @Test
    fun allFourRotationsInvertAsymmetricRectangle() {
        val cases = listOf(
            0 to DetectedImageObject(null, 40, 60, 100, 100),
            90 to DetectedImageObject(null, 60, 100, 140, 130),
            180 to DetectedImageObject(null, 140, 80, 200, 120),
            270 to DetectedImageObject(null, 100, 50, 180, 80),
        )
        for ((rotation, expected) in cases) {
            assertEquals(expected, BaselineCpuBoxMapper.map(image, plan(rotation), box))
        }
    }

    @Test
    fun fullNormalizedBoxReturnsEntireRoiForEveryRotation() {
        for (rotation in listOf(0, 90, 180, 270)) {
            assertEquals(DetectedImageObject(null, 20, 40, 220, 140),
                BaselineCpuBoxMapper.map(image, plan(rotation), NormalizedBoundingBox(0, 0, 1000, 1000)))
        }
    }

    @Test
    fun fractionalPixelBoundsRoundOutwards() {
        assertEquals(DetectedImageObject(null, 20, 40, 21, 41),
            BaselineCpuBoxMapper.map(image, plan(), NormalizedBoundingBox(1, 1, 2, 2)))
    }

    @Test
    fun downscaledJpegStillMapsToOriginalCpuExtent() {
        val large = CpuCameraImage(1920, 1080, 123L, emptyList())
        val p = (VlmImageEncodingPlanner.create(1920, 1080,
            PixelRoi(0, 0, 1920, 1080), 90) as VlmImagePlanResult.Planned).plan
        assertEquals(1600, p.outputHeightPx)
        assertEquals(DetectedImageObject(null, 384, 648, 1152, 972),
            BaselineCpuBoxMapper.map(large, p, box))
    }

    @Test
    fun eachOddRoiBoundaryIsRejected() {
        for (region in listOf(PixelRoi(21, 40, 220, 140), PixelRoi(20, 41, 220, 140),
            PixelRoi(20, 40, 221, 140), PixelRoi(20, 40, 220, 141))) {
            assertNull(BaselineCpuBoxMapper.map(image, plan(region = region), box))
        }
    }

    @Test
    fun malformedAndOutOfRangeBoxesAreRejected() {
        for (bad in listOf(box.copy(xMin = -1), box.copy(yMin = -1),
            box.copy(xMax = 1001), box.copy(yMax = 1001),
            box.copy(xMin = box.xMax), box.copy(yMin = box.yMax),
            box.copy(xMin = 900), box.copy(yMin = 900))) {
            assertNull(BaselineCpuBoxMapper.map(image, plan(), bad))
        }
    }

    @Test
    fun alteredPlanAndOutOfImageRoiAreRejected() {
        val p = plan()
        for (bad in listOf(p.copy(outputWidthPx = p.outputWidthPx + 1),
            p.copy(outputHeightPx = 0), p.copy(jpegQuality = 1),
            p.copy(roi = PixelRoi(-2, 40, 220, 140)),
            p.copy(roi = PixelRoi(20, 40, 402, 140)),
            p.copy(roi = PixelRoi(220, 40, 20, 140)))) {
            assertNull(BaselineCpuBoxMapper.map(image, bad, box))
        }
    }

    @Test
    fun invalidDimensionsAndPixelBudgetAreRejectedWithoutOverflow() {
        for (bad in listOf(image.copy(widthPx = 0), image.copy(heightPx = -1),
            image.copy(widthPx = 1920, heightPx = 1082),
            image.copy(widthPx = Int.MAX_VALUE, heightPx = Int.MAX_VALUE))) {
            assertNull(BaselineCpuBoxMapper.map(bad, plan(), box))
        }
    }
}
