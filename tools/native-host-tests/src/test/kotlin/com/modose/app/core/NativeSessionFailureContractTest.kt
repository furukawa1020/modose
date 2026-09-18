package com.modose.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class NativeSessionFailureContractTest {
    @Test
    fun corruptAndOversizedNativePacketsRetireOnlyTheirOwner() {
        for (packet in listOf(byteArrayOf(), ByteArray(4097))) {
            newSession().use { peer ->
                withRawSession { handle ->
                    assertThrows(IllegalStateException::class.java) {
                        NativeSceneBindings.nativeApply(handle, 0, packet)
                    }
                    assertThrows(IllegalStateException::class.java) {
                        NativeSceneBindings.nativeApply(handle, 1, CoreFrameEncoder.trackingLost())
                    }
                }
                aligned(peer)
            }
        }
    }

    @Test
    fun unknownNativeHandleCannotDamageAnotherSession() {
        newSession().use { peer ->
            assertThrows(IllegalStateException::class.java) {
                NativeSceneBindings.nativeApply(Long.MAX_VALUE, 0, CoreFrameEncoder.trackingLost())
            }
            aligned(peer)
        }
    }

    @Test
    fun lostTrackingInvalidatesAnInFlightConfirmation() {
        newSession().use { session ->
            aligned(session)
            val ticket = session.beginVerification(800)
            val result = session.update(900, false, emptyList(), emptyList()) as FrameSubmission.Applied
            assertEquals(CoreRestoreState.GUIDING, result.state)
            assertThrows(IllegalStateException::class.java) {
                session.completeVerification(ticket, 900, verifiedResult())
            }
            assertThrows(IllegalStateException::class.java) { session.beginVerification(900) }
        }
    }

    @Test
    fun replayedConfirmationCannotReturnCachedSuccess() {
        newSession().use { session ->
            aligned(session)
            val ticket = session.beginVerification(800)
            assertEquals(CoreRestoreState.VERIFIED, session.completeVerification(ticket, 800, verifiedResult()))
            assertThrows(IllegalStateException::class.java) {
                session.completeVerification(ticket, 800, verifiedResult())
            }
        }
    }

    @Test
    fun foreignTicketRejectsTheCallerWithoutConsumingTheOwnersTicket() {
        newSession().use { first ->
            newSession().use { second ->
                aligned(first)
                aligned(second)
                val ticket = first.beginVerification(800)
                second.beginVerification(800)
                assertThrows(IllegalArgumentException::class.java) {
                    second.completeVerification(ticket, 800, verifiedResult())
                }
                assertEquals(CoreRestoreState.VERIFIED, first.completeVerification(ticket, 800, verifiedResult()))
            }
        }
    }

    @Test
    fun backwardNativeClockClosesTheKotlinOwner() {
        newSession().use { session ->
            aligned(session)
            assertThrows(IllegalStateException::class.java) {
                session.update(799, true, detections(), evidence())
            }
            assertThrows(IllegalStateException::class.java) {
                session.update(900, true, detections(), evidence())
            }
        }
    }

    @Test
    fun nativeCapacityIsReleasedByIdempotentClose() {
        val sessions = mutableListOf<NativeSceneSession>()
        try {
            repeat(4) { sessions.add(newSession()) }
            assertThrows(IllegalStateException::class.java) { newSession() }
            sessions[0].close()
            sessions[0].close()
            newSession().use { aligned(it) }
        } finally {
            sessions.forEach { it.close() }
        }
    }

    @Test
    fun nativeVerificationRejectsMalformedEnumsIdsAndArrays() {
        data class Invalid(val overall: Int, val ids: IntArray, val verdicts: IntArray)
        val invalid = listOf(
            Invalid(99, intArrayOf(1), intArrayOf(0)),
            Invalid(0, intArrayOf(1), intArrayOf(99)),
            Invalid(0, intArrayOf(0), intArrayOf(0)),
            Invalid(0, intArrayOf(-1), intArrayOf(0)),
            Invalid(0, intArrayOf(2), intArrayOf(0)),
            Invalid(0, intArrayOf(1, 1), intArrayOf(0, 0)),
            Invalid(0, intArrayOf(1), intArrayOf()),
            Invalid(0, intArrayOf(), intArrayOf()),
            Invalid(3, intArrayOf(1), intArrayOf(0)),
            Invalid(0, IntArray(6) { it + 1 }, IntArray(6)),
        )
        for (input in invalid) {
            withRawSession { handle ->
                rawAlign(handle)
                val token = NativeSceneBindings.nativeBeginVerification(handle, 800)
                assertThrows(IllegalStateException::class.java) {
                    NativeSceneBindings.nativeCompleteVerification(
                        handle, token, 800, input.overall, input.ids, input.verdicts,
                    )
                }
                assertThrows(IllegalStateException::class.java) {
                    NativeSceneBindings.nativeBeginVerification(handle, 800)
                }
            }
        }
    }

    @Test
    fun nativeCreateRejectsInvalidGeometryBeforeOwningASession() {
        for (geometry in listOf(DoubleArray(138), tableGeometry().also { it[0] = Double.NaN })) {
            assertThrows(IllegalStateException::class.java) {
                NativeSceneBindings.nativeCreate(geometry, intArrayOf(1), doubleArrayOf(0.0, 0.0))
            }
        }
        newSession().use { aligned(it) }
    }

    private fun rawAlign(handle: Long) {
        val bytes = (CoreFrameEncoder.encode(true, detections(), evidence()) as FrameEncoding.Encoded).bytes
        for (time in 0L..800L step 100) NativeSceneBindings.nativeApply(handle, time, bytes)
    }

    private fun withRawSession(block: (Long) -> Unit) {
        val handle = NativeSceneBindings.nativeCreate(tableGeometry(), intArrayOf(1), doubleArrayOf(0.0, 0.0))
        try {
            block(handle)
        } finally {
            // An expected native rejection may already have retired the handle.
            runCatching { NativeSceneBindings.nativeClose(handle) }
        }
    }
}
