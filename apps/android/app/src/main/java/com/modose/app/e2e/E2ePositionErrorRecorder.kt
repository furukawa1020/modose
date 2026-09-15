package com.modose.app.e2e

import kotlin.math.hypot

data class E2ePlanePositionMeters(
    val x: Double,
    val z: Double,
)

data class E2ePositionErrorSample(
    val sceneObjectId: String,
    val errorCentimeters: Double,
)

enum class E2ePositionUnavailableReason {
    Missing,
    Ambiguous,
    TrackingLost,
}

data class E2ePositionErrorReport(
    val samples: List<E2ePositionErrorSample>,
    val unavailable: Map<String, E2ePositionUnavailableReason>,
)

enum class E2ePositionErrorFailure {
    InvalidExpectedObjects,
    UnknownObject,
    AlreadyResolved,
    NonFiniteCoordinate,
    ErrorOutOfRange,
    UnresolvedObjects,
    AlreadyFinished,
}

sealed interface E2ePositionErrorResult {
    data object Accepted : E2ePositionErrorResult
    data class Finished(val report: E2ePositionErrorReport) :
        E2ePositionErrorResult
    data class Rejected(val reason: E2ePositionErrorFailure) :
        E2ePositionErrorResult
}

class E2ePositionErrorRecorder private constructor(
    private val expectedObjectIds: List<String>,
) {
    private val samples = linkedMapOf<String, E2ePositionErrorSample>()
    private val unavailable =
        linkedMapOf<String, E2ePositionUnavailableReason>()
    private var finished = false

    @Synchronized
    fun record(
        sceneObjectId: String,
        current: E2ePlanePositionMeters,
        target: E2ePlanePositionMeters,
    ): E2ePositionErrorResult {
        validateMutableObject(sceneObjectId)?.let { return it }
        if (
            !current.x.isFinite() ||
            !current.z.isFinite() ||
            !target.x.isFinite() ||
            !target.z.isFinite()
        ) {
            return rejected(E2ePositionErrorFailure.NonFiniteCoordinate)
        }
        val errorCentimeters =
            hypot(target.x - current.x, target.z - current.z) * 100.0
        if (
            !errorCentimeters.isFinite() ||
            errorCentimeters > MAX_ERROR_CENTIMETERS
        ) {
            return rejected(E2ePositionErrorFailure.ErrorOutOfRange)
        }
        samples[sceneObjectId] = E2ePositionErrorSample(
            sceneObjectId,
            errorCentimeters,
        )
        return E2ePositionErrorResult.Accepted
    }

    @Synchronized
    fun markUnavailable(
        sceneObjectId: String,
        reason: E2ePositionUnavailableReason,
    ): E2ePositionErrorResult {
        validateMutableObject(sceneObjectId)?.let { return it }
        unavailable[sceneObjectId] = reason
        return E2ePositionErrorResult.Accepted
    }

    @Synchronized
    fun finish(): E2ePositionErrorResult {
        if (finished) {
            return rejected(E2ePositionErrorFailure.AlreadyFinished)
        }
        if ((samples.keys + unavailable.keys).toSet() != expectedObjectIds.toSet()) {
            return rejected(E2ePositionErrorFailure.UnresolvedObjects)
        }
        finished = true
        return E2ePositionErrorResult.Finished(
            E2ePositionErrorReport(
                samples = expectedObjectIds.mapNotNull(samples::get),
                unavailable = expectedObjectIds
                    .mapNotNull { id ->
                        unavailable[id]?.let { id to it }
                    }
                    .toMap(linkedMapOf()),
            ),
        )
    }

    private fun validateMutableObject(
        sceneObjectId: String,
    ): E2ePositionErrorResult.Rejected? {
        if (finished) {
            return rejected(E2ePositionErrorFailure.AlreadyFinished)
        }
        if (sceneObjectId !in expectedObjectIds) {
            return rejected(E2ePositionErrorFailure.UnknownObject)
        }
        if (sceneObjectId in samples || sceneObjectId in unavailable) {
            return rejected(E2ePositionErrorFailure.AlreadyResolved)
        }
        return null
    }

    private fun rejected(reason: E2ePositionErrorFailure) =
        E2ePositionErrorResult.Rejected(reason)

    companion object {
        fun create(
            expectedObjectIds: List<String>,
        ): E2ePositionErrorResult =
            if (
                expectedObjectIds.size !in 1..MAX_OBJECTS ||
                expectedObjectIds.distinct().size != expectedObjectIds.size ||
                expectedObjectIds.any { !SAFE_ID.matches(it) }
            ) {
                E2ePositionErrorResult.Rejected(
                    E2ePositionErrorFailure.InvalidExpectedObjects,
                )
            } else {
                Created(E2ePositionErrorRecorder(expectedObjectIds.toList()))
            }

        const val MAX_ERROR_CENTIMETERS = 200.0
        private const val MAX_OBJECTS = 5
        private val SAFE_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
    }

    data class Created(
        val recorder: E2ePositionErrorRecorder,
    ) : E2ePositionErrorResult
}
