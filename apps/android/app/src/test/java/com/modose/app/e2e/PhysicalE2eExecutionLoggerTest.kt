package com.modose.app.e2e

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhysicalE2eExecutionLoggerTest {
    @Test
    fun finish_createsVerifiedRecordFromLatestStageLatencies() {
        val logger = logger()
        logger.recordVisionCall(E2eVisionStage.Baseline, 1_000)
        logger.recordVisionCall(E2eVisionStage.Baseline, 900)
        logger.recordVisionCall(E2eVisionStage.Compare, 800)
        logger.recordVisionCall(E2eVisionStage.Verify, 700)

        val result = logger.finish(
            E2eMatchResult.Matched,
            E2eFinalResult.Verified,
            falseSuccess = false,
        )

        assertTrue(result is E2eLoggerResult.Finished)
        val record = (result as E2eLoggerResult.Finished).record
        assertEquals(4, record.vlmCallCount)
        assertEquals(900, record.latencies.baselineMillis)
        assertEquals(800, record.latencies.compareMillis)
        assertEquals(700, record.latencies.verifyMillis)
    }

    @Test
    fun finish_allowsRejectedRunWithoutVlmCall() {
        val result = logger().finish(
            E2eMatchResult.NotRun,
            E2eFinalResult.Rejected,
            falseSuccess = false,
        )

        assertTrue(result is E2eLoggerResult.Finished)
        assertEquals(
            0,
            (result as E2eLoggerResult.Finished).record.vlmCallCount,
        )
    }

    @Test
    fun finish_requiresVerifyForVerifiedResult() {
        val logger = logger()
        logger.recordVisionCall(E2eVisionStage.Baseline, 100)
        logger.recordVisionCall(E2eVisionStage.Compare, 100)

        assertEquals(
            E2eLoggerResult.Rejected(
                E2eLoggerFailure.MissingRequiredStage,
            ),
            logger.finish(
                E2eMatchResult.Matched,
                E2eFinalResult.Verified,
                falseSuccess = false,
            ),
        )
    }

    @Test
    fun finish_rejectsFalseSuccessForNonVerifiedResult() {
        val logger = logger()
        logger.recordVisionCall(E2eVisionStage.Baseline, 100)
        logger.recordVisionCall(E2eVisionStage.Compare, 100)

        assertEquals(
            E2eLoggerResult.Rejected(
                E2eLoggerFailure.InvalidFinalResult,
            ),
            logger.finish(
                E2eMatchResult.Mixed,
                E2eFinalResult.NeedsCorrection,
                falseSuccess = true,
            ),
        )
    }

    @Test
    fun recordVisionCall_enforcesLatencyAndCallLimits() {
        val logger = logger()
        assertEquals(
            E2eLoggerResult.Rejected(E2eLoggerFailure.InvalidLatency),
            logger.recordVisionCall(E2eVisionStage.Baseline, 12_001),
        )
        repeat(PhysicalE2eExecutionLogger.MAX_VLM_CALLS) {
            assertEquals(
                E2eLoggerResult.Accepted,
                logger.recordVisionCall(E2eVisionStage.Baseline, 100),
            )
        }
        assertEquals(
            E2eLoggerResult.Rejected(
                E2eLoggerFailure.VlmCallLimitExceeded,
            ),
            logger.recordVisionCall(E2eVisionStage.Baseline, 100),
        )
    }

    @Test
    fun logger_rejectsWritesAfterFinish() {
        val logger = logger()
        logger.finish(
            E2eMatchResult.NotRun,
            E2eFinalResult.Aborted,
            falseSuccess = false,
        )

        assertEquals(
            E2eLoggerResult.Rejected(E2eLoggerFailure.AlreadyFinished),
            logger.recordVisionCall(E2eVisionStage.Baseline, 100),
        )
    }

    @Test
    fun create_rejectsUnsafeMetadata() {
        val result = PhysicalE2eExecutionLogger.create(
            metadata().copy(runId = "../run"),
        )

        assertEquals(
            E2eLoggerResult.Rejected(E2eLoggerFailure.InvalidMetadata),
            result,
        )
    }

    private fun logger(): PhysicalE2eExecutionLogger =
        (
            PhysicalE2eExecutionLogger.create(metadata()) as
                PhysicalE2eExecutionLogger.Created
        ).logger

    private fun metadata() = E2eRunMetadata(
        runId = "run-001",
        sceneId = "scene-001",
        appVersion = "1.0.0-rc1",
        deviceModel = "Pixel 8",
        modelId = "gemini-3.5-flash",
        promptVersion = "baseline-v3",
        testCaseId = "T01",
        objectCount = 5,
    )
}
