package com.modose.app.flow.guidance.rotation

import com.modose.app.flow.guidance.SingleObjectGuidePresentation
import com.modose.app.flow.guidance.SingleObjectGuidePresentationMapper
import com.modose.app.flow.guidance.SingleObjectGuideState

data class RotationAwareGuidePresentation(
    val base: SingleObjectGuidePresentation,
    val rotation: RotationGuideDecision?,
)

sealed interface RotationPresentationResult {
    data class Presented(val value: RotationAwareGuidePresentation) :
        RotationPresentationResult

    data class Rejected(val failure: RotationPresentationFailure) :
        RotationPresentationResult
}

sealed interface RotationPresentationFailure {
    data object ActiveObjectMismatch : RotationPresentationFailure
    data class InvalidRotation(val reason: RotationGuideFailure) :
        RotationPresentationFailure
}

object RotationAwareGuidePresentationMapper {
    fun map(
        state: SingleObjectGuideState,
        rotationInput: RotationGuideInput?,
    ): RotationPresentationResult {
        val base = SingleObjectGuidePresentationMapper.map(state)
        if (base !is SingleObjectGuidePresentation.PositionalGuide) {
            return RotationPresentationResult.Presented(
                RotationAwareGuidePresentation(base = base, rotation = null),
            )
        }
        if (rotationInput == null) {
            return RotationPresentationResult.Presented(
                RotationAwareGuidePresentation(base = base, rotation = null),
            )
        }
        if (base.sceneObjectId != rotationInput.sceneObjectId) {
            return RotationPresentationResult.Rejected(
                RotationPresentationFailure.ActiveObjectMismatch,
            )
        }

        return when (
            val calculated = ShortestRotationGuideCalculator.calculate(rotationInput)
        ) {
            is RotationGuideResult.Rejected ->
                RotationPresentationResult.Rejected(
                    RotationPresentationFailure.InvalidRotation(calculated.reason),
                )
            is RotationGuideResult.Decided ->
                RotationPresentationResult.Presented(
                    RotationAwareGuidePresentation(
                        base = base,
                        rotation = calculated.decision,
                    ),
                )
        }
    }
}
