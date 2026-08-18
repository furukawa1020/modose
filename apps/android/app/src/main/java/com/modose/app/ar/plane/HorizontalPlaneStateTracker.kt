package com.modose.app.ar.plane

class HorizontalPlaneStateTracker {
    private var selectedPlaneId: Long? = null

    fun update(selected: SelectedHorizontalPlane?): HorizontalPlaneState {
        if (selected != null) {
            selectedPlaneId = selected.id
            return HorizontalPlaneState.Tracking(selected)
        }

        val previousId = selectedPlaneId
        selectedPlaneId = null
        return if (previousId == null) {
            HorizontalPlaneState.Searching
        } else {
            HorizontalPlaneState.Lost(previousId)
        }
    }

    fun reset() {
        selectedPlaneId = null
    }
}
