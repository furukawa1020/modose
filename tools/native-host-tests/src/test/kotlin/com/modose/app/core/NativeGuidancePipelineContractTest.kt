package com.modose.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeGuidancePipelineContractTest {
    private fun open(
        clock: () -> Long,
        sink: NativeGuidanceSink,
    ) = NativeGuidancePipeline.open(
        "scene-1", tableGeometry(), intArrayOf(1), doubleArrayOf(0.0, 0.0), clock, sink,
    )

    private fun input(p: NativeGuidancePipeline, time: Long) =
        NativeRecognitionFrame(p.epoch, time, true, detections(0.3), evidence())

    @Test
    fun acceptedFramePublishesRealRustGuidance() {
        val published = mutableListOf<NativeWorldFrameSnapshot>()
        open({ 100 }, NativeGuidanceSink { published.add(it); true }).use { p ->
            assertEquals(NativeFrameProcessing.Published(100), p.submit(input(p, 100)))
            val ready = published.single().presentationAt(100) as NativeWorldFramePresentation.Ready
            assertEquals(CoreRestoreState.GUIDING, ready.state)
            assertEquals(0.3, (ready.guidance as NativeWorldGuidance.Move).distanceMeters, 1e-9)
        }
    }

    @Test
    fun invalidArrivalTimesDoNotOverwriteTheNewerPublishedFrame() {
        val published = mutableListOf<NativeWorldFrameSnapshot>()
        open({ 1000 }, NativeGuidanceSink { published.add(it); true }).use { p ->
            p.submit(input(p, 1000))
            for ((time, reason) in listOf(
                -1L to NativeFrameDropReason.INVALID_TIMESTAMP,
                1001L to NativeFrameDropReason.FUTURE,
                799L to NativeFrameDropReason.STALE,
                900L to NativeFrameDropReason.NON_INCREASING,
                1000L to NativeFrameDropReason.NON_INCREASING,
            )) assertEquals(NativeFrameProcessing.Dropped(reason), p.submit(input(p, time)))
            assertEquals(1, published.size)
            assertTrue(published.single().presentationAt(1000) is NativeWorldFramePresentation.Ready)
        }
    }

    @Test
    fun exactlyTwoHundredMillisecondsIsAccepted() {
        var publications = 0
        open({ 1000 }, NativeGuidanceSink { publications++; true }).use { p ->
            assertEquals(NativeFrameProcessing.Published(800), p.submit(input(p, 800)))
            assertEquals(1, publications)
        }
    }

    @Test
    fun sameSceneNameDoesNotAllowAnotherGeneration() {
        var publications = 0
        open({ 0 }, NativeGuidanceSink { publications++; true }).use { current ->
            open({ 0 }, NativeGuidanceSink { true }).use { old ->
                assertEquals(NativeFrameProcessing.Dropped(NativeFrameDropReason.FOREIGN_STREAM),
                    current.submit(input(old, 0)))
                assertEquals(0, publications)
                assertEquals(NativeFrameProcessing.Published(0), current.submit(input(current, 0)))
            }
        }
    }

    @Test
    fun clockRegressionClosesTheOwnerAndRevokesItsSnapshot() {
        var now = 100L
        val published = mutableListOf<NativeWorldFrameSnapshot>()
        open({ now }, NativeGuidanceSink { published.add(it); true }).use { p ->
            p.submit(input(p, 100))
            now = 99
            assertEquals(NativeFrameProcessing.Failed(NativePipelineFailure.CLOCK), p.submit(input(p, 101)))
            assertTrue(published.single().presentationAt(100) is NativeWorldFramePresentation.Hidden)
            assertEquals(NativeFrameProcessing.Dropped(NativeFrameDropReason.CLOSED), p.submit(input(p, 102)))
        }
    }

    @Test
    fun publicationRejectionRetiresEvenTheJustProducedSnapshot() {
        var produced: NativeWorldFrameSnapshot? = null
        open({ 0 }, NativeGuidanceSink { produced = it; false }).use { p ->
            assertEquals(NativeFrameProcessing.Failed(NativePipelineFailure.PUBLICATION), p.submit(input(p, 0)))
            assertTrue(produced!!.presentationAt(0) is NativeWorldFramePresentation.Hidden)
            assertEquals(NativeFrameProcessing.Dropped(NativeFrameDropReason.CLOSED), p.submit(input(p, 0)))
        }
    }

    @Test
    fun publicationExceptionClosesTheNativeOwner() {
        open({ 0 }, NativeGuidanceSink { error("publication failed") }).use { p ->
            assertEquals(NativeFrameProcessing.Failed(NativePipelineFailure.PUBLICATION), p.submit(input(p, 0)))
            assertEquals(NativeFrameProcessing.Dropped(NativeFrameDropReason.CLOSED), p.submit(input(p, 0)))
        }
    }

    @Test
    fun nativeRejectionDoesNotLeaveAnOldArrow() {
        var now = 0L
        val published = mutableListOf<NativeWorldFrameSnapshot>()
        open({ now }, NativeGuidanceSink { published.add(it); true }).use { p ->
            p.submit(input(p, 0))
            now = 100
            val ray = CoreDetection(10, CoreVector(0.0, 1.0, 0.0),
                CoreVector(Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE), false)
            assertEquals(NativeFrameProcessing.Failed(NativePipelineFailure.NATIVE),
                p.submit(input(p, 100).copy(detections = listOf(ray))))
            assertTrue(published.single().presentationAt(100) is NativeWorldFramePresentation.Hidden)
        }
    }

    @Test
    fun invalidEncodingPublishesHiddenAndCanRecoverOnTheNextFrame() {
        var now = 0L
        val published = mutableListOf<NativeWorldFrameSnapshot>()
        open({ now }, NativeGuidanceSink { published.add(it); true }).use { p ->
            assertEquals(NativeFrameProcessing.Published(0),
                p.submit(input(p, 0).copy(detections = List(6) { detections().single() })))
            assertTrue(published.single().presentationAt(0) is NativeWorldFramePresentation.Hidden)
            now = 100
            assertEquals(NativeFrameProcessing.Published(100), p.submit(input(p, 100)))
            assertTrue(published.last().presentationAt(100) is NativeWorldFramePresentation.Ready)
        }
    }

    @Test
    fun closePreventsDelayedCallbacksFromPublishing() {
        var publications = 0
        val p = open({ 0 }, NativeGuidanceSink { publications++; true })
        val delayed = input(p, 0)
        p.close()
        p.close()
        assertEquals(NativeFrameProcessing.Dropped(NativeFrameDropReason.CLOSED), p.submit(delayed))
        assertEquals(0, publications)
    }

    @Test
    fun reentrantSinkCannotApplyAnotherFrame() {
        lateinit var p: NativeGuidancePipeline
        var nested: NativeFrameProcessing? = null
        p = open({ 0 }, NativeGuidanceSink {
            nested = p.submit(input(p, 0))
            true
        })
        p.use {
            assertEquals(NativeFrameProcessing.Published(0), p.submit(input(p, 0)))
            assertEquals(NativeFrameProcessing.Dropped(NativeFrameDropReason.REENTRANT), nested)
        }
    }

    @Test
    fun finalVerificationKeepsTheSameOwnerAndClock() {
        var now = 0L
        val published = mutableListOf<NativeWorldFrameSnapshot>()
        open({ now }, NativeGuidanceSink { published.add(it); true }).use { p ->
            for (time in 0L..800L step 100) {
                now = time
                p.submit(input(p, time).copy(detections = detections()))
            }
            val local = published.last()
            assertEquals(CoreRestoreState.AWAITING_VERIFICATION,
                (local.presentationAt(800) as NativeWorldFramePresentation.Ready).state)
            val ticket = p.beginVerification(p.epoch)
            assertTrue(local.presentationAt(800) is NativeWorldFramePresentation.Hidden)
            assertEquals(CoreRestoreState.VERIFIED, p.completeVerification(p.epoch, ticket, verifiedResult()))
            now = 900
            p.submit(input(p, 900).copy(detections = detections()))
            assertEquals(CoreRestoreState.VERIFIED,
                (published.last().presentationAt(900) as NativeWorldFramePresentation.Ready).state)
        }
    }
}
