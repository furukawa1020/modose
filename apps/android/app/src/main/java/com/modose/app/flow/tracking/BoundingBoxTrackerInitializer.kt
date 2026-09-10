package com.modose.app.flow.tracking

import com.modose.app.flow.compare.CompareBoundingBox
import com.modose.app.flow.compare.CurrentObjectState
import kotlin.math.max
import kotlin.math.min

object BoundingBoxTrackerInitializer {
    private const val MIN_IOU = 0.25

    fun initialize(input: TrackerInitializationInput): TrackerInitializationResult {
        validate(input)?.let { return TrackerInitializationResult.Rejected(it) }

        val fixedAssignments = mutableMapOf<String, TrackerObjectAssignment>()
        val assignableObjects = input.objects
            .sortedBy { it.sceneObjectId }
            .filter { item ->
                when (item.state) {
                    CurrentObjectState.Missing -> {
                        fixedAssignments[item.sceneObjectId] =
                            TrackerObjectAssignment.Missing(item.sceneObjectId)
                        false
                    }
                    CurrentObjectState.Ambiguous -> {
                        fixedAssignments[item.sceneObjectId] =
                            TrackerObjectAssignment.Ambiguous(item.sceneObjectId)
                        false
                    }
                    else -> true
                }
            }

        val scores = assignableObjects.flatMap { item ->
            input.detections.map { detection ->
                PairScore(
                    item = item,
                    detection = detection,
                    iou = intersectionOverUnion(
                        requireNotNull(item.currentBoundingBox),
                        detection.boundingBox,
                    ),
                )
            }
        }.filter { it.iou >= MIN_IOU }
            .sortedWith(
                compareByDescending<PairScore> { it.iou }
                    .thenBy { it.item.sceneObjectId }
                    .thenBy { it.detection.trackingId },
            )

        val assignedObjects = mutableSetOf<String>()
        val assignedTrackingIds = mutableSetOf<Int>()
        scores.forEach { score ->
            if (
                score.item.sceneObjectId !in assignedObjects &&
                score.detection.trackingId !in assignedTrackingIds
            ) {
                assignedObjects += score.item.sceneObjectId
                assignedTrackingIds += score.detection.trackingId
                fixedAssignments[score.item.sceneObjectId] =
                    TrackerObjectAssignment.Trackable(
                        sceneObjectId = score.item.sceneObjectId,
                        trackingId = score.detection.trackingId,
                        confidence = (
                            score.iou *
                                score.item.sameObjectConfidence *
                                score.detection.detectionConfidence
                            ).coerceIn(0.0, 1.0),
                        reason = TrackerAssignmentReason.BoundingBoxMatch,
                    )
            }
        }

        assignableObjects
            .filterNot { it.sceneObjectId in assignedObjects }
            .forEach { item ->
                fixedAssignments[item.sceneObjectId] =
                    TrackerObjectAssignment.StaticGuide(
                        sceneObjectId = item.sceneObjectId,
                        currentBoundingBox = requireNotNull(item.currentBoundingBox),
                        reason = TrackerAssignmentReason.NoDetectionCandidate,
                    )
            }

        return TrackerInitializationResult.Initialized(
            TrackerInitializationPlan(
                assignments = fixedAssignments.values.sortedBy { it.sceneObjectId },
            ),
        )
    }

    private fun validate(input: TrackerInitializationInput): TrackerInitializationFailure? {
        if (input.objects.size !in
            TrackerInitializationContract.MIN_OBJECTS..TrackerInitializationContract.MAX_OBJECTS
        ) {
            return TrackerInitializationFailure.ObjectCountOutOfRange
        }
        if (input.objects.map { it.sceneObjectId }.distinct().size != input.objects.size) {
            return TrackerInitializationFailure.DuplicateSceneObjectId
        }
        if (input.detections.map { it.trackingId }.distinct().size != input.detections.size) {
            return TrackerInitializationFailure.DuplicateTrackingId
        }
        if (
            input.objects.any { it.sameObjectConfidence !in 0.0..1.0 } ||
            input.detections.any {
                it.detectionConfidence !in 0.0..1.0 ||
                    (it.embeddingSimilarity != null && it.embeddingSimilarity !in 0.0..1.0)
            }
        ) {
            return TrackerInitializationFailure.InvalidConfidence
        }
        if (
            input.detections.any { !it.boundingBox.isValid() } ||
            input.objects.any {
                it.currentBoundingBox != null && !it.currentBoundingBox.isValid()
            }
        ) {
            return TrackerInitializationFailure.InvalidBoundingBox
        }
        if (
            input.objects.any {
                it.state == CurrentObjectState.Missing && it.currentBoundingBox != null
            }
        ) {
            return TrackerInitializationFailure.MissingStateHasCoordinates
        }
        if (
            input.objects.any {
                it.state == CurrentObjectState.Ambiguous && it.currentBoundingBox != null
            }
        ) {
            return TrackerInitializationFailure.AmbiguousStateHasCoordinates
        }
        if (
            input.objects.any {
                it.state != CurrentObjectState.Missing &&
                    it.state != CurrentObjectState.Ambiguous &&
                    it.currentBoundingBox == null
            }
        ) {
            return TrackerInitializationFailure.InvalidBoundingBox
        }
        return null
    }

    private fun intersectionOverUnion(
        first: CompareBoundingBox,
        second: CompareBoundingBox,
    ): Double {
        val width = max(0, min(first.xMax, second.xMax) - max(first.xMin, second.xMin))
        val height = max(0, min(first.yMax, second.yMax) - max(first.yMin, second.yMin))
        val intersection = width.toLong() * height.toLong()
        val firstArea = (first.xMax - first.xMin).toLong() * (first.yMax - first.yMin)
        val secondArea = (second.xMax - second.xMin).toLong() * (second.yMax - second.yMin)
        val union = firstArea + secondArea - intersection
        return if (union == 0L) 0.0 else intersection.toDouble() / union.toDouble()
    }

    private fun CompareBoundingBox.isValid(): Boolean =
        yMin in 0..999 &&
            xMin in 0..999 &&
            yMax in 1..1000 &&
            xMax in 1..1000 &&
            yMin < yMax &&
            xMin < xMax

    private data class PairScore(
        val item: TrackerInitializationObject,
        val detection: TrackerDetectionCandidate,
        val iou: Double,
    )
}
