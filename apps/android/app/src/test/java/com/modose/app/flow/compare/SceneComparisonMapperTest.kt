package com.modose.app.flow.compare

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SceneComparisonMapperTest {
    @Test
    fun validResponseIsMappedInBaselineOrder() {
        val result = SceneComparisonMapper.map(context(), validRaw())

        assertTrue(result is CompareMappingResult.Mapped)
        result as CompareMappingResult.Mapped
        assertEquals(
            listOf("obj_01", "obj_02"),
            result.comparison.matches.map { it.sceneObjectId },
        )
        assertEquals(
            CurrentObjectState.Moved,
            result.comparison.matches[0].state,
        )
        assertEquals(
            CurrentObjectState.Missing,
            result.comparison.matches[1].state,
        )
        assertNull(result.comparison.matches[1].trackingBoundingBox)
    }

    @Test
    fun unknownStateIsRejected() {
        val raw = validRaw().let {
            it.copy(
                matches = it.matches.mapIndexed { index, match ->
                    if (index == 0) match.copy(state = "invented") else match
                },
            )
        }

        assertRejected(raw, CompareMappingFailure.UnknownState)
    }

    @Test
    fun duplicateObjectIdIsRejected() {
        val first = validRaw().matches.first()
        val raw = validRaw().copy(matches = listOf(first, first))

        assertRejected(raw, CompareMappingFailure.InvalidMatchSet)
    }

    @Test
    fun ambiguousObjectCannotCarryGuideCoordinates() {
        val raw = validRaw().let {
            it.copy(
                matches = it.matches.map { match ->
                    if (match.sceneObjectId == "obj_01") {
                        match.copy(
                            state = "ambiguous",
                            currentBoundingBox = box(),
                            reasonCodes = listOf("OBJECT_AMBIGUOUS"),
                        )
                    } else {
                        match
                    }
                },
            )
        }

        assertRejected(raw, CompareMappingFailure.InvalidBoundingBox)
    }

    @Test
    fun unknownOccludingObjectIsRejected() {
        val raw = validRaw().let {
            it.copy(
                matches = it.matches.map { match ->
                    if (match.sceneObjectId == "obj_01") {
                        match.copy(
                            state = "occluded",
                            currentBoundingBox = null,
                            occludedBy = listOf("obj_99"),
                            reasonCodes = listOf("OBJECT_OCCLUDED"),
                        )
                    } else {
                        match
                    }
                },
            )
        }

        assertRejected(raw, CompareMappingFailure.InvalidOcclusion)
    }

    @Test
    fun modelMismatchIsRejected() {
        val raw = validRaw().copy(modelId = "other-model")

        assertRejected(raw, CompareMappingFailure.ModelMismatch)
    }

    private fun assertRejected(
        raw: RawSceneComparison,
        expected: CompareMappingFailure,
    ) {
        assertEquals(
            CompareMappingResult.Rejected(expected),
            SceneComparisonMapper.map(context(), raw),
        )
    }

    private fun context() = CompareMappingContext(
        sceneId = "scene-001",
        expectedObjectIds = listOf("obj_01", "obj_02"),
        expectedModelId = "model",
        expectedPromptVersion = "compare-v1",
    )

    private fun validRaw() = RawSceneComparison(
        schemaVersion = "1.0",
        status = "ok",
        modelId = "model",
        promptVersion = "compare-v1",
        matches = listOf(
            RawCompareMatch(
                sceneObjectId = "obj_02",
                state = "missing",
                currentBoundingBox = null,
                sameObjectConfidence = 0.95,
                orientationDeltaDegrees = null,
                occludedBy = emptyList(),
                reasonCodes = listOf("OBJECT_MISSING"),
            ),
            RawCompareMatch(
                sceneObjectId = "obj_01",
                state = "moved",
                currentBoundingBox = box(),
                sameObjectConfidence = 0.98,
                orientationDeltaDegrees = null,
                occludedBy = emptyList(),
                reasonCodes = listOf("POSITION_CHANGED"),
            ),
        ),
        extraObjects = emptyList(),
    )

    private fun box() = RawCompareBoundingBox(
        yMin = 100,
        xMin = 200,
        yMax = 500,
        xMax = 600,
    )
}
