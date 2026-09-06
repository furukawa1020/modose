package com.modose.app.flow.save

import com.modose.app.ar.anchor.SceneAnchorState
import com.modose.app.data.local.SceneSnapshotDao
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface SavedSceneBindingWriteResult {
    data class Bound(
        val binding: SavedSceneAnchorBinding,
    ) : SavedSceneBindingWriteResult

    data class AlreadyBound(
        val binding: SavedSceneAnchorBinding,
    ) : SavedSceneBindingWriteResult

    data class Conflict(
        val existing: SavedSceneAnchorBinding,
    ) : SavedSceneBindingWriteResult
}

interface SavedSceneAnchorBindingStore {
    fun current(): SavedSceneAnchorBinding?

    fun bindIfAbsent(
        binding: SavedSceneAnchorBinding,
    ): SavedSceneBindingWriteResult

    fun clear(sceneId: String): Boolean
}

class InMemorySavedSceneAnchorBindingStore : SavedSceneAnchorBindingStore {
    private var binding: SavedSceneAnchorBinding? = null

    @Synchronized
    override fun current(): SavedSceneAnchorBinding? = binding

    @Synchronized
    override fun bindIfAbsent(
        binding: SavedSceneAnchorBinding,
    ): SavedSceneBindingWriteResult {
        val existing = this.binding
        if (existing == null) {
            this.binding = binding
            return SavedSceneBindingWriteResult.Bound(binding)
        }
        return if (
            existing.sceneId == binding.sceneId &&
            existing.anchorId == binding.anchorId
        ) {
            SavedSceneBindingWriteResult.AlreadyBound(existing)
        } else {
            SavedSceneBindingWriteResult.Conflict(existing)
        }
    }

    @Synchronized
    override fun clear(sceneId: String): Boolean {
        val existing = binding ?: return true
        if (existing.sceneId != sceneId) {
            return false
        }
        binding = null
        return true
    }
}

enum class CommitSavedSceneFailure {
    DatabaseUnavailable,
    BindingConflict,
}

sealed interface CommitSavedSceneResult {
    data class Committed(
        val binding: SavedSceneAnchorBinding,
    ) : CommitSavedSceneResult

    data class AlreadyCommitted(
        val binding: SavedSceneAnchorBinding,
    ) : CommitSavedSceneResult

    data class Rejected(
        val reason: SavedSceneCommitRejection,
    ) : CommitSavedSceneResult

    data class Failed(
        val reason: CommitSavedSceneFailure,
    ) : CommitSavedSceneResult
}

class CommitSavedSceneUseCase(
    private val dao: SceneSnapshotDao,
    private val bindingStore: SavedSceneAnchorBindingStore,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
) {
    private val commitMutex = Mutex()

    suspend fun execute(
        sceneId: String,
        anchorState: SceneAnchorState,
    ): CommitSavedSceneResult = commitMutex.withLock {
        val committedSnapshot = try {
            dao.findCommitted(sceneId)
        } catch (_: RuntimeException) {
            return@withLock CommitSavedSceneResult.Failed(
                CommitSavedSceneFailure.DatabaseUnavailable,
            )
        }

        val decision = SavedSceneCommitPolicy.decide(
            SavedSceneCommitRequest(
                sceneId = sceneId,
                snapshotCommitted = committedSnapshot != null,
                anchorState = anchorState,
                existingBinding = bindingStore.current(),
                requestedAtEpochMillis = nowEpochMillis(),
            ),
        )
        when (decision) {
            is SavedSceneCommitDecision.Reject ->
                CommitSavedSceneResult.Rejected(decision.reason)

            is SavedSceneCommitDecision.AlreadyBound ->
                CommitSavedSceneResult.AlreadyCommitted(decision.binding)

            is SavedSceneCommitDecision.Bind ->
                when (val write = bindingStore.bindIfAbsent(decision.binding)) {
                    is SavedSceneBindingWriteResult.Bound ->
                        CommitSavedSceneResult.Committed(write.binding)
                    is SavedSceneBindingWriteResult.AlreadyBound ->
                        CommitSavedSceneResult.AlreadyCommitted(write.binding)
                    is SavedSceneBindingWriteResult.Conflict ->
                        CommitSavedSceneResult.Failed(
                            CommitSavedSceneFailure.BindingConflict,
                        )
                }
        }
    }
}
