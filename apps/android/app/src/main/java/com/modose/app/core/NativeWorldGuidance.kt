package com.modose.app.core

/** World coordinates use exactly the basis supplied to the Rust session. */
internal data class NativeWorldTarget(
    val position: CoreVector,
    val xAxis: CoreVector,
    val zAxis: CoreVector,
)

internal sealed interface NativeWorldGuidance {
    val objectId: Int

    data class Move(
        override val objectId: Int,
        val from: CoreVector,
        val to: CoreVector,
        val distanceMeters: Double,
    ) : NativeWorldGuidance

    data class Ring(
        override val objectId: Int,
        val target: NativeWorldTarget,
    ) : NativeWorldGuidance

    data class CheckOrientation(
        override val objectId: Int,
        val target: NativeWorldTarget,
    ) : NativeWorldGuidance

    data class Recover(
        override val objectId: Int,
        val reason: NativeRecoveryReason,
    ) : NativeWorldGuidance
}

internal sealed interface NativeWorldFramePresentation {
    data class Ready(
        val state: CoreRestoreState,
        val guidance: NativeWorldGuidance?,
    ) : NativeWorldFramePresentation

    data class Hidden(val source: NativeFramePresentation.Hidden) : NativeWorldFramePresentation
    data object InvalidProjection : NativeWorldFramePresentation
}

/**
 * Rechecks the underlying lease on every draw. Do not cache Ready independently.
 * Does not apply a later ARCore anchor pose or change the core's distance/state.
 */
internal class NativeWorldFrameSnapshot internal constructor(
    private val source: NativeFrameSnapshot,
    private val basis: NativeWorldBasis,
) {
    fun presentationAt(nowMs: Long): NativeWorldFramePresentation {
        return when (val frame = source.presentationAt(nowMs)) {
            is NativeFramePresentation.Hidden -> NativeWorldFramePresentation.Hidden(frame)
            is NativeFramePresentation.Ready -> {
                val guide = frame.guidance
                if (guide == null) {
                    NativeWorldFramePresentation.Ready(frame.state, null)
                } else {
                    val projected = basis.project(guide)
                        ?: return NativeWorldFramePresentation.InvalidProjection
                    NativeWorldFramePresentation.Ready(frame.state, projected)
                }
            }
        }
    }
}

/** Immutable copy; Rust validates the full plane before the owner is published. */
internal class NativeWorldBasis private constructor(
    private val origin: CoreVector,
    private val xAxis: CoreVector,
    private val zAxis: CoreVector,
) {
    fun project(guide: NativeGuidance): NativeWorldGuidance? {
        if (guide.objectId <= 0) return null
        return when (guide) {
            is NativeGuidance.Move -> {
                if (!guide.distanceMeters.isFinite() || guide.distanceMeters < 0.0) return null
                NativeWorldGuidance.Move(
                    guide.objectId, point(guide.from) ?: return null,
                    point(guide.to) ?: return null, guide.distanceMeters,
                )
            }
            is NativeGuidance.Ring ->
                NativeWorldGuidance.Ring(guide.objectId, target(guide.target) ?: return null)
            is NativeGuidance.CheckOrientation ->
                NativeWorldGuidance.CheckOrientation(guide.objectId, target(guide.target) ?: return null)
            is NativeGuidance.Recover ->
                NativeWorldGuidance.Recover(guide.objectId, guide.reason)
        }
    }

    private fun target(position: NativeTablePosition): NativeWorldTarget? =
        point(position)?.let { NativeWorldTarget(it, xAxis, zAxis) }

    private fun point(position: NativeTablePosition): CoreVector? = CoreVector(
        origin.x + xAxis.x * position.x + zAxis.x * position.z,
        origin.y + xAxis.y * position.x + zAxis.y * position.z,
        origin.z + xAxis.z * position.x + zAxis.z * position.z,
    ).takeIf { it.isFinite() }

    companion object {
        fun fromGeometry(geometry: DoubleArray): NativeWorldBasis {
            require(geometry.size >= 9 && (0..8).all { geometry[it].isFinite() }) {
                "Invalid world basis"
            }
            fun vector(offset: Int) = CoreVector(
                geometry[offset], geometry[offset + 1], geometry[offset + 2],
            )
            return NativeWorldBasis(vector(0), vector(3), vector(6))
        }
    }
}
