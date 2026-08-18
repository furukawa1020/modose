package com.modose.app.permission

import android.content.Context

interface CameraPermissionHistory {
    val hasRequested: Boolean

    fun markRequested()
}

class SharedPreferencesCameraPermissionHistory(context: Context) : CameraPermissionHistory {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override val hasRequested: Boolean
        get() = preferences.getBoolean(HAS_REQUESTED_KEY, false)

    override fun markRequested() {
        preferences.edit().putBoolean(HAS_REQUESTED_KEY, true).apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "camera_permission"
        const val HAS_REQUESTED_KEY = "has_requested"
    }
}
