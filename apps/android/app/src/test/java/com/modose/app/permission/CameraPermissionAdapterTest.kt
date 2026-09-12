package com.modose.app.permission

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraPermissionAdapterTest {
    @Test
    fun resolveCombinesPlatformSnapshotWithRequestHistory() {
        val cases = listOf(
            ResolveCase(
                "初回",
                hasRequested = false,
                isGranted = false,
                shouldShowRationale = false,
                expected = CameraPermissionState.InitialRequest,
            ),
            ResolveCase(
                "拒否後に説明可能",
                hasRequested = true,
                isGranted = false,
                shouldShowRationale = true,
                expected = CameraPermissionState.Denied,
            ),
            ResolveCase(
                "永久拒否",
                hasRequested = true,
                isGranted = false,
                shouldShowRationale = false,
                expected = CameraPermissionState.PermanentlyDenied,
            ),
            ResolveCase(
                "許可済み",
                hasRequested = true,
                isGranted = true,
                shouldShowRationale = false,
                expected = CameraPermissionState.Granted,
            ),
        )

        cases.forEach { case ->
            val adapter = CameraPermissionAdapter(
                FakeHistory(case.hasRequested),
            )

            assertEquals(
                case.name,
                case.expected,
                adapter.resolve(
                    isGranted = case.isGranted,
                    shouldShowRationale = case.shouldShowRationale,
                ),
            )
        }
    }

    @Test
    fun requestPersistsHistoryBeforeLaunchingPlatformDialog() {
        val history = FakeHistory()
        val adapter = CameraPermissionAdapter(history)
        var launched = false

        val requested = adapter.request(
            CameraPermissionState.InitialRequest,
        ) {
            assertTrue(history.hasRequested)
            launched = true
        }

        assertTrue(requested)
        assertTrue(launched)
        assertEquals(1, history.markCalls)
    }

    @Test
    fun retryableDenialCanRequestAgain() {
        val history = FakeHistory(hasRequested = true)
        val adapter = CameraPermissionAdapter(history)
        var launches = 0

        val requested = adapter.request(CameraPermissionState.Denied) {
            launches += 1
        }

        assertTrue(requested)
        assertEquals(1, launches)
        assertEquals(1, history.markCalls)
    }

    @Test
    fun grantedAndPermanentDenialNeverLaunchPlatformRequest() {
        listOf(
            CameraPermissionState.Granted,
            CameraPermissionState.PermanentlyDenied,
        ).forEach { state ->
            val history = FakeHistory(hasRequested = true)
            val adapter = CameraPermissionAdapter(history)
            var launched = false

            val requested = adapter.request(state) {
                launched = true
            }

            assertFalse(state.name, requested)
            assertFalse(state.name, launched)
            assertEquals(state.name, 0, history.markCalls)
        }
    }

    private data class ResolveCase(
        val name: String,
        val hasRequested: Boolean,
        val isGranted: Boolean,
        val shouldShowRationale: Boolean,
        val expected: CameraPermissionState,
    )
}

private class FakeHistory(
    override var hasRequested: Boolean = false,
) : CameraPermissionHistory {
    var markCalls: Int = 0

    override fun markRequested() {
        markCalls += 1
        hasRequested = true
    }
}
