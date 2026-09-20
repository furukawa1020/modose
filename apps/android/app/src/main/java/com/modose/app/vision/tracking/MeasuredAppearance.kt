package com.modose.app.vision.tracking

import kotlin.math.abs
import kotlin.math.sqrt

/** Immutable unit vector. Model identity includes the exact embedding model revision. */
internal class ImageEmbedding private constructor(
    private val modelId: String,
    private val preprocessingId: String,
    private val unit: DoubleArray,
) {
    /** Orthogonal/negative cosine provides zero support, not a probability estimate. */
    fun similarity(other: ImageEmbedding): Double? {
        if (modelId != other.modelId || preprocessingId != other.preprocessingId ||
            unit.size != other.unit.size
        ) return null
        var dot = 0.0
        for (index in unit.indices) dot += unit[index] * other.unit[index]
        return dot.coerceIn(0.0, 1.0)
    }

    companion object {
        fun create(modelId: String, preprocessingId: String, values: DoubleArray): ImageEmbedding? {
            if (modelId.isBlank() || modelId.length > 256 ||
                preprocessingId.isBlank() || preprocessingId.length > 256 ||
                values.size !in 1..4096
            ) return null
            val copy = values.copyOf()
            if (copy.any { !it.isFinite() }) return null
            val scale = copy.maxOf { abs(it) }
            if (scale == 0.0) return null
            // Scale before squaring, so very large or very small finite vectors remain usable.
            var normSquared = 0.0
            for (index in copy.indices) {
                copy[index] /= scale
                normSquared += copy[index] * copy[index]
            }
            val norm = sqrt(normSquared)
            for (index in copy.indices) copy[index] /= norm
            return ImageEmbedding(modelId, preprocessingId, copy)
        }
    }
}

/** Independent appearance cue: 16 bins per RGB channel, without positions or orientation. */
internal class RgbAppearanceSignature private constructor(
    private val preprocessingId: String,
    private val probabilities: DoubleArray,
) {
    fun similarity(other: RgbAppearanceSignature): Double? {
        if (preprocessingId != other.preprocessingId) return null
        var coefficient = 0.0
        for (index in probabilities.indices) {
            coefficient += sqrt(probabilities[index] * other.probabilities[index])
        }
        return coefficient.coerceIn(0.0, 1.0)
    }

    companion object {
        /** The caller supplies the object's crop, not the whole scene. Alpha must be opaque. */
        fun fromArgb(preprocessingId: String, pixels: IntArray): RgbAppearanceSignature? {
            if (preprocessingId.isBlank() || preprocessingId.length > 256 ||
                pixels.isEmpty() || pixels.size > 1920 * 1080
            ) return null
            val bins = DoubleArray(48)
            var count = 0
            for (pixel in pixels) {
                if (pixel ushr 24 != 255) continue
                bins[(pixel ushr 20) and 15] += 1.0
                bins[16 + ((pixel ushr 12) and 15)] += 1.0
                bins[32 + ((pixel ushr 4) and 15)] += 1.0
                count++
            }
            if (count == 0) return null
            val total = count * 3.0
            for (index in bins.indices) bins[index] /= total
            return RgbAppearanceSignature(preprocessingId, bins)
        }
    }
}
