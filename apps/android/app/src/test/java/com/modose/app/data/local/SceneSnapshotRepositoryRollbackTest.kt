package com.modose.app.data.local

import com.modose.app.network.baseline.BaselineObject
import com.modose.app.network.baseline.NormalizedBoundingBox
import com.modose.app.network.baseline.ObjectSymmetry
import java.nio.file.AtomicMoveNotSupportedException
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SceneSnapshotRepositoryRollbackTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun databaseInsertFailureAbortsPreparedImage() = runBlocking {
        val root = temporaryFolder.newFolder("insert-failure")
        val dao = FailureDao(failInsert = true)
        val result = SceneSnapshotRepository(
            dao,
            SceneImageFileStore(root),
        ).save(write())

        assertEquals(
            SceneSaveResult.Failed(SceneSaveStage.DATABASE_INSERT),
            result,
        )
        assertTrue(root.listFiles().orEmpty().isEmpty())
        assertFalse(dao.rowPresent)
    }

    @Test
    fun imageCommitFailureDeletesStagingRowAndTemporaryImage() =
        runBlocking {
            val root = temporaryFolder.newFolder("image-failure")
            val dao = FailureDao()
            val store = SceneImageFileStore(root) { source, target ->
                throw AtomicMoveNotSupportedException(
                    source.toString(),
                    target.toString(),
                    "injected",
                )
            }

            val result = SceneSnapshotRepository(dao, store).save(write())

            assertEquals(
                SceneSaveResult.Failed(
                    SceneSaveStage.IMAGE_COMMIT,
                    SceneImageFailure.AtomicMoveUnsupported,
                ),
                result,
            )
            assertTrue(root.listFiles().orEmpty().isEmpty())
            assertFalse(dao.rowPresent)
            assertEquals(1, dao.deleteCalls)
        }

    @Test
    fun databaseCommitFailureDeletesCommittedImageAndStagingRow() =
        runBlocking {
            val root = temporaryFolder.newFolder("commit-failure")
            val dao = FailureDao(markCommittedResult = 0)

            val result = SceneSnapshotRepository(
                dao,
                SceneImageFileStore(root),
            ).save(write())

            assertEquals(
                SceneSaveResult.Failed(SceneSaveStage.DATABASE_COMMIT),
                result,
            )
            assertTrue(root.listFiles().orEmpty().isEmpty())
            assertFalse(dao.rowPresent)
            assertEquals(1, dao.deleteCalls)
        }

    private fun write() = SceneSnapshotWrite(
        sceneId = SCENE_ID,
        createdAt = Instant.parse("2026-09-12T00:00:00Z"),
        modelId = "gemini-test",
        promptVersion = "baseline-v1",
        repaired = false,
        jpeg = byteArrayOf(1, 2, 3),
        objects = listOf(
            SceneObjectWrite(
                objectValue = BaselineObject(
                    id = "wallet",
                    displayName = "財布",
                    appearanceFeatures = listOf("黒色"),
                    boundingBox = NormalizedBoundingBox(
                        yMin = 100,
                        xMin = 100,
                        yMax = 400,
                        xMax = 500,
                    ),
                    orientationImportant = true,
                    symmetry = ObjectSymmetry.None,
                ),
                origin = SceneObjectOrigin.Detected,
            ),
        ),
    )

    companion object {
        private const val SCENE_ID =
            "018f0f90-1234-7abc-8def-123456789abe"
    }
}

private class FailureDao(
    private val failInsert: Boolean = false,
    private val markCommittedResult: Int = 1,
) : SceneSnapshotDao {
    var rowPresent: Boolean = false
    var deleteCalls: Int = 0

    override suspend fun insertScene(scene: SceneEntity) = Unit

    override suspend fun insertObjects(
        objects: List<SceneObjectEntity>,
    ) = Unit

    override suspend fun insertStagingSnapshot(
        scene: SceneEntity,
        objects: List<SceneObjectEntity>,
    ) {
        if (failInsert) {
            throw IllegalStateException("injected insert failure")
        }
        rowPresent = true
    }

    override suspend fun findCommitted(
        sceneId: String,
    ): SceneSnapshotRecord? = null

    override suspend fun findAnyState(sceneId: String): SceneEntity? = null

    override suspend fun listStaging(): List<SceneEntity> = emptyList()

    override suspend fun markCommitted(
        sceneId: String,
        committedAtEpochMillis: Long,
    ): Int {
        if (markCommittedResult == 1) {
            rowPresent = false
        }
        return markCommittedResult
    }

    override suspend fun deleteScene(sceneId: String): Int {
        deleteCalls += 1
        val deleted = rowPresent
        rowPresent = false
        return if (deleted) 1 else 0
    }
}
