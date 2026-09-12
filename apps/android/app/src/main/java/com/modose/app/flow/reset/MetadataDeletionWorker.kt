package com.modose.app.flow.reset

import com.modose.app.network.VisionApiRequest
import com.modose.app.network.VisionApiResult
import com.modose.app.network.VisionHttpMethod

enum class MetadataDeletionEnqueueFailure {
    OutboxUnavailable,
}

sealed interface MetadataDeletionEnqueueResult {
    data class Queued(val sceneId: String, val reused: Boolean) :
        MetadataDeletionEnqueueResult
    data class Failed(val reason: MetadataDeletionEnqueueFailure) :
        MetadataDeletionEnqueueResult
}

enum class MetadataDeletionProblemKind {
    RetryScheduled,
    AttemptsExhausted,
    NonRetryableFailure,
    OutboxWriteFailed,
    OutboxClearFailed,
}

data class MetadataDeletionProblem(
    val sceneId: String,
    val kind: MetadataDeletionProblemKind,
)

data class MetadataDeletionRunReport(
    val pendingCount: Int,
    val completedCount: Int,
    val malformedMarkerNames: List<String>,
    val problems: List<MetadataDeletionProblem>,
)

sealed interface MetadataDeletionRunResult {
    data class Completed(val report: MetadataDeletionRunReport) :
        MetadataDeletionRunResult
    data object OutboxUnavailable : MetadataDeletionRunResult
}

class MetadataDeletionWorker(
    private val outbox: MetadataDeletionOutbox,
    private val executeRequest: (VisionApiRequest) -> VisionApiResult,
) {
    fun enqueue(plan: SceneResetPlan): MetadataDeletionEnqueueResult {
        val listed = outbox.list()
        if (listed !is MetadataOutboxListResult.Loaded) {
            return MetadataDeletionEnqueueResult.Failed(
                MetadataDeletionEnqueueFailure.OutboxUnavailable,
            )
        }
        if (listed.jobs.any { it.sceneId == plan.sceneId }) {
            return MetadataDeletionEnqueueResult.Queued(
                sceneId = plan.sceneId,
                reused = true,
            )
        }

        return when (
            outbox.store(
                MetadataDeletionJob(
                    sceneId = plan.sceneId,
                    resetKey = plan.resetKey,
                    attemptsUsed = 0,
                ),
            )
        ) {
            MetadataOutboxWriteResult.Stored ->
                MetadataDeletionEnqueueResult.Queued(
                    sceneId = plan.sceneId,
                    reused = false,
                )
            is MetadataOutboxWriteResult.Failed ->
                MetadataDeletionEnqueueResult.Failed(
                    MetadataDeletionEnqueueFailure.OutboxUnavailable,
                )
        }
    }

    fun runPending(): MetadataDeletionRunResult {
        val listed = outbox.list()
        if (listed !is MetadataOutboxListResult.Loaded) {
            return MetadataDeletionRunResult.OutboxUnavailable
        }

        var completedCount = 0
        val problems = mutableListOf<MetadataDeletionProblem>()
        listed.jobs.forEach { job ->
            if (job.attemptsUsed >= MetadataDeletionOutbox.MAX_ATTEMPTS) {
                problems += problem(
                    job,
                    MetadataDeletionProblemKind.AttemptsExhausted,
                )
                return@forEach
            }

            when (val response = executeRequest(job.toRequest())) {
                is VisionApiResult.Success -> {
                    if (
                        outbox.clear(job.sceneId) is
                            MetadataOutboxClearResult.ClearedOrAbsent
                    ) {
                        completedCount += 1
                    } else {
                        problems += problem(
                            job,
                            MetadataDeletionProblemKind.OutboxClearFailed,
                        )
                    }
                }
                else -> {
                    val retryable = response.isRetryable()
                    val nextAttempts = if (retryable) {
                        job.attemptsUsed + 1
                    } else {
                        MetadataDeletionOutbox.MAX_ATTEMPTS
                    }
                    if (
                        outbox.store(job.copy(attemptsUsed = nextAttempts)) !is
                            MetadataOutboxWriteResult.Stored
                    ) {
                        problems += problem(
                            job,
                            MetadataDeletionProblemKind.OutboxWriteFailed,
                        )
                    } else {
                        problems += problem(
                            job,
                            when {
                                !retryable ->
                                    MetadataDeletionProblemKind.NonRetryableFailure
                                nextAttempts >=
                                    MetadataDeletionOutbox.MAX_ATTEMPTS ->
                                    MetadataDeletionProblemKind.AttemptsExhausted
                                else ->
                                    MetadataDeletionProblemKind.RetryScheduled
                            },
                        )
                    }
                }
            }
        }

        return MetadataDeletionRunResult.Completed(
            MetadataDeletionRunReport(
                pendingCount = listed.jobs.size,
                completedCount = completedCount,
                malformedMarkerNames = listed.malformedMarkerNames,
                problems = problems,
            ),
        )
    }

    private fun MetadataDeletionJob.toRequest() = VisionApiRequest(
        method = VisionHttpMethod.DELETE,
        path = "/v1/scenes/$sceneId",
        idempotencyKey = resetKey,
    )

    private fun VisionApiResult.isRetryable(): Boolean = when (this) {
        VisionApiResult.IDTokenUnavailable,
        VisionApiResult.AppCheckTokenUnavailable,
        VisionApiResult.TimedOut,
        VisionApiResult.NetworkFailure,
        -> true
        is VisionApiResult.HttpFailure -> retryable
        is VisionApiResult.Success,
        VisionApiResult.InvalidRequest,
        VisionApiResult.ResponseTooLarge,
        -> false
    }

    private fun problem(
        job: MetadataDeletionJob,
        kind: MetadataDeletionProblemKind,
    ) = MetadataDeletionProblem(job.sceneId, kind)
}
