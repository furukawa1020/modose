package com.modose.app.ar.render

import com.modose.app.ar.image.CpuCameraImage
import com.modose.app.ar.image.CpuImageAcquisitionResult
import com.modose.app.ar.session.*
import org.junit.Assert.*
import org.junit.Test

class BaselineCaptureMailboxTest {
    @Test
    fun secondRequestCannotReplaceOutstandingConsumer() {
        val mailbox = BaselineCaptureMailbox()
        var first = 0
        var second = 0
        assertTrue(mailbox.request { first++ })
        assertFalse(mailbox.request { second++ })
        mailbox.offer(frame(1))
        mailbox.offer(frame(2))
        assertEquals(1, first)
        assertEquals(0, second)
    }

    @Test
    fun waitsForAcquiredCpuImageAndDeliversExactFrame() {
        val mailbox = BaselineCaptureMailbox()
        var received: BaselineCameraFrame? = null
        mailbox.request { received = it }
        mailbox.offer(ArCameraFrame(1L, null))
        assertNull(received)
        val imageFrame = frame(2)
        mailbox.offer(imageFrame)
        assertSame(imageFrame, requireNotNull(received).frame)
    }

    @Test
    fun framesBeforeRequestAreNotDeliveredRetroactively() {
        val mailbox = BaselineCaptureMailbox()
        mailbox.offer(frame(1))
        var received: BaselineCameraFrame? = null
        mailbox.request { received = it }
        assertNull(received)
        val next = frame(2)
        mailbox.offer(next)
        assertSame(next, requireNotNull(received).frame)
    }

    @Test
    fun cancellationNotifiesOnceAndAllowsNextRequest() {
        val mailbox = BaselineCaptureMailbox()
        val received = mutableListOf<BaselineCameraFrame?>()
        mailbox.request { received.add(it) }
        mailbox.cancel()
        mailbox.cancel()
        mailbox.offer(frame(1))
        assertEquals(1, received.size)
        assertNull(received.single())
        assertTrue(mailbox.request { received.add(it) })
        mailbox.offer(frame(2))
        assertEquals(2, received.size)
        assertNotNull(received.last())
    }

    @Test
    fun precedingPoseIsCopiedRatherThanBorrowed() {
        val mailbox = BaselineCaptureMailbox()
        val previous = FloatArray(16) { it.toFloat() }
        mailbox.offer(frame(10, view = previous))
        previous.fill(-1f)
        var received: BaselineCameraFrame? = null
        mailbox.request { received = it }
        mailbox.offer(frame(20))
        val packet = requireNotNull(received)
        assertEquals(10L, packet.previousTimestampNanos)
        assertArrayEquals(FloatArray(16) { it.toFloat() }, packet.previousViewMatrix, 0f)
    }

    @Test
    fun trackingLossClearsPreviousPose() {
        val mailbox = BaselineCaptureMailbox()
        mailbox.offer(frame(10))
        mailbox.offer(frame(20, phase = ArTrackingPhase.Paused))
        var received: BaselineCameraFrame? = null
        mailbox.request { received = it }
        mailbox.offer(frame(30))
        assertEquals(0L, requireNotNull(received).previousTimestampNanos)
        assertNull(requireNotNull(received).previousViewMatrix)
    }

    @Test
    fun cancellationClearsPoseEvenWithoutPendingRequest() {
        val mailbox = BaselineCaptureMailbox()
        mailbox.offer(frame(10))
        mailbox.cancel()
        var received: BaselineCameraFrame? = null
        mailbox.request { received = it }
        mailbox.offer(frame(20))
        assertEquals(0L, requireNotNull(received).previousTimestampNanos)
        assertNull(requireNotNull(received).previousViewMatrix)
    }

    @Test
    fun olderTrackingFrameCannotOverwriteLatestPose() {
        val mailbox = BaselineCaptureMailbox()
        mailbox.offer(frame(20, view = FloatArray(16) { 2f }))
        mailbox.offer(frame(10, view = FloatArray(16) { 1f }))
        var received: BaselineCameraFrame? = null
        mailbox.request { received = it }
        mailbox.offer(frame(30))
        assertEquals(20L, requireNotNull(received).previousTimestampNanos)
        assertArrayEquals(FloatArray(16) { 2f }, requireNotNull(received).previousViewMatrix, 0f)
    }

    @Test
    fun consumerMayRequestNextFrameWithoutLosingIt() {
        val mailbox = BaselineCaptureMailbox()
        var calls = 0
        mailbox.request {
            calls++
            assertTrue(mailbox.request { calls++ })
        }
        mailbox.offer(frame(1))
        assertEquals(1, calls)
        mailbox.offer(frame(2))
        assertEquals(2, calls)
    }

    private fun frame(
        timestamp: Long,
        phase: ArTrackingPhase = ArTrackingPhase.Tracking,
        view: FloatArray = FloatArray(16),
    ) = ArCameraFrame(
        timestampNanos = timestamp,
        transformedTextureCoordinates = null,
        trackingDiagnostics = ArTrackingDiagnostics(phase, ArTrackingIssue.None),
        cpuImageResult = CpuImageAcquisitionResult.Acquired(
            CpuCameraImage(2, 2, timestamp, emptyList())),
        viewMatrix = view,
    )
}
