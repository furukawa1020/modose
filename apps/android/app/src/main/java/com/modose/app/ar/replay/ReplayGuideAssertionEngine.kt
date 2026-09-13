package com.modose.app.ar.replay

import com.modose.app.flow.guidance.frame.GuideFrameInput
import com.modose.app.flow.guidance.frame.GuideFrameLoopResult
import com.modose.app.flow.guidance.frame.GuideFrameUpdateLoop
import com.modose.app.flow.guidance.frame.GuideFrameUpdateResult
import com.modose.app.flow.guidance.frame.GuideFrameVisual
import com.modose.app.flow.guidance.frame.GuideTrackingState
import com.modose.app.flow.guidance.frame.ScenePlanePoint
import kotlin.math.abs

enum class ReplayGuideVisualExpectation {
    Arrow,
    AlignmentRing,
    LocallyCompleted,
    FrozenTrackingLost,
    FrozenAmbiguous,
}

data class ReplayGuideExpectation(
    val sceneObjectId: String,
    val visual: ReplayGuideVisualExpectation,
    val expectedDistanceMeters: Double? = null,
    val distanceToleranceMeters: Double = 0.001,
)

enum class ReplayGuideAssertionFailure {
    InvalidExpectation,
    ObjectNotRecorded,
    VisualMismatch,
    DistanceOutsideTolerance,
}

sealed interface ReplayGuideAssertionResult {
    data class Passed(
        val actual: GuideFrameLoopResult,
    ) : ReplayGuideAssertionResult

    data class Failed(
        val reason: ReplayGuideAssertionFailure,
        val actual: GuideFrameLoopResult? = null,
    ) : ReplayGuideAssertionResult
}

class ReplayGuideAssertionEngine {
    private val loops = mutableMapOf<String, GuideFrameUpdateLoop>()

    fun assertFrame(
        frame: ReplayFrame,
        expectation: ReplayGuideExpectation,
    ): ReplayGuideAssertionResult {
        if (
            expectation.sceneObjectId.isBlank() ||
            !expectation.distanceToleranceMeters.isFinite() ||
            expectation.distanceToleranceMeters < 0.0 ||
            expectation.expectedDistanceMeters?.isFinite() == false
        ) {
            return ReplayGuideAssertionResult.Failed(
                ReplayGuideAssertionFailure.InvalidExpectation,
            )
        }

        val trackedObject = frame.objects.firstOrNull {
            it.objectId == expectation.sceneObjectId
        } ?: return ReplayGuideAssertionResult.Failed(
            ReplayGuideAssertionFailure.ObjectNotRecorded,
        )

        val actual = loops
            .getOrPut(trackedObject.objectId, ::GuideFrameUpdateLoop)
            .update(
                GuideFrameInput(
                    sceneObjectId = trackedObject.objectId,
                    frameTimestampNanos = frame.timestampNanos,
                    currentPosition = ScenePlanePoint(
                        trackedObject.currentX,
                        trackedObject.currentZ,
                    ),
                    targetPosition = ScenePlanePoint(
                        trackedObject.targetX,
                        trackedObject.targetZ,
                    ),
                    trackingState = frame.guideTrackingState(trackedObject),
                ),
            )

        if (!matches(expectation.visual, actual.update)) {
            return ReplayGuideAssertionResult.Failed(
                reason = ReplayGuideAssertionFailure.VisualMismatch,
                actual = actual,
            )
        }

        val expectedDistance = expectation.expectedDistanceMeters
        val actualDistance = actual.update.distanceMetersOrNull()
        if (
            expectedDistance != null &&
            (
                actualDistance == null ||
                abs(actualDistance - expectedDistance) >
                    expectation.distanceToleranceMeters
            )
        ) {
            return ReplayGuideAssertionResult.Failed(
                reason = ReplayGuideAssertionFailure.DistanceOutsideTolerance,
                actual = actual,
            )
        }

        return ReplayGuideAssertionResult.Passed(actual)
    }

    fun reset() {
        loops.values.forEach(GuideFrameUpdateLoop::reset)
        loops.clear()
    }

    private fun ReplayFrame.guideTrackingState(
        trackedObject: ReplayTrackedObject,
    ): GuideTrackingState =
        when {
            trackingState != ReplayTrackingState.Tracking ->
                GuideTrackingState.Lost
            trackedObject.matchState == ReplayMatchState.Ambiguous ->
                GuideTrackingState.Ambiguous
            trackedObject.matchState == ReplayMatchState.Missing ||
                !trackedObject.trackingValid ->
                GuideTrackingState.Lost
            else -> GuideTrackingState.Valid
        }

    private fun matches(
        expected: ReplayGuideVisualExpectation,
        actual: GuideFrameUpdateResult,
    ): Boolean =
        when (expected) {
            ReplayGuideVisualExpectation.Arrow ->
                actual.updatedVisual() is GuideFrameVisual.Arrow
            ReplayGuideVisualExpectation.AlignmentRing ->
                actual.updatedVisual() is GuideFrameVisual.AlignmentRing
            ReplayGuideVisualExpectation.LocallyCompleted ->
                actual.updatedVisual() is GuideFrameVisual.LocallyCompleted
            ReplayGuideVisualExpectation.FrozenTrackingLost ->
                actual is GuideFrameUpdateResult.Frozen &&
                    actual.reason.name == "TrackingLost"
            ReplayGuideVisualExpectation.FrozenAmbiguous ->
                actual is GuideFrameUpdateResult.Frozen &&
                    actual.reason.name == "AmbiguousTracking"
        }

    private fun GuideFrameUpdateResult.updatedVisual(): GuideFrameVisual? =
        (this as? GuideFrameUpdateResult.Updated)?.visual

    private fun GuideFrameUpdateResult.distanceMetersOrNull(): Double? =
        when (this) {
            is GuideFrameUpdateResult.Updated -> visual.distanceMeters
            is GuideFrameUpdateResult.Frozen -> previousVisual?.distanceMeters
            is GuideFrameUpdateResult.Rejected -> null
        }
}
