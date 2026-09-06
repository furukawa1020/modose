package com.modose.app.ui.intermission

import com.modose.app.ar.anchor.SceneAnchorPose
import com.modose.app.ar.anchor.SceneAnchorSnapshot
import com.modose.app.ar.anchor.SceneAnchorState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MoveObjectsIntermissionReducerTest {
    @Test
    fun readySceneStartsCurrentCaptureOnce() {
        val initial = state()
        val first = MoveObjectsIntermissionReducer.reduce(
            initial,
            MoveObjectsIntermissionEvent.StartRestoration,
        )
        val second = MoveObjectsIntermissionReducer.reduce(
            first.state,
            MoveObjectsIntermissionEvent.StartRestoration,
        )

        assertTrue(first.state.startInFlight)
        assertEquals(
            MoveObjectsIntermissionEffect.CaptureCurrentScene(SCENE_ID),
            first.effect,
        )
        assertEquals(first.state, second.state)
        assertNull(second.effect)
    }

    @Test
    fun lostAnchorBlocksRestorationStart() {
        val lost = MoveObjectsIntermissionReducer.reduce(
            state(),
            MoveObjectsIntermissionEvent.AnchorChanged(
                SceneAnchorState.Lost(42L),
            ),
        )
        val start = MoveObjectsIntermissionReducer.reduce(
            lost.state,
            MoveObjectsIntermissionEvent.StartRestoration,
        )

        assertEquals(
            RestorationStartAvailability.AnchorLost,
            lost.state.startAvailability,
        )
        assertFalse(start.state.startInFlight)
        assertNull(start.effect)
    }

    @Test
    fun resetRequestDoesNotDeleteSceneWithoutConfirmation() {
        val requested = MoveObjectsIntermissionReducer.reduce(
            state(),
            MoveObjectsIntermissionEvent.RequestReset,
        )

        assertTrue(requested.state.resetConfirmationVisible)
        assertNull(requested.effect)

        val cancelled = MoveObjectsIntermissionReducer.reduce(
            requested.state,
            MoveObjectsIntermissionEvent.CancelReset,
        )
        assertFalse(cancelled.state.resetConfirmationVisible)
        assertNull(cancelled.effect)
    }

    @Test
    fun confirmedResetEmitsSceneScopedEffect() {
        val requested = MoveObjectsIntermissionReducer.reduce(
            state(),
            MoveObjectsIntermissionEvent.RequestReset,
        )
        val confirmed = MoveObjectsIntermissionReducer.reduce(
            requested.state,
            MoveObjectsIntermissionEvent.ConfirmReset,
        )

        assertFalse(confirmed.state.resetConfirmationVisible)
        assertEquals(
            MoveObjectsIntermissionEffect.ResetScene(SCENE_ID),
            confirmed.effect,
        )
    }

    @Test
    fun resetCannotInterruptCaptureInFlight() {
        val busy = state().copy(
            startInFlight = true,
            resetConfirmationVisible = true,
        )
        val update = MoveObjectsIntermissionReducer.reduce(
            busy,
            MoveObjectsIntermissionEvent.ConfirmReset,
        )

        assertEquals(busy, update.state)
        assertNull(update.effect)
    }

    private fun state() = MoveObjectsIntermissionState(
        sceneId = SCENE_ID,
        objects = listOf(
            SavedObjectThumbnailModel(
                objectId = "object-1",
                displayName = "鍵",
                imageFileName = "scene-001.jpg",
                yMin = 100,
                xMin = 200,
                yMax = 500,
                xMax = 600,
            ),
        ),
        startAvailability = MoveObjectsIntermissionReducer.availability(
            SceneAnchorState.Tracking(
                SceneAnchorSnapshot(
                    id = 42L,
                    pose = SceneAnchorPose(
                        translationX = 0f,
                        translationY = 0f,
                        translationZ = 0f,
                        rotationX = 0f,
                        rotationY = 0f,
                        rotationZ = 0f,
                        rotationW = 1f,
                    ),
                ),
            ),
        ),
    )

    private companion object {
        const val SCENE_ID = "scene-001"
    }
}
