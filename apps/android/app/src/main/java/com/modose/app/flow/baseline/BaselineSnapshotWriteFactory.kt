package com.modose.app.flow.baseline

import com.modose.app.data.local.SceneObjectOrigin
import com.modose.app.data.local.SceneObjectWrite
import com.modose.app.data.local.SceneSnapshotWrite
import com.modose.app.network.baseline.BaselineObject
import com.modose.app.vision.tracking.MeasuredBaseline

internal object BaselineSnapshotWriteFactory {
    fun create(
        review: BaselineImageReview,
        measured: MeasuredBaseline,
        objects: List<BaselineObject>,
    ): SceneSnapshotWrite {
        review.validity.requireCurrent()
        val capture = review.reviewing.capture
        val ids = objects.map { it.id }
        require(objects.size in 1..5 && ids.toSet().size == ids.size)
        require(measured.sceneId == capture.sceneId &&
            measured.imageTimestampNanos == review.image.timestampNanos &&
            measured.objectIds.keys == ids.toSet() &&
            measured.appearances.size == objects.size)
        val detectedIds = review.reviewing.analysis.objects.map { it.id }.toSet()
        return SceneSnapshotWrite(
            sceneId = capture.sceneId,
            createdAt = capture.capturedAt,
            modelId = review.reviewing.analysis.modelId,
            // BaselineAnalysisDecoder accepts exactly this prompt version.
            promptVersion = "baseline-v1",
            repaired = review.reviewing.analysis.repaired,
            jpeg = capture.image.bytes.copyOf(),
            objects = objects.map { value ->
                SceneObjectWrite(value.copy(appearanceFeatures = value.appearanceFeatures.toList()),
                    if (value.id in detectedIds) SceneObjectOrigin.Detected else SceneObjectOrigin.Manual)
            },
        )
    }
}
