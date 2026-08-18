package com.modose.app.ar.session

import org.junit.Assert.assertEquals
import org.junit.Test

class ArSessionActivityCoordinatorTest {
    @Test
    fun startsOnlyAfterResumeAndArCoreReadiness() {
        val lifecycle = FakeLifecycle()
        val coordinator = ArSessionActivityCoordinator({ lifecycle }) {}

        coordinator.onResume()
        assertEquals(emptyList<String>(), lifecycle.calls)

        coordinator.onArCoreReady()
        assertEquals(listOf("create", "resume"), lifecycle.calls)
    }

    @Test
    fun pauseAndResumeReuseTheOwnedLifecycle() {
        val lifecycle = FakeLifecycle()
        var createCount = 0
        val coordinator = ArSessionActivityCoordinator(
            lifecycleFactory = {
                createCount += 1
                lifecycle
            },
            onResult = {},
        )
        coordinator.onArCoreReady()
        coordinator.onResume()
        coordinator.onPause()
        coordinator.onResume()

        assertEquals(1, createCount)
        assertEquals(listOf("create", "resume", "pause", "create", "resume"), lifecycle.calls)
    }

    @Test
    fun unavailablePrerequisiteClosesOwnedLifecycle() {
        val lifecycle = FakeLifecycle()
        val coordinator = ArSessionActivityCoordinator({ lifecycle }) {}
        coordinator.onArCoreReady()
        coordinator.onResume()

        coordinator.onPrerequisiteUnavailable()

        assertEquals("close", lifecycle.calls.last())
    }

    @Test
    fun destroyClosesAndPreventsRecreation() {
        val lifecycle = FakeLifecycle()
        var createCount = 0
        val coordinator = ArSessionActivityCoordinator(
            lifecycleFactory = {
                createCount += 1
                lifecycle
            },
            onResult = {},
        )
        coordinator.onArCoreReady()
        coordinator.onResume()
        coordinator.onDestroy()
        coordinator.onArCoreReady()
        coordinator.onResume()

        assertEquals(1, createCount)
        assertEquals("close", lifecycle.calls.last())
    }

    private class FakeLifecycle : ArSessionLifecycle {
        val calls = mutableListOf<String>()
        override var phase = ArSessionPhase.Empty

        override fun create(): ArSessionResult {
            calls += "create"
            if (phase == ArSessionPhase.Empty) phase = ArSessionPhase.Created
            return ArSessionResult.Applied(phase)
        }

        override fun resume(): ArSessionResult {
            calls += "resume"
            phase = ArSessionPhase.Resumed
            return ArSessionResult.Applied(phase)
        }

        override fun pause(): ArSessionResult {
            calls += "pause"
            phase = ArSessionPhase.Paused
            return ArSessionResult.Applied(phase)
        }

        override fun close(): ArSessionResult {
            calls += "close"
            phase = ArSessionPhase.Closed
            return ArSessionResult.Applied(phase)
        }
    }
}
