package com.modose.app.flow.compare

import com.modose.app.network.VisionApiResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CompareIdentitySnapshotTest {
    @Test
    fun callerMutationDuringTransportCannotAuthorizeAnotherObject() {
        val ids = mutableListOf("cup")
        val useCase = ExecuteSceneComparisonUseCase(
            executeRequest = {
                ids[0] = "other"
                VisionApiResult.Success(200, byteArrayOf())
            },
            decoder = { CompareIdentityFixture.response(listOf("other")) },
        )
        assertEquals(
            SceneCompareResult.Failed(SceneCompareFailure.MappingRejected(
                CompareMappingFailure.InvalidMatchSet,
            )),
            useCase.execute(CompareIdentityFixture.capture(ids)),
        )
        assertTrue(useCase.state is SceneCompareFlowState.Ready)
    }

    @Test
    fun callerMutationInStateNotificationDoesNotChangeOriginalTarget() {
        val ids = mutableListOf("cup")
        val useCase = ExecuteSceneComparisonUseCase(
            executeRequest = { VisionApiResult.Success(200, byteArrayOf()) },
            decoder = { CompareIdentityFixture.response() },
            onStateChanged = {
                if (it is SceneCompareFlowState.Comparing) ids.clear()
            },
        )
        val result = useCase.execute(CompareIdentityFixture.capture(ids))
            as SceneCompareResult.Completed
        assertEquals(listOf("cup"), result.comparison.matches.map { it.sceneObjectId })
    }

    @Test
    fun comparingStateCannotExposeWritableTargetIds() {
        var rejectedMutation = false
        val useCase = ExecuteSceneComparisonUseCase(
            executeRequest = { VisionApiResult.Success(200, byteArrayOf()) },
            decoder = { CompareIdentityFixture.response() },
            onStateChanged = {
                if (it is SceneCompareFlowState.Comparing) {
                    try {
                        (it.capture.mappingContext.expectedObjectIds as MutableList<String>)[0] = "other"
                    } catch (_: UnsupportedOperationException) {
                        rejectedMutation = true
                    }
                }
            },
        )
        assertTrue(useCase.execute(CompareIdentityFixture.capture()) is SceneCompareResult.Completed)
        assertTrue(rejectedMutation)
    }
}
