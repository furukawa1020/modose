package com.modose.app.flow.guidance

import com.modose.app.flow.compare.CompareBoundingBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SingleObjectGuidePlannerTest {
    @Test
    fun occludingPositionalObjectWinsBeforeConfidenceDistanceAndMissing() {
        val candidates = listOf(
            candidate(
                id = "missing",
                location = GuideCandidateLocation.Missing,
                confidence = 1.0,
                distance = null,
            ),
            candidate(id = "near", confidence = 0.99, distance = 0.01),
            candidate(
                id = "occluder",
                confidence = 0.20,
                distance = 1.0,
                occludes = setOf("near"),
            ),
        )

        val state = accepted(SingleObjectGuidePlanner.start(candidates))
        assertEquals("occluder", (state as SingleObjectGuideState.Guiding).active.sceneObjectId)
        assertEquals(listOf("near", "missing"), state.remainingObjectIds)
    }

    @Test
    fun selectionDoesNotDependOnInputOrder() {
        val candidates = listOf(
            candidate(id = "b", confidence = 0.80, distance = 0.20),
            candidate(id = "a", confidence = 0.80, distance = 0.20),
            candidate(
                id = "ambiguous",
                location = GuideCandidateLocation.Ambiguous,
                confidence = 1.0,
                distance = null,
            ),
        )

        assertEquals(
            accepted(SingleObjectGuidePlanner.start(candidates)),
            accepted(SingleObjectGuidePlanner.start(candidates.reversed())),
        )
    }

    @Test
    fun onlyActiveObjectCanCompleteAndLastCompletionEnablesVerification() {
        val session = SingleObjectGuideSession(
            listOf(
                candidate(id = "first", confidence = 0.9, distance = 0.1),
                candidate(id = "second", confidence = 0.8, distance = 0.2),
            ),
        )
        session.start()

        assertEquals(
            GuideProgressionResult.Rejected(
                GuideProgressionFailure.ActiveObjectMismatch,
            ),
            session.markActiveCompleted("second"),
        )
        assertEquals(
            "first",
            (session.state as SingleObjectGuideState.Guiding).active.sceneObjectId,
        )

        val second = session.markActiveCompleted("first") as GuideProgressionResult.Updated
        assertEquals(
            "second",
            (second.state as SingleObjectGuideState.Guiding).active.sceneObjectId,
        )

        val finished = session.markActiveCompleted("second") as GuideProgressionResult.Updated
        assertEquals(
            listOf("first", "second"),
            (finished.state as SingleObjectGuideState.ReadyForVerification).completedObjectIds,
        )
    }

    @Test
    fun missingAndAmbiguousNeverProducePositionalPresentation() {
        val missing = SingleObjectGuidePresentationMapper.map(
            SingleObjectGuideState.Guiding(
                active = ActiveGuideContent.MissingObject("missing"),
                remainingObjectIds = emptyList(),
            ),
        )
        val ambiguous = SingleObjectGuidePresentationMapper.map(
            SingleObjectGuideState.Guiding(
                active = ActiveGuideContent.AmbiguousObject("ambiguous"),
                remainingObjectIds = emptyList(),
            ),
        )

        assertTrue(missing is SingleObjectGuidePresentation.MissingObjectGuide)
        assertTrue(ambiguous is SingleObjectGuidePresentation.AmbiguousObjectGuide)
        assertTrue(missing !is SingleObjectGuidePresentation.PositionalGuide)
        assertTrue(ambiguous !is SingleObjectGuidePresentation.PositionalGuide)
    }

    @Test
    fun invalidPoseAndUnknownOcclusionAreRejected() {
        assertEquals(
            SingleObjectGuideResult.Rejected(
                SingleObjectGuideFailure.PositionalTargetMissing,
            ),
            SingleObjectGuidePlanner.start(
                listOf(candidate(id = "a", targetPose = null)),
            ),
        )
        assertEquals(
            SingleObjectGuideResult.Rejected(
                SingleObjectGuideFailure.UnknownOcclusionReference,
            ),
            SingleObjectGuidePlanner.start(
                listOf(candidate(id = "a", occludes = setOf("unknown"))),
            ),
        )
    }

    private fun accepted(result: SingleObjectGuideResult): SingleObjectGuideState =
        (result as SingleObjectGuideResult.Accepted).state

    private fun candidate(
        id: String,
        location: GuideCandidateLocation = GuideCandidateLocation.Static(BOX),
        targetPose: GuideTargetPose? =
            if (location is GuideCandidateLocation.Missing ||
                location is GuideCandidateLocation.Ambiguous
            ) {
                null
            } else {
                GuideTargetPose(0.2, 0.3, null)
            },
        confidence: Double = 0.9,
        distance: Double? = 0.1,
        occludes: Set<String> = emptySet(),
    ) = SingleObjectGuideCandidate(
        sceneObjectId = id,
        location = location,
        targetPose = targetPose,
        occludesObjectIds = occludes,
        correspondenceConfidence = confidence,
        distanceToTargetMeters = distance,
    )

    private companion object {
        val BOX = CompareBoundingBox(
            yMin = 100,
            xMin = 100,
            yMax = 300,
            xMax = 300,
        )
    }
}
