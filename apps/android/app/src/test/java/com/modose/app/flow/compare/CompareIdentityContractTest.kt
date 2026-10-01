package com.modose.app.flow.compare

import com.modose.app.network.VisionApiResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CompareIdentityContractTest {
    @Test
    fun invalidOrMismatchedIdsNeverInvokeTransportAndRestoreReadyState() {
        for (ids in listOf(
            emptyList(), listOf(""), listOf(" "), listOf("x".repeat(65)),
            listOf("cup", "cup"), (1..6).map { "object-$it" },
            listOf("other"), listOf("cup", "other"),
        )) {
            var calls = 0
            val useCase = ExecuteSceneComparisonUseCase(
                executeRequest = {
                    calls++
                    VisionApiResult.Success(200, byteArrayOf())
                },
                decoder = { CompareIdentityFixture.response() },
            )
            val expected = SceneCompareFailure.MappingRejected(CompareMappingFailure.InvalidContext)
            assertEquals(SceneCompareResult.Failed(expected),
                useCase.execute(CompareIdentityFixture.capture(ids)))
            assertEquals(0, calls)
            assertEquals(SceneCompareFlowState.Ready(expected), useCase.state)
        }
    }

    @Test
    fun missingExpectedIdIsRejectedEvenWhenItExistsInSubmittedObjects() {
        var calls = 0
        val useCase = ExecuteSceneComparisonUseCase(
            executeRequest = { calls++; VisionApiResult.NetworkFailure },
            decoder = { CompareIdentityFixture.response() },
        )
        val result = useCase.execute(CompareIdentityFixture.capture(
            ids = listOf("cup"), payloadIds = listOf("cup", "pen"),
        ))
        assertEquals(SceneCompareResult.Failed(SceneCompareFailure.MappingRejected(
            CompareMappingFailure.InvalidContext,
        )), result)
        assertEquals(0, calls)
    }

    @Test
    fun matchingIdsAreAcceptedRegardlessOfOrder() {
        var calls = 0
        val useCase = ExecuteSceneComparisonUseCase(
            executeRequest = { calls++; VisionApiResult.Success(200, byteArrayOf()) },
            decoder = { CompareIdentityFixture.response(listOf("cup", "pen")) },
        )
        val result = useCase.execute(CompareIdentityFixture.capture(
            ids = listOf("pen", "cup"), payloadIds = listOf("cup", "pen"),
        )) as SceneCompareResult.Completed
        assertEquals(1, calls)
        assertEquals(listOf("pen", "cup"), result.comparison.matches.map { it.sceneObjectId })
        assertTrue(useCase.state is SceneCompareFlowState.Guiding)
    }
}
