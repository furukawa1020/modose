package com.modose.app.flow.save

import com.modose.app.ar.anchor.SceneAnchorPose
import com.modose.app.ar.anchor.SceneAnchorSnapshot
import com.modose.app.ar.anchor.SceneAnchorState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class SavedSceneCommitPolicyTest {
    @Test
    fun committedSnapshotAndTrackingAnchorCreateBinding() {
        val decision = SavedSceneCommitPolicy.decide(request())

        val bind = decision as SavedSceneCommitDecision.Bind
        assertEquals("scene-001", bind.binding.sceneId)
        assertEquals(42L, bind.binding.anchorId)
        assertEquals(1_000L, bind.binding.boundAtEpochMillis)
    }

    @Test
    fun sameSceneAndAnchorAreAlreadyBoundWithoutReplacement() {
        val existing = binding()
        val decision = SavedSceneCommitPolicy.decide(
            request(existingBinding = existing),
        )

        val already = decision as SavedSceneCommitDecision.AlreadyBound
        assertSame(existing, already.binding)
    }

    @Test
    fun uncommittedSnapshotIsRejectedBeforeBinding() {
        val decision = SavedSceneCommitPolicy.decide(
            request(snapshotCommitted = false),
        )

        assertEquals(
            SavedSceneCommitDecision.Reject(
                SavedSceneCommitRejection.SnapshotNotCommitted,
            ),
            decision,
        )
    }

    @Test
    fun pausedAnchorIsRejected() {
        val decision = SavedSceneCommitPolicy.decide(
            request(anchorState = SceneAnchorState.Paused(anchor())),
        )

        assertEquals(
            SavedSceneCommitDecision.Reject(
                SavedSceneCommitRejection.AnchorPaused,
            ),
            decision,
        )
    }

    @Test
    fun anotherSceneCannotTakeExistingBinding() {
        val decision = SavedSceneCommitPolicy.decide(
            request(
                sceneId = "scene-002",
                existingBinding = binding(),
            ),
        )

        assertEquals(
            SavedSceneCommitDecision.Reject(
                SavedSceneCommitRejection.SceneConflict,
            ),
            decision,
        )
    }

    @Test
    fun anotherAnchorCannotReplaceExistingSceneAnchor() {
        val decision = SavedSceneCommitPolicy.decide(
            request(
                anchorState = SceneAnchorState.Tracking(anchor(id = 99L)),
                existingBinding = binding(),
            ),
        )

        assertEquals(
            SavedSceneCommitDecision.Reject(
                SavedSceneCommitRejection.AnchorConflict,
            ),
            decision,
        )
    }

    @Test
    fun nonFiniteAnchorPoseIsRejected() {
        val invalidAnchor = anchor(
            pose = pose().copy(translationX = Float.NaN),
        )
        val decision = SavedSceneCommitPolicy.decide(
            request(anchorState = SceneAnchorState.Tracking(invalidAnchor)),
        )

        assertEquals(
            SavedSceneCommitDecision.Reject(
                SavedSceneCommitRejection.InvalidAnchor,
            ),
            decision,
        )
    }

    private fun request(
        sceneId: String = "scene-001",
        snapshotCommitted: Boolean = true,
        anchorState: SceneAnchorState = SceneAnchorState.Tracking(anchor()),
        existingBinding: SavedSceneAnchorBinding? = null,
    ) = SavedSceneCommitRequest(
        sceneId = sceneId,
        snapshotCommitted = snapshotCommitted,
        anchorState = anchorState,
        existingBinding = existingBinding,
        requestedAtEpochMillis = 1_000L,
    )

    private fun binding() = SavedSceneAnchorBinding(
        sceneId = "scene-001",
        anchorId = 42L,
        anchorPose = pose(),
        boundAtEpochMillis = 500L,
    )

    private fun anchor(
        id: Long = 42L,
        pose: SceneAnchorPose = pose(),
    ) = SceneAnchorSnapshot(id = id, pose = pose)

    private fun pose() = SceneAnchorPose(
        translationX = 0.1f,
        translationY = 0.0f,
        translationZ = -0.2f,
        rotationX = 0.0f,
        rotationY = 0.0f,
        rotationZ = 0.0f,
        rotationW = 1.0f,
    )
}
