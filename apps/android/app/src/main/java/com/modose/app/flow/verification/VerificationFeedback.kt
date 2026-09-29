package com.modose.app.flow.verification

import com.modose.app.core.CoreRestoreState

/** Historical request feedback only. Never emits the live verified-success banner. */
internal object VerificationFeedback {
    fun message(
        result: ExecuteVerificationResult,
        state: CoreRestoreState,
        savedLabels: Map<String, String>,
    ): String {
        if (state == CoreRestoreState.MANUAL_CONFIRMATION) {
            return "最終確認の上限に達しました。手動で確認してください。成功扱いにはしません。"
        }
        val labels = savedLabels.toMap()
        if (labels.size !in 1..5 || labels.any { (id, name) ->
                id.isBlank() || id.length > 64 || name.isBlank() || name.length > 80
            }) return INVALID_RESPONSE
        return when (result) {
            is ExecuteVerificationResult.Failed -> failure(result.reason)
            is ExecuteVerificationResult.Completed -> {
                val decision = result.decision
                val corrections = decision.correctionObjectIds.toList()
                val reasons = decision.reasonCodes.toSet()
                when (decision.status) {
                    SceneVerificationStatus.Verified -> if (corrections.isEmpty() && reasons.isEmpty()) {
                        "最終確認応答を受信しました。現在の追跡状態で結果を確認してください。"
                    } else INVALID_RESPONSE
                    SceneVerificationStatus.NeedsCorrection -> {
                        if (corrections.isEmpty() || corrections.toSet().size != corrections.size ||
                            corrections.any { it !in labels } || reasons.isEmpty() || reasons.any { it.isBlank() }
                        ) INVALID_RESPONSE else {
                            val names = corrections.joinToString("、") { labels.getValue(it) }
                            "修正が必要です：" + names + "。位置・向きを見直し、局所完了後に最終確認してください。"
                        }
                    }
                    SceneVerificationStatus.Uncertain -> if (corrections.isEmpty() &&
                        reasons.isNotEmpty() && reasons.none { it.isBlank() }) {
                        "最終確認では判断できませんでした。対象物全体が明るく見えるようにしてください。成功扱いにはしません。"
                    } else INVALID_RESPONSE
                }
            }
        }
    }

    private fun failure(reason: ExecuteVerificationFailure): String = when (reason) {
        ExecuteVerificationFailure.IdTokenUnavailable ->
            "最終確認待ちです。ユーザー認証を取得できません。接続・Firebase設定を確認してください。"
        ExecuteVerificationFailure.AppCheckTokenUnavailable ->
            "最終確認待ちです。端末の正当性を確認できません。App Check設定を確認してください。"
        ExecuteVerificationFailure.TimedOut ->
            "最終確認が時間内に終わりませんでした。通信を確認してください。自動再送せず、成功も保留します。"
        ExecuteVerificationFailure.NetworkUnavailable ->
            "最終確認待ちです。通信を再接続してください。自動再送せず、成功も保留します。"
        is ExecuteVerificationFailure.HttpFailure -> when {
            reason.statusCode == 401 || reason.statusCode == 403 ->
                "最終確認が認証・端末検証で拒否されました。FirebaseとApp Checkの設定を確認してください。"
            reason.retryable ->
                "最終確認APIを一時的に利用できません。少し待ってください。自動再送せず、成功も保留します。"
            else -> "最終確認APIが要求を拒否しました。設定を確認してください。成功扱いにはしません。"
        }
        is ExecuteVerificationFailure.InvalidRequest, ExecuteVerificationFailure.InvalidApiRequest ->
            "最終確認要求を作成できません。保存状態とアプリ設定を確認してください。"
        ExecuteVerificationFailure.ResponseTooLarge,
        is ExecuteVerificationFailure.DecodeRejected,
        is ExecuteVerificationFailure.MappingRejected -> INVALID_RESPONSE
    }

    private const val INVALID_RESPONSE =
        "最終確認結果が契約に適合しません。成功扱いにはしません。"
}
