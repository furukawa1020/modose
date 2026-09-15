package com.modose.app.e2e

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class E2ePositionErrorRecorderTest {
    @Test
    fun finish_calculatesCentimeterErrorsInExpectedOrder() {
        val recorder = recorder("wallet", "keys")
        recorder.record(
            "keys",
            E2ePlanePositionMeters(0.0, 0.0),
            E2ePlanePositionMeters(0.03, 0.04),
        )
        recorder.record(
            "wallet",
            E2ePlanePositionMeters(0.01, 0.01),
            E2ePlanePositionMeters(0.01, 0.01),
        )

        val result = recorder.finish()

        assertTrue(result is E2ePositionErrorResult.Finished)
        val samples =
            (result as E2ePositionErrorResult.Finished).report.samples
        assertEquals(listOf("wallet", "keys"), samples.map { it.sceneObjectId })
        assertEquals(0.0, samples[0].errorCentimeters, 0.0001)
        assertEquals(5.0, samples[1].errorCentimeters, 0.0001)
    }

    @Test
    fun finish_preservesTypedUnavailableReason() {
        val recorder = recorder("wallet", "keys")
        recorder.record(
            "wallet",
            E2ePlanePositionMeters(0.0, 0.0),
            E2ePlanePositionMeters(0.01, 0.0),
        )
        recorder.markUnavailable(
            "keys",
            E2ePositionUnavailableReason.Missing,
        )

        val result = recorder.finish() as E2ePositionErrorResult.Finished

        assertEquals(
            mapOf("keys" to E2ePositionUnavailableReason.Missing),
            result.report.unavailable,
        )
    }

    @Test
    fun finish_rejectsUnresolvedObjects() {
        val recorder = recorder("wallet", "keys")
        recorder.record(
            "wallet",
            E2ePlanePositionMeters(0.0, 0.0),
            E2ePlanePositionMeters(0.0, 0.0),
        )

        assertEquals(
            E2ePositionErrorResult.Rejected(
                E2ePositionErrorFailure.UnresolvedObjects,
            ),
            recorder.finish(),
        )
    }

    @Test
    fun record_rejectsDuplicateUnknownAndNonFiniteInput() {
        val recorder = recorder("wallet")
        assertEquals(
            E2ePositionErrorResult.Rejected(
                E2ePositionErrorFailure.UnknownObject,
            ),
            recorder.record(
                "keys",
                E2ePlanePositionMeters(0.0, 0.0),
                E2ePlanePositionMeters(0.0, 0.0),
            ),
        )
        assertEquals(
            E2ePositionErrorResult.Rejected(
                E2ePositionErrorFailure.NonFiniteCoordinate,
            ),
            recorder.record(
                "wallet",
                E2ePlanePositionMeters(Double.NaN, 0.0),
                E2ePlanePositionMeters(0.0, 0.0),
            ),
        )
        recorder.markUnavailable(
            "wallet",
            E2ePositionUnavailableReason.Ambiguous,
        )
        assertEquals(
            E2ePositionErrorResult.Rejected(
                E2ePositionErrorFailure.AlreadyResolved,
            ),
            recorder.markUnavailable(
                "wallet",
                E2ePositionUnavailableReason.TrackingLost,
            ),
        )
    }

    @Test
    fun record_rejectsErrorOverSchemaLimit() {
        val recorder = recorder("wallet")

        assertEquals(
            E2ePositionErrorResult.Rejected(
                E2ePositionErrorFailure.ErrorOutOfRange,
            ),
            recorder.record(
                "wallet",
                E2ePlanePositionMeters(0.0, 0.0),
                E2ePlanePositionMeters(2.01, 0.0),
            ),
        )
    }

    @Test
    fun create_rejectsDuplicateAndUnsafeObjectIds() {
        assertEquals(
            E2ePositionErrorResult.Rejected(
                E2ePositionErrorFailure.InvalidExpectedObjects,
            ),
            E2ePositionErrorRecorder.create(listOf("wallet", "wallet")),
        )
        assertEquals(
            E2ePositionErrorResult.Rejected(
                E2ePositionErrorFailure.InvalidExpectedObjects,
            ),
            E2ePositionErrorRecorder.create(listOf("../wallet")),
        )
    }

    private fun recorder(
        vararg ids: String,
    ): E2ePositionErrorRecorder =
        (
            E2ePositionErrorRecorder.create(ids.toList()) as
                E2ePositionErrorRecorder.Created
        ).recorder
}
