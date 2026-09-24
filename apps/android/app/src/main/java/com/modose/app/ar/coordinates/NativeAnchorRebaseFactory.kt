package com.modose.app.ar.coordinates

import com.modose.app.ar.anchor.SceneAnchorPose
import com.modose.app.ar.anchor.SceneAnchorSnapshot
import com.modose.app.core.NativeAnchorRebase

/** Only the current tracking frame may supply current; anchor IDs never substitute for poses. */
internal object NativeAnchorRebaseFactory {
    fun create(saved: SceneAnchorSnapshot, current: SceneAnchorSnapshot): NativeAnchorRebase? {
        if (saved.id != current.id) return null
        return NativeAnchorRebase.create(saved.pose.values(), current.pose.values())
    }

    fun viewMatrix(rebase: NativeAnchorRebase, currentView: FloatArray): FloatArray? {
        val transformed = rebase.rebaseView(DoubleArray(currentView.size) { currentView[it].toDouble() })
            ?: return null
        val result = FloatArray(16) { transformed[it].toFloat() }
        return result.takeIf { values -> values.all { it.isFinite() } }
    }

    private fun SceneAnchorPose.values() = doubleArrayOf(
        translationX.toDouble(), translationY.toDouble(), translationZ.toDouble(),
        rotationX.toDouble(), rotationY.toDouble(), rotationZ.toDouble(), rotationW.toDouble(),
    )
}
