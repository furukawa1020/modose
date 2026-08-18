package com.modose.app.ar.session

data class ArCameraFrame(
    val timestampNanos: Long,
    val transformedTextureCoordinates: FloatArray?,
    val trackingDiagnostics: ArTrackingDiagnostics = ArTrackingDiagnostics(
        phase = ArTrackingPhase.Paused,
        issue = ArTrackingIssue.Unknown,
    ),
)

enum class ArTrackingPhase {
    Tracking,
    Paused,
    Stopped,
}

enum class ArTrackingIssue {
    None,
    BadState,
    InsufficientLight,
    ExcessiveMotion,
    InsufficientFeatures,
    CameraUnavailable,
    Unknown,
}

data class ArTrackingDiagnostics(
    val phase: ArTrackingPhase,
    val issue: ArTrackingIssue,
)

enum class ArCameraFrameFailureReason {
    SessionNotResumed,
    TextureNotBound,
    InvalidSurfaceSize,
    WrongGlThread,
    CameraUnavailable,
    Unknown,
}

sealed interface ArCameraFrameResult {
    data object TextureBound : ArCameraFrameResult
    data class Updated(val frame: ArCameraFrame) : ArCameraFrameResult
    data class Rejected(val reason: ArCameraFrameFailureReason) : ArCameraFrameResult
}

interface ArCameraFrameSource {
    fun bindCameraTexture(textureId: Int): ArCameraFrameResult

    fun updateCameraFrame(
        displayRotation: Int,
        widthPx: Int,
        heightPx: Int,
    ): ArCameraFrameResult
}
