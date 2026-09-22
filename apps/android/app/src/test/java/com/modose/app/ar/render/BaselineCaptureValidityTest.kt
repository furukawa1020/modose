package com.modose.app.ar.render

import com.modose.app.ar.anchor.*
import com.modose.app.ar.image.*
import com.modose.app.ar.session.*
import org.junit.Assert.*
import org.junit.Test

class BaselineCaptureValidityTest {
    private val mailbox = BaselineCaptureMailbox()

    @Test fun captureRecordsActualAnchor() {
        val token = capture(17)
        assertEquals(17L, token.anchorId)
        assertTrue(token.isCurrent)
    }

    @Test fun sameAnchorKeepsCaptureValid() {
        val token = capture(17)
        mailbox.observe(frame(17))
        token.requireCurrent()
    }

    @Test fun anchorReplacementInvalidatesOldCapture() {
        val token = capture(17)
        mailbox.observe(frame(18))
        assertFalse(token.isCurrent)
        assertThrows(IllegalStateException::class.java) { token.requireCurrent() }
        assertTrue(capture(18).isCurrent)
    }

    @Test fun trackingLossDoesNotRequireCpuImage() {
        val token = capture(17)
        mailbox.observe(ArCameraFrame(2L, null))
        assertFalse(token.isCurrent)
    }

    @Test fun reacquiringSameAnchorCannotReviveOldCapture() {
        val token = capture(17)
        mailbox.observe(ArCameraFrame(2L, null))
        val replacement = capture(17)
        assertFalse(token.isCurrent)
        assertTrue(replacement.isCurrent)
        assertNotSame(token, replacement)
    }

    @Test fun cancellationInvalidatesDeliveredCapture() {
        val token = capture(17)
        mailbox.cancel()
        assertFalse(token.isCurrent)
        assertTrue(capture(17).isCurrent)
    }

    @Test fun anchorLossInvalidatesEvenWhileCameraTracks() {
        val token = capture(17)
        mailbox.observe(frame(17).copy(sceneAnchorState = SceneAnchorState.Lost(17)))
        assertFalse(token.isCurrent)
    }

    @Test fun cameraPauseInvalidatesEvenIfAnchorStillTracks() {
        val token = capture(17)
        mailbox.observe(frame(17).copy(trackingDiagnostics =
            ArTrackingDiagnostics(ArTrackingPhase.Paused, ArTrackingIssue.Unknown)))
        assertFalse(token.isCurrent)
    }

    @Test fun unavailableAnchorCannotIssueValidCapture() {
        var packet: BaselineCameraFrame? = null
        mailbox.request { packet = it }
        mailbox.offer(frame(17).copy(sceneAnchorState = SceneAnchorState.Unavailable))
        assertNull(requireNotNull(packet).validity)
    }

    private fun capture(id: Long): BaselineCaptureValidity {
        var packet: BaselineCameraFrame? = null
        assertTrue(mailbox.request { packet = it })
        mailbox.offer(frame(id))
        return requireNotNull(requireNotNull(packet).validity)
    }

    private fun frame(id: Long) = ArCameraFrame(
        timestampNanos = 1L,
        transformedTextureCoordinates = null,
        trackingDiagnostics = ArTrackingDiagnostics(ArTrackingPhase.Tracking, ArTrackingIssue.None),
        sceneAnchorState = SceneAnchorState.Tracking(SceneAnchorSnapshot(id,
            SceneAnchorPose(0f, 0f, 0f, 0f, 0f, 0f, 1f))),
        cpuImageResult = CpuImageAcquisitionResult.Acquired(CpuCameraImage(2, 2, 1L, emptyList())),
    )
}
