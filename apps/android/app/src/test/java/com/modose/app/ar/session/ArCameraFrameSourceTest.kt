package com.modose.app.ar.session

import org.junit.Assert.assertEquals
import org.junit.Test

class ArCameraFrameSourceTest {
    @Test
    fun requiresResumedSessionBeforeTextureBinding() {
        val lifecycle = ArCoreSessionLifecycle { FrameRuntime() }
        lifecycle.create()

        assertEquals(
            ArCameraFrameResult.Rejected(ArCameraFrameFailureReason.SessionNotResumed),
            lifecycle.bindCameraTexture(7),
        )
    }

    @Test
    fun bindsSameTextureOnlyOnce() {
        val runtime = FrameRuntime()
        val lifecycle = resumedLifecycle(runtime)

        lifecycle.bindCameraTexture(7)
        lifecycle.bindCameraTexture(7)

        assertEquals(listOf(7), runtime.boundTextureIds)
    }

    @Test
    fun updatesFrameWithSurfaceGeometry() {
        val runtime = FrameRuntime()
        val lifecycle = resumedLifecycle(runtime)
        lifecycle.bindCameraTexture(7)

        assertEquals(
            ArCameraFrameResult.Updated(ArCameraFrame(42L, null)),
            lifecycle.updateCameraFrame(displayRotation = 0, widthPx = 1080, heightPx = 1920),
        )
        assertEquals(Triple(0, 1080, 1920), runtime.lastGeometry)
    }

    @Test
    fun rejectsInvalidSurfaceSize() {
        val lifecycle = resumedLifecycle(FrameRuntime())
        lifecycle.bindCameraTexture(7)

        assertEquals(
            ArCameraFrameResult.Rejected(ArCameraFrameFailureReason.InvalidSurfaceSize),
            lifecycle.updateCameraFrame(displayRotation = 0, widthPx = 0, heightPx = 1920),
        )
    }

    private fun resumedLifecycle(runtime: FrameRuntime): ArCoreSessionLifecycle =
        ArCoreSessionLifecycle { runtime }.also {
            it.create()
            it.resume()
        }

    private class FrameRuntime : ArSessionRuntime {
        val boundTextureIds = mutableListOf<Int>()
        var lastGeometry: Triple<Int, Int, Int>? = null

        override fun resume() = Unit
        override fun pause() = Unit
        override fun close() = Unit

        override fun bindCameraTexture(textureId: Int) {
            boundTextureIds += textureId
        }

        override fun updateCameraFrame(
            displayRotation: Int,
            widthPx: Int,
            heightPx: Int,
        ): ArCameraFrame {
            lastGeometry = Triple(displayRotation, widthPx, heightPx)
            return ArCameraFrame(timestampNanos = 42L, transformedTextureCoordinates = null)
        }
    }
}
