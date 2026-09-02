package com.modose.app.vision.quality

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureQualityEvaluatorTest {
    private val evaluator = CaptureQualityEvaluator()

    @Test
    fun `allows capture at every acceptance boundary`() {
        val result = evaluator.evaluate(
            goodSignals().copy(
                luminanceMean = 45.0,
                clippedBlackRatio = 0.20,
                clippedWhiteRatio = 0.12,
                blurScore = 0.60,
                motionScore = 0.70,
                roiCoverage = 0.35,
            ),
        )

        val quality = (result as CaptureQualityEvaluationResult.Evaluated).quality
        assertTrue(quality.captureAllowed)
        assertTrue(quality.reasonCodes.isEmpty())
    }

    @Test
    fun `reports every failed gate without declaring success`() {
        val result = evaluator.evaluate(
            CaptureQualitySignals(
                luminanceMean = 20.0,
                clippedBlackRatio = 0.40,
                clippedWhiteRatio = 0.20,
                blurScore = 0.20,
                motionScore = 0.30,
                roiCoverage = 0.10,
                trackingGood = false,
                planeAvailable = false,
            ),
        )

        val quality = (result as CaptureQualityEvaluationResult.Evaluated).quality
        assertFalse(quality.captureAllowed)
        assertEquals(CaptureQualityReason.entries.toSet(), quality.reasonCodes)
    }

    @Test
    fun `rejects non finite and out of range metrics`() {
        val result = evaluator.evaluate(
            goodSignals().copy(
                luminanceMean = Double.NaN,
                blurScore = 1.01,
                roiCoverage = Double.POSITIVE_INFINITY,
            ),
        )

        assertEquals(
            setOf(
                InvalidCaptureQualityMetric.LuminanceMean,
                InvalidCaptureQualityMetric.BlurScore,
                InvalidCaptureQualityMetric.RoiCoverage,
            ),
            (result as CaptureQualityEvaluationResult.Rejected).invalidMetrics,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects inverted luminance thresholds`() {
        CaptureQualityThresholds(
            minimumLuminanceMean = 220.0,
            maximumLuminanceMean = 200.0,
        )
    }

    private fun goodSignals() = CaptureQualitySignals(
        luminanceMean = 128.0,
        clippedBlackRatio = 0.05,
        clippedWhiteRatio = 0.04,
        blurScore = 0.85,
        motionScore = 0.90,
        roiCoverage = 0.60,
        trackingGood = true,
        planeAvailable = true,
    )
}
