package com.modose.app.ar.plane

data class HorizontalPlaneCandidate(
    val id: Long,
    val distanceMeters: Float,
    val extentXMeters: Float,
    val extentZMeters: Float,
    val isUpwardFacing: Boolean,
    val isTracking: Boolean,
    val isSubsumed: Boolean,
    val containsCenterHit: Boolean,
)

data class SelectedHorizontalPlane(
    val id: Long,
    val distanceMeters: Float,
    val extentXMeters: Float,
    val extentZMeters: Float,
)

sealed interface HorizontalPlaneState {
    data object Searching : HorizontalPlaneState
    data class Tracking(val plane: SelectedHorizontalPlane) : HorizontalPlaneState
    data class Lost(val previousPlaneId: Long) : HorizontalPlaneState
}

object HorizontalPlaneSelectionPolicy {
    fun select(candidates: List<HorizontalPlaneCandidate>): SelectedHorizontalPlane? = candidates
        .asSequence()
            .filter { candidate -> candidate.isValid() }
        .minByOrNull(HorizontalPlaneCandidate::distanceMeters)
        ?.let { candidate ->
            SelectedHorizontalPlane(
                id = candidate.id,
                distanceMeters = candidate.distanceMeters,
                extentXMeters = candidate.extentXMeters,
                extentZMeters = candidate.extentZMeters,
            )
        }

    private fun HorizontalPlaneCandidate.isValid(): Boolean =
        isUpwardFacing &&
            isTracking &&
            !isSubsumed &&
            containsCenterHit &&
            distanceMeters.isFinite() &&
            distanceMeters >= 0f &&
            extentXMeters.isFinite() &&
            extentXMeters > 0f &&
            extentZMeters.isFinite() &&
            extentZMeters > 0f
}
