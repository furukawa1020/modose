package com.modose.app.core

import java.util.Collections
import kotlin.math.hypot

internal data class NativeComparedBox(val savedId: Int, val box: NativeImageBox)
internal data class NativeCandidateDistance(
    val currentId: Int, val savedId: Int,
    val currentPosition: NativeTablePosition?, val distanceMeters: Double?,
)
internal enum class NativeComparisonRejection { FRAME_MISMATCH, INVALID_OBJECT_SET, INVALID_RAY }
internal sealed interface NativeComparisonProjection {
    class Projected(distances: List<NativeCandidateDistance>) : NativeComparisonProjection {
        val distances: List<NativeCandidateDistance> = Collections.unmodifiableList(distances.toList())
    }
    data class Rejected(val reason: NativeComparisonRejection) : NativeComparisonProjection
}

/** Historical image measurements, never a live guide, identity decision or completion verdict. */
internal object NativeComparisonProjector {
    fun project(
        sceneId: String, imageTimestampNanos: Long, capture: NativeImageCapture,
        baseline: NativeBaselineTargets, candidates: List<NativeComparedBox>,
    ): NativeComparisonProjection {
        if (sceneId.isBlank() || capture.epoch.sceneId != sceneId ||
            capture.imageTimestampNanos != imageTimestampNanos
        ) return reject(NativeComparisonRejection.FRAME_MISMATCH)
        val objects = candidates.toList()
        val ids = baseline.copyIds()
        if (objects.size > 5 || objects.any { it.savedId !in ids || it.box.currentId <= 0 } ||
            objects.map { it.savedId }.toSet().size != objects.size ||
            objects.map { it.box.currentId }.toSet().size != objects.size
        ) return reject(NativeComparisonRejection.INVALID_OBJECT_SET)
        val rays = capture.project(NativeImageRecognition(
            capture.epoch, imageTimestampNanos, objects.map { it.box }, emptyList(),
        )) as? NativeImageProjection.Projected ?: return reject(NativeComparisonRejection.INVALID_RAY)
        val targets = baseline.copyPositions()
        val geometry = baseline.copyGeometry()
        val distances = objects.zip(rays.detections).map { (item, ray) ->
            // Stateless JNI: one unprojectable candidate does not invent a point or hide other candidates.
            val coordinates = try {
                NativeBaselineTargets.project(geometry, listOf(ray)).copyPositions()
            } catch (_: IllegalArgumentException) {
                null
            } catch (_: IllegalStateException) {
                null
            }
            val targetIndex = ids.indexOf(item.savedId) * 2
            val position = coordinates?.let { NativeTablePosition(it[0], it[1]) }
            val distance = position?.let {
                hypot(it.x - targets[targetIndex], it.z - targets[targetIndex + 1])
            }?.takeIf { it.isFinite() }
            NativeCandidateDistance(item.box.currentId, item.savedId,
                position.takeIf { distance != null }, distance)
        }
        return NativeComparisonProjection.Projected(distances)
    }

    private fun reject(reason: NativeComparisonRejection) = NativeComparisonProjection.Rejected(reason)
}
