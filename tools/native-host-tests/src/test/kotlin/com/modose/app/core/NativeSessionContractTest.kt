package com.modose.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

internal fun tableGeometry() = doubleArrayOf(
    0.0, 0.0, 0.0,
    1.0, 0.0, 0.0,
    0.0, 0.0, 1.0,
    -1.0, -1.0, 1.0, -1.0, 1.0, 1.0, -1.0, 1.0,
)

internal fun newSession() = NativeSceneSession.create(
    tableGeometry(), intArrayOf(1), doubleArrayOf(0.0, 0.0),
)

internal fun detections(x: Double = 0.0) = listOf(CoreDetection(
    10, CoreVector(x, 1.0, 0.0), CoreVector(0.0, -1.0, 0.0), false,
))

internal fun evidence() = listOf(CorePairEvidence(1, 10, 1.0, 1.0, 1.0, true))

internal fun aligned(session: NativeSceneSession) {
    for (time in 0L..800L step 100) {
        val result = session.update(time, true, detections(), evidence())
            as FrameSubmission.Applied
        assertEquals(
            if (time == 800L) CoreRestoreState.AWAITING_VERIFICATION else CoreRestoreState.GUIDING,
            result.state,
        )
    }
}

internal fun verifiedResult() = NativeVerificationResult.Analyzed(
    CoreVerificationVerdict.VERIFIED,
    listOf(NativeObjectVerdict(1, CoreVerificationVerdict.VERIFIED)),
)

class NativeSessionContractTest {
    @Test
    fun realLibraryRunsCreateFramesVerifyAndClose() {
        val session = newSession()
        session.use {
            aligned(it)
            val ticket = it.beginVerification(800)
            for (time in listOf(900L, 1000L)) {
                val frame = it.update(time, true, detections(), evidence()) as FrameSubmission.Applied
                assertEquals(CoreRestoreState.VERIFYING, frame.state)
            }
            assertEquals(CoreRestoreState.VERIFIED, it.completeVerification(ticket, 1000, verifiedResult()))
        }
        session.close()
        assertThrows(IllegalStateException::class.java) {
            session.update(1100, true, detections(), evidence())
        }
    }

    @Test
    fun threeRealNativeTransportFailuresRequireManualConfirmation() {
        newSession().use { session ->
            aligned(session)
            repeat(3) { attempt ->
                val ticket = session.beginVerification(800)
                val result = session.completeVerification(ticket, 800, NativeVerificationResult.Unavailable)
                assertEquals(
                    if (attempt == 2) CoreRestoreState.MANUAL_CONFIRMATION
                    else CoreRestoreState.AWAITING_VERIFICATION,
                    result,
                )
            }
        }
    }

    @Test
    fun uncertainAndCorrectionResultsNeverReturnVerified() {
        newSession().use { session ->
            aligned(session)
            val first = session.beginVerification(800)
            assertEquals(
                CoreRestoreState.AWAITING_VERIFICATION,
                session.completeVerification(first, 800, NativeVerificationResult.Analyzed(
                    CoreVerificationVerdict.UNCERTAIN,
                    listOf(NativeObjectVerdict(1, CoreVerificationVerdict.UNCERTAIN)),
                )),
            )
            val second = session.beginVerification(800)
            assertEquals(
                CoreRestoreState.GUIDING,
                session.completeVerification(second, 800, NativeVerificationResult.Analyzed(
                    CoreVerificationVerdict.NEEDS_CORRECTION,
                    listOf(NativeObjectVerdict(1, CoreVerificationVerdict.NEEDS_CORRECTION)),
                )),
            )
        }
    }
}
