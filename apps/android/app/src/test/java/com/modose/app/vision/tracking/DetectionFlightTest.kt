package com.modose.app.vision.tracking

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DetectionFlightTest {
    @Test
    fun completionReleasesTheSlot() {
        val flight = DetectionFlight()
        assertTrue(flight.acquire())
        var published = false
        assertFalse(flight.finish { published = true })
        assertTrue(published)
        assertTrue(flight.acquire())
        assertFalse(flight.finish {})
    }

    @Test
    fun busyFrameIsDroppedWithoutCreatingAnotherSlot() {
        val flight = DetectionFlight()
        assertTrue(flight.acquire())
        assertFalse(flight.acquire())
        assertFalse(flight.finish {})
        assertTrue(flight.acquire())
        assertFalse(flight.finish {})
    }

    @Test
    fun reentrantSubmissionDuringPublicationIsRejected() {
        val flight = DetectionFlight()
        assertTrue(flight.acquire())
        assertFalse(flight.finish { assertFalse(flight.acquire()) })
    }

    @Test
    fun closingInFlightRevokesItsLatePublication() {
        val flight = DetectionFlight()
        assertTrue(flight.acquire())
        assertFalse(flight.close())
        var published = false
        assertTrue(flight.finish { published = true })
        assertFalse(published)
        assertFalse(flight.acquire())
    }

    @Test
    fun idleCloseIsIdempotentAndPreventsAdmission() {
        val flight = DetectionFlight()
        assertTrue(flight.close())
        assertTrue(flight.close())
        assertFalse(flight.acquire())
    }

    @Test
    fun callbackExceptionStillReleasesTheSlot() {
        val flight = DetectionFlight()
        assertTrue(flight.acquire())
        assertThrows(IllegalStateException::class.java) {
            flight.finish { throw IllegalStateException("consumer failed") }
        }
        assertTrue(flight.acquire())
        assertFalse(flight.finish {})
    }

    @Test(timeout = 10000L)
    fun admissionDoesNotWaitForAnExecutingCallback() {
        val flight = DetectionFlight()
        val callbackEntered = CountDownLatch(1)
        val releaseCallback = CountDownLatch(1)
        val threads = Executors.newFixedThreadPool(2)
        try {
            assertTrue(flight.acquire())
            val completion = threads.submit<Boolean> {
                flight.finish {
                    callbackEntered.countDown()
                    check(releaseCallback.await(5, TimeUnit.SECONDS))
                }
            }
            assertTrue(callbackEntered.await(2, TimeUnit.SECONDS))
            val admission = threads.submit<Boolean> { flight.acquire() }
            // The callback is still blocked. A synchronized acquire would time out here.
            assertFalse(admission.get(1, TimeUnit.SECONDS))
            releaseCallback.countDown()
            assertFalse(completion.get(2, TimeUnit.SECONDS))
            assertTrue(flight.acquire())
            assertFalse(flight.finish {})
        } finally {
            releaseCallback.countDown()
            threads.shutdownNow()
            threads.awaitTermination(2, TimeUnit.SECONDS)
        }
    }

    @Test
    fun closeInsideCallbackDefersDisposalUntilCompletion() {
        val flight = DetectionFlight()
        assertTrue(flight.acquire())
        assertTrue(flight.finish { assertFalse(flight.close()) })
        assertFalse(flight.acquire())
    }

    @Test
    fun completionWithoutAnOwnedSlotIsRejected() {
        val flight = DetectionFlight()
        assertThrows(IllegalStateException::class.java) { flight.finish {} }
    }
}
