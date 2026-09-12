package com.modose.app.ui.intermission

import org.junit.Assert.assertEquals
import org.junit.Test

class MoveObjectsIntermissionTransitionTableTest {
    @Test
    fun controlEventsFollowTransitionTable() {
        val ready = state()
        val cases = listOf(
            Case(
                "readyから復元開始",
                ready,
                MoveObjectsIntermissionEvent.StartRestoration,
                ready.copy(startInFlight = true),
                MoveObjectsIntermissionEffect.CaptureCurrentScene(SCENE_ID),
            ),
            Case(
                "anchor停止中は開始しない",
                state(RestorationStartAvailability.AnchorPaused),
                MoveObjectsIntermissionEvent.StartRestoration,
                state(RestorationStartAvailability.AnchorPaused),
            ),
            Case(
                "anchor喪失中は開始しない",
                state(RestorationStartAvailability.AnchorLost),
                MoveObjectsIntermissionEvent.StartRestoration,
                state(RestorationStartAvailability.AnchorLost),
            ),
            Case(
                "anchor失敗中は開始しない",
                state(RestorationStartAvailability.AnchorFailed),
                MoveObjectsIntermissionEvent.StartRestoration,
                state(RestorationStartAvailability.AnchorFailed),
            ),
            Case(
                "開始処理中は二重開始しない",
                ready.copy(startInFlight = true),
                MoveObjectsIntermissionEvent.StartRestoration,
                ready.copy(startInFlight = true),
            ),
            Case(
                "reset確認中は開始しない",
                ready.copy(resetConfirmationVisible = true),
                MoveObjectsIntermissionEvent.StartRestoration,
                ready.copy(resetConfirmationVisible = true),
            ),
            Case(
                "開始失敗で再試行可能に戻る",
                ready.copy(startInFlight = true),
                MoveObjectsIntermissionEvent.StartFailed,
                ready,
            ),
            Case(
                "reset要求で確認を表示",
                ready,
                MoveObjectsIntermissionEvent.RequestReset,
                ready.copy(resetConfirmationVisible = true),
            ),
            Case(
                "reset取消で確認を閉じる",
                ready.copy(resetConfirmationVisible = true),
                MoveObjectsIntermissionEvent.CancelReset,
                ready,
            ),
            Case(
                "確認後だけreset effectを出す",
                ready.copy(resetConfirmationVisible = true),
                MoveObjectsIntermissionEvent.ConfirmReset,
                ready,
                MoveObjectsIntermissionEffect.ResetScene(SCENE_ID),
            ),
            Case(
                "確認なしではresetしない",
                ready,
                MoveObjectsIntermissionEvent.ConfirmReset,
                ready,
            ),
            Case(
                "開始処理中はresetを確定しない",
                ready.copy(
                    startInFlight = true,
                    resetConfirmationVisible = true,
                ),
                MoveObjectsIntermissionEvent.ConfirmReset,
                ready.copy(
                    startInFlight = true,
                    resetConfirmationVisible = true,
                ),
            ),
        )

        cases.forEach { case ->
            val actual = MoveObjectsIntermissionReducer.reduce(
                case.initial,
                case.event,
            )

            assertEquals(case.name, case.expectedState, actual.state)
            assertEquals(case.name, case.expectedEffect, actual.effect)
        }
    }

    private fun state(
        availability: RestorationStartAvailability =
            RestorationStartAvailability.Ready,
    ) = MoveObjectsIntermissionState(
        sceneId = SCENE_ID,
        objects = listOf(
            SavedObjectThumbnailModel(
                objectId = "wallet",
                displayName = "財布",
                imageFileName = "wallet.jpg",
                yMin = 100,
                xMin = 100,
                yMax = 300,
                xMax = 400,
            ),
        ),
        startAvailability = availability,
    )

    private data class Case(
        val name: String,
        val initial: MoveObjectsIntermissionState,
        val event: MoveObjectsIntermissionEvent,
        val expectedState: MoveObjectsIntermissionState,
        val expectedEffect: MoveObjectsIntermissionEffect? = null,
    )

    companion object {
        private const val SCENE_ID = "scene-transition-table"
    }
}
