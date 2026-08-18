package com.modose.app.di

import android.content.Context
import com.modose.app.permission.CameraPermissionHistory
import com.modose.app.permission.SharedPreferencesCameraPermissionHistory

class AppContainer private constructor(
    val cameraPermissionHistory: CameraPermissionHistory,
) {
    companion object {
        fun create(context: Context): AppContainer = AppContainer(
            cameraPermissionHistory = SharedPreferencesCameraPermissionHistory(context),
        )
    }
}
