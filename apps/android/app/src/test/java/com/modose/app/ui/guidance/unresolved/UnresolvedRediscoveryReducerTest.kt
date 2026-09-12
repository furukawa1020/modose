package com.modose.app.ui.guidance.unresolved

import com.modose.app.flow.compare.CompareBoundingBox
import com.modose.app.flow.guidance.GuideCandidateLocation
import com.modose.app.flow.guidance.GuideTargetPose
import com.modose.app.flow.guidance.SingleObjectGuideCandidate
import com.modose.app.flow.guidance.SingleObjectGuideState
import com.modose.app.ui.intermission.SavedObjectThumbnailModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UnresolvedRediscoveryReducerTest {
    @Test
    fun requestUsesMatchingSceneAndObjectAndSuppressesDuplicate() {
        val initial = state()
        val first = UnresolvedRediscoveryReducer.reduce(
            initial,
            UnresolvedRediscoveryEvent.Request(SCENE_ID, OBJECT_ID),
        )
        val effect = first.effect as UnresolvedRediscoveryEffect.CaptureAndCompare

        assertEquals(RediscoveryStatus.InFlight, first.state.status)
        assertEquals(1, first.state.attemptNumber)
        assertEquals(SCENE_ID + ":" + OBJECT_ID + ":1", effect.attemptKey)

        val duplicate = UnresolvedRediscoveryReducer.reduce(
            first.state,
            UnresolvedRediscoveryEvent.Request(SCENE_ID, OBJECT_ID),
        )
        assertEquals(first.state, duplicate.state)
        assertNull(duplicate.effect)
    }

    @Test
    fun mismatchedRequestDoesNotStartRediscovery() {
        val update = UnresolvedRediscoveryReducer.reduce(
            state(),
            UnresolvedRediscoveryEvent.Request("different-scene", OBJECT_ID),
        )

        assertEquals(RediscoveryStatus.Idle, update.state.status)
        assertNull(update.effect)
    }

    @Test
    fun failureExposesOnlySafeUnknownReason() {
        val inFlight = state().copy(status = RediscoveryStatus.InFlight)

        val failed = UnresolvedRediscoveryReducer.reduce(
            inFlight,
            UnresolvedRediscoveryEvent.Failed,
        )

        assertEquals(RediscoveryStatus.Failed, failed.state.status)
        assertEquals(UnresolvedObjectReason.Unknown, failed.state.model.reason)
    }

    @Test
    fun unresolvedObjectsBlockVerificationInStableOrder() {
        val second = state("object-b")
        val first = state("object-a")

        assertEquals(
            UnresolvedCompletionDecision.Blocked(
                listOf("object-a", "object-b"),
            ),
            UnresolvedCompletionGate.evaluate(listOf(second, first)),
        )
        assertEquals(
            UnresolvedCompletionDecision.EligibleForVerification,
            UnresolvedCompletionGate.evaluate(
                listOf(
                    first.copy(status = RediscoveryStatus.Resolved),
                    second.copy(status = RediscoveryStatus.Resolved),
                ),
            ),
        )
    }

    @Test
    fun onlyResolvedPositionalCandidateReturnsToPlanner() {
        val rediscovery = state().copy(status = RediscoveryStatus.Resolved)
        val candidate = positionalCandidate(OBJECT_ID)

        val result = UnresolvedObjectPlannerReentry.plan(
            rediscovery = rediscovery,
            resolvedCandidate = candidate,
            pendingCandidates = listOf(positionalCandidate("other")),
        ) as UnresolvedPlannerReentryResult.Planned

        val guiding = result.state as SingleObjectGuideState.Guiding
        assertTrue(
            listOf(guiding.active.sceneObjectId) + guiding.remainingObjectIds
                contains OBJECT_ID,
        )
    }

    @Test
    fun unresolvedOrNonPositionalCandidateCannotReturnToPlanner() {
        assertEquals(
            UnresolvedPlannerReentryResult.Rejected(
                UnresolvedPlannerReentryFailure.NotResolved,
            ),
            UnresolvedObjectPlannerReentry.plan(
                rediscovery = state(),
                resolvedCandidate = positionalCandidate(OBJECT_ID),
                pendingCandidates = emptyList(),
            ),
        )

        val missingCandidate = positionalCandidate(OBJECT_ID).copy(
            location = GuideCandidateLocation.Missing,
            targetPose = null,
        )
        assertEquals(
            UnresolvedPlannerReentryResult.Rejected(
                UnresolvedPlannerReentryFailure.StillNonPositional,
            ),
            UnresolvedObjectPlannerReentry.plan(
                rediscovery = state().copy(status = RediscoveryStatus.Resolved),
                resolvedCandidate = missingCandidate,
                pendingCandidates = emptyList(),
            ),
        )
    }

    private fun state(objectId: String = OBJECT_ID) = UnresolvedRediscoveryState(
        model = UnresolvedObjectUiModel(
            sceneId = SCENE_ID,
            objectId = objectId,
            displayName = "鍵",
            thumbnail = thumbnail(objectId),
            kind = UnresolvedObjectKind.Missing,
            reason = UnresolvedObjectReason.NotVisible,
            rediscoveryEnabled = true,
        ),
    )

    private fun thumbnail(objectId: String) = SavedObjectThumbnailModel(
        objectId = objectId,
        displayName = "鍵",
        imageFileName = "scene.jpg",
        yMin = 100,
        xMin = 100,
        yMax = 300,
        xMax = 300,
    )

    private fun positionalCandidate(objectId: String) = SingleObjectGuideCandidate(
        sceneObjectId = objectId,
        location = GuideCandidateLocation.Static(
            CompareBoundingBox(100, 100, 300, 300),
        ),
        targetPose = GuideTargetPose(0.1, 0.2, null),
        occludesObjectIds = emptySet(),
        correspondenceConfidence = 0.9,
        distanceToTargetMeters = 0.2,
    )

    private companion object {
        const val SCENE_ID = "scene-1"
        const val OBJECT_ID = "object-1"
    }
}
