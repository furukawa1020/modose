package com.modose.app.ui.camera

import com.modose.app.vision.quality.CaptureQualityReason
import com.modose.app.vision.quality.FrameCaptureQualityResult

enum class CaptureQualityStatusTone {
    Ready,
    Waiting,
    Blocked,
}

data class CaptureQualityUiModel(
    val title: String,
    val reasonMessages: List<String>,
    val contentDescription: String,
    val saveEnabled: Boolean,
    val tone: CaptureQualityStatusTone,
)

object CaptureQualityPresentationMapper {
    fun map(result: FrameCaptureQualityResult?): CaptureQualityUiModel =
        when (result) {
            null -> waiting(
                title = "撮影準備中",
                message = "カメラの状態を確認しています",
            )
            is FrameCaptureQualityResult.Unavailable -> waiting(
                title = "撮影準備中",
                message = when (result.reason) {
                    com.modose.app.vision.quality.FrameCaptureQualityUnavailableReason
                        .CpuImageUnavailable -> "カメラ画像を待っています"
                    com.modose.app.vision.quality.FrameCaptureQualityUnavailableReason
                        .InvalidCpuImage -> "カメラ画像を取得し直しています"
                    com.modose.app.vision.quality.FrameCaptureQualityUnavailableReason
                        .InvalidAngularVelocity -> "端末の動きを確認できません"
                },
            )
            is FrameCaptureQualityResult.RejectedMetrics -> blocked(
                listOf("撮影品質を確認できません"),
            )
            is FrameCaptureQualityResult.Evaluated -> {
                val quality = result.quality
                if (quality.captureAllowed) {
                    CaptureQualityUiModel(
                        title = "保存できます",
                        reasonMessages = emptyList(),
                        contentDescription = "撮影準備完了。保存できます",
                        saveEnabled = true,
                        tone = CaptureQualityStatusTone.Ready,
                    )
                } else {
                    blocked(
                        REASON_PRIORITY.mapNotNull { reason ->
                            if (reason in quality.reasonCodes) {
                                reason.toMessage()
                            } else {
                                null
                            }
                        },
                    )
                }
            }
        }

    private fun waiting(
        title: String,
        message: String,
    ) = CaptureQualityUiModel(
        title = title,
        reasonMessages = listOf(message),
        contentDescription = "$title。$message",
        saveEnabled = false,
        tone = CaptureQualityStatusTone.Waiting,
    )

    private fun blocked(messages: List<String>): CaptureQualityUiModel {
        val safeMessages =
            messages.ifEmpty { listOf("撮影条件を確認してください") }
        return CaptureQualityUiModel(
            title = "撮影条件を調整してください",
            reasonMessages = safeMessages,
            contentDescription =
                "保存できません。" + safeMessages.joinToString("。"),
            saveEnabled = false,
            tone = CaptureQualityStatusTone.Blocked,
        )
    }

    private fun CaptureQualityReason.toMessage(): String = when (this) {
        CaptureQualityReason.TrackingUnavailable -> "端末をゆっくり動かしてください"
        CaptureQualityReason.PlaneNotFound -> "机または床を画面中央に入れてください"
        CaptureQualityReason.ImageTooDark -> "周囲を明るくしてください"
        CaptureQualityReason.ImageTooBright -> "強い光や反射を避けてください"
        CaptureQualityReason.ImageTooBlurry -> "端末を静止してください"
        CaptureQualityReason.ExcessiveMotion -> "端末の動きを止めてください"
        CaptureQualityReason.RoiInsufficient -> "戻したい範囲を大きく映してください"
    }

    private val REASON_PRIORITY = listOf(
        CaptureQualityReason.TrackingUnavailable,
        CaptureQualityReason.PlaneNotFound,
        CaptureQualityReason.ImageTooDark,
        CaptureQualityReason.ImageTooBright,
        CaptureQualityReason.ImageTooBlurry,
        CaptureQualityReason.ExcessiveMotion,
        CaptureQualityReason.RoiInsufficient,
    )
}
