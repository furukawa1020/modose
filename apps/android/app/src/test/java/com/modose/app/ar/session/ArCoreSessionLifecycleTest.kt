package com.modose.app.ar.session

import com.google.ar.core.exceptions.CameraNotAvailableException
import org.junit.Assert.assertEquals
import org.junit.Test

class ArCoreSessionLifecycleTest {
    @Test
    fun createsExactlyOneRuntime() {
        var createCount = 0
        val lifecycle = ArCoreSessionLifecycle {
            createCount += 1
            FakeRuntime()
        }

        lifecycle.create()
        lifecycle.create()

        assertEquals(1, createCount)
        assertEquals(ArSessionPhase.Created, lifecycle.phase)
    }

    @Test
    fun failedCreationRetainsNoOwnedRuntime() {
        var attempts = 0
        val lifecycle = ArCoreSessionLifecycle {
            attempts += 1
            if (attempts == 1) throw SecurityException("denied")
            FakeRuntime()
        }

        assertEquals(
            ArSessionResult.Rejected(
                ArSessionPhase.Empty,
                ArSessionFailureReason.PermissionMissing,
            ),
            lifecycle.create(),
        )
        assertEquals(ArSessionResult.Applied(ArSessionPhase.Created), lifecycle.create())
        assertEquals(2, attempts)
    }

    @Test
    fun resumeFailureKeepsPreviousPhase() {
        val lifecycle = ArCoreSessionLifecycle {
            FakeRuntime(resumeError = CameraNotAvailableException())
        }
        lifecycle.create()

        assertEquals(
            ArSessionResult.Rejected(
                ArSessionPhase.Created,
                ArSessionFailureReason.CameraUnavailable,
            ),
            lifecycle.resume(),
        )
        assertEquals(ArSessionPhase.Created, lifecycle.phase)
    }

    @Test
    fun closeReleasesRuntimeOnceAndIsTerminal() {
        val runtime = FakeRuntime()
        val lifecycle = ArCoreSessionLifecycle { runtime }
        lifecycle.create()

        lifecycle.close()
        lifecycle.close()

        assertEquals(1, runtime.closeCount)
        assertEquals(ArSessionPhase.Closed, lifecycle.phase)
        assertEquals(
            ArSessionResult.Rejected(
                ArSessionPhase.Closed,
                ArSessionFailureReason.Closed,
            ),
            lifecycle.resume(),
        )
    }

    private class FakeRuntime(
        private val resumeError: Exception? = null,
    ) : ArSessionRuntime {
        var closeCount = 0

        override fun resume() {
            resumeError?.let { throw it }
        }

        override fun pause() = Unit

        override fun close() {
            closeCount += 1
        }
    }
}
