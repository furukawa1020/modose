package com.modose.app.flow.guidance

sealed interface SingleObjectGuidePresentation {
    data object Hidden : SingleObjectGuidePresentation

    data class PositionalGuide(
        val sceneObjectId: String,
        val location: GuideCandidateLocation,
        val targetPose: GuideTargetPose,
    ) : SingleObjectGuidePresentation

    data class MissingObjectGuide(
        val sceneObjectId: String,
        val showBaselineThumbnail: Boolean = true,
    ) : SingleObjectGuidePresentation

    data class AmbiguousObjectGuide(
        val sceneObjectId: String,
        val requestIdentityReconfirmation: Boolean = true,
    ) : SingleObjectGuidePresentation

    data class FrozenGuide(
        val sceneObjectId: String,
        val targetPose: GuideTargetPose,
        val reason: GuidePauseReason,
    ) : SingleObjectGuidePresentation

    data class VerificationCandidate(
        val completedObjectIds: List<String>,
    ) : SingleObjectGuidePresentation
}

object SingleObjectGuidePresentationMapper {
    fun map(state: SingleObjectGuideState): SingleObjectGuidePresentation =
        when (state) {
            SingleObjectGuideState.Idle -> SingleObjectGuidePresentation.Hidden
            is SingleObjectGuideState.ReadyForVerification ->
                SingleObjectGuidePresentation.VerificationCandidate(
                    completedObjectIds = state.completedObjectIds,
                )
            is SingleObjectGuideState.Paused ->
                SingleObjectGuidePresentation.FrozenGuide(
                    sceneObjectId = state.active.sceneObjectId,
                    targetPose = state.active.targetPose,
                    reason = state.reason,
                )
            is SingleObjectGuideState.Guiding -> state.active.toPresentation()
        }

    private fun ActiveGuideContent.toPresentation(): SingleObjectGuidePresentation =
        when (this) {
            is ActiveGuideContent.Positional ->
                SingleObjectGuidePresentation.PositionalGuide(
                    sceneObjectId = sceneObjectId,
                    location = location,
                    targetPose = targetPose,
                )
            is ActiveGuideContent.MissingObject ->
                SingleObjectGuidePresentation.MissingObjectGuide(
                    sceneObjectId = sceneObjectId,
                )
            is ActiveGuideContent.AmbiguousObject ->
                SingleObjectGuidePresentation.AmbiguousObjectGuide(
                    sceneObjectId = sceneObjectId,
                )
        }
}
