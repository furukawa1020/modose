package com.modose.app.vision.tracking

import android.content.Context
import android.graphics.Bitmap
import android.os.Looper
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.imageembedder.ImageEmbedder
import com.modose.app.ar.image.CpuCameraImage
import java.io.IOException
import java.security.MessageDigest

/** Create, extract, and close on one dedicated worker. Never call from UI or GL. */
internal class MediaPipeAppearanceExtractor private constructor(
    private val embedder: ImageEmbedder,
    private val modelId: String,
    private val owner: Thread,
) : AppearanceExtractor {
    private var closed = false

    override fun extract(image: CpuCameraImage, box: DetectedImageObject): AppearanceExtractionResult {
        if (Thread.currentThread() !== owner) return reject(AppearanceExtractionFailure.WRONG_THREAD)
        if (closed) return reject(AppearanceExtractionFailure.CLOSED)
        val converted = CpuObjectCropper.crop(image, box)
        if (converted !is ObjectCropResult.Cropped) return reject(AppearanceExtractionFailure.INVALID_IMAGE)
        val crop = converted.crop
        val signature = RgbAppearanceSignature.fromArgb(PREPROCESSING_ID, crop.pixels)
            ?: return reject(AppearanceExtractionFailure.INVALID_IMAGE)
        return try {
            val bitmap = Bitmap.createBitmap(crop.pixels, crop.width, crop.height, Bitmap.Config.ARGB_8888)
            try {
                val mpImage = BitmapImageBuilder(bitmap).build()
                try {
                    val heads = embedder.embed(mpImage).embeddingResult().embeddings()
                    if (heads.size != 1 || heads.single().headIndex() != 0) {
                        return reject(AppearanceExtractionFailure.INVALID_OUTPUT)
                    }
                    val values = heads.single().floatEmbedding()
                    if (values.size !in 1..4096) return reject(AppearanceExtractionFailure.INVALID_OUTPUT)
                    val embedding = ImageEmbedding.create(modelId, PREPROCESSING_ID,
                        DoubleArray(values.size) { values[it].toDouble() })
                        ?: return reject(AppearanceExtractionFailure.INVALID_OUTPUT)
                    AppearanceExtractionResult.Extracted(image.timestampNanos, box,
                        MeasuredAppearance(embedding, signature))
                } finally {
                    mpImage.close()
                }
            } finally {
                if (!bitmap.isRecycled) bitmap.recycle()
            }
        } catch (_: RuntimeException) {
            reject(AppearanceExtractionFailure.INFERENCE)
        } catch (_: LinkageError) {
            reject(AppearanceExtractionFailure.INFERENCE)
        }
    }

    override fun close() {
        check(Thread.currentThread() === owner) { "Extractor close requires its owning worker" }
        if (closed) return
        closed = true
        embedder.close()
    }

    private fun reject(reason: AppearanceExtractionFailure) = AppearanceExtractionResult.Rejected(reason)

    companion object {
        private const val PREPROCESSING_ID =
            CpuObjectCropper.PREPROCESSING_ID + ":mediapipe-1.0.0-image-head0-float"
        private const val MAX_MODEL_BYTES = 32L * 1024 * 1024

        fun open(context: Context, assetPath: String, expectedSha256: String): AppearanceExtractorOpen {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                return AppearanceExtractorOpen.Rejected(AppearanceExtractionFailure.WRONG_THREAD)
            }
            if (assetPath.isBlank() || assetPath.startsWith("/") || assetPath.split('/').any { it == ".." } ||
                !expectedSha256.matches(Regex("[a-f0-9]{64}"))
            ) return AppearanceExtractorOpen.Rejected(AppearanceExtractionFailure.INVALID_MODEL)
            val digest = MessageDigest.getInstance("SHA-256")
            try {
                context.assets.open(assetPath).use { input ->
                    val buffer = ByteArray(8192)
                    var total = 0L
                    while (true) {
                        val size = input.read(buffer)
                        if (size < 0) break
                        total += size
                        if (total > MAX_MODEL_BYTES) {
                            return AppearanceExtractorOpen.Rejected(AppearanceExtractionFailure.INVALID_MODEL)
                        }
                        digest.update(buffer, 0, size)
                    }
                    if (total == 0L) return AppearanceExtractorOpen.Rejected(AppearanceExtractionFailure.INVALID_MODEL)
                }
            } catch (_: IOException) {
                return AppearanceExtractorOpen.Rejected(AppearanceExtractionFailure.MODEL_UNAVAILABLE)
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
            if (actual != expectedSha256) {
                return AppearanceExtractorOpen.Rejected(AppearanceExtractionFailure.HASH_MISMATCH)
            }
            return try {
                val options = ImageEmbedder.ImageEmbedderOptions.builder()
                    .setBaseOptions(BaseOptions.builder().setModelAssetPath(assetPath).build())
                    .setRunningMode(RunningMode.IMAGE)
                    .setQuantize(false)
                    .build()
                AppearanceExtractorOpen.Opened(MediaPipeAppearanceExtractor(
                    ImageEmbedder.createFromOptions(context.applicationContext, options),
                    "sha256:$actual", Thread.currentThread()))
            } catch (_: RuntimeException) {
                AppearanceExtractorOpen.Rejected(AppearanceExtractionFailure.MODEL_INITIALIZATION)
            } catch (_: LinkageError) {
                AppearanceExtractorOpen.Rejected(AppearanceExtractionFailure.MODEL_INITIALIZATION)
            }
        }
    }
}
