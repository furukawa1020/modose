package com.modose.app.vision.quality

import com.modose.app.ar.image.CpuCameraImage
import com.modose.app.ar.image.CpuCameraImagePlane
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class YuvLumaQualityAssessorTest {
    private val assessor = YuvLumaQualityAssessor()

    @Test
    fun `reads luminance through row and pixel strides`() {
        val image = image(
            width = 3,
            height = 3,
            rowStride = 8,
            pixelStride = 2,
            values = intArrayOf(
                0, 50, 100,
                150, 200, 250,
                10, 128, 240,
            ),
        )

        val metrics = (assessor.assess(image) as ImageQualityAssessmentResult.Assessed).metrics

        assertEquals(125.333, metrics.luminanceMean, 0.001)
        assertEquals(2.0 / 9.0, metrics.clippedBlackRatio, 0.0001)
        assertEquals(2.0 / 9.0, metrics.clippedWhiteRatio, 0.0001)
        assertTrue(metrics.blurScore in 0.0..1.0)
    }

    @Test
    fun `gives textured image a higher blur score than flat image`() {
        val flat = image(3, 3, 3, 1, IntArray(9) { 128 })
        val textured = image(
            3,
            3,
            3,
            1,
            intArrayOf(
                0, 255, 0,
                255, 0, 255,
                0, 255, 0,
            ),
        )

        val flatScore =
            (assessor.assess(flat) as ImageQualityAssessmentResult.Assessed)
                .metrics.blurScore
        val texturedScore =
            (assessor.assess(textured) as ImageQualityAssessmentResult.Assessed)
                .metrics.blurScore

        assertEquals(0.0, flatScore, 0.0)
        assertTrue(texturedScore > flatScore)
    }

    @Test
    fun `rejects a truncated luma plane`() {
        val result = assessor.assess(
            image(
                width = 3,
                height = 3,
                rowStride = 3,
                pixelStride = 1,
                values = IntArray(9) { 128 },
                truncateLumaTo = 8,
            ),
        )

        assertEquals(
            ImageQualityAssessmentFailure.InvalidLumaPlane,
            (result as ImageQualityAssessmentResult.Failed).reason,
        )
    }

    @Test
    fun `rejects image too small for laplacian variance`() {
        val result = assessor.assess(
            image(2, 2, 2, 1, IntArray(4) { 128 }),
        )

        assertEquals(
            ImageQualityAssessmentFailure.ImageTooSmall,
            (result as ImageQualityAssessmentResult.Failed).reason,
        )
    }

    private fun image(
        width: Int,
        height: Int,
        rowStride: Int,
        pixelStride: Int,
        values: IntArray,
        truncateLumaTo: Int? = null,
    ): CpuCameraImage {
        val requiredSize = (height - 1) * rowStride + (width - 1) * pixelStride + 1
        val luma = ByteArray(requiredSize)
        values.forEachIndexed { index, value ->
            val y = index / width
            val x = index % width
            luma[y * rowStride + x * pixelStride] = value.toByte()
        }
        val effectiveLuma = truncateLumaTo?.let(luma::copyOf) ?: luma
        val chroma = CpuCameraImagePlane(
            bytes = byteArrayOf(128.toByte()),
            rowStride = 1,
            pixelStride = 1,
        )
        return CpuCameraImage(
            widthPx = width,
            heightPx = height,
            timestampNanos = 1L,
            planes = listOf(
                CpuCameraImagePlane(effectiveLuma, rowStride, pixelStride),
                chroma,
                chroma,
            ),
        )
    }
}
