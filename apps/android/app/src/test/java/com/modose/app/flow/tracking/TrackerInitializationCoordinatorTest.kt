package com.modose.app.flow.tracking

import com.modose.app.flow.compare.CompareBoundingBox
import com.modose.app.flow.compare.CurrentObjectState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackerInitializationCoordinatorTest {
    @Test
    fun combinesDirectEmbeddingStaticMissingAndAmbiguousAssignments() {
        val input = TrackerInitializationInput(
            objects = listOf(
                objectInput("a", box(0, 0, 100, 100)),
                objectInput("b", box(300, 300, 400, 400)),
                objectInput("missing", null, CurrentObjectState.Missing),
                objectInput("ambiguous", null, CurrentObjectState.Ambiguous),
            ),
            detections = listOf(
                detection(10, box(0, 0, 100, 100)),
                detection(20, box(700, 700, 800, 800)),
            ),
        )

        val result = TrackerInitializationCoordinator.initialize(
            input,
            listOf(EmbeddingReidentificationCandidate("b", 20, 0.90)),
        ) as CoordinatedTrackerInitializationResult.Initialized

        val assignments = result.plan.assignments.associateBy { it.sceneObjectId }
        assertEquals(
            TrackerAssignmentReason.BoundingBoxMatch,
            (assignments.getValue("a") as TrackerObjectAssignment.Trackable).reason,
        )
        assertEquals(
            TrackerAssignmentReason.EmbeddingFallback,
            (assignments.getValue("b") as TrackerObjectAssignment.Trackable).reason,
        )
        assertTrue(assignments.getValue("missing") is TrackerObjectAssignment.Missing)
        assertTrue(assignments.getValue("ambiguous") is TrackerObjectAssignment.Ambiguous)
    }

    @Test
    fun resultDoesNotDependOnInputOrder() {
        val objects = listOf(
            objectInput("a", box(0, 0, 100, 100)),
            objectInput("wallet", box(300, 300, 400, 400)),
        )
        val detections = listOf(
            detection(1, box(0, 0, 100, 100)),
            detection(2, box(700, 700, 800, 800)),
        )
        val candidates = listOf(
            EmbeddingReidentificationCandidate("wallet", 2, 0.91),
        )

        val first = initialized(
            TrackerInitializationInput(objects, detections),
            candidates,
        )
        val reversed = initialized(
            TrackerInitializationInput(objects.reversed(), detections.reversed()),
            candidates.reversed(),
        )

        assertEquals(first, reversed)
    }

    @Test
    fun belowThresholdCandidateRemainsStaticGuide() {
        val input = TrackerInitializationInput(
            objects = listOf(objectInput("wallet", box(0, 0, 100, 100))),
            detections = listOf(detection(2, box(700, 700, 800, 800))),
        )

        val plan = initialized(
            input,
            listOf(EmbeddingReidentificationCandidate("wallet", 2, 0.799)),
        )

        assertTrue(plan.assignments.single() is TrackerObjectAssignment.StaticGuide)
    }

    @Test
    fun competingObjectsCannotShareTrackingId() {
        val input = TrackerInitializationInput(
            objects = listOf(
                objectInput("a", box(0, 0, 100, 100)),
                objectInput("b", box(300, 300, 400, 400)),
            ),
            detections = listOf(detection(7, box(700, 700, 900, 900))),
        )

        val plan = initialized(
            input,
            listOf(
                EmbeddingReidentificationCandidate("b", 7, 0.90),
                EmbeddingReidentificationCandidate("a", 7, 0.91),
            ),
        )

        assertEquals(listOf(7), plan.trackable.map { it.trackingId })
        assertEquals("a", plan.trackable.single().sceneObjectId)
        assertEquals(listOf("b"), plan.staticGuides.map { it.sceneObjectId })
    }

    @Test
    fun duplicateDetectionTrackingIdsAreRejected() {
        val input = TrackerInitializationInput(
            objects = listOf(objectInput("a", box(0, 0, 100, 100))),
            detections = listOf(
                detection(7, box(0, 0, 100, 100)),
                detection(7, box(300, 300, 400, 400)),
            ),
        )

        assertEquals(
            CoordinatedTrackerInitializationResult.InputRejected(
                TrackerInitializationFailure.DuplicateTrackingId,
            ),
            TrackerInitializationCoordinator.initialize(input, emptyList()),
        )
    }

    private fun initialized(
        input: TrackerInitializationInput,
        candidates: List<EmbeddingReidentificationCandidate>,
    ): TrackerInitializationPlan =
        (TrackerInitializationCoordinator.initialize(
            input,
            candidates,
        ) as CoordinatedTrackerInitializationResult.Initialized).plan

    private fun objectInput(
        id: String,
        boundingBox: CompareBoundingBox?,
        state: CurrentObjectState = CurrentObjectState.Moved,
    ) = TrackerInitializationObject(
        sceneObjectId = id,
        state = state,
        currentBoundingBox = boundingBox,
        sameObjectConfidence = 0.95,
    )

    private fun detection(
        id: Int,
        boundingBox: CompareBoundingBox,
    ) = TrackerDetectionCandidate(
        trackingId = id,
        boundingBox = boundingBox,
        detectionConfidence = 0.95,
    )

    private fun box(
        yMin: Int,
        xMin: Int,
        yMax: Int,
        xMax: Int,
    ) = CompareBoundingBox(yMin, xMin, yMax, xMax)
}
