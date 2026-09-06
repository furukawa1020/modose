package com.modose.app.flow.save

import com.modose.app.ar.anchor.SceneAnchorState
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class WaitingForChangeTransitionRejection {
    InvalidCurrentState,
    SceneMismatch,
}

sealed interface WaitingForChangeTransitionResult {
    data object Transitioned : WaitingForChangeTransitionResult

    data object AlreadyWaiting : WaitingForChangeTransitionResult

    data class Rejected(
        val reason: WaitingForChangeTransitionRejection,
    ) : WaitingForChangeTransitionResult
}

fun interface WaitingForChangeTransitionGateway {
    fun transition(
        sceneId: String,
        anchorId: Long,
    ): WaitingForChangeTransitionResult
}

enum class CommitSavedSceneAndWaitFailure {
    TransitionUnavailable,
    CompensationFailed,
}

sealed interface CommitSavedSceneAndWaitResult {
    data class Transitioned(
        val binding: SavedSceneAnchorBinding,
    ) : CommitSavedSceneAndWaitResult

    data class AlreadyWaiting(
        val binding: SavedSceneAnchorBinding,
    ) : CommitSavedSceneAndWaitResult

    data class CommitRejected(
        val reason: SavedSceneCommitRejection,
    ) : CommitSavedSceneAndWaitResult

    data class CommitFailed(
        val reason: CommitSavedSceneFailure,
    ) : CommitSavedSceneAndWaitResult

    data class TransitionRejected(
        val binding: SavedSceneAnchorBinding,
        val reason: WaitingForChangeTransitionRejection,
        val bindingRetained: Boolean,
    ) : CommitSavedSceneAndWaitResult

    data class Failed(
        val binding: SavedSceneAnchorBinding,
        val reason: CommitSavedSceneAndWaitFailure,
    ) : CommitSavedSceneAndWaitResult
}

class CommitSavedSceneAndWaitUseCase(
    private val commitSavedScene: CommitSavedSceneUseCase,
    private val bindingStore: SavedSceneAnchorBindingStore,
    private val transitionGateway: WaitingForChangeTransitionGateway,
) {
    private val transitionMutex = Mutex()

    suspend fun execute(
        sceneId: String,
        anchorState: SceneAnchorState,
    ): CommitSavedSceneAndWaitResult = transitionMutex.withLock {
        when (val commit = commitSavedScene.execute(sceneId, anchorState)) {
            is CommitSavedSceneResult.Rejected ->
                CommitSavedSceneAndWaitResult.CommitRejected(commit.reason)

            is CommitSavedSceneResult.Failed ->
                CommitSavedSceneAndWaitResult.CommitFailed(commit.reason)

            is CommitSavedSceneResult.Committed ->
                transitionNewBinding(commit.binding)

            is CommitSavedSceneResult.AlreadyCommitted ->
                transitionExistingBinding(commit.binding)
        }
    }

    private fun transitionNewBinding(
        binding: SavedSceneAnchorBinding,
    ): CommitSavedSceneAndWaitResult {
        val transition = tryTransition(binding)
            ?: return compensateUnavailableTransition(binding)

        return when (transition) {
            WaitingForChangeTransitionResult.Transitioned ->
                CommitSavedSceneAndWaitResult.Transitioned(binding)

            WaitingForChangeTransitionResult.AlreadyWaiting ->
                CommitSavedSceneAndWaitResult.AlreadyWaiting(binding)

            is WaitingForChangeTransitionResult.Rejected -> {
                val cleared = bindingStore.clear(binding.sceneId)
                if (cleared) {
                    CommitSavedSceneAndWaitResult.TransitionRejected(
                        binding = binding,
                        reason = transition.reason,
                        bindingRetained = false,
                    )
                } else {
                    CommitSavedSceneAndWaitResult.Failed(
                        binding,
                        CommitSavedSceneAndWaitFailure.CompensationFailed,
                    )
                }
            }
        }
    }

    private fun transitionExistingBinding(
        binding: SavedSceneAnchorBinding,
    ): CommitSavedSceneAndWaitResult {
        val transition = tryTransition(binding)
            ?: return CommitSavedSceneAndWaitResult.Failed(
                binding,
                CommitSavedSceneAndWaitFailure.TransitionUnavailable,
            )

        return when (transition) {
            WaitingForChangeTransitionResult.Transitioned ->
                CommitSavedSceneAndWaitResult.Transitioned(binding)

            WaitingForChangeTransitionResult.AlreadyWaiting ->
                CommitSavedSceneAndWaitResult.AlreadyWaiting(binding)

            is WaitingForChangeTransitionResult.Rejected ->
                CommitSavedSceneAndWaitResult.TransitionRejected(
                    binding = binding,
                    reason = transition.reason,
                    bindingRetained = true,
                )
        }
    }

    private fun tryTransition(
        binding: SavedSceneAnchorBinding,
    ): WaitingForChangeTransitionResult? =
        try {
            transitionGateway.transition(binding.sceneId, binding.anchorId)
        } catch (_: RuntimeException) {
            null
        }

    private fun compensateUnavailableTransition(
        binding: SavedSceneAnchorBinding,
    ): CommitSavedSceneAndWaitResult =
        if (bindingStore.clear(binding.sceneId)) {
            CommitSavedSceneAndWaitResult.Failed(
                binding,
                CommitSavedSceneAndWaitFailure.TransitionUnavailable,
            )
        } else {
            CommitSavedSceneAndWaitResult.Failed(
                binding,
                CommitSavedSceneAndWaitFailure.CompensationFailed,
            )
        }
}
