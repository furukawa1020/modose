package com.modose.app.ar.render

import org.junit.Assert.assertEquals
import org.junit.Test

class CameraSurfaceControllerRegistryTest {
    @Test
    fun attachedControllerFollowsResumeAndPause() {
        val controller = FakeController()
        val registry = CameraSurfaceControllerRegistry()
        registry.replace(controller)

        registry.onActivityResume()
        registry.onActivityPause()

        assertEquals(listOf("resume", "pause"), controller.calls)
    }

    @Test
    fun replacingControllerReleasesOldAndResumesNew() {
        val first = FakeController()
        val second = FakeController()
        val registry = CameraSurfaceControllerRegistry()
        registry.replace(first)
        registry.onActivityResume()

        registry.replace(second)

        assertEquals(listOf("resume", "release"), first.calls)
        assertEquals(listOf("resume"), second.calls)
    }

    @Test
    fun repeatedAttachmentDoesNotDuplicateLifecycleCalls() {
        val controller = FakeController()
        val registry = CameraSurfaceControllerRegistry()
        registry.replace(controller)
        registry.replace(controller)
        registry.onActivityResume()
        registry.onActivityResume()

        assertEquals(listOf("resume"), controller.calls)
    }

    @Test
    fun destroyReleasesAndRejectsLateAttachment() {
        val first = FakeController()
        val late = FakeController()
        val registry = CameraSurfaceControllerRegistry()
        registry.replace(first)
        registry.onActivityResume()

        registry.onDestroy()
        registry.replace(late)

        assertEquals(listOf("resume", "pause", "release"), first.calls)
        assertEquals(listOf("release"), late.calls)
    }

    private class FakeController : CameraBackgroundSurfaceController {
        val calls = mutableListOf<String>()

        override fun onActivityResume() {
            calls += "resume"
        }

        override fun onActivityPause() {
            calls += "pause"
        }

        override fun releaseSurface() {
            calls += "release"
        }
    }
}
