package com.modose.app.vision.quality

data class CaptureQualitySignals(
    val luminanceMean: Double,
    val clippedBlackRatio: Double,
    val clippedWhiteRatio: Double,
    val blurScore: Double,
    val motionScore: Double,
    val roiCoverage: Double,
    val trackingGood: Boolean,
    val planeAvailable: Boolean,
)

data class CaptureQualityThresholds(
    val minimumLuminanceMean: Double = 45.0,
    val maximumLuminanceMean: Double = 210.0,
    val maximumClippedBlackRatio: Double = 0.20,
    val maximumClippedWhiteRatio: Double = 0.12,
    val minimumBlurScore: Double = 0.60,
    val minimumMotionScore: Double = 0.70,
    val minimumRoiCoverage: Double = 0.35,
) {
    init {
        require(minimumLuminanceMean in LUMINANCE_RANGE)
        require(maximumLuminanceMean in LUMINANCE_RANGE)
        require(minimumLuminanceMean < maximumLuminanceMean)
        require(maximumClippedBlackRatio in UNIT_RANGE)
        require(maximumClippedWhiteRatio in UNIT_RANGE)
        require(minimumBlurScore in UNIT_RANGE)
        require(minimumMotionScore in UNIT_RANGE)
        require(minimumRoiCoverage in UNIT_RANGE)
    }

    private companion object {
        val LUMINANCE_RANGE = 0.0..255.0
        val UNIT_RANGE = 0.0..1.0
    }
}

enum class CaptureQualityReason {
    TrackingUnavailable,
    PlaneNotFound,
    ImageTooDark,
    ImageTooBright,
    ImageTooBlurry,
    ExcessiveMotion,
    RoiInsufficient,
}

data class CaptureQuality(
    val luminanceMean: Double,
    val clippedBlackRatio: Double,
    val clippedWhiteRatio: Double,
    val blurScore: Double,
    val motionScore: Double,
    val roiCoverage: Double,
    val trackingGood: Boolean,
    val planeAvailable: Boolean,
    val reasonCodes: Set<CaptureQualityReason>,
) {
    val captureAllowed: Boolean
        get() = reasonCodes.isEmpty()
}

enum class InvalidCaptureQualityMetric {
    LuminanceMean,
    ClippedBlackRatio,
    ClippedWhiteRatio,
    BlurScore,
    MotionScore,
    RoiCoverage,
}

sealed interface CaptureQualityEvaluationResult {
    data class Evaluated(val quality: CaptureQuality) : CaptureQualityEvaluationResult

    data class Rejected(
        val invalidMetrics: Set<InvalidCaptureQualityMetric>,
    ) : CaptureQualityEvaluationResult
}

class CaptureQualityEvaluator(
    private val thresholds: CaptureQualityThresholds = CaptureQualityThresholds(),
) {
    fun evaluate(signals: CaptureQualitySignals): CaptureQualityEvaluationResult {
        val invalidMetrics = buildSet {
            addIfInvalid(
                metric = InvalidCaptureQualityMetric.LuminanceMean,
                value = signals.luminanceMean,
                range = 0.0..255.0,
            )
            addIfInvalid(
                InvalidCaptureQualityMetric.ClippedBlackRatio,
                signals.clippedBlackRatio,
            )
            addIfInvalid(
                InvalidCaptureQualityMetric.ClippedWhiteRatio,
                signals.clippedWhiteRatio,
            )
            addIfInvalid(InvalidCaptureQualityMetric.BlurScore, signals.blurScore)
            addIfInvalid(InvalidCaptureQualityMetric.MotionScore, signals.motionScore)
            addIfInvalid(InvalidCaptureQualityMetric.RoiCoverage, signals.roiCoverage)
        }
        if (invalidMetrics.isNotEmpty()) {
            return CaptureQualityEvaluationResult.Rejected(invalidMetrics)
        }

        val reasons = buildSet {
            if (!signals.trackingGood) add(CaptureQualityReason.TrackingUnavailable)
            if (!signals.planeAvailable) add(CaptureQualityReason.PlaneNotFound)
            if (
                signals.luminanceMean < thresholds.minimumLuminanceMean ||
                signals.clippedBlackRatio > thresholds.maximumClippedBlackRatio
            ) {
                add(CaptureQualityReason.ImageTooDark)
            }
            if (
                signals.luminanceMean > thresholds.maximumLuminanceMean ||
                signals.clippedWhiteRatio > thresholds.maximumClippedWhiteRatio
            ) {
                add(CaptureQualityReason.ImageTooBright)
            }
            if (signals.blurScore < thresholds.minimumBlurScore) {
                add(CaptureQualityReason.ImageTooBlurry)
            }
            if (signals.motionScore < thresholds.minimumMotionScore) {
                add(CaptureQualityReason.ExcessiveMotion)
            }
            if (signals.roiCoverage < thresholds.minimumRoiCoverage) {
                add(CaptureQualityReason.RoiInsufficient)
            }
        }

        return CaptureQualityEvaluationResult.Evaluated(
            CaptureQuality(
                luminanceMean = signals.luminanceMean,
                clippedBlackRatio = signals.clippedBlackRatio,
                clippedWhiteRatio = signals.clippedWhiteRatio,
                blurScore = signals.blurScore,
                motionScore = signals.motionScore,
                roiCoverage = signals.roiCoverage,
                trackingGood = signals.trackingGood,
                planeAvailable = signals.planeAvailable,
                reasonCodes = reasons,
            ),
        )
    }

    private fun MutableSet<InvalidCaptureQualityMetric>.addIfInvalid(
        metric: InvalidCaptureQualityMetric,
        value: Double,
        range: ClosedFloatingPointRange<Double> = 0.0..1.0,
    ) {
        if (!value.isFinite() || value !in range) add(metric)
    }
}
