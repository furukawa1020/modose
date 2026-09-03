package com.modose.app.network

/**
 * Retries only transient transport failures while enforcing one deadline across every attempt.
 *
 * The original request is copied solely to reduce its per-attempt timeout. URL, headers, body,
 * and therefore the Idempotency-Key remain unchanged across retries.
 */
class RetryingVisionHttpTransport(
    private val delegate: VisionHttpTransport,
    private val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    private val totalDeadlineMillis: Long = DEFAULT_TOTAL_DEADLINE_MILLIS,
    private val initialBackoffMillis: Long = DEFAULT_INITIAL_BACKOFF_MILLIS,
    private val maximumBackoffMillis: Long = DEFAULT_MAXIMUM_BACKOFF_MILLIS,
    private val clock: MonotonicClock = MonotonicClock {
        System.nanoTime() / NANOS_PER_MILLISECOND
    },
    private val sleeper: RetrySleeper = RetrySleeper { delayMillis ->
        Thread.sleep(delayMillis)
    },
) : VisionHttpTransport {
    init {
        require(maxAttempts > 0) { "maxAttempts must be positive" }
        require(totalDeadlineMillis > 0) { "totalDeadlineMillis must be positive" }
        require(initialBackoffMillis >= 0) { "initialBackoffMillis must not be negative" }
        require(maximumBackoffMillis >= initialBackoffMillis) {
            "maximumBackoffMillis must be at least initialBackoffMillis"
        }
    }

    override fun execute(request: VisionTransportRequest): VisionTransportResult {
        val startedAtMillis = clock.nowMillis()
        val deadlineBudgetMillis = minOf(request.timeoutMillis.toLong(), totalDeadlineMillis)
        var backoffMillis = initialBackoffMillis

        repeat(maxAttempts) { attemptIndex ->
            val remainingMillis = remainingMillis(startedAtMillis, deadlineBudgetMillis)
            if (remainingMillis <= 0) {
                return VisionTransportResult.TimedOut
            }

            val result = delegate.execute(
                request.copy(timeoutMillis = remainingMillis.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()),
            )
            if (!result.isRetryable() || attemptIndex == maxAttempts - 1) {
                return result
            }

            val remainingAfterAttempt = remainingMillis(startedAtMillis, deadlineBudgetMillis)
            if (remainingAfterAttempt <= 0) {
                return VisionTransportResult.TimedOut
            }

            val delayMillis = minOf(backoffMillis, remainingAfterAttempt)
            if (delayMillis > 0) {
                try {
                    sleeper.sleep(delayMillis)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return VisionTransportResult.NetworkFailure
                }
            }
            backoffMillis = (backoffMillis * 2).coerceAtMost(maximumBackoffMillis)
        }

        return VisionTransportResult.NetworkFailure
    }

    private fun remainingMillis(startedAtMillis: Long, budgetMillis: Long): Long =
        budgetMillis - (clock.nowMillis() - startedAtMillis).coerceAtLeast(0)

    private fun VisionTransportResult.isRetryable(): Boolean = when (this) {
        VisionTransportResult.NetworkFailure,
        VisionTransportResult.TimedOut,
        -> true
        is VisionTransportResult.Received -> response.statusCode in RETRYABLE_STATUS_CODES
        VisionTransportResult.ResponseTooLarge -> false
    }

    private companion object {
        const val NANOS_PER_MILLISECOND = 1_000_000L
        const val DEFAULT_MAX_ATTEMPTS = 3
        const val DEFAULT_TOTAL_DEADLINE_MILLIS = 12_000L
        const val DEFAULT_INITIAL_BACKOFF_MILLIS = 250L
        const val DEFAULT_MAXIMUM_BACKOFF_MILLIS = 1_000L
        val RETRYABLE_STATUS_CODES = setOf(429, 500, 502, 503, 504)
    }
}

fun interface MonotonicClock {
    fun nowMillis(): Long
}

fun interface RetrySleeper {
    fun sleep(delayMillis: Long)
}
