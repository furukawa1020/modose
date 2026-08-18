package com.modose.app.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import com.modose.app.di.AppContainer
import com.modose.app.ar.ArCoreGateState
import com.modose.app.ar.session.ArSessionPhase
import com.modose.app.ar.session.ArSessionResult
import com.modose.app.ar.session.ArCameraFrameSource
import com.modose.app.ar.session.ArTrackingDiagnostics
import com.modose.app.ar.render.CameraBackgroundSurfaceController
import com.modose.app.ar.render.CameraBackgroundSurfaceFailure
import com.modose.app.ar.render.CameraBackgroundSurfaceView
import com.modose.app.ui.camera.CameraLiveStatus
import com.modose.app.ui.camera.CameraOverlayHost
import com.modose.app.permission.CameraPermissionState
import com.modose.app.ui.theme.ModoseTheme

@Composable
fun ModoseApp(
    appContainer: AppContainer,
    cameraPermissionState: CameraPermissionState,
    arCoreGateState: ArCoreGateState,
    arSessionResult: ArSessionResult?,
    cameraFrameSource: ArCameraFrameSource?,
    cameraBackgroundFailure: CameraBackgroundSurfaceFailure?,
    trackingDiagnostics: ArTrackingDiagnostics?,
    onRequestCameraPermission: () -> Unit,
    onOpenApplicationSettings: () -> Unit,
    onRequestArCoreInstall: () -> Unit,
    onRetryArSession: () -> Unit,
    onRetryCameraBackground: () -> Unit,
    onCameraBackgroundFailure: (CameraBackgroundSurfaceFailure) -> Unit,
    onTrackingDiagnostics: (ArTrackingDiagnostics?) -> Unit,
    onCameraSurfaceChanged: (CameraBackgroundSurfaceController?) -> Unit,
) {
    CompositionLocalProvider(LocalAppContainer provides appContainer) {
        ModoseTheme {
            Surface(modifier = Modifier.fillMaxSize()) {
                if (cameraPermissionState == CameraPermissionState.Granted) {
                    ArCoreGate(
                        state = arCoreGateState,
                        sessionResult = arSessionResult,
                        frameSource = cameraFrameSource,
                        backgroundFailure = cameraBackgroundFailure,
                        trackingDiagnostics = trackingDiagnostics,
                        onRequestInstall = onRequestArCoreInstall,
                        onRetrySession = onRetryArSession,
                        onRetryBackground = onRetryCameraBackground,
                        onBackgroundFailure = onCameraBackgroundFailure,
                        onTrackingDiagnostics = onTrackingDiagnostics,
                        onSurfaceChanged = onCameraSurfaceChanged,
                    )
                } else {
                    CameraPermissionGate(
                        state = cameraPermissionState,
                        onRequestPermission = onRequestCameraPermission,
                        onOpenSettings = onOpenApplicationSettings,
                    )
                }
            }
        }
    }
}

@Composable
private fun ArCoreGate(
    state: ArCoreGateState,
    sessionResult: ArSessionResult?,
    frameSource: ArCameraFrameSource?,
    backgroundFailure: CameraBackgroundSurfaceFailure?,
    trackingDiagnostics: ArTrackingDiagnostics?,
    onRequestInstall: () -> Unit,
    onRetrySession: () -> Unit,
    onRetryBackground: () -> Unit,
    onBackgroundFailure: (CameraBackgroundSurfaceFailure) -> Unit,
    onTrackingDiagnostics: (ArTrackingDiagnostics?) -> Unit,
    onSurfaceChanged: (CameraBackgroundSurfaceController?) -> Unit,
) {
    when (state) {
        ArCoreGateState.Ready -> ArSessionGate(
            result = sessionResult,
            frameSource = frameSource,
            backgroundFailure = backgroundFailure,
            trackingDiagnostics = trackingDiagnostics,
            onRetry = onRetrySession,
            onRetryBackground = onRetryBackground,
            onBackgroundFailure = onBackgroundFailure,
            onTrackingDiagnostics = onTrackingDiagnostics,
            onSurfaceChanged = onSurfaceChanged,
        )
        ArCoreGateState.Checking,
        ArCoreGateState.Installing,
        -> GateMessage(
            title = if (state == ArCoreGateState.Checking) "Checking AR support" else "Installing AR services",
            body = "MODOSE will continue when Google Play Services for AR is ready.",
        )
        ArCoreGateState.NotInstalled,
        ArCoreGateState.UpdateRequired,
        -> GateMessage(
            title = if (state == ArCoreGateState.NotInstalled) "AR services are required" else "AR services need an update",
            body = "Install the supported version before restoring a scene.",
            actionLabel = if (state == ArCoreGateState.NotInstalled) "Install" else "Update",
            onAction = onRequestInstall,
        )
        ArCoreGateState.Unsupported -> GateMessage(
            title = "This device is not supported",
            body = "MODOSE requires an ARCore-compatible Android device.",
        )
        is ArCoreGateState.Unavailable -> GateMessage(
            title = "AR services are unavailable",
            body = "AR support could not be confirmed. Try again from the install action.",
            actionLabel = "Try again",
            onAction = onRequestInstall,
        )
    }
}

@Composable
private fun ArSessionGate(
    result: ArSessionResult?,
    frameSource: ArCameraFrameSource?,
    backgroundFailure: CameraBackgroundSurfaceFailure?,
    trackingDiagnostics: ArTrackingDiagnostics?,
    onRetry: () -> Unit,
    onRetryBackground: () -> Unit,
    onBackgroundFailure: (CameraBackgroundSurfaceFailure) -> Unit,
    onTrackingDiagnostics: (ArTrackingDiagnostics?) -> Unit,
    onSurfaceChanged: (CameraBackgroundSurfaceController?) -> Unit,
) {
    when {
        backgroundFailure != null -> GateMessage(
            title = "Camera background stopped",
            body = "MODOSE stopped drawing instead of showing an invalid camera frame.",
            actionLabel = "Retry",
            onAction = onRetryBackground,
        )
        result is ArSessionResult.Applied && result.phase == ArSessionPhase.Resumed -> {
            if (frameSource == null) {
                GateMessage(
                    title = "Preparing camera background",
                    body = "MODOSE is connecting the camera frame source.",
                )
            } else {
                CameraBackgroundHost(
                    frameSource = frameSource,
                    trackingDiagnostics = trackingDiagnostics,
                    onFailure = onBackgroundFailure,
                    onTrackingDiagnostics = onTrackingDiagnostics,
                    onSurfaceChanged = onSurfaceChanged,
                )
            }
        }
        result is ArSessionResult.Rejected -> GateMessage(
            title = "Camera session could not start",
            body = "MODOSE kept the camera closed. Retry when the camera is available.",
            actionLabel = "Retry",
            onAction = onRetry,
        )
        else -> GateMessage(
            title = "Starting camera session",
            body = "MODOSE is preparing the AR camera.",
        )
    }
}

@Composable
private fun CameraBackgroundHost(
    frameSource: ArCameraFrameSource,
    trackingDiagnostics: ArTrackingDiagnostics?,
    onFailure: (CameraBackgroundSurfaceFailure) -> Unit,
    onTrackingDiagnostics: (ArTrackingDiagnostics?) -> Unit,
    onSurfaceChanged: (CameraBackgroundSurfaceController?) -> Unit,
) {
    val context = LocalContext.current
    val view = remember(context) {
        CameraBackgroundSurfaceView(
            context = context,
            onFailure = onFailure,
            onTrackingDiagnostics = onTrackingDiagnostics,
        )
    }
    DisposableEffect(view) {
        onSurfaceChanged(view)
        view.onActivityResume()
        onDispose {
            onSurfaceChanged(null)
            view.releaseSurface()
        }
    }
    CameraOverlayHost(
        cameraSurface = {
            AndroidView(
                factory = { view },
                modifier = Modifier.fillMaxSize(),
                update = { it.frameSource = frameSource },
            )
        },
        overlay = { CameraLiveStatus(trackingDiagnostics) },
    )
}

@Composable
private fun GateMessage(
    title: String,
    body: String,
    actionLabel: String? = null,
    onAction: () -> Unit = {},
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = title)
        Text(text = body, modifier = Modifier.padding(vertical = 20.dp))
        if (actionLabel != null) {
            Button(onClick = onAction) { Text(text = actionLabel) }
        }
    }
}

@Composable
private fun CameraPermissionGate(
    state: CameraPermissionState,
    onRequestPermission: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val permanentlyDenied = state == CameraPermissionState.PermanentlyDenied

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = if (permanentlyDenied) "Camera access is blocked" else "Camera access is required")
        Text(
            text = if (permanentlyDenied) {
                "Allow camera access in Android settings to restore a saved scene."
            } else {
                "MODOSE uses the camera to save a scene and guide you back to it."
            },
            modifier = Modifier.padding(vertical = 20.dp),
        )
        Button(onClick = if (permanentlyDenied) onOpenSettings else onRequestPermission) {
            Text(text = if (permanentlyDenied) "Open settings" else "Allow camera")
        }
    }
}
