package com.modose.app.ar.render

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraBackgroundDrawPolicyTest {
    @Test
    fun drawsOnlyInitializedPositiveTimestampFrameWithCoordinates() {
        assertTrue(
            CameraBackgroundDrawPolicy.canDraw(
                initialized = true,
                hasTextureCoordinates = true,
                timestampNanos = 1L,
            ),
        )
    }

    @Test
    fun skipsTimestampZeroAndIncompleteState() {
        assertFalse(CameraBackgroundDrawPolicy.canDraw(true, true, 0L))
        assertFalse(CameraBackgroundDrawPolicy.canDraw(false, true, 1L))
        assertFalse(CameraBackgroundDrawPolicy.canDraw(true, false, 1L))
    }
}
