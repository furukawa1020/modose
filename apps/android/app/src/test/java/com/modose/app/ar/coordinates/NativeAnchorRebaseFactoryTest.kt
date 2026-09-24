package com.modose.app.ar.coordinates

import com.modose.app.ar.anchor.SceneAnchorPose
import com.modose.app.ar.anchor.SceneAnchorSnapshot
import org.junit.Assert.*
import org.junit.Test

class NativeAnchorRebaseFactoryTest {
    private fun anchor(id: Long = 7, x: Float = 0f, w: Float = 1f) =
        SceneAnchorSnapshot(id, SceneAnchorPose(x, 0f, 0f, 0f, 0f, 0f, w))
    private fun identity() = FloatArray(16) { if (it % 5 == 0) 1f else 0f }

    @Test fun anotherAnchorIsRejectedEvenWithTheSamePose() {
        assertNull(NativeAnchorRebaseFactory.create(anchor(), anchor(id = 8)))
    }

    @Test fun sameAnchorMayHaveAnUpdatedPose() {
        val rebase = NativeAnchorRebaseFactory.create(anchor(), anchor(x = 10f))!!
        val currentView = identity().apply { this[12] = -10f }
        assertArrayEquals(identity(), NativeAnchorRebaseFactory.viewMatrix(rebase, currentView)!!, 1e-5f)
    }

    @Test fun invalidPoseAndViewNeverProduceRenderMatrices() {
        assertNull(NativeAnchorRebaseFactory.create(anchor(), anchor(x = Float.NaN)))
        assertNull(NativeAnchorRebaseFactory.create(anchor(), anchor(w = 0f)))
        val rebase = NativeAnchorRebaseFactory.create(anchor(), anchor())!!
        assertNull(NativeAnchorRebaseFactory.viewMatrix(rebase, FloatArray(15)))
        assertNull(NativeAnchorRebaseFactory.viewMatrix(rebase, identity().apply { this[0] = Float.NaN }))
    }

    @Test fun finiteDoubleValuesThatOverflowFloatAreRejected() {
        val rebase = NativeAnchorRebaseFactory.create(anchor(x = -Float.MAX_VALUE),
            anchor(x = Float.MAX_VALUE))!!
        assertNull(NativeAnchorRebaseFactory.viewMatrix(rebase, identity()))
    }
}
