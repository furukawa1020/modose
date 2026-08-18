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
import com.google.ar.core.ArCoreApk
import com.google.ar.core.exceptions.UnavailableApkTooOldException
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException
import com.google.ar.core.exceptions.UnavailableException
import com.google.ar.core.exceptions.UnavailableSdkTooOldException
import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException
import com.modose.app.ar.ArCoreAvailabilityPolicy
import com.modose.app.ar.ArCoreFailureReason
import com.modose.app.ar.ArCoreGateState
import com.modose.app.permission.CameraPermissionPolicy
import com.modose.app.permission.CameraPermissionState
import com.modose.app.ui.ModoseApp

class MainActivity : ComponentActivity() {
    private val appContainer by lazy { (application as ModoseApplication).appContainer }
    private var cameraPermissionState by mutableStateOf(CameraPermissionState.InitialRequest)
    private var arCoreGateState by mutableStateOf<ArCoreGateState>(ArCoreGateState.Checking)

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
                arCoreGateState = arCoreGateState,
                onRequestCameraPermission = ::requestCameraPermission,
                onOpenApplicationSettings = ::openApplicationSettings,
                onRequestArCoreInstall = ::requestArCoreInstall,
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
        if (cameraPermissionState == CameraPermissionState.Granted) {
            refreshArCoreAvailability()
        }
    }

    private fun refreshArCoreAvailability() {
        arCoreGateState = ArCoreGateState.Checking
        ArCoreApk.getInstance().checkAvailabilityAsync(applicationContext) { availability ->
            arCoreGateState = ArCoreAvailabilityPolicy.resolve(availability)
        }
    }

    private fun requestArCoreInstall() {
        try {
            arCoreGateState = when (ArCoreApk.getInstance().requestInstall(this, true)) {
                ArCoreApk.InstallStatus.INSTALL_REQUESTED -> ArCoreGateState.Installing
                ArCoreApk.InstallStatus.INSTALLED -> ArCoreGateState.Ready
            }
        } catch (_: UnavailableDeviceNotCompatibleException) {
            arCoreGateState = ArCoreGateState.Unsupported
        } catch (_: UnavailableUserDeclinedInstallationException) {
            arCoreGateState = ArCoreGateState.Unavailable(ArCoreFailureReason.InstallationDeclined)
        } catch (_: UnavailableApkTooOldException) {
            arCoreGateState = ArCoreGateState.UpdateRequired
        } catch (_: UnavailableSdkTooOldException) {
            arCoreGateState = ArCoreGateState.Unavailable(ArCoreFailureReason.SdkTooOld)
        } catch (_: UnavailableException) {
            arCoreGateState = ArCoreGateState.Unavailable(ArCoreFailureReason.InstallationFailed)
        }
    }
}
