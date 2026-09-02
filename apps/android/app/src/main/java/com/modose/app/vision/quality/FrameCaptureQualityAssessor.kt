package com.modose.app.vision.quality

import com.modose.app.ar.image.CpuImageAcquisitionResult
import com.modose.app.ar.plane.HorizontalPlaneState
import com.modose.app.ar.session.ArCameraFrame
import com.modose.app.ar.session.ArTrackingIssue
import com.modose.app.ar.session.ArTrackingPhase
import kotlin.math.abs

enum class FrameCaptureQualityUnavailableReason {
    CpuImageUnavailable,
    InvalidCpuImage,
    InvalidAngularVelocity,
}

sealed interface FrameCaptureQualityResult {
    data class Evaluated(val quality: CaptureQuality) : FrameCaptureQualityResult

    data class RejectedMetrics(
        val invalidMetrics: Set<InvalidCaptureQualityMetric>,
    ) : FrameCaptureQualityResult

    data class Unavailable(
        val reason: FrameCaptureQualityUnavailableReason,
    ) : FrameCaptureQualityResult
}

class FrameCaptureQualityAssessor(
    private val imageAssessor: YuvLumaQualityAssessor = YuvLumaQualityAssessor(),
    private val evaluator: CaptureQualityEvaluator = CaptureQualityEvaluator(),
    private val maximumAngularVelocityRadPerSecond: Double = 2.5,
) {
    init {
        require(
            maximumAngularVelocityRadPerSecond.isFinite() &&
                maximumAngularVelocityRadPerSecond > 0.0,
        )
    }

    fun assess(
        frame: ArCameraFrame,
        angularVelocityRadPerSecond: Double,
        roiCoverage: Double,
    ): FrameCaptureQualityResult {
        if (
            !angularVelocityRadPerSecond.isFinite() ||
            angularVelocityRadPerSecond < 0.0
        ) {
            return FrameCaptureQualityResult.Unavailable(
                FrameCaptureQualityUnavailableReason.InvalidAngularVelocity,
            )
        }

        val image = when (val imageResult = frame.cpuImageResult) {
            is CpuImageAcquisitionResult.Acquired -> imageResult.image
            is CpuImageAcquisitionResult.Failed,
            is CpuImageAcquisitionResult.Skipped,
            -> {
                return FrameCaptureQualityResult.Unavailable(
                    FrameCaptureQualityUnavailableReason.CpuImageUnavailable,
                )
            }
        }
        val imageMetrics = when (val imageResult = imageAssessor.assess(image)) {
            is ImageQualityAssessmentResult.Assessed -> imageResult.metrics
            is ImageQualityAssessmentResult.Failed -> {
                return FrameCaptureQualityResult.Unavailable(
                    FrameCaptureQualityUnavailableReason.InvalidCpuImage,
                )
            }
        }

        val motionScore =
            (1.0 - abs(angularVelocityRadPerSecond) /
                maximumAngularVelocityRadPerSecond).coerceIn(0.0, 1.0)
        val evaluation = evaluator.evaluate(
            CaptureQualitySignals(
                luminanceMean = imageMetrics.luminanceMean,
                clippedBlackRatio = imageMetrics.clippedBlackRatio,
                clippedWhiteRatio = imageMetrics.clippedWhiteRatio,
                blurScore = imageMetrics.blurScore,
                motionScore = motionScore,
                roiCoverage = roiCoverage,
                trackingGood =
                    frame.trackingDiagnostics.phase == ArTrackingPhase.Tracking &&
                        frame.trackingDiagnostics.issue == ArTrackingIssue.None,
                planeAvailable =
                    frame.horizontalPlaneState is HorizontalPlaneState.Tracking,
            ),
        )

        return when (evaluation) {
            is CaptureQualityEvaluationResult.Evaluated ->
                FrameCaptureQualityResult.Evaluated(evaluation.quality)
            is CaptureQualityEvaluationResult.Rejected ->
                FrameCaptureQualityResult.RejectedMetrics(
                    evaluation.invalidMetrics,
                )
        }
    }
}
