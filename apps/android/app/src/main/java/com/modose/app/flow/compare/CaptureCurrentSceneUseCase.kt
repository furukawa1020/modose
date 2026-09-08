package com.modose.app.flow.compare

import com.modose.app.ar.image.CpuCameraImage
import com.modose.app.ar.image.CpuCameraImageValidator
import com.modose.app.ar.image.CpuImageAcquisitionResult
import com.modose.app.ar.image.CpuImageFailureReason
import com.modose.app.ar.image.CpuImageRuntimeSkipReason
import com.modose.app.ar.image.PixelRoi
import com.modose.app.ar.image.VlmImageEncodingPlan
import com.modose.app.ar.image.VlmImageEncodingPlanner
import com.modose.app.ar.image.VlmImageFailureReason
import com.modose.app.ar.image.VlmImagePlanResult
import com.modose.app.ar.image.VlmJpegEncoder
import com.modose.app.ar.image.VlmJpegEncodingResult
import com.modose.app.ar.image.VlmJpegImage
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class CaptureCurrentSceneRequest(
    val sceneId: String,
    val captureKey: String,
    val roi: PixelRoi? = null,
    val rotationDegreesClockwise: Int,
)

data class CapturedCurrentScene(
    val sceneId: String,
    val captureKey: String,
    val sourceTimestampNanos: Long,
    val image: VlmJpegImage,
)

sealed interface CaptureCurrentSceneFailure {
    data object InvalidRequest : CaptureCurrentSceneFailure

    data object CaptureConflict : CaptureCurrentSceneFailure

    data class CaptureSkipped(
        val reason: CpuImageRuntimeSkipReason,
    ) : CaptureCurrentSceneFailure

    data class CaptureFailed(
        val reason: CpuImageFailureReason,
    ) : CaptureCurrentSceneFailure

    data object InvalidCpuImage : CaptureCurrentSceneFailure

    data class ImageRejected(
        val reason: VlmImageFailureReason,
    ) : CaptureCurrentSceneFailure
}

sealed interface CaptureCurrentSceneResult {
    data class Captured(
        val capture: CapturedCurrentScene,
    ) : CaptureCurrentSceneResult

    data class AlreadyCaptured(
        val capture: CapturedCurrentScene,
    ) : CaptureCurrentSceneResult

    data class Failed(
        val reason: CaptureCurrentSceneFailure,
    ) : CaptureCurrentSceneResult
}

fun interface CurrentCpuImageSource {
    suspend fun capture(): CpuImageAcquisitionResult
}

fun interface CurrentSceneJpegEncoder {
    fun encode(
        source: CpuCameraImage,
        plan: VlmImageEncodingPlan,
    ): VlmJpegEncodingResult
}

class AndroidCurrentSceneJpegEncoder(
    private val encoder: VlmJpegEncoder = VlmJpegEncoder(),
) : CurrentSceneJpegEncoder {
    override fun encode(
        source: CpuCameraImage,
        plan: VlmImageEncodingPlan,
    ): VlmJpegEncodingResult = encoder.encode(source, plan)
}

class CaptureCurrentSceneUseCase(
    private val imageSource: CurrentCpuImageSource,
    private val jpegEncoder: CurrentSceneJpegEncoder,
) {
    private val captureMutex = Mutex()
    private var completedRequest: CaptureCurrentSceneRequest? = null
    private var completedCapture: CapturedCurrentScene? = null

    suspend fun execute(
        request: CaptureCurrentSceneRequest,
    ): CaptureCurrentSceneResult = captureMutex.withLock {
        if (request.sceneId.isBlank() || request.captureKey.isBlank()) {
            return@withLock failed(CaptureCurrentSceneFailure.InvalidRequest)
        }

        val previousRequest = completedRequest
        val previousCapture = completedCapture
        if (previousRequest != null || previousCapture != null) {
            if (previousRequest == request && previousCapture != null) {
                return@withLock CaptureCurrentSceneResult.AlreadyCaptured(previousCapture)
            }
            return@withLock failed(CaptureCurrentSceneFailure.CaptureConflict)
        }

        val image = when (val result = imageSource.capture()) {
            is CpuImageAcquisitionResult.Acquired -> result.image
            is CpuImageAcquisitionResult.Skipped ->
                return@withLock failed(
                    CaptureCurrentSceneFailure.CaptureSkipped(result.reason),
                )
            is CpuImageAcquisitionResult.Failed ->
                return@withLock failed(
                    CaptureCurrentSceneFailure.CaptureFailed(result.reason),
                )
        }
        if (!CpuCameraImageValidator.isValid(image)) {
            return@withLock failed(CaptureCurrentSceneFailure.InvalidCpuImage)
        }

        val roi = request.roi ?: PixelRoi(
            left = 0,
            top = 0,
            rightExclusive = image.widthPx,
            bottomExclusive = image.heightPx,
        )
        val plan = when (
            val result = VlmImageEncodingPlanner.create(
                sourceWidthPx = image.widthPx,
                sourceHeightPx = image.heightPx,
                roi = roi,
                rotationDegreesClockwise = request.rotationDegreesClockwise,
            )
        ) {
            is VlmImagePlanResult.Planned -> result.plan
            is VlmImagePlanResult.Rejected ->
                return@withLock failed(
                    CaptureCurrentSceneFailure.ImageRejected(result.reason),
                )
        }

        val encoded = when (val result = jpegEncoder.encode(image, plan)) {
            is VlmJpegEncodingResult.Encoded -> result.image
            is VlmJpegEncodingResult.Rejected ->
                return@withLock failed(
                    CaptureCurrentSceneFailure.ImageRejected(result.reason),
                )
        }

        val capture = CapturedCurrentScene(
            sceneId = request.sceneId,
            captureKey = request.captureKey,
            sourceTimestampNanos = image.timestampNanos,
            image = encoded,
        )
        completedRequest = request
        completedCapture = capture
        CaptureCurrentSceneResult.Captured(capture)
    }

    fun reset() {
        completedRequest = null
        completedCapture = null
    }

    private fun failed(
        reason: CaptureCurrentSceneFailure,
    ) = CaptureCurrentSceneResult.Failed(reason)
}
