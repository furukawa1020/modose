package com.modose.app.vision.tracking

import com.modose.app.ar.image.CpuCameraImage

internal interface CpuImageEvidenceSource : GuidanceImageEvidenceSource, AutoCloseable {
    fun <T> withImage(image: CpuCameraImage, action: () -> T): T
}

internal data class SemanticFrameMeasurements(
    val observation: GuidanceImageObservation,
    val pairs: List<SemanticPairMeasurement>,
    val occludesOther: Map<Int, Boolean>,
)

internal fun interface SemanticFrameMeasurementSource {
    /** Image-specific, local result only. No network request or fabricated fallback here. */
    fun read(observation: GuidanceImageObservation): SemanticFrameMeasurements?
}

/** Optional local semantics that require this frame's extracted features. */
internal interface AppearanceSemanticFrameMeasurementSource : SemanticFrameMeasurementSource {
    override fun read(observation: GuidanceImageObservation): SemanticFrameMeasurements? = null
    fun readMeasured(
        observation: GuidanceImageObservation,
        appearances: Map<Int, MeasuredAppearance>,
    ): SemanticFrameMeasurements?
}

/** Construct per session; all processing and close belong to the detector worker. */
internal class CpuMeasuredEvidenceSource(
    sceneId: String,
    baseline: List<SavedMeasuredAppearance>,
    private val semantics: SemanticFrameMeasurementSource,
    private val openExtractor: () -> AppearanceExtractorOpen,
) : CpuImageEvidenceSource {
    private var owner: Thread? = null
    private var activeImage: CpuCameraImage? = null
    private var extractor: AppearanceExtractor? = null
    private var startupFailure: AppearanceExtractionFailure? = null
    private var closed = false
    @Volatile
    var lastFailure: AppearanceExtractionFailure? = null
        private set
    private val measured = MeasuredGuidanceEvidenceSource(sceneId, baseline,
        GuidanceMeasurementReader { readMeasurements(it) })

    override fun <T> withImage(image: CpuCameraImage, action: () -> T): T {
        check(owner == null || owner === Thread.currentThread()) { "Evidence requires its owning worker" }
        check(!closed && activeImage == null) { "Evidence frame is unavailable" }
        owner = Thread.currentThread()
        activeImage = image
        try {
            return action()
        } finally {
            activeImage = null
        }
    }

    override fun resolve(observation: GuidanceImageObservation): GuidanceImageEvidence? {
        if (closed || owner !== Thread.currentThread() || activeImage == null) return null
        return measured.resolve(observation)
    }

    private fun readMeasurements(observation: GuidanceImageObservation): GuidanceFrameMeasurements? {
        lastFailure = null
        val image = activeImage ?: return null
        if (image.timestampNanos != observation.capture.imageTimestampNanos) return null
        if (observation.objects.size > 5) return null
        val semantic = if (semantics is AppearanceSemanticFrameMeasurementSource) null
            else semantics.read(observation) ?: return null
        val ids = observation.objects.map { it.currentId }.toSet()
        if (ids.size != observation.objects.size) return null
        fun finish(appearances: Map<Int, MeasuredAppearance>): GuidanceFrameMeasurements? {
            val resolved = semantic ?: (semantics as? AppearanceSemanticFrameMeasurementSource)
                ?.readMeasured(observation, appearances) ?: return null
            val pairs = resolved.pairs.toList()
            val occlusion = resolved.occludesOther.toMap()
            if (resolved.observation !== observation || occlusion.keys != ids || pairs.size > 25) return null
            return GuidanceFrameMeasurements(observation, appearances, pairs, occlusion)
        }
        if (semantic != null && (semantic.observation !== observation ||
                semantic.occludesOther.keys != ids || semantic.pairs.size > 25)) return null
        if (observation.objects.isEmpty()) return finish(emptyMap())
        startupFailure?.let { lastFailure = it; return null }
        val engine = extractor ?: when (val opened = openExtractor()) {
            is AppearanceExtractorOpen.Opened -> opened.extractor.also { extractor = it }
            is AppearanceExtractorOpen.Rejected -> {
                startupFailure = opened.reason
                lastFailure = opened.reason
                return null
            }
        }
        val appearances = LinkedHashMap<Int, MeasuredAppearance>()
        for (item in observation.objects) {
            when (val result = engine.extract(image, item.detection)) {
                is AppearanceExtractionResult.Rejected -> {
                    lastFailure = result.reason
                    return null
                }
                is AppearanceExtractionResult.Extracted -> {
                    if (result.imageTimestampNanos != image.timestampNanos || result.box != item.detection) {
                        lastFailure = AppearanceExtractionFailure.INVALID_OUTPUT
                        return null
                    }
                    appearances[item.currentId] = result.appearance
                }
            }
        }
        return finish(appearances)
    }

    override fun close() {
        check(owner == null || owner === Thread.currentThread()) { "Evidence close requires its owning worker" }
        if (closed) return
        closed = true
        activeImage = null
        val owned = extractor
        extractor = null
        owned?.close()
    }
}
