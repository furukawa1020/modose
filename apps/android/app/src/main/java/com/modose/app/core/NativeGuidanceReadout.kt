package com.modose.app.core

/** UI data only. No variant represents verified restoration. */
internal sealed interface NativeGuidanceReadout {
    data object Waiting : NativeGuidanceReadout
    data object Unavailable : NativeGuidanceReadout
    data object ConfirmationRequired : NativeGuidanceReadout
    data class Hidden(val reason: NativeFrameHiddenReason) : NativeGuidanceReadout
    data class Move(val objectId: Int, val distanceMeters: Double) : NativeGuidanceReadout
    data class NearTarget(val objectId: Int) : NativeGuidanceReadout
    data class OrientationRequired(val objectId: Int) : NativeGuidanceReadout
    data class Recover(val objectId: Int, val reason: NativeRecoveryReason) : NativeGuidanceReadout
}

/** Call each UI frame. Never retain a Ready presentation or extrapolate a distance. */
internal object NativeGuidanceReadoutReader {
    fun read(snapshot: NativeWorldFrameSnapshot?, nowMs: Long, savedIds: Set<Int>): NativeGuidanceReadout {
        if (nowMs < 0 || savedIds.size !in 1..5 || savedIds.any { it <= 0 }) {
            return NativeGuidanceReadout.Unavailable
        }
        if (snapshot == null) return NativeGuidanceReadout.Waiting
        val frame = when (val presentation = snapshot.presentationAt(nowMs)) {
            is NativeWorldFramePresentation.Hidden ->
                return NativeGuidanceReadout.Hidden(presentation.source.reason)
            NativeWorldFramePresentation.InvalidProjection -> return NativeGuidanceReadout.Unavailable
            is NativeWorldFramePresentation.Ready -> presentation
        }
        if (frame.state != CoreRestoreState.GUIDING) return NativeGuidanceReadout.ConfirmationRequired
        val guide = frame.guidance ?: return NativeGuidanceReadout.Waiting
        if (guide.objectId !in savedIds) return NativeGuidanceReadout.Unavailable
        return when (guide) {
            is NativeWorldGuidance.Move -> if (guide.distanceMeters.isFinite() && guide.distanceMeters >= 0.0) {
                NativeGuidanceReadout.Move(guide.objectId, guide.distanceMeters)
            } else NativeGuidanceReadout.Unavailable
            is NativeWorldGuidance.Ring -> NativeGuidanceReadout.NearTarget(guide.objectId)
            is NativeWorldGuidance.CheckOrientation -> NativeGuidanceReadout.OrientationRequired(guide.objectId)
            is NativeWorldGuidance.Recover -> NativeGuidanceReadout.Recover(guide.objectId, guide.reason)
        }
    }
}
