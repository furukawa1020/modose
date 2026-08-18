package com.modose.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.modose.app.permission.CameraPermissionPolicy
import com.modose.app.permission.CameraPermissionState
import com.modose.app.ui.ModoseApp

class MainActivity : ComponentActivity() {
    private val appContainer by lazy { (application as ModoseApplication).appContainer }
    private var cameraPermissionState by mutableStateOf(CameraPermissionState.InitialRequest)

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        refreshCameraPermission()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        refreshCameraPermission()
        setContent {
            ModoseApp(
                appContainer = appContainer,
                cameraPermissionState = cameraPermissionState,
                onRequestCameraPermission = ::requestCameraPermission,
                onOpenApplicationSettings = ::openApplicationSettings,
            )
        }
    }

    override fun onResume() {
        super.onResume()
        refreshCameraPermission()
    }

    private fun requestCameraPermission() {
        appContainer.cameraPermissionHistory.markRequested()
        cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
    }

    private fun openApplicationSettings() {
        startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", packageName, null),
            ),
        )
    }

    private fun refreshCameraPermission() {
        cameraPermissionState = CameraPermissionPolicy.resolve(
            isGranted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA,
            ) == PackageManager.PERMISSION_GRANTED,
            hasRequested = appContainer.cameraPermissionHistory.hasRequested,
            shouldShowRationale = shouldShowRequestPermissionRationale(Manifest.permission.CAMERA),
        )
    }
}
