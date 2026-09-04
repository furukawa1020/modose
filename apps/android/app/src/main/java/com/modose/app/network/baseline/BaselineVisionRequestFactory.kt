package com.modose.app.network.baseline

import com.modose.app.ar.image.VlmJpegImage
import com.modose.app.network.VisionApiRequest
import com.modose.app.network.VisionHttpMethod
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.UUID

enum class BaselineRequestRejection {
    InvalidSceneId,
    InvalidIdempotencyKey,
    EmptyImage,
    UnsupportedImageType,
    ImageTooLarge,
    RequestTooLarge,
}

sealed interface BaselineRequestBuildResult {
    data class Built(val request: VisionApiRequest) : BaselineRequestBuildResult
    data class Rejected(val reason: BaselineRequestRejection) : BaselineRequestBuildResult
}

object BaselineVisionRequestFactory {
    private const val PATH = "/v1/vision/baseline"
    private const val IMAGE_PART_NAME = "image"
    private const val IMAGE_FILE_NAME = "baseline.jpg"
    private const val MAX_IMAGE_BYTES = 2_000_000
    private const val MAX_REQUEST_BYTES = 4_500_000

    fun create(
        sceneId: String,
        capturedAt: Instant,
        idempotencyKey: String,
        image: VlmJpegImage,
    ): BaselineRequestBuildResult {
        if (!sceneId.isCanonicalUuid()) {
            return rejected(BaselineRequestRejection.InvalidSceneId)
        }
        if (!idempotencyKey.isCanonicalUuid(version = 7)) {
            return rejected(BaselineRequestRejection.InvalidIdempotencyKey)
        }
        if (image.bytes.isEmpty()) {
            return rejected(BaselineRequestRejection.EmptyImage)
        }
        if (image.mimeType != VlmJpegImage.MIME_TYPE) {
            return rejected(BaselineRequestRejection.UnsupportedImageType)
        }
        if (image.bytes.size > MAX_IMAGE_BYTES) {
            return rejected(BaselineRequestRejection.ImageTooLarge)
        }

        val boundary = "modose-${idempotencyKey.replace("-", "")}"
        val metadata = """{"sceneId":"$sceneId","capturedAt":"$capturedAt"}"""
        val body = ByteArrayOutputStream(image.bytes.size + 512).use { output ->
            output.writeUtf8("--$boundary\r\n")
            output.writeUtf8("Content-Disposition: form-data; name=\"metadata\"\r\n")
            output.writeUtf8("Content-Type: application/json; charset=utf-8\r\n\r\n")
            output.writeUtf8(metadata)
            output.writeUtf8("\r\n--$boundary\r\n")
            output.writeUtf8(
                "Content-Disposition: form-data; name=\"$IMAGE_PART_NAME\"; " +
                    "filename=\"$IMAGE_FILE_NAME\"\r\n",
            )
            output.writeUtf8("Content-Type: ${VlmJpegImage.MIME_TYPE}\r\n\r\n")
            output.write(image.bytes)
            output.writeUtf8("\r\n--$boundary--\r\n")
            output.toByteArray()
        }
        if (body.size > MAX_REQUEST_BYTES) {
            return rejected(BaselineRequestRejection.RequestTooLarge)
        }

        return BaselineRequestBuildResult.Built(
            VisionApiRequest(
                method = VisionHttpMethod.POST,
                path = PATH,
                idempotencyKey = idempotencyKey,
                body = body,
                contentType = "multipart/form-data; boundary=$boundary",
            ),
        )
    }

    private fun String.isCanonicalUuid(version: Int? = null): Boolean {
        val parsed = try {
            UUID.fromString(this)
        } catch (_: IllegalArgumentException) {
            return false
        }
        return parsed.toString().equals(this, ignoreCase = true) &&
            (version == null || parsed.version() == version)
    }

    private fun ByteArrayOutputStream.writeUtf8(value: String) {
        write(value.toByteArray(StandardCharsets.UTF_8))
    }

    private fun rejected(reason: BaselineRequestRejection) =
        BaselineRequestBuildResult.Rejected(reason)
}
