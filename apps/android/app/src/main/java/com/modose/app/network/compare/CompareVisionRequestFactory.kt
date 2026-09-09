package com.modose.app.network.compare

import com.modose.app.ar.image.VlmJpegImage
import com.modose.app.network.VisionApiRequest
import com.modose.app.network.VisionHttpMethod
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.UUID

enum class CompareRequestRejection {
    InvalidSceneId,
    InvalidIdempotencyKey,
    EmptyBaselineImage,
    EmptyCurrentImage,
    UnsupportedImageType,
    ImageTooLarge,
    InvalidConfirmedObjects,
    RequestTooLarge,
}

sealed interface CompareRequestBuildResult {
    data class Built(val request: VisionApiRequest) : CompareRequestBuildResult
    data class Rejected(val reason: CompareRequestRejection) : CompareRequestBuildResult
}

object CompareVisionRequestFactory {
    private const val PATH = "/v1/vision/compare"
    private const val MAX_IMAGE_BYTES = 2_000_000
    private const val MAX_REQUEST_BYTES = 4_500_000
    private const val MAX_CONFIRMED_OBJECTS_BYTES = 64_000

    fun create(
        sceneId: String,
        capturedAt: Instant,
        idempotencyKey: String,
        baselineImage: VlmJpegImage,
        currentImage: VlmJpegImage,
        confirmedObjectsJson: String,
    ): CompareRequestBuildResult {
        if (!sceneId.isCanonicalUuid()) {
            return rejected(CompareRequestRejection.InvalidSceneId)
        }
        if (!idempotencyKey.isCanonicalUuid(version = 7)) {
            return rejected(CompareRequestRejection.InvalidIdempotencyKey)
        }
        if (baselineImage.bytes.isEmpty()) {
            return rejected(CompareRequestRejection.EmptyBaselineImage)
        }
        if (currentImage.bytes.isEmpty()) {
            return rejected(CompareRequestRejection.EmptyCurrentImage)
        }
        if (
            baselineImage.mimeType != VlmJpegImage.MIME_TYPE ||
            currentImage.mimeType != VlmJpegImage.MIME_TYPE
        ) {
            return rejected(CompareRequestRejection.UnsupportedImageType)
        }
        if (
            baselineImage.bytes.size > MAX_IMAGE_BYTES ||
            currentImage.bytes.size > MAX_IMAGE_BYTES
        ) {
            return rejected(CompareRequestRejection.ImageTooLarge)
        }

        val confirmedObjects = confirmedObjectsJson.trim()
        val confirmedBytes = confirmedObjects.toByteArray(StandardCharsets.UTF_8)
        if (
            !confirmedObjects.startsWith("{") ||
            !confirmedObjects.endsWith("}") ||
            confirmedBytes.size > MAX_CONFIRMED_OBJECTS_BYTES
        ) {
            return rejected(CompareRequestRejection.InvalidConfirmedObjects)
        }

        val boundary = "modose-" + idempotencyKey.replace("-", "")
        val metadata = "{\"sceneId\":\"" + sceneId + "\",\"capturedAt\":\"" + capturedAt + "\"}"
        val body = ByteArrayOutputStream(
            baselineImage.bytes.size + currentImage.bytes.size + confirmedBytes.size + 1024,
        ).use { output ->
            output.writeJsonPart(boundary, "metadata", metadata)
            output.writeImagePart(boundary, "baselineImage", "baseline.jpg", baselineImage.bytes)
            output.writeImagePart(boundary, "currentImage", "current.jpg", currentImage.bytes)
            output.writeJsonPart(boundary, "confirmedObjects", confirmedObjects)
            output.writeUtf8("--" + boundary + "--\r\n")
            output.toByteArray()
        }
        if (body.size > MAX_REQUEST_BYTES) {
            return rejected(CompareRequestRejection.RequestTooLarge)
        }

        return CompareRequestBuildResult.Built(
            VisionApiRequest(
                method = VisionHttpMethod.POST,
                path = PATH,
                idempotencyKey = idempotencyKey,
                body = body,
                contentType = "multipart/form-data; boundary=" + boundary,
            ),
        )
    }

    private fun ByteArrayOutputStream.writeJsonPart(
        boundary: String,
        name: String,
        value: String,
    ) {
        writeUtf8("--" + boundary + "\r\n")
        writeUtf8("Content-Disposition: form-data; name=\"" + name + "\"\r\n")
        writeUtf8("Content-Type: application/json; charset=utf-8\r\n\r\n")
        writeUtf8(value)
        writeUtf8("\r\n")
    }

    private fun ByteArrayOutputStream.writeImagePart(
        boundary: String,
        name: String,
        filename: String,
        bytes: ByteArray,
    ) {
        writeUtf8("--" + boundary + "\r\n")
        writeUtf8(
            "Content-Disposition: form-data; name=\"" + name +
                "\"; filename=\"" + filename + "\"\r\n",
        )
        writeUtf8("Content-Type: " + VlmJpegImage.MIME_TYPE + "\r\n\r\n")
        write(bytes)
        writeUtf8("\r\n")
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

    private fun rejected(reason: CompareRequestRejection) =
        CompareRequestBuildResult.Rejected(reason)
}
