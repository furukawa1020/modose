package com.modose.app.ui.review

import com.modose.app.network.baseline.BaselineAnalysis
import com.modose.app.network.baseline.BaselineObject
import com.modose.app.network.baseline.NormalizedBoundingBox
import com.modose.app.network.baseline.ObjectSymmetry

enum class ReviewObjectSource {
    Detected,
    Manual,
}

data class BaselineReviewEntry(
    val number: Int,
    val objectValue: BaselineObject,
    val source: ReviewObjectSource,
    val selected: Boolean,
)

data class BaselineReviewState(
    val entries: List<BaselineReviewEntry>,
) {
    val selectedEntries: List<BaselineReviewEntry>
        get() = entries.filter(BaselineReviewEntry::selected)

    val canConfirm: Boolean
        get() = selectedEntries.size in 1..MAX_CONFIRMED_OBJECTS

    val canAddManualObject: Boolean
        get() = entries.none { it.source == ReviewObjectSource.Manual } &&
            selectedEntries.size < MAX_CONFIRMED_OBJECTS

    companion object {
        const val MAX_CONFIRMED_OBJECTS = 5

        fun from(analysis: BaselineAnalysis): BaselineReviewState =
            BaselineReviewState(
                entries = analysis.objects.mapIndexed { index, objectValue ->
                    BaselineReviewEntry(
                        number = index + 1,
                        objectValue = objectValue,
                        source = ReviewObjectSource.Detected,
                        selected = true,
                    )
                },
            )
    }
}

sealed interface BaselineReviewAction {
    data class ToggleSelection(val objectId: String) : BaselineReviewAction
    data class AddManualObject(val boundingBox: NormalizedBoundingBox) : BaselineReviewAction
    data object RemoveManualObject : BaselineReviewAction
}

enum class BaselineReviewRejection {
    ObjectNotFound,
    MaximumSelectedObjects,
    ManualObjectAlreadyExists,
    ManualObjectNotFound,
    InvalidBoundingBox,
}

sealed interface BaselineReviewUpdate {
    data class Updated(val state: BaselineReviewState) : BaselineReviewUpdate
    data class Rejected(
        val state: BaselineReviewState,
        val reason: BaselineReviewRejection,
    ) : BaselineReviewUpdate
}

sealed interface BaselineReviewConfirmation {
    data class Confirmed(val objects: List<BaselineObject>) : BaselineReviewConfirmation
    data object NoSelectedObjects : BaselineReviewConfirmation
}

object BaselineReviewReducer {
    fun reduce(
        state: BaselineReviewState,
        action: BaselineReviewAction,
    ): BaselineReviewUpdate = when (action) {
        is BaselineReviewAction.ToggleSelection -> toggle(state, action.objectId)
        is BaselineReviewAction.AddManualObject -> addManual(state, action.boundingBox)
        BaselineReviewAction.RemoveManualObject -> removeManual(state)
    }

    fun confirm(state: BaselineReviewState): BaselineReviewConfirmation {
        if (!state.canConfirm) {
            return BaselineReviewConfirmation.NoSelectedObjects
        }
        return BaselineReviewConfirmation.Confirmed(
            state.selectedEntries.map { it.objectValue },
        )
    }

    private fun toggle(
        state: BaselineReviewState,
        objectId: String,
    ): BaselineReviewUpdate {
        val index = state.entries.indexOfFirst { it.objectValue.id == objectId }
        if (index < 0) {
            return rejected(state, BaselineReviewRejection.ObjectNotFound)
        }
        val current = state.entries[index]
        if (!current.selected &&
            state.selectedEntries.size >= BaselineReviewState.MAX_CONFIRMED_OBJECTS
        ) {
            return rejected(state, BaselineReviewRejection.MaximumSelectedObjects)
        }
        val updated = state.entries.mapIndexed { entryIndex, entry ->
            if (entryIndex == index) entry.copy(selected = !entry.selected) else entry
        }
        return BaselineReviewUpdate.Updated(state.copy(entries = updated))
    }

    private fun addManual(
        state: BaselineReviewState,
        boundingBox: NormalizedBoundingBox,
    ): BaselineReviewUpdate {
        if (state.entries.any { it.source == ReviewObjectSource.Manual }) {
            return rejected(state, BaselineReviewRejection.ManualObjectAlreadyExists)
        }
        if (state.selectedEntries.size >= BaselineReviewState.MAX_CONFIRMED_OBJECTS) {
            return rejected(state, BaselineReviewRejection.MaximumSelectedObjects)
        }
        if (!boundingBox.isValid()) {
            return rejected(state, BaselineReviewRejection.InvalidBoundingBox)
        }

        val manual = BaselineObject(
            id = nextManualId(state),
            displayName = "手動追加",
            appearanceFeatures = listOf("ユーザーが画像上で選択"),
            boundingBox = boundingBox,
            orientationImportant = false,
            symmetry = ObjectSymmetry.None,
        )
        val entry = BaselineReviewEntry(
            number = state.entries.size + 1,
            objectValue = manual,
            source = ReviewObjectSource.Manual,
            selected = true,
        )
        return BaselineReviewUpdate.Updated(
            state.copy(entries = state.entries + entry),
        )
    }

    private fun removeManual(state: BaselineReviewState): BaselineReviewUpdate {
        val index = state.entries.indexOfFirst { it.source == ReviewObjectSource.Manual }
        if (index < 0) {
            return rejected(state, BaselineReviewRejection.ManualObjectNotFound)
        }
        return BaselineReviewUpdate.Updated(
            state.copy(
                entries = state.entries
                    .filterIndexed { entryIndex, _ -> entryIndex != index }
                    .mapIndexed { entryIndex, entry -> entry.copy(number = entryIndex + 1) },
            ),
        )
    }

    private fun NormalizedBoundingBox.isValid(): Boolean =
        yMin in 0..1000 &&
            xMin in 0..1000 &&
            yMax in 0..1000 &&
            xMax in 0..1000 &&
            yMin < yMax &&
            xMin < xMax

    private fun nextManualId(state: BaselineReviewState): String {
        val existing = state.entries.mapTo(mutableSetOf()) { it.objectValue.id }
        return generateSequence(1) { it + 1 }
            .map { "manual-$it" }
            .first { it !in existing }
    }

    private fun rejected(
        state: BaselineReviewState,
        reason: BaselineReviewRejection,
    ) = BaselineReviewUpdate.Rejected(state, reason)
}
