package com.modose.app.ui.camera

import com.modose.app.vision.quality.CaptureQuality
import com.modose.app.vision.quality.CaptureQualityReason
import com.modose.app.vision.quality.FrameCaptureQualityResult
import com.modose.app.vision.quality.FrameCaptureQualityUnavailableReason
import com.modose.app.vision.quality.InvalidCaptureQualityMetric
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureQualityPresentationMapperTest {
    @Test
    fun `enables save only for an allowed quality result`() {
        val model = CaptureQualityPresentationMapper.map(
            FrameCaptureQualityResult.Evaluated(quality()),
        )

        assertTrue(model.saveEnabled)
        assertEquals(CaptureQualityStatusTone.Ready, model.tone)
        assertEquals("保存できます", model.title)
        assertTrue(model.reasonMessages.isEmpty())
    }

    @Test
    fun `orders multiple reasons deterministically`() {
        val model = CaptureQualityPresentationMapper.map(
            FrameCaptureQualityResult.Evaluated(
                quality(
                    setOf(
                        CaptureQualityReason.RoiInsufficient,
                        CaptureQualityReason.ImageTooDark,
                        CaptureQualityReason.TrackingUnavailable,
                    ),
                ),
            ),
        )

        assertFalse(model.saveEnabled)
        assertEquals(CaptureQualityStatusTone.Blocked, model.tone)
        assertEquals(
            listOf(
                "端末をゆっくり動かしてください",
                "周囲を明るくしてください",
                "戻したい範囲を大きく映してください",
            ),
            model.reasonMessages,
        )
    }

    @Test
    fun `keeps save disabled while image is unavailable`() {
        val model = CaptureQualityPresentationMapper.map(
            FrameCaptureQualityResult.Unavailable(
                FrameCaptureQualityUnavailableReason.CpuImageUnavailable,
            ),
        )

        assertFalse(model.saveEnabled)
        assertEquals(CaptureQualityStatusTone.Waiting, model.tone)
        assertEquals(listOf("カメラ画像を待っています"), model.reasonMessages)
    }

    @Test
    fun `keeps save disabled for rejected metrics`() {
        val model = CaptureQualityPresentationMapper.map(
            FrameCaptureQualityResult.RejectedMetrics(
                setOf(InvalidCaptureQualityMetric.RoiCoverage),
            ),
        )

        assertFalse(model.saveEnabled)
        assertEquals(CaptureQualityStatusTone.Blocked, model.tone)
        assertTrue(model.contentDescription.startsWith("保存できません"))
    }

    private fun quality(
        reasons: Set<CaptureQualityReason> = emptySet(),
    ) = CaptureQuality(
        luminanceMean = 128.0,
        clippedBlackRatio = 0.02,
        clippedWhiteRatio = 0.02,
        blurScore = 0.80,
        motionScore = 0.90,
        roiCoverage = 0.70,
        trackingGood = true,
        planeAvailable = true,
        reasonCodes = reasons,
    )
}
