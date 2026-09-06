package com.modose.app.ui.intermission

import com.modose.app.ar.anchor.SceneAnchorPose
import com.modose.app.ar.anchor.SceneAnchorSnapshot
import com.modose.app.ar.anchor.SceneAnchorState
import com.modose.app.data.local.SceneEntity
import com.modose.app.data.local.SceneObjectEntity
import com.modose.app.data.local.SceneSnapshotDao
import com.modose.app.data.local.SceneSnapshotRecord
import com.modose.app.data.local.SceneStorageState
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LoadMoveObjectsIntermissionUseCaseTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun committedSceneAndMatchingImageBuildReadyState() = runBlocking {
        val root = temporaryFolder.newFolder("images")
        val bytes = "saved-image".toByteArray()
        root.resolve(IMAGE_FILE_NAME).writeBytes(bytes)
        val loader = LoadMoveObjectsIntermissionUseCase(
            dao = FakeDao(record(bytes.sha256())),
            imageRootDirectory = root,
        )

        val result = loader.execute(SCENE_ID, trackingAnchor())

        assertTrue(result is LoadMoveObjectsIntermissionResult.Loaded)
        result as LoadMoveObjectsIntermissionResult.Loaded
        assertEquals(SCENE_ID, result.state.sceneId)
        assertEquals(
            RestorationStartAvailability.Ready,
            result.state.startAvailability,
        )
        assertEquals(listOf("object-1"), result.state.objects.map { it.objectId })
        assertArrayEquals(bytes, result.savedImageBytes)
    }

    @Test
    fun uncommittedSceneIsRejected() = runBlocking {
        val loader = LoadMoveObjectsIntermissionUseCase(
            dao = FakeDao(null),
            imageRootDirectory = temporaryFolder.newFolder("uncommitted"),
        )

        val result = loader.execute(SCENE_ID, trackingAnchor())

        assertEquals(
            LoadMoveObjectsIntermissionResult.Failed(
                LoadMoveObjectsIntermissionFailure.SceneNotCommitted,
            ),
            result,
        )
    }

    @Test
    fun missingImageIsRejectedWithoutPlaceholderData() = runBlocking {
        val loader = LoadMoveObjectsIntermissionUseCase(
            dao = FakeDao(record("a".repeat(64))),
            imageRootDirectory = temporaryFolder.newFolder("missing"),
        )

        val result = loader.execute(SCENE_ID, trackingAnchor())

        assertEquals(
            LoadMoveObjectsIntermissionResult.Failed(
                LoadMoveObjectsIntermissionFailure.ImageUnavailable,
            ),
            result,
        )
    }

    @Test
    fun imageHashMismatchIsRejected() = runBlocking {
        val root = temporaryFolder.newFolder("mismatch")
        root.resolve(IMAGE_FILE_NAME).writeBytes("changed-image".toByteArray())
        val loader = LoadMoveObjectsIntermissionUseCase(
            dao = FakeDao(record("b".repeat(64))),
            imageRootDirectory = root,
        )

        val result = loader.execute(SCENE_ID, trackingAnchor())

        assertEquals(
            LoadMoveObjectsIntermissionResult.Failed(
                LoadMoveObjectsIntermissionFailure.ImageHashMismatch,
            ),
            result,
        )
    }

    private class FakeDao(
        private val committed: SceneSnapshotRecord?,
    ) : SceneSnapshotDao {
        override suspend fun insertScene(scene: SceneEntity) = Unit

        override suspend fun insertObjects(objects: List<SceneObjectEntity>) = Unit

        override suspend fun findCommitted(sceneId: String): SceneSnapshotRecord? =
            committed?.takeIf { it.scene.sceneId == sceneId }

        override suspend fun findAnyState(sceneId: String): SceneEntity? =
            findCommitted(sceneId)?.scene

        override suspend fun listStaging(): List<SceneEntity> = emptyList()

        override suspend fun markCommitted(
            sceneId: String,
            committedAtEpochMillis: Long,
        ): Int = 0

        override suspend fun deleteScene(sceneId: String): Int = 0
    }

    private companion object {
        const val SCENE_ID = "scene-001"
        const val IMAGE_FILE_NAME = "scene-001.jpg"

        fun record(imageSha256: String) = SceneSnapshotRecord(
            scene = SceneEntity(
                sceneId = SCENE_ID,
                schemaVersion = "1.0",
                createdAtEpochMillis = 100L,
                committedAtEpochMillis = 200L,
                modelId = "model",
                promptVersion = "prompt-v1",
                vlmRepaired = false,
                imageFileName = IMAGE_FILE_NAME,
                imageSha256 = imageSha256,
                contentFingerprint = "c".repeat(64),
                storageState = SceneStorageState.COMMITTED,
            ),
            objects = listOf(
                SceneObjectEntity(
                    sceneId = SCENE_ID,
                    objectId = "object-1",
                    displayOrder = 0,
                    displayName = "鍵",
                    appearanceFeaturesJson = "[]",
                    yMin = 100,
                    xMin = 200,
                    yMax = 500,
                    xMax = 600,
                    orientationImportant = false,
                    symmetry = "none",
                    source = "DETECTED",
                ),
            ),
        )

        fun trackingAnchor() = SceneAnchorState.Tracking(
            SceneAnchorSnapshot(
                id = 42L,
                pose = SceneAnchorPose(
                    translationX = 0f,
                    translationY = 0f,
                    translationZ = 0f,
                    rotationX = 0f,
                    rotationY = 0f,
                    rotationZ = 0f,
                    rotationW = 1f,
                ),
            ),
        )

        fun ByteArray.sha256(): String =
            MessageDigest.getInstance("SHA-256")
                .digest(this)
                .joinToString(separator = "") { byte -> "%02x".format(byte) }
    }
}
