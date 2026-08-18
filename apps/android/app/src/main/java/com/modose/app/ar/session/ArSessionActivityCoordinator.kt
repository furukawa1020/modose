package com.modose.app.ar.session

class ArSessionActivityCoordinator(
    private val lifecycleFactory: () -> ArSessionLifecycle,
    private val onResult: (ArSessionResult) -> Unit,
) {
    private var lifecycle: ArSessionLifecycle? = null
    private var activityResumed = false
    private var arCoreReady = false
    private var destroyed = false

    fun onResume() {
        activityResumed = true
        startIfAllowed()
    }

    fun onPause() {
        activityResumed = false
        lifecycle?.pause()?.let(onResult)
    }

    fun onArCoreReady() {
        arCoreReady = true
        startIfAllowed()
    }

    fun onPrerequisiteUnavailable() {
        arCoreReady = false
        release()
    }

    fun retry() {
        startIfAllowed()
    }

    fun onDestroy() {
        destroyed = true
        activityResumed = false
        arCoreReady = false
        release()
    }

    private fun startIfAllowed() {
        if (destroyed || !activityResumed || !arCoreReady) return

        val ownedLifecycle = lifecycle ?: lifecycleFactory().also { lifecycle = it }
        val createResult = ownedLifecycle.create()
        onResult(createResult)
        if (createResult is ArSessionResult.Applied) {
            onResult(ownedLifecycle.resume())
        }
    }

    private fun release() {
        val ownedLifecycle = lifecycle
        lifecycle = null
        ownedLifecycle?.close()?.let(onResult)
    }
}
