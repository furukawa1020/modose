package com.modose.app.ui.review

import com.modose.app.network.baseline.BaselineAnalysis
import com.modose.app.network.baseline.BaselineObject
import com.modose.app.network.baseline.NormalizedBoundingBox
import com.modose.app.network.baseline.ObjectSymmetry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BaselineReviewReducerTest {
    @Test
    fun analysisStartsWithNumberedSelectedObjects() {
        val state = stateWithObjects(3)

        assertEquals(listOf(1, 2, 3), state.entries.map { it.number })
        assertTrue(state.entries.all { it.selected })
        assertTrue(state.canConfirm)
        assertTrue(state.canAddManualObject)
    }

    @Test
    fun excludingEveryObjectDisablesConfirmation() {
        var state = stateWithObjects(2)
        state.entries.forEach { entry ->
            state = updated(
                BaselineReviewReducer.reduce(
                    state,
                    BaselineReviewAction.ToggleSelection(entry.objectValue.id),
                ),
            )
        }

        assertFalse(state.canConfirm)
        assertEquals(
            BaselineReviewConfirmation.NoSelectedObjects,
            BaselineReviewReducer.confirm(state),
        )
    }

    @Test
    fun addsOnlyOneValidManualObject() {
        val original = stateWithObjects(2)
        val box = NormalizedBoundingBox(100, 200, 400, 600)
        val state = updated(
            BaselineReviewReducer.reduce(
                original,
                BaselineReviewAction.AddManualObject(box),
            ),
        )

        val manual = state.entries.last()
        assertEquals(ReviewObjectSource.Manual, manual.source)
        assertEquals(3, manual.number)
        assertEquals(box, manual.objectValue.boundingBox)
        assertTrue(manual.selected)
        assertFalse(state.canAddManualObject)

        val second = BaselineReviewReducer.reduce(
            state,
            BaselineReviewAction.AddManualObject(NormalizedBoundingBox(10, 10, 20, 20)),
        ) as BaselineReviewUpdate.Rejected
        assertEquals(BaselineReviewRejection.ManualObjectAlreadyExists, second.reason)
        assertEquals(state, second.state)
    }

    @Test
    fun invalidManualBoxesDoNotChangeState() {
        val state = stateWithObjects(1)
        listOf(
            NormalizedBoundingBox(10, 10, 10, 20),
            NormalizedBoundingBox(30, 10, 20, 20),
            NormalizedBoundingBox(0, -1, 20, 20),
            NormalizedBoundingBox(0, 0, 1001, 20),
        ).forEach { box ->
            val result = BaselineReviewReducer.reduce(
                state,
                BaselineReviewAction.AddManualObject(box),
            ) as BaselineReviewUpdate.Rejected
            assertEquals(BaselineReviewRejection.InvalidBoundingBox, result.reason)
            assertEquals(state, result.state)
        }
    }

    @Test
    fun neverSelectsMoreThanFiveObjects() {
        var state = stateWithObjects(5)
        state = updated(
            BaselineReviewReducer.reduce(
                state,
                BaselineReviewAction.ToggleSelection("object-5"),
            ),
        )
        state = updated(
            BaselineReviewReducer.reduce(
                state,
                BaselineReviewAction.AddManualObject(
                    NormalizedBoundingBox(10, 10, 20, 20),
                ),
            ),
        )

        val rejected = BaselineReviewReducer.reduce(
            state,
            BaselineReviewAction.ToggleSelection("object-5"),
        ) as BaselineReviewUpdate.Rejected

        assertEquals(BaselineReviewRejection.MaximumSelectedObjects, rejected.reason)
        assertEquals(5, rejected.state.selectedEntries.size)
    }

    @Test
    fun confirmationContainsOnlySelectedObjects() {
        val initial = stateWithObjects(2)
        val state = updated(
            BaselineReviewReducer.reduce(
                initial,
                BaselineReviewAction.ToggleSelection("object-1"),
            ),
        )

        val confirmation = BaselineReviewReducer.confirm(state) as
            BaselineReviewConfirmation.Confirmed

        assertEquals(listOf("object-2"), confirmation.objects.map { it.id })
    }

    private fun updated(result: BaselineReviewUpdate): BaselineReviewState =
        (result as BaselineReviewUpdate.Updated).state

    private fun stateWithObjects(count: Int): BaselineReviewState =
        BaselineReviewState.from(
            BaselineAnalysis(
                modelId = "gemini-test",
                repaired = false,
                objects = (1..count).map { index ->
                    BaselineObject(
                        id = "object-$index",
                        displayName = "物体$index",
                        appearanceFeatures = listOf("特徴$index"),
                        boundingBox = NormalizedBoundingBox(
                            yMin = index * 10,
                            xMin = index * 10,
                            yMax = index * 10 + 100,
                            xMax = index * 10 + 100,
                        ),
                        orientationImportant = false,
                        symmetry = ObjectSymmetry.None,
                    )
                },
                excludedCandidates = emptyList(),
            ),
        )
}
