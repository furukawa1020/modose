package com.modose.app.flow.reset

import com.modose.app.data.local.SceneDeletionStage
import com.modose.app.network.VisionApiResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SceneResetCoordinatorTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `ローカル削除失敗時は後続処理を実行しない`() = runBlocking {
        val calls = mutableListOf<String>()
        val coordinator = coordinator(
            calls = calls,
            local = LocalSceneResetResult.RetryRequired(
                SCENE_ID,
                SceneDeletionStage.IMAGE_DELETE,
            ),
        )

        assertEquals(
            SceneResetExecutionResult.RetryRequired(
                SCENE_ID,
                SceneResetExecutionStage.LocalDelete,
            ),
            coordinator.execute(plan()),
        )
        assertEquals(listOf("local"), calls)
    }

    @Test
    fun `ローカル解放UIの順で完了しmetadataをenqueueする`() = runBlocking {
        val calls = mutableListOf<String>()

        assertEquals(
            SceneResetExecutionResult.ReadyForNewScene(
                SCENE_ID,
                metadataQueued = true,
                reused = false,
            ),
            coordinator(calls).execute(plan()),
        )
        assertEquals(
            listOf("local", "runtime", "ui", "metadata"),
            calls,
        )
    }

    @Test
    fun `metadata enqueue失敗でローカル完了を巻き戻さない`() = runBlocking {
        val calls = mutableListOf<String>()
        var metadataFails = true
        val coordinator = SceneResetCoordinator(
            deleteLocal = {
                calls += "local"
                LocalSceneResetResult.Completed(SCENE_ID, false)
            },
            releaseRuntime = {
                calls += "runtime"
                SceneRuntimeReleaseResult.Completed(SCENE_ID, false)
            },
            resetUi = {
                calls += "ui"
                true
            },
            enqueueMetadata = {
                calls += "metadata"
                if (metadataFails) {
                    MetadataDeletionEnqueueResult.Failed(
                        MetadataDeletionEnqueueFailure.OutboxUnavailable,
                    )
                } else {
                    MetadataDeletionEnqueueResult.Queued(SCENE_ID, false)
                }
            },
        )

        assertEquals(
            SceneResetExecutionResult.ReadyForNewScene(
                SCENE_ID,
                metadataQueued = false,
                reused = false,
            ),
            coordinator.execute(plan()),
        )

        metadataFails = false
        assertEquals(
            SceneResetExecutionResult.ReadyForNewScene(
                SCENE_ID,
                metadataQueued = true,
                reused = false,
            ),
            coordinator.execute(plan()),
        )
        assertEquals(
            listOf("local", "runtime", "ui", "metadata", "metadata"),
            calls,
        )
    }

    @Test
    fun `runtime資源を順番に一度だけ解放する`() {
        val calls = mutableListOf<String>()
        val executor = SceneRuntimeReleaseExecutor(
            anchor = SceneAnchorRelease {
                calls += "anchor"
                true
            },
            tracker = SceneTrackerRelease {
                calls += "tracker"
                true
            },
            embeddings = SceneEmbeddingRelease {
                calls += "embedding"
                true
            },
        )

        assertEquals(
            SceneRuntimeReleaseResult.Completed(SCENE_ID, false),
            executor.release(plan()),
        )
        assertEquals(
            SceneRuntimeReleaseResult.Completed(SCENE_ID, true),
            executor.release(plan()),
        )
        assertEquals(listOf("anchor", "tracker", "embedding"), calls)
    }

    @Test
    fun `outboxから再起動後にmetadata削除を再開する`() {
        val directory = temporaryFolder.newFolder()
        val first = MetadataDeletionWorker(
            MetadataDeletionOutbox(directory),
        ) { VisionApiResult.NetworkFailure }
        assertEquals(
            MetadataDeletionEnqueueResult.Queued(SCENE_ID, false),
            first.enqueue(plan()),
        )
        first.runPending()

        var requestPath = ""
        val restarted = MetadataDeletionWorker(
            MetadataDeletionOutbox(directory),
        ) { request ->
            requestPath = request.path
            VisionApiResult.Success(204, ByteArray(0))
        }
        val result = restarted.runPending() as MetadataDeletionRunResult.Completed

        assertEquals("/v1/scenes/$SCENE_ID", requestPath)
        assertEquals(1, result.report.completedCount)
        assertTrue(
            (MetadataDeletionOutbox(directory).list() as
                MetadataOutboxListResult.Loaded).jobs.isEmpty(),
        )
    }

    @Test
    fun `metadata削除は3回失敗後に再送しない`() {
        val directory = temporaryFolder.newFolder()
        val outbox = MetadataDeletionOutbox(directory)
        outbox.store(
            MetadataDeletionJob(SCENE_ID, RESET_KEY, attemptsUsed = 3),
        )
        var calls = 0
        val worker = MetadataDeletionWorker(outbox) {
            calls += 1
            VisionApiResult.Success(204, ByteArray(0))
        }

        val result = worker.runPending() as MetadataDeletionRunResult.Completed

        assertEquals(0, calls)
        assertEquals(
            MetadataDeletionProblemKind.AttemptsExhausted,
            result.report.problems.single().kind,
        )
    }

    private fun coordinator(
        calls: MutableList<String>,
        local: LocalSceneResetResult =
            LocalSceneResetResult.Completed(SCENE_ID, false),
    ) = SceneResetCoordinator(
        deleteLocal = {
            calls += "local"
            local
        },
        releaseRuntime = {
            calls += "runtime"
            SceneRuntimeReleaseResult.Completed(SCENE_ID, false)
        },
        resetUi = {
            calls += "ui"
            true
        },
        enqueueMetadata = {
            calls += "metadata"
            MetadataDeletionEnqueueResult.Queued(SCENE_ID, false)
        },
    )

    private fun plan() = (
        SceneResetPlanFactory.create(
            SceneResetRequest(SCENE_ID, RESET_KEY),
        ) as SceneResetPlanResult.Accepted
    ).plan

    private companion object {
        const val SCENE_ID = "scene-090"
        const val RESET_KEY = "018f0f90-1234-7abc-8def-123456789abc"
    }
}
