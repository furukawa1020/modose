package com.modose.app.network.verify

import com.modose.app.ar.image.VlmJpegImage
import com.modose.app.network.VisionApiRequest
import com.modose.app.network.VisionHttpMethod
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.UUID

enum class VerifyRequestRejection {
    InvalidSceneId,
    InvalidIdempotencyKey,
    EmptyImage,
    UnsupportedImageType,
    ImageTooLarge,
    InvalidConfirmedObjects,
    RequestTooLarge,
}

sealed interface VerifyRequestBuildResult {
    data class Built(val request: VisionApiRequest) : VerifyRequestBuildResult
    data class Rejected(val reason: VerifyRequestRejection) : VerifyRequestBuildResult
}

object VerifyVisionRequestFactory {
    private const val MAX_IMAGE_BYTES = 2_000_000
    private const val MAX_REQUEST_BYTES = 4_500_000
    private const val MAX_CONFIRMED_BYTES = 64_000

    fun create(
        sceneId: String,
        capturedAt: Instant,
        idempotencyKey: String,
        baselineImage: VlmJpegImage,
        finalImage: VlmJpegImage,
        confirmedObjectsJson: String,
    ): VerifyRequestBuildResult {
        if (!sceneId.isUuid()) return rejected(VerifyRequestRejection.InvalidSceneId)
        if (!idempotencyKey.isUuid(7)) {
            return rejected(VerifyRequestRejection.InvalidIdempotencyKey)
        }
        if (baselineImage.bytes.isEmpty() || finalImage.bytes.isEmpty()) {
            return rejected(VerifyRequestRejection.EmptyImage)
        }
        if (
            baselineImage.mimeType != VlmJpegImage.MIME_TYPE ||
            finalImage.mimeType != VlmJpegImage.MIME_TYPE
        ) return rejected(VerifyRequestRejection.UnsupportedImageType)
        if (
            baselineImage.bytes.size > MAX_IMAGE_BYTES ||
            finalImage.bytes.size > MAX_IMAGE_BYTES
        ) return rejected(VerifyRequestRejection.ImageTooLarge)

        val confirmed = confirmedObjectsJson.trim()
        if (
            !confirmed.startsWith("{") ||
            !confirmed.endsWith("}") ||
            confirmed.toByteArray(StandardCharsets.UTF_8).size > MAX_CONFIRMED_BYTES
        ) return rejected(VerifyRequestRejection.InvalidConfirmedObjects)

        val boundary = "modose-" + idempotencyKey.replace("-", "")
        val metadata = "{\"sceneId\":\"" + sceneId +
            "\",\"capturedAt\":\"" + capturedAt + "\"}"
        val body = ByteArrayOutputStream(
            baselineImage.bytes.size + finalImage.bytes.size + confirmed.length + 1024,
        ).use { output ->
            output.jsonPart(boundary, "metadata", metadata)
            output.imagePart(boundary, "baselineImage", "baseline.jpg", baselineImage.bytes)
            output.imagePart(boundary, "finalImage", "final.jpg", finalImage.bytes)
            output.jsonPart(boundary, "confirmedObjects", confirmed)
            output.utf8("--" + boundary + "--\r\n")
            output.toByteArray()
        }
        if (body.size > MAX_REQUEST_BYTES) {
            return rejected(VerifyRequestRejection.RequestTooLarge)
        }
        return VerifyRequestBuildResult.Built(
            VisionApiRequest(
                method = VisionHttpMethod.POST,
                path = "/v1/vision/verify",
                idempotencyKey = idempotencyKey,
                body = body,
                contentType = "multipart/form-data; boundary=" + boundary,
            ),
        )
    }

    private fun ByteArrayOutputStream.jsonPart(
        boundary: String,
        name: String,
        value: String,
    ) {
        utf8("--" + boundary + "\r\n")
        utf8("Content-Disposition: form-data; name=\"" + name + "\"\r\n")
        utf8("Content-Type: application/json; charset=utf-8\r\n\r\n")
        utf8(value + "\r\n")
    }

    private fun ByteArrayOutputStream.imagePart(
        boundary: String,
        name: String,
        filename: String,
        bytes: ByteArray,
    ) {
        utf8("--" + boundary + "\r\n")
        utf8(
            "Content-Disposition: form-data; name=\"" + name +
                "\"; filename=\"" + filename + "\"\r\n",
        )
        utf8("Content-Type: image/jpeg\r\n\r\n")
        write(bytes)
        utf8("\r\n")
    }

    private fun ByteArrayOutputStream.utf8(value: String) {
        write(value.toByteArray(StandardCharsets.UTF_8))
    }

    private fun String.isUuid(version: Int? = null): Boolean {
        val parsed = try {
            UUID.fromString(this)
        } catch (_: IllegalArgumentException) {
            return false
        }
        return parsed.toString().equals(this, ignoreCase = true) &&
            (version == null || parsed.version() == version)
    }

    private fun rejected(reason: VerifyRequestRejection) =
        VerifyRequestBuildResult.Rejected(reason)
}
