package com.modose.app.vision.quality

import com.modose.app.ar.image.CpuCameraImage
import com.modose.app.ar.image.CpuCameraImageValidator

data class ImageQualityMetrics(
    val luminanceMean: Double,
    val clippedBlackRatio: Double,
    val clippedWhiteRatio: Double,
    val blurScore: Double,
)

enum class ImageQualityAssessmentFailure {
    InvalidImage,
    InvalidLumaPlane,
    ImageTooSmall,
}

sealed interface ImageQualityAssessmentResult {
    data class Assessed(val metrics: ImageQualityMetrics) : ImageQualityAssessmentResult

    data class Failed(
        val reason: ImageQualityAssessmentFailure,
    ) : ImageQualityAssessmentResult
}

class YuvLumaQualityAssessor {
    fun assess(image: CpuCameraImage): ImageQualityAssessmentResult {
        if (!CpuCameraImageValidator.isValid(image)) {
            return ImageQualityAssessmentResult.Failed(
                ImageQualityAssessmentFailure.InvalidImage,
            )
        }
        if (image.widthPx < MIN_DIMENSION || image.heightPx < MIN_DIMENSION) {
            return ImageQualityAssessmentResult.Failed(
                ImageQualityAssessmentFailure.ImageTooSmall,
            )
        }

        val plane = image.planes.first()
        val lastIndex =
            (image.heightPx - 1).toLong() * plane.rowStride +
                (image.widthPx - 1).toLong() * plane.pixelStride
        if (lastIndex < 0L || lastIndex >= plane.bytes.size.toLong()) {
            return ImageQualityAssessmentResult.Failed(
                ImageQualityAssessmentFailure.InvalidLumaPlane,
            )
        }

        var sum = 0L
        var blackCount = 0L
        var whiteCount = 0L
        val pixelCount = image.widthPx.toLong() * image.heightPx

        for (y in 0 until image.heightPx) {
            for (x in 0 until image.widthPx) {
                val luminance = luminanceAt(image, x, y)
                sum += luminance
                if (luminance <= BLACK_CLIP) blackCount += 1
                if (luminance >= WHITE_CLIP) whiteCount += 1
            }
        }

        var laplacianCount = 0L
        var laplacianSquareSum = 0.0
        for (y in 1 until image.heightPx - 1) {
            for (x in 1 until image.widthPx - 1) {
                val laplacian =
                    4.0 * luminanceAt(image, x, y) -
                        luminanceAt(image, x - 1, y) -
                        luminanceAt(image, x + 1, y) -
                        luminanceAt(image, x, y - 1) -
                        luminanceAt(image, x, y + 1)
                laplacianCount += 1
                laplacianSquareSum += laplacian * laplacian
            }
        }

        val laplacianEnergy = laplacianSquareSum / laplacianCount
        val blurScore =
            laplacianEnergy / (laplacianEnergy + BLUR_NORMALIZATION)

        return ImageQualityAssessmentResult.Assessed(
            ImageQualityMetrics(
                luminanceMean = sum.toDouble() / pixelCount,
                clippedBlackRatio = blackCount.toDouble() / pixelCount,
                clippedWhiteRatio = whiteCount.toDouble() / pixelCount,
                blurScore = blurScore,
            ),
        )
    }

    private fun luminanceAt(
        image: CpuCameraImage,
        x: Int,
        y: Int,
    ): Int {
        val plane = image.planes.first()
        val index = y * plane.rowStride + x * plane.pixelStride
        return plane.bytes[index].toInt() and 0xff
    }

    private companion object {
        const val MIN_DIMENSION = 3
        const val BLACK_CLIP = 16
        const val WHITE_CLIP = 235
        const val BLUR_NORMALIZATION = 400.0
    }
}
