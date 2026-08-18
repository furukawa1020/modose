package com.modose.app.ar.render

class CameraSurfaceControllerRegistry {
    private var controller: CameraBackgroundSurfaceController? = null
    private var resumed = false
    private var destroyed = false

    fun replace(next: CameraBackgroundSurfaceController?) {
        if (controller === next) return

        controller?.releaseSurface()
        controller = null
        if (next == null) return
        if (destroyed) {
            next.releaseSurface()
            return
        }

        controller = next
        if (resumed) next.onActivityResume()
    }

    fun onActivityResume() {
        if (destroyed || resumed) return
        resumed = true
        controller?.onActivityResume()
    }

    fun onActivityPause() {
        if (!resumed) return
        controller?.onActivityPause()
        resumed = false
    }

    fun onDestroy() {
        if (destroyed) return
        onActivityPause()
        destroyed = true
        controller?.releaseSurface()
        controller = null
    }
}
