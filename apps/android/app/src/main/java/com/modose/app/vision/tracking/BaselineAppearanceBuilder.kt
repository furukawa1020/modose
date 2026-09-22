package com.modose.app.vision.tracking

import com.modose.app.ar.image.CpuCameraImage
import com.modose.app.ar.image.VlmImageEncodingPlan
import com.modose.app.network.baseline.BaselineObject
import java.util.Collections

internal enum class BaselineAppearanceFailure {
    INVALID_SCENE, INVALID_OBJECTS, INVALID_IMAGE_MAPPING, EXTRACTION, INVALID_OUTPUT, INCOMPATIBLE_FEATURES,
}

internal sealed interface BaselineAppearanceResult {
    data class Built(val baseline: MeasuredBaseline) : BaselineAppearanceResult
    data class Rejected(
        val reason: BaselineAppearanceFailure,
        val extractionFailure: AppearanceExtractionFailure? = null,
    ) : BaselineAppearanceResult
}

/** Session-local result. Does not retain CPU pixels or persist embeddings to Room. */
internal class MeasuredBaseline internal constructor(
    val sceneId: String,
    val imageTimestampNanos: Long,
    objectIds: Map<String, Int>,
    appearances: List<SavedMeasuredAppearance>,
) {
    val objectIds: Map<String, Int> = Collections.unmodifiableMap(LinkedHashMap(objectIds))
    val appearances: List<SavedMeasuredAppearance> = Collections.unmodifiableList(appearances.toList())

    fun createEvidenceSource(
        semantics: SemanticFrameMeasurementSource,
        openExtractor: () -> AppearanceExtractorOpen,
    ): CpuMeasuredEvidenceSource = CpuMeasuredEvidenceSource(sceneId, appearances, semantics, openExtractor)
}

/**
 * Called on the extractor's owning worker with the CPU image and encoding plan retained
 * for this baseline request. The caller owns the extractor and releases it in finally.
 */
internal object BaselineAppearanceBuilder {
    fun build(
        sceneId: String,
        image: CpuCameraImage,
        plan: VlmImageEncodingPlan,
        objects: List<BaselineObject>,
        extractor: AppearanceExtractor,
    ): BaselineAppearanceResult {
        if (sceneId.isBlank()) return rejected(BaselineAppearanceFailure.INVALID_SCENE)
        if (objects.size !in 1..5) return rejected(BaselineAppearanceFailure.INVALID_OBJECTS)
        // Snapshot only immutable fields needed below; never retain caller-owned lists.
        val targets = objects.map { it.id to it.boundingBox }
        if (targets.any { it.first.isBlank() || it.first.length > 64 } ||
            targets.map { it.first }.toSet().size != targets.size
        ) return rejected(BaselineAppearanceFailure.INVALID_OBJECTS)
        val boxes = targets.map { (_, box) ->
            BaselineCpuBoxMapper.map(image, plan, box)
                ?: return rejected(BaselineAppearanceFailure.INVALID_IMAGE_MAPPING)
        }
        val saved = ArrayList<SavedMeasuredAppearance>(targets.size)
        val ids = LinkedHashMap<String, Int>()
        for ((index, box) in boxes.withIndex()) {
            val result = try {
                extractor.extract(image, box)
            } catch (_: RuntimeException) {
                return rejected(BaselineAppearanceFailure.EXTRACTION, AppearanceExtractionFailure.INFERENCE)
            }
            when (result) {
                is AppearanceExtractionResult.Rejected ->
                    return rejected(BaselineAppearanceFailure.EXTRACTION, result.reason)
                is AppearanceExtractionResult.Extracted -> {
                    if (result.imageTimestampNanos != image.timestampNanos || result.box != box) {
                        return rejected(BaselineAppearanceFailure.INVALID_OUTPUT)
                    }
                    val reference = saved.firstOrNull()?.appearance
                    if (reference != null &&
                        (reference.embedding.similarity(result.appearance.embedding) == null ||
                            reference.signature.similarity(result.appearance.signature) == null)
                    ) return rejected(BaselineAppearanceFailure.INCOMPATIBLE_FEATURES)
                    val savedId = index + 1
                    saved.add(SavedMeasuredAppearance(savedId, result.appearance))
                    ids[targets[index].first] = savedId
                }
            }
        }
        return BaselineAppearanceResult.Built(MeasuredBaseline(sceneId, image.timestampNanos, ids, saved))
    }

    private fun rejected(
        reason: BaselineAppearanceFailure,
        extraction: AppearanceExtractionFailure? = null,
    ) = BaselineAppearanceResult.Rejected(reason, extraction)
}
