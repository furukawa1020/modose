package com.modose.app.vision.tracking

import kotlin.math.min

/**
 * Transfers the Compare claim only through measured appearance compatibility.
 * Rust still owns global assignment and ambiguity margins. Tracker IDs are ignored.
 * Orientation remains unconfirmed unless the saved scene explicitly does not require it.
 * Neither this requirement nor local completion is a final verification verdict.
 */
internal class CompareFrameSemantics(
    private val comparison: MeasuredComparison,
    private val orientationPolicy: ConfirmedOrientationPolicy? = null,
) : AppearanceSemanticFrameMeasurementSource {
    init {
        require(comparison.sceneId.isNotBlank() && comparison.imageTimestampNanos > 0)
        require(comparison.objects.size in 1..5)
        require(comparison.objects.map { it.currentId }.toSet().size == comparison.objects.size)
        require(comparison.objects.map { it.claimedSavedId }.toSet().size == comparison.objects.size)
        require(comparison.objects.all { it.currentId > 0 && it.claimedSavedId > 0 })
        require(disjoint(comparison.objects.map { it.box }))
        for (seed in comparison.objects) {
            val claim = comparison.pairs.singleOrNull {
                it.savedId == seed.claimedSavedId && it.currentId == seed.currentId
            }
            require(claim?.semanticConfidence?.let { it.isFinite() && it in 0.0..1.0 } == true)
        }
    }

    override fun readMeasured(
        observation: GuidanceImageObservation,
        appearances: Map<Int, MeasuredAppearance>,
    ): SemanticFrameMeasurements? {
        if (observation.capture.epoch.sceneId != comparison.sceneId ||
            observation.capture.imageTimestampNanos <= comparison.imageTimestampNanos ||
            observation.objects.size > 5
        ) return null
        val ids = observation.objects.map { it.currentId }
        if (ids.any { it <= 0 } || ids.toSet().size != ids.size || appearances.keys != ids.toSet()) return null
        // Overlapping rectangles do not establish who occludes whom. Withhold the frame.
        if (!disjoint(observation.objects.map { it.detection })) return null
        val pairs = ArrayList<SemanticPairMeasurement>()
        for (seed in comparison.objects) {
            val confidence = comparison.pairs.single {
                it.savedId == seed.claimedSavedId && it.currentId == seed.currentId
            }.semanticConfidence ?: return null
            for (item in observation.objects) {
                val fresh = appearances.getValue(item.currentId)
                val embedding = seed.appearance.embedding.similarity(fresh.embedding) ?: return null
                val signature = seed.appearance.signature.similarity(fresh.signature) ?: return null
                // Derived confidence cannot exceed the original VLM claim or either measured feature.
                pairs.add(SemanticPairMeasurement(seed.claimedSavedId, item.currentId,
                    min(confidence, min(embedding, signature)), orientationAligned =
                        orientationPolicy?.satisfiedWithoutMeasurement(comparison.sceneId, seed.claimedSavedId) == true))
            }
        }
        // Only disjoint observed rectangles reach here; no priority is inferred for unseen objects.
        return SemanticFrameMeasurements(observation, pairs, ids.associateWith { false })
    }

    private fun disjoint(boxes: List<DetectedImageObject>): Boolean {
        if (boxes.any { it.left < 0 || it.top < 0 || it.right <= it.left || it.bottom <= it.top }) return false
        for (i in boxes.indices) for (j in 0 until i) {
            val a = boxes[i]
            val b = boxes[j]
            if (a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom) return false
        }
        return true
    }
}
