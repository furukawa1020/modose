package com.modose.app.flow.save

import com.modose.app.ar.anchor.SceneAnchorPose
import com.modose.app.ar.anchor.SceneAnchorSnapshot
import com.modose.app.ar.anchor.SceneAnchorState
import com.modose.app.data.local.SceneEntity
import com.modose.app.data.local.SceneObjectEntity
import com.modose.app.data.local.SceneSnapshotDao
import com.modose.app.data.local.SceneSnapshotRecord
import com.modose.app.data.local.SceneStorageState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CommitSavedSceneAndWaitUseCaseTest {
    @Test
    fun committedSceneTransitionsExactlyOnceAcrossDuplicateExecution() = runBlocking {
        val dao = FakeSceneSnapshotDao(committed = true)
        val bindingStore = InMemorySavedSceneAnchorBindingStore()
        val transition = CountingTransitionGateway()
        val useCase = useCase(dao, bindingStore, transition)

        val first = useCase.execute(SCENE_ID, trackingAnchor())
        val second = useCase.execute(SCENE_ID, trackingAnchor())

        assertTrue(first is CommitSavedSceneAndWaitResult.Transitioned)
        assertTrue(second is CommitSavedSceneAndWaitResult.AlreadyWaiting)
        assertEquals(1, transition.transitionCount)
        assertEquals(SCENE_ID, bindingStore.current()?.sceneId)
    }

    @Test
    fun uncommittedSceneNeverCreatesBindingOrTransition() = runBlocking {
        val dao = FakeSceneSnapshotDao(committed = false)
        val bindingStore = InMemorySavedSceneAnchorBindingStore()
        val transition = CountingTransitionGateway()
        val useCase = useCase(dao, bindingStore, transition)

        val result = useCase.execute(SCENE_ID, trackingAnchor())

        assertEquals(
            CommitSavedSceneAndWaitResult.CommitRejected(
                SavedSceneCommitRejection.SnapshotNotCommitted,
            ),
            result,
        )
        assertNull(bindingStore.current())
        assertEquals(0, transition.transitionCount)
    }

    @Test
    fun databaseFailureDoesNotCreateBindingOrTransition() = runBlocking {
        val dao = FakeSceneSnapshotDao(committed = true, failRead = true)
        val bindingStore = InMemorySavedSceneAnchorBindingStore()
        val transition = CountingTransitionGateway()
        val useCase = useCase(dao, bindingStore, transition)

        val result = useCase.execute(SCENE_ID, trackingAnchor())

        assertEquals(
            CommitSavedSceneAndWaitResult.CommitFailed(
                CommitSavedSceneFailure.DatabaseUnavailable,
            ),
            result,
        )
        assertNull(bindingStore.current())
        assertEquals(0, transition.transitionCount)
    }

    @Test
    fun firstTransitionFailureCompensatesNewBinding() = runBlocking {
        val dao = FakeSceneSnapshotDao(committed = true)
        val bindingStore = InMemorySavedSceneAnchorBindingStore()
        val useCase = useCase(
            dao,
            bindingStore,
            WaitingForChangeTransitionGateway { _, _ ->
                throw IllegalStateException("reducer unavailable")
            },
        )

        val result = useCase.execute(SCENE_ID, trackingAnchor())

        assertTrue(result is CommitSavedSceneAndWaitResult.Failed)
        result as CommitSavedSceneAndWaitResult.Failed
        assertEquals(
            CommitSavedSceneAndWaitFailure.TransitionUnavailable,
            result.reason,
        )
        assertNull(bindingStore.current())
    }

    @Test
    fun rejectedFirstTransitionReportsThatBindingWasRemoved() = runBlocking {
        val dao = FakeSceneSnapshotDao(committed = true)
        val bindingStore = InMemorySavedSceneAnchorBindingStore()
        val useCase = useCase(
            dao,
            bindingStore,
            WaitingForChangeTransitionGateway { _, _ ->
                WaitingForChangeTransitionResult.Rejected(
                    WaitingForChangeTransitionRejection.InvalidCurrentState,
                )
            },
        )

        val result = useCase.execute(SCENE_ID, trackingAnchor())

        assertEquals(
            CommitSavedSceneAndWaitResult.TransitionRejected(
                binding = binding(),
                reason = WaitingForChangeTransitionRejection.InvalidCurrentState,
                bindingRetained = false,
            ),
            result,
        )
        assertNull(bindingStore.current())
    }

    private fun useCase(
        dao: SceneSnapshotDao,
        bindingStore: SavedSceneAnchorBindingStore,
        transition: WaitingForChangeTransitionGateway,
    ): CommitSavedSceneAndWaitUseCase {
        val commit = CommitSavedSceneUseCase(
            dao = dao,
            bindingStore = bindingStore,
            nowEpochMillis = { BOUND_AT },
        )
        return CommitSavedSceneAndWaitUseCase(commit, bindingStore, transition)
    }

    private class CountingTransitionGateway : WaitingForChangeTransitionGateway {
        var transitionCount = 0
        private var waitingSceneId: String? = null
        private var waitingAnchorId: Long? = null

        override fun transition(
            sceneId: String,
            anchorId: Long,
        ): WaitingForChangeTransitionResult {
            if (waitingSceneId == sceneId && waitingAnchorId == anchorId) {
                return WaitingForChangeTransitionResult.AlreadyWaiting
            }
            transitionCount += 1
            waitingSceneId = sceneId
            waitingAnchorId = anchorId
            return WaitingForChangeTransitionResult.Transitioned
        }
    }

    private class FakeSceneSnapshotDao(
        private val committed: Boolean,
        private val failRead: Boolean = false,
    ) : SceneSnapshotDao {
        override suspend fun insertScene(scene: SceneEntity) = Unit

        override suspend fun insertObjects(objects: List<SceneObjectEntity>) = Unit

        override suspend fun findCommitted(sceneId: String): SceneSnapshotRecord? {
            if (failRead) {
                throw IllegalStateException("database unavailable")
            }
            return if (committed && sceneId == SCENE_ID) {
                SceneSnapshotRecord(sceneEntity(), emptyList())
            } else {
                null
            }
        }

        override suspend fun findAnyState(sceneId: String): SceneEntity? =
            findCommitted(sceneId)?.scene

        override suspend fun listStaging(): List<SceneEntity> = emptyList()

        override suspend fun markCommitted(
            sceneId: String,
            committedAtEpochMillis: Long,
        ): Int = 0

        override suspend fun deleteScene(sceneId: String): Int = 0
    }

    companion object {
        private const val SCENE_ID = "scene-001"
        private const val ANCHOR_ID = 42L
        private const val BOUND_AT = 1_000L

        private fun trackingAnchor() = SceneAnchorState.Tracking(
            SceneAnchorSnapshot(ANCHOR_ID, pose()),
        )

        private fun binding() = SavedSceneAnchorBinding(
            sceneId = SCENE_ID,
            anchorId = ANCHOR_ID,
            anchorPose = pose(),
            boundAtEpochMillis = BOUND_AT,
        )

        private fun pose() = SceneAnchorPose(
            translationX = 0.1f,
            translationY = 0.0f,
            translationZ = -0.2f,
            rotationX = 0.0f,
            rotationY = 0.0f,
            rotationZ = 0.0f,
            rotationW = 1.0f,
        )

        private fun sceneEntity() = SceneEntity(
            sceneId = SCENE_ID,
            schemaVersion = "1",
            createdAtEpochMillis = 100L,
            committedAtEpochMillis = 200L,
            modelId = "model",
            promptVersion = "prompt-v1",
            vlmRepaired = false,
            imageFileName = SCENE_ID + ".jpg",
            imageSha256 = "sha256",
            contentFingerprint = "fingerprint",
            storageState = SceneStorageState.COMMITTED,
        )
    }
}
