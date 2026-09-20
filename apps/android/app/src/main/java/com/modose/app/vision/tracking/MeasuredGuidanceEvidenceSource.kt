package com.modose.app.vision.tracking

import com.modose.app.core.CorePairEvidence
import com.modose.app.core.NativeGuidanceEpoch

internal data class MeasuredAppearance(
    val embedding: ImageEmbedding,
    val signature: RgbAppearanceSignature,
)

internal data class SavedMeasuredAppearance(val savedId: Int, val appearance: MeasuredAppearance)

/** Semantic confidence and orientation must come from actual analysis, not tracker IDs. */
internal data class SemanticPairMeasurement(
    val savedId: Int,
    val currentId: Int,
    val confidence: Double,
    val orientationAligned: Boolean?,
)

internal data class GuidanceFrameMeasurements(
    val observation: GuidanceImageObservation,
    val appearances: Map<Int, MeasuredAppearance>,
    val semanticPairs: List<SemanticPairMeasurement>,
    val occludesOther: Map<Int, Boolean>,
)

internal fun interface GuidanceMeasurementReader {
    /** Return image-specific local measurements promptly; null means not ready. No network IO. */
    fun read(observation: GuidanceImageObservation): GuidanceFrameMeasurements?
}

/** One instance per guidance start. Baseline features must belong to sceneId. */
internal class MeasuredGuidanceEvidenceSource(
    private val sceneId: String,
    baseline: List<SavedMeasuredAppearance>,
    private val reader: GuidanceMeasurementReader,
) : GuidanceImageEvidenceSource {
    private val baseline = baseline.associate { it.savedId to it.appearance }
    private var epoch: NativeGuidanceEpoch? = null

    init {
        require(sceneId.isNotBlank())
        require(baseline.size in 1..5 && this.baseline.size == baseline.size)
        require(this.baseline.keys.all { it > 0 })
    }

    @Synchronized
    override fun resolve(observation: GuidanceImageObservation): GuidanceImageEvidence? {
        if (observation.capture.epoch.sceneId != sceneId) return null
        val owner = epoch
        if (owner != null && owner !== observation.capture.epoch) return null
        epoch = observation.capture.epoch
        val measurements = reader.read(observation) ?: return null
        if (measurements.observation !== observation) return null
        val current = measurements.appearances.toMap()
        val semantic = measurements.semanticPairs.toList()
        val occlusion = measurements.occludesOther.toMap()
        val ids = observation.objects.map { it.currentId }.toSet()
        if (ids.size != observation.objects.size || ids.any { it <= 0 } ||
            ids.size > 5 || current.keys != ids || occlusion.keys != ids || semantic.size > 25
        ) return null

        val pairs = ArrayList<CorePairEvidence>(semantic.size)
        val keys = HashSet<Pair<Int, Int>>()
        for (measurement in semantic) {
            if (!measurement.confidence.isFinite() || measurement.confidence !in 0.0..1.0 ||
                !keys.add(measurement.savedId to measurement.currentId)
            ) return null
            val saved = baseline[measurement.savedId] ?: return null
            val detected = current[measurement.currentId] ?: return null
            val orientation = measurement.orientationAligned ?: return null
            val embedding = saved.embedding.similarity(detected.embedding) ?: return null
            val signature = saved.signature.similarity(detected.signature) ?: return null
            pairs.add(CorePairEvidence(
                measurement.savedId, measurement.currentId, measurement.confidence,
                embedding, signature, orientation,
            ))
        }
        return GuidanceImageEvidence(observation, pairs, occlusion)
    }
}
