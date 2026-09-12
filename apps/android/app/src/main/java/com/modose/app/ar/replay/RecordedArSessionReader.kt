package com.modose.app.ar.replay

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.content
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

data class ReplayVector3(
    val x: Double,
    val y: Double,
    val z: Double,
)

data class ReplayQuaternion(
    val x: Double,
    val y: Double,
    val z: Double,
    val w: Double,
)

data class ReplayPose(
    val translationMeters: ReplayVector3,
    val rotation: ReplayQuaternion,
)

enum class ReplayTrackingState {
    Tracking,
    Paused,
    Stopped,
}

enum class ReplayMatchState {
    Accepted,
    Ambiguous,
    Missing,
}

data class ReplayTrackedObject(
    val objectId: String,
    val currentX: Double,
    val currentZ: Double,
    val targetX: Double,
    val targetZ: Double,
    val matchState: ReplayMatchState,
    val trackingValid: Boolean,
)

data class ReplayFrame(
    val index: Int,
    val timestampNanos: Long,
    val trackingState: ReplayTrackingState,
    val cameraPose: ReplayPose,
    val objects: List<ReplayTrackedObject>,
)

data class RecordedArSession(
    val sessionId: String,
    val anchorPose: ReplayPose,
    val frames: List<ReplayFrame>,
)

enum class ReplayReadRejection {
    MalformedJson,
    UnsupportedSchema,
    UnknownField,
    InvalidValue,
    NonSequentialFrame,
    NonMonotonicTimestamp,
    DuplicateObjectId,
}

sealed interface ReplayReadResult {
    data class Accepted(val session: RecordedArSession) : ReplayReadResult

    data class Rejected(val reason: ReplayReadRejection) : ReplayReadResult
}

object RecordedArSessionReader {
    fun read(raw: String): ReplayReadResult {
        val root = try {
            Json.parseToJsonElement(raw).jsonObject
        } catch (_: RuntimeException) {
            return rejected(ReplayReadRejection.MalformedJson)
        }

        return try {
            root.requireKeys(
                "schemaVersion",
                "sessionId",
                "anchorPose",
                "frames",
            )
            if (root.text("schemaVersion") != "1.0") {
                reject(ReplayReadRejection.UnsupportedSchema)
            }
            val sessionId = root.text("sessionId")
            if (!SAFE_ID.matches(sessionId)) {
                reject(ReplayReadRejection.InvalidValue)
            }
            val frames = root.array("frames")
            if (frames.size !in 1..MAX_FRAMES) {
                reject(ReplayReadRejection.InvalidValue)
            }
            var previousTimestamp = -1L
            val decodedFrames = frames.mapIndexed { expectedIndex, element ->
                val frame = element.jsonObject.readFrame()
                if (frame.index != expectedIndex) {
                    reject(ReplayReadRejection.NonSequentialFrame)
                }
                if (frame.timestampNanos <= previousTimestamp) {
                    reject(ReplayReadRejection.NonMonotonicTimestamp)
                }
                previousTimestamp = frame.timestampNanos
                frame
            }
            ReplayReadResult.Accepted(
                RecordedArSession(
                    sessionId = sessionId,
                    anchorPose = root.obj("anchorPose").readPose(),
                    frames = decodedFrames,
                ),
            )
        } catch (failure: RejectedValue) {
            rejected(failure.reason)
        } catch (_: RuntimeException) {
            rejected(ReplayReadRejection.InvalidValue)
        }
    }

    private fun JsonObject.readFrame(): ReplayFrame {
        requireKeys(
            "index",
            "timestampNanos",
            "trackingState",
            "cameraPose",
            "objects",
        )
        val objects = array("objects").map {
            it.jsonObject.readObject()
        }
        if (objects.size > MAX_OBJECTS) {
            reject(ReplayReadRejection.InvalidValue)
        }
        if (objects.map { it.objectId }.distinct().size != objects.size) {
            reject(ReplayReadRejection.DuplicateObjectId)
        }
        return ReplayFrame(
            index = number("index").toIntExact(),
            timestampNanos = number("timestampNanos"),
            trackingState = when (text("trackingState")) {
                "tracking" -> ReplayTrackingState.Tracking
                "paused" -> ReplayTrackingState.Paused
                "stopped" -> ReplayTrackingState.Stopped
                else -> reject(ReplayReadRejection.InvalidValue)
            },
            cameraPose = obj("cameraPose").readPose(),
            objects = objects,
        )
    }

    private fun JsonObject.readObject(): ReplayTrackedObject {
        requireKeys(
            "objectId",
            "currentPlanePositionMeters",
            "targetPlanePositionMeters",
            "matchState",
            "trackingValid",
        )
        val id = text("objectId")
        if (!SAFE_ID.matches(id)) {
            reject(ReplayReadRejection.InvalidValue)
        }
        val current = vector("currentPlanePositionMeters", 2)
        val target = vector("targetPlanePositionMeters", 2)
        return ReplayTrackedObject(
            objectId = id,
            currentX = current[0],
            currentZ = current[1],
            targetX = target[0],
            targetZ = target[1],
            matchState = when (text("matchState")) {
                "accepted" -> ReplayMatchState.Accepted
                "ambiguous" -> ReplayMatchState.Ambiguous
                "missing" -> ReplayMatchState.Missing
                else -> reject(ReplayReadRejection.InvalidValue)
            },
            trackingValid = getValue("trackingValid").jsonPrimitive.boolean,
        )
    }

    private fun JsonObject.readPose(): ReplayPose {
        requireKeys("translationMeters", "rotationQuaternion")
        val translation = vector("translationMeters", 3)
        val rotation = vector("rotationQuaternion", 4)
        if (rotation.any { it !in -1.0..1.0 }) {
            reject(ReplayReadRejection.InvalidValue)
        }
        return ReplayPose(
            translationMeters = ReplayVector3(
                translation[0],
                translation[1],
                translation[2],
            ),
            rotation = ReplayQuaternion(
                rotation[0],
                rotation[1],
                rotation[2],
                rotation[3],
            ),
        )
    }

    private fun JsonObject.requireKeys(vararg expected: String) {
        if (keys != expected.toSet()) {
            reject(ReplayReadRejection.UnknownField)
        }
    }

    private fun JsonObject.text(name: String): String =
        getValue(name).jsonPrimitive.content

    private fun JsonObject.number(name: String): Long =
        getValue(name).jsonPrimitive.long

    private fun JsonObject.obj(name: String): JsonObject =
        getValue(name).jsonObject

    private fun JsonObject.array(name: String): JsonArray =
        getValue(name).jsonArray

    private fun JsonObject.vector(
        name: String,
        size: Int,
    ): List<Double> {
        val values = array(name)
        if (values.size != size) {
            reject(ReplayReadRejection.InvalidValue)
        }
        return values.map { it.jsonPrimitive.double }
    }

    private fun Long.toIntExact(): Int {
        if (this !in 0..Int.MAX_VALUE.toLong()) {
            reject(ReplayReadRejection.InvalidValue)
        }
        return toInt()
    }

    private fun reject(reason: ReplayReadRejection): Nothing =
        throw RejectedValue(reason)

    private fun rejected(reason: ReplayReadRejection) =
        ReplayReadResult.Rejected(reason)

    private class RejectedValue(
        val reason: ReplayReadRejection,
    ) : RuntimeException()

    private const val MAX_FRAMES = 3_600
    private const val MAX_OBJECTS = 5
    private val SAFE_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
}
