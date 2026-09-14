package com.modose.app.e2e

enum class E2eVisionStage {
    Baseline,
    Compare,
    Verify,
}

enum class E2eMatchResult {
    Matched,
    Missing,
    Ambiguous,
    Mixed,
    NotRun,
}

enum class E2eFinalResult {
    Verified,
    NeedsCorrection,
    Uncertain,
    ManualConfirmation,
    Rejected,
    Aborted,
}

data class E2eRunMetadata(
    val runId: String,
    val sceneId: String,
    val appVersion: String,
    val deviceModel: String,
    val modelId: String,
    val promptVersion: String,
    val testCaseId: String,
    val objectCount: Int,
)

data class E2eApiLatencies(
    val baselineMillis: Long?,
    val compareMillis: Long?,
    val verifyMillis: Long?,
)

data class E2eExecutionRecord(
    val metadata: E2eRunMetadata,
    val latencies: E2eApiLatencies,
    val vlmCallCount: Int,
    val matchResult: E2eMatchResult,
    val finalResult: E2eFinalResult,
    val falseSuccess: Boolean,
)

enum class E2eLoggerFailure {
    InvalidMetadata,
    InvalidLatency,
    VlmCallLimitExceeded,
    AlreadyFinished,
    MissingRequiredStage,
    InvalidFinalResult,
}

sealed interface E2eLoggerResult {
    data object Accepted : E2eLoggerResult
    data class Finished(val record: E2eExecutionRecord) : E2eLoggerResult
    data class Rejected(val reason: E2eLoggerFailure) : E2eLoggerResult
}

class PhysicalE2eExecutionLogger private constructor(
    private val metadata: E2eRunMetadata,
) {
    private val latestLatencies = mutableMapOf<E2eVisionStage, Long>()
    private var vlmCallCount = 0
    private var finished = false

    @Synchronized
    fun recordVisionCall(
        stage: E2eVisionStage,
        latencyMillis: Long,
    ): E2eLoggerResult {
        if (finished) return rejected(E2eLoggerFailure.AlreadyFinished)
        if (latencyMillis !in 0..MAX_LATENCY_MILLIS) {
            return rejected(E2eLoggerFailure.InvalidLatency)
        }
        if (vlmCallCount >= MAX_VLM_CALLS) {
            return rejected(E2eLoggerFailure.VlmCallLimitExceeded)
        }

        latestLatencies[stage] = latencyMillis
        vlmCallCount += 1
        return E2eLoggerResult.Accepted
    }

    @Synchronized
    fun finish(
        matchResult: E2eMatchResult,
        finalResult: E2eFinalResult,
        falseSuccess: Boolean,
    ): E2eLoggerResult {
        if (finished) return rejected(E2eLoggerFailure.AlreadyFinished)
        if (!requiredStagesPresent(finalResult)) {
            return rejected(E2eLoggerFailure.MissingRequiredStage)
        }
        if (
            falseSuccess &&
            finalResult != E2eFinalResult.Verified
        ) {
            return rejected(E2eLoggerFailure.InvalidFinalResult)
        }
        if (
            matchResult == E2eMatchResult.NotRun &&
            finalResult !in setOf(
                E2eFinalResult.Rejected,
                E2eFinalResult.Aborted,
            )
        ) {
            return rejected(E2eLoggerFailure.InvalidFinalResult)
        }

        finished = true
        return E2eLoggerResult.Finished(
            E2eExecutionRecord(
                metadata = metadata,
                latencies = E2eApiLatencies(
                    baselineMillis = latestLatencies[E2eVisionStage.Baseline],
                    compareMillis = latestLatencies[E2eVisionStage.Compare],
                    verifyMillis = latestLatencies[E2eVisionStage.Verify],
                ),
                vlmCallCount = vlmCallCount,
                matchResult = matchResult,
                finalResult = finalResult,
                falseSuccess = falseSuccess,
            ),
        )
    }

    private fun requiredStagesPresent(finalResult: E2eFinalResult): Boolean =
        when (finalResult) {
            E2eFinalResult.Rejected,
            E2eFinalResult.Aborted,
            -> true
            E2eFinalResult.Verified ->
                E2eVisionStage.Baseline in latestLatencies &&
                    E2eVisionStage.Compare in latestLatencies &&
                    E2eVisionStage.Verify in latestLatencies
            E2eFinalResult.NeedsCorrection,
            E2eFinalResult.Uncertain,
            E2eFinalResult.ManualConfirmation,
            -> E2eVisionStage.Baseline in latestLatencies &&
                E2eVisionStage.Compare in latestLatencies
        }

    private fun rejected(reason: E2eLoggerFailure) =
        E2eLoggerResult.Rejected(reason)

    companion object {
        fun create(metadata: E2eRunMetadata): E2eLoggerResult =
            if (metadata.isValid()) {
                Created(PhysicalE2eExecutionLogger(metadata))
            } else {
                E2eLoggerResult.Rejected(E2eLoggerFailure.InvalidMetadata)
            }

        private fun E2eRunMetadata.isValid(): Boolean =
            SAFE_ID.matches(runId) &&
                SAFE_ID.matches(sceneId) &&
                SAFE_VERSION.matches(appVersion) &&
                deviceModel.isNotBlank() &&
                deviceModel.length <= 80 &&
                SAFE_MODEL_ID.matches(modelId) &&
                SAFE_ID.matches(promptVersion) &&
                TEST_CASE.matches(testCaseId) &&
                objectCount in 0..6

        const val MAX_LATENCY_MILLIS = 12_000L
        const val MAX_VLM_CALLS = 6
        private val SAFE_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
        private val SAFE_VERSION = Regex("[A-Za-z0-9][A-Za-z0-9.+_-]{0,63}")
        private val SAFE_MODEL_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,79}")
        private val TEST_CASE = Regex("T(0[1-9]|1[0-9]|20)")
    }

    data class Created(
        val logger: PhysicalE2eExecutionLogger,
    ) : E2eLoggerResult
}
