package com.modose.app.flow.compare

import com.modose.app.ar.image.VlmJpegImage
import com.modose.app.ar.render.BaselineCaptureValidity
import com.modose.app.flow.verification.ExecuteVerificationInput
import com.modose.app.network.*
import com.modose.app.network.baseline.BaselineObject
import com.modose.app.network.compare.*
import java.time.Instant
import java.util.Collections
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

internal sealed interface SavedCompareResult {
    data class Compared(val analysis: CompareAnalysis) : SavedCompareResult
    data class TransportFailure(val failure: VisionApiResult) : SavedCompareResult
    data object InvalidCapture : SavedCompareResult
    data object InvalidResponse : SavedCompareResult
    data object AlreadyAttempted : SavedCompareResult
}

/** One Compare invocation per saved scene; no retry or previous-success fallback. */
internal class SavedSceneComparison(
    private val sceneId: String,
    private val savedTimestampNanos: Long,
    val validity: BaselineCaptureValidity,
    image: VlmJpegImage,
    objects: List<BaselineObject>,
) {
    private val baseline = image.copy(bytes = image.bytes.copyOf())
    private val confirmedJson = requireNotNull(ConfirmedObjectsEncoder.encode(objects))
    val labels: Map<String, String> = Collections.unmodifiableMap(objects.associate { it.id to it.displayName })
    private val expectedIds = labels.keys.toList()
    private val attempted = AtomicBoolean(false)
    val hasAttempted: Boolean get() = attempted.get()

    @Volatile
    var verificationAttempts: Int = 0
        private set
    private var lastVerificationImage = savedTimestampNanos

    /** Reserve only after Rust issues a ticket. No automatic transport retry. */
    @Synchronized
    fun reserveVerification(
        currentValidity: BaselineCaptureValidity,
        timestampNanos: Long,
        finalImage: VlmJpegImage,
    ): ExecuteVerificationInput? {
        if (!hasAttempted || !validity.isCurrent || currentValidity !== validity ||
            verificationAttempts >= 3 || timestampNanos <= lastVerificationImage ||
            finalImage.mimeType != VlmJpegImage.MIME_TYPE || finalImage.bytes.size !in 1..2_000_000
        ) return null
        val now = Instant.now()
        val random = UUID.randomUUID()
        val key = UUID((now.toEpochMilli() shl 16) or 0x7000L or
            (random.mostSignificantBits and 0xfffL),
            (random.leastSignificantBits and 0x3fffffffffffffffL) or Long.MIN_VALUE).toString()
        val input = ExecuteVerificationInput(sceneId, now, key,
            baseline.copy(bytes = baseline.bytes.copyOf()),
            finalImage.copy(bytes = finalImage.bytes.copyOf()), confirmedJson, expectedIds.toSet())
        verificationAttempts++
        lastVerificationImage = timestampNanos
        return input
    }

    init {
        require(savedTimestampNanos > 0 && image.mimeType == VlmJpegImage.MIME_TYPE)
        require(image.bytes.size in 1..2_000_000)
    }

    fun execute(
        currentValidity: BaselineCaptureValidity,
        currentTimestampNanos: Long,
        currentImage: VlmJpegImage,
        checkActive: () -> Unit,
        send: (VisionApiRequest) -> VisionApiResult,
    ): SavedCompareResult {
        checkActive()
        fun valid() = validity.isCurrent && currentValidity === validity &&
            currentTimestampNanos > savedTimestampNanos
        if (!valid()) return SavedCompareResult.InvalidCapture
        if (attempted.get()) return SavedCompareResult.AlreadyAttempted
        val now = Instant.now()
        val random = UUID.randomUUID()
        // UUIDv7: 48-bit Unix milliseconds, version 7, 12 random bits, RFC variant.
        val key = UUID((now.toEpochMilli() shl 16) or 0x7000L or
            (random.mostSignificantBits and 0xfffL),
            (random.leastSignificantBits and 0x3fffffffffffffffL) or Long.MIN_VALUE).toString()
        val built = CompareVisionRequestFactory.create(sceneId, now, key, baseline, currentImage, confirmedJson)
            as? CompareRequestBuildResult.Built ?: return SavedCompareResult.InvalidCapture
        checkActive()
        if (!valid()) return SavedCompareResult.InvalidCapture
        if (!attempted.compareAndSet(false, true)) return SavedCompareResult.AlreadyAttempted
        val response = send(built.request)
        checkActive()
        if (!valid()) return SavedCompareResult.InvalidCapture
        if (response !is VisionApiResult.Success) return SavedCompareResult.TransportFailure(response)
        val analysis = CompareAnalysisDecoder.decode(response.body, expectedIds)
            ?: return SavedCompareResult.InvalidResponse
        if (!valid()) return SavedCompareResult.InvalidCapture
        return SavedCompareResult.Compared(analysis)
    }
}
