package com.modose.app.flow.guidance

object SingleObjectGuidePlanner {
    fun start(candidates: List<SingleObjectGuideCandidate>): SingleObjectGuideResult {
        validate(candidates)?.let {
            return SingleObjectGuideResult.Rejected(it)
        }

        val ordered = candidates.sortedWith(
            compareBy<SingleObjectGuideCandidate> { priorityGroup(it) }
                .thenByDescending { it.correspondenceConfidence }
                .thenBy { it.distanceToTargetMeters ?: Double.MAX_VALUE }
                .thenBy { it.sceneObjectId },
        )
        val selected = ordered.first()
        return SingleObjectGuideResult.Accepted(
            SingleObjectGuideState.Guiding(
                active = selected.toActiveContent(),
                remainingObjectIds = ordered.drop(1).map { it.sceneObjectId },
            ),
        )
    }

    private fun validate(
        candidates: List<SingleObjectGuideCandidate>,
    ): SingleObjectGuideFailure? {
        if (candidates.isEmpty()) {
            return SingleObjectGuideFailure.EmptyScene
        }
        if (candidates.size > SingleObjectGuideContract.MAX_OBJECTS) {
            return SingleObjectGuideFailure.TooManyObjects
        }

        val objectIds = candidates.map { it.sceneObjectId }
        if (
            objectIds.any { it.isBlank() } ||
            objectIds.distinct().size != objectIds.size
        ) {
            return SingleObjectGuideFailure.DuplicateSceneObjectId
        }
        if (candidates.any { it.correspondenceConfidence !in 0.0..1.0 }) {
            return SingleObjectGuideFailure.InvalidConfidence
        }
        if (
            candidates.any {
                it.distanceToTargetMeters?.let { distance ->
                    !distance.isFinite() || distance < 0.0
                } == true
            }
        ) {
            return SingleObjectGuideFailure.InvalidDistance
        }
        if (
            candidates.any {
                it.targetPose?.let { pose ->
                    !pose.xMeters.isFinite() ||
                        !pose.zMeters.isFinite() ||
                        pose.yawRadians?.isFinite() == false
                } == true
            }
        ) {
            return SingleObjectGuideFailure.InvalidTargetPose
        }

        candidates.forEach { candidate ->
            when (candidate.location) {
                is GuideCandidateLocation.Tracked,
                is GuideCandidateLocation.Static,
                -> if (candidate.targetPose == null) {
                    return SingleObjectGuideFailure.PositionalTargetMissing
                }
                GuideCandidateLocation.Missing,
                GuideCandidateLocation.Ambiguous,
                -> if (candidate.targetPose != null) {
                    return SingleObjectGuideFailure.NonPositionalTargetHasPose
                }
            }
        }

        val knownIds = objectIds.toSet()
        if (
            candidates.any { candidate ->
                candidate.occludesObjectIds.any {
                    it !in knownIds || it == candidate.sceneObjectId
                }
            }
        ) {
            return SingleObjectGuideFailure.UnknownOcclusionReference
        }
        return null
    }

    private fun priorityGroup(candidate: SingleObjectGuideCandidate): Int =
        when (candidate.location) {
            GuideCandidateLocation.Missing,
            GuideCandidateLocation.Ambiguous,
            -> 2
            else -> if (candidate.occludesObjectIds.isNotEmpty()) 0 else 1
        }

    private fun SingleObjectGuideCandidate.toActiveContent(): ActiveGuideContent =
        when (location) {
            is GuideCandidateLocation.Tracked,
            is GuideCandidateLocation.Static,
            -> ActiveGuideContent.Positional(
                sceneObjectId = sceneObjectId,
                location = location,
                targetPose = requireNotNull(targetPose),
            )
            GuideCandidateLocation.Missing ->
                ActiveGuideContent.MissingObject(sceneObjectId)
            GuideCandidateLocation.Ambiguous ->
                ActiveGuideContent.AmbiguousObject(sceneObjectId)
        }
}
