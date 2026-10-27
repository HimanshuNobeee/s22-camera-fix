package com.s22camerafix.lensisolator.data

import android.content.Context
import android.content.SharedPreferences

class CameraPreferences(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("s22_camera_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_SETUP_COMPLETED = "is_setup_completed"
        private const val KEY_ENABLED_CAMERA_IDS = "enabled_camera_ids"
        private const val KEY_DEFAULT_CAMERA_ID = "default_camera_id"
        private const val KEY_ASPECT_RATIO = "aspect_ratio"
        private const val KEY_FLASH_MODE = "flash_mode"
        private const val KEY_TIMER_SECONDS = "timer_seconds"
        private const val KEY_GRID_LINES = "show_grid_lines"
    }

    var isSetupCompleted: Boolean
        get() = prefs.getBoolean(KEY_SETUP_COMPLETED, false)
        set(value) = prefs.edit().putBoolean(KEY_SETUP_COMPLETED, value).apply()

    var enabledCameraIds: Set<String>
        get() = prefs.getStringSet(KEY_ENABLED_CAMERA_IDS, emptySet()) ?: emptySet()
        set(value) = prefs.edit().putStringSet(KEY_ENABLED_CAMERA_IDS, value).apply()

    var defaultCameraId: String?
        get() = prefs.getString(KEY_DEFAULT_CAMERA_ID, null)
        set(value) = prefs.edit().putString(KEY_DEFAULT_CAMERA_ID, value).apply()

    var aspectRatio: String
        get() = prefs.getString(KEY_ASPECT_RATIO, "4:3") ?: "4:3"
        set(value) = prefs.edit().putString(KEY_ASPECT_RATIO, value).apply()

    var flashMode: String
        get() = prefs.getString(KEY_FLASH_MODE, "OFF") ?: "OFF"
        set(value) = prefs.edit().putString(KEY_FLASH_MODE, value).apply()

    var timerSeconds: Int
        get() = prefs.getInt(KEY_TIMER_SECONDS, 0)
        set(value) = prefs.edit().putInt(KEY_TIMER_SECONDS, value).apply()

    var showGridLines: Boolean
        get() = prefs.getBoolean(KEY_GRID_LINES, false)
        set(value) = prefs.edit().putBoolean(KEY_GRID_LINES, value).apply()
}
