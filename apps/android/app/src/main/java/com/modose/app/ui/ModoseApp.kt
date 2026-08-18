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
import com.modose.app.permission.CameraPermissionState
import com.modose.app.ui.theme.ModoseTheme

@Composable
fun ModoseApp(
    appContainer: AppContainer,
    cameraPermissionState: CameraPermissionState,
    onRequestCameraPermission: () -> Unit,
    onOpenApplicationSettings: () -> Unit,
) {
    CompositionLocalProvider(LocalAppContainer provides appContainer) {
        ModoseTheme {
            Surface(modifier = Modifier.fillMaxSize()) {
                if (cameraPermissionState == CameraPermissionState.Granted) {
                    GrantedCameraContent()
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
private fun GrantedCameraContent() {
    Surface(modifier = Modifier.fillMaxSize()) {}
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
