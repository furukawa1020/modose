package com.modose.app.vision.tracking

import com.modose.app.ar.image.CpuCameraImage
import com.modose.app.ar.image.VlmImageEncodingPlan
import com.modose.app.flow.compare.CurrentObjectState
import com.modose.app.network.compare.CompareAnalysis
import java.util.Collections

internal enum class CompareAppearanceFailure {
    INVALID_BASELINE, INVALID_IMAGE, INVALID_MATCHES, INVALID_MAPPING,
    COMPETING_CROPS, EXTRACTION, INVALID_OUTPUT, INCOMPATIBLE_FEATURES,
}
internal sealed interface CompareAppearanceResult {
    data class Built(val measurements: MeasuredComparison) : CompareAppearanceResult
    data class Rejected(val reason: CompareAppearanceFailure) : CompareAppearanceResult
}
/** currentId identifies a candidate in THIS image, not an ML Kit tracking ID. */
internal data class ComparedAppearance(
    val currentId: Int, val claimedSavedId: Int,
    val box: DetectedImageObject, val appearance: MeasuredAppearance,
)
internal data class ComparedAppearancePair(
    val savedId: Int, val currentId: Int,
    val embeddingSimilarity: Double, val signatureSimilarity: Double,
    // Null means the VLM did not supply semantic evidence for this pair.
    val semanticConfidence: Double?,
)
/** Immutable, image-bound measurements. Not a tracking lease or restoration verdict. */
internal class MeasuredComparison internal constructor(
    val sceneId: String, val imageTimestampNanos: Long,
    objects: List<ComparedAppearance>, pairs: List<ComparedAppearancePair>,
) {
    val objects: List<ComparedAppearance> = Collections.unmodifiableList(objects.toList())
    val pairs: List<ComparedAppearancePair> = Collections.unmodifiableList(pairs.toList())
}

internal object CompareAppearanceBuilder {
    fun build(
        baseline: MeasuredBaseline, analysis: CompareAnalysis, image: CpuCameraImage,
        plan: VlmImageEncodingPlan, extractor: AppearanceExtractor,
        checkActive: () -> Unit = {},
    ): CompareAppearanceResult {
        checkActive()
        val saved = baseline.appearances.associate { it.savedId to it.appearance }
        val ids = baseline.objectIds
        if (baseline.sceneId.isBlank() || ids.size !in 1..5 ||
            ids.values.any { it <= 0 } || ids.values.toSet().size != ids.size ||
            baseline.appearances.size != ids.size || saved.keys != ids.values.toSet()
        ) return rejected(CompareAppearanceFailure.INVALID_BASELINE)
        if (baseline.imageTimestampNanos <= 0 || image.timestampNanos <= baseline.imageTimestampNanos ||
            image.widthPx <= 0 || image.heightPx <= 0 ||
            image.widthPx.toLong() * image.heightPx > 1920L * 1080L
        ) return rejected(CompareAppearanceFailure.INVALID_IMAGE)
        val matches = analysis.matches.toList()
        if (matches.size != ids.size || matches.map { it.baselineObjectId }.toSet() != ids.keys ||
            matches.any { !it.confidence.isFinite() || it.confidence !in 0.0..1.0 }
        ) return rejected(CompareAppearanceFailure.INVALID_MATCHES)
        val byId = matches.associateBy { it.baselineObjectId }
        val candidates = ids.keys.map { byId.getValue(it) }.filter { it.state in positioned }
        // Preflight ALL crops before borrowing the extractor, including duplicate regions.
        val boxes = candidates.map { candidate ->
            val box = candidate.currentBox ?: return rejected(CompareAppearanceFailure.INVALID_MAPPING)
            BaselineCpuBoxMapper.map(image, plan, box)
                ?: return rejected(CompareAppearanceFailure.INVALID_MAPPING)
        }
        if (boxes.toSet().size != boxes.size) return rejected(CompareAppearanceFailure.COMPETING_CROPS)
        val measured = ArrayList<ComparedAppearance>(candidates.size)
        val pairs = ArrayList<ComparedAppearancePair>(candidates.size * saved.size)
        for ((index, box) in boxes.withIndex()) {
            checkActive()
            val extracted = try {
                extractor.extract(image, box)
            } catch (_: RuntimeException) {
                return rejected(CompareAppearanceFailure.EXTRACTION)
            }
            checkActive()
            if (extracted !is AppearanceExtractionResult.Extracted) return rejected(CompareAppearanceFailure.EXTRACTION)
            if (extracted.imageTimestampNanos != image.timestampNanos || extracted.box != box) {
                return rejected(CompareAppearanceFailure.INVALID_OUTPUT)
            }
            val candidate = candidates[index]
            val claimedId = ids.getValue(candidate.baselineObjectId)
            val currentId = index + 1
            for ((savedId, reference) in saved) {
                val embedding = reference.embedding.similarity(extracted.appearance.embedding)
                    ?: return rejected(CompareAppearanceFailure.INCOMPATIBLE_FEATURES)
                val signature = reference.signature.similarity(extracted.appearance.signature)
                    ?: return rejected(CompareAppearanceFailure.INCOMPATIBLE_FEATURES)
                pairs.add(ComparedAppearancePair(savedId, currentId, embedding, signature,
                    candidate.confidence.takeIf { savedId == claimedId }))
            }
            measured.add(ComparedAppearance(currentId, claimedId, box, extracted.appearance))
        }
        checkActive()
        return CompareAppearanceResult.Built(MeasuredComparison(
            baseline.sceneId, image.timestampNanos, measured, pairs))
    }

    private fun rejected(reason: CompareAppearanceFailure) = CompareAppearanceResult.Rejected(reason)
    private val positioned = setOf(CurrentObjectState.Aligned, CurrentObjectState.Moved,
        CurrentObjectState.Rotated, CurrentObjectState.MovedRotated)
}
