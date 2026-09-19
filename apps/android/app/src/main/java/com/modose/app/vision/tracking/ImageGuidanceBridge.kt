package com.modose.app.vision.tracking

import com.modose.app.core.CorePairEvidence
import com.modose.app.core.NativeFrameDropReason
import com.modose.app.core.NativeFrameProcessing
import com.modose.app.core.NativeGuidancePipeline
import com.modose.app.core.NativeImageBox
import com.modose.app.core.NativeImageCapture
import com.modose.app.core.NativeImageRecognition
import com.modose.app.core.NativeRecognitionFrame
import java.util.Collections

/** currentId is local to this image; trackingId is only a hint for the evidence provider. */
internal data class GuidanceImageObject(
    val currentId: Int,
    val detection: DetectedImageObject,
)

internal class GuidanceImageObservation(
    val capture: NativeImageCapture,
    objects: List<GuidanceImageObject>,
) {
    val objects: List<GuidanceImageObject> = Collections.unmodifiableList(objects.toList())
}

/** Identity of observation binds every score and occlusion flag to the exact detector result. */
internal data class GuidanceImageEvidence(
    val observation: GuidanceImageObservation,
    val pairs: List<CorePairEvidence>,
    val occludesOther: Map<Int, Boolean>,
)

internal fun interface GuidanceImageEvidenceSource {
    /** Local, bounded lookup only. Return null until evidence exists; never perform network IO. */
    fun resolve(observation: GuidanceImageObservation): GuidanceImageEvidence?
}

internal enum class ImageGuidanceRejection {
    FOREIGN_STREAM, NON_INCREASING, DETECTION_FAILED, INVALID_OBJECTS,
    EVIDENCE_UNAVAILABLE, EVIDENCE_FAILED, INVALID_EVIDENCE, PROJECTION,
}

internal data class ImageGuidanceDelivery(
    val rejection: ImageGuidanceRejection?,
    val processing: NativeFrameProcessing,
)

/** No fabricated scores, orientation, occlusion, or world positions. */
internal class ImageGuidanceBridge(
    private val pipeline: NativeGuidancePipeline,
    savedIds: IntArray,
    private val evidenceSource: GuidanceImageEvidenceSource,
) {
    private val savedIds = savedIds.toSet()
    private var lastImageTimestamp = 0L

    init {
        require(savedIds.size in 1..5 && this.savedIds.size == savedIds.size)
        require(this.savedIds.all { it > 0 })
    }

    @Synchronized
    fun process(capture: NativeImageCapture, result: ImageDetectionResult): ImageGuidanceDelivery {
        if (capture.epoch !== pipeline.epoch) {
            return ImageGuidanceDelivery(ImageGuidanceRejection.FOREIGN_STREAM,
                NativeFrameProcessing.Dropped(NativeFrameDropReason.FOREIGN_STREAM))
        }
        if (capture.imageTimestampNanos <= lastImageTimestamp) {
            return ImageGuidanceDelivery(ImageGuidanceRejection.NON_INCREASING,
                NativeFrameProcessing.Dropped(NativeFrameDropReason.NON_INCREASING))
        }
        lastImageTimestamp = capture.imageTimestampNanos
        if (result !is ImageDetectionResult.Detected) {
            return invalidate(capture, ImageGuidanceRejection.DETECTION_FAILED)
        }
        val detections = result.objects.toList()
        val trackingIds = detections.mapNotNull { it.trackingId }
        if (detections.size > 5 || trackingIds.toSet().size != trackingIds.size ||
            detections.any {
                it.left < 0 || it.top < 0 || it.left >= it.right || it.top >= it.bottom
            }
        ) return invalidate(capture, ImageGuidanceRejection.INVALID_OBJECTS)

        val observation = GuidanceImageObservation(capture,
            detections.mapIndexed { index, detection -> GuidanceImageObject(index + 1, detection) })
        val evidence = try {
            evidenceSource.resolve(observation)
        } catch (_: RuntimeException) {
            return invalidate(capture, ImageGuidanceRejection.EVIDENCE_FAILED)
        } ?: return invalidate(capture, ImageGuidanceRejection.EVIDENCE_UNAVAILABLE)
        if (evidence.observation !== observation) {
            return invalidate(capture, ImageGuidanceRejection.INVALID_EVIDENCE)
        }
        val pairs = evidence.pairs.toList()
        val occlusion = evidence.occludesOther.toMap()
        val currentIds = observation.objects.map { it.currentId }.toSet()
        val pairKeys = HashSet<Pair<Int, Int>>()
        if (pairs.size > 25 || occlusion.keys != currentIds || pairs.any {
                it.savedId !in savedIds || it.currentId !in currentIds ||
                    !pairKeys.add(it.savedId to it.currentId) ||
                    !validScore(it.semanticScore) || !validScore(it.embeddingScore) ||
                    !validScore(it.signatureScore)
            }
        ) return invalidate(capture, ImageGuidanceRejection.INVALID_EVIDENCE)

        val boxes = observation.objects.map { item ->
            val box = item.detection
            NativeImageBox(item.currentId, box.left.toDouble(), box.top.toDouble(),
                box.right.toDouble(), box.bottom.toDouble(), occlusion.getValue(item.currentId))
        }
        val submission = pipeline.submitImage(capture,
            NativeImageRecognition(capture.epoch, capture.imageTimestampNanos, boxes, pairs))
        return ImageGuidanceDelivery(
            if (submission.projectionError == null) null else ImageGuidanceRejection.PROJECTION,
            submission.processing,
        )
    }

    private fun invalidate(
        capture: NativeImageCapture,
        reason: ImageGuidanceRejection,
    ): ImageGuidanceDelivery = ImageGuidanceDelivery(reason, pipeline.submit(
        NativeRecognitionFrame(capture.epoch, capture.observedAtMs, false, emptyList(), emptyList()),
    ))

    private fun validScore(value: Double): Boolean = value.isFinite() && value in 0.0..1.0
}
