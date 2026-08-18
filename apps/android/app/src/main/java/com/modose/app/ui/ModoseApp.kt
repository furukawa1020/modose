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
import androidx.compose.ui.unit.dp
import com.modose.app.di.AppContainer
import com.modose.app.ar.ArCoreGateState
import com.modose.app.ar.session.ArSessionPhase
import com.modose.app.ar.session.ArSessionResult
import com.modose.app.permission.CameraPermissionState
import com.modose.app.ui.theme.ModoseTheme

@Composable
fun ModoseApp(
    appContainer: AppContainer,
    cameraPermissionState: CameraPermissionState,
    arCoreGateState: ArCoreGateState,
    arSessionResult: ArSessionResult?,
    onRequestCameraPermission: () -> Unit,
    onOpenApplicationSettings: () -> Unit,
    onRequestArCoreInstall: () -> Unit,
    onRetryArSession: () -> Unit,
) {
    CompositionLocalProvider(LocalAppContainer provides appContainer) {
        ModoseTheme {
            Surface(modifier = Modifier.fillMaxSize()) {
                if (cameraPermissionState == CameraPermissionState.Granted) {
                    ArCoreGate(
                        state = arCoreGateState,
                        sessionResult = arSessionResult,
                        onRequestInstall = onRequestArCoreInstall,
                        onRetrySession = onRetryArSession,
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
    onRequestInstall: () -> Unit,
    onRetrySession: () -> Unit,
) {
    when (state) {
        ArCoreGateState.Ready -> ArSessionGate(sessionResult, onRetrySession)
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
    onRetry: () -> Unit,
) {
    when {
        result is ArSessionResult.Applied && result.phase == ArSessionPhase.Resumed -> {
            Surface(modifier = Modifier.fillMaxSize()) {}
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
