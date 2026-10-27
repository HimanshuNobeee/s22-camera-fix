package com.s22camerafix.lensisolator.engine

import android.graphics.SurfaceTexture
import android.hardware.Camera
import android.view.SurfaceHolder
import com.s22camerafix.lensisolator.data.model.CameraTestResult
import java.io.IOException

@Suppress("DEPRECATION")
class Camera1LegacyTester(
    private val onLog: (LogEntry) -> Unit,
    private val onFpsUpdate: (Float, Int) -> Unit,
    private val onTestCompleted: (CameraTestResult) -> Unit
) {
    private var camera: Camera? = null
    private var frameCount = 0
    private var lastFpsCalculationTime = 0L
    private var currentFps = 0f
    private val sessionLogs = mutableListOf<String>()
    private var activeCameraId: String = "0"

    private fun log(message: String, isError: Boolean = false, isWarning: Boolean = false, isSuccess: Boolean = false) {
        val entry = LogEntry.create(message, isError, isWarning, isSuccess)
        sessionLogs.add("[${entry.timestamp}] $message")
        onLog(entry)
    }

    fun startTestWithSurfaceHolder(cameraIdStr: String, holder: SurfaceHolder) {
        startInternal(cameraIdStr) { cam ->
            try {
                cam.setPreviewDisplay(holder)
            } catch (e: IOException) {
                throw RuntimeException("Failed to setPreviewDisplay: ${e.message}")
            }
        }
    }

    fun startTestWithSurfaceTexture(cameraIdStr: String, texture: SurfaceTexture) {
        startInternal(cameraIdStr) { cam ->
            try {
                cam.setPreviewTexture(texture)
            } catch (e: IOException) {
                throw RuntimeException("Failed to setPreviewTexture: ${e.message}")
            }
        }
    }

    private fun startInternal(cameraIdStr: String, attachPreview: (Camera) -> Unit) {
        stopTest()

        activeCameraId = cameraIdStr
        frameCount = 0
        currentFps = 0f
        sessionLogs.clear()

        val idInt = cameraIdStr.toIntOrNull() ?: 0
        log("Testing Camera ID $cameraIdStr via Legacy Camera1 API (android.hardware.Camera)...")
        log("Legacy API bypasses modern multi-camera fusion. This tests if the primary sensor works in isolation.")

        try {
            log("Invoking Camera.open($idInt)...")
            val cam = Camera.open(idInt)
            camera = cam

            log("Camera.open($idInt) SUCCESS! Acquired legacy hardware handle.", isSuccess = true)

            // Configure parameters
            val params = cam.parameters
            val previewSizes = params.supportedPreviewSizes
            if (!previewSizes.isNullOrEmpty()) {
                val chosenSize = previewSizes.find { it.width == 1920 && it.height == 1080 }
                    ?: previewSizes.find { it.width == 1280 && it.height == 720 }
                    ?: previewSizes.first()
                params.setPreviewSize(chosenSize.width, chosenSize.height)
                log("Configured legacy preview size: ${chosenSize.width}x${chosenSize.height}")
            }

            if (params.supportedFocusModes?.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE) == true) {
                params.focusMode = Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE
            }

            cam.parameters = params
            attachPreview(cam)

            lastFpsCalculationTime = System.currentTimeMillis()

            cam.setPreviewCallback { data, _ ->
                if (data != null) {
                    frameCount++
                    val now = System.currentTimeMillis()
                    val delta = now - lastFpsCalculationTime
                    if (delta >= 1000) {
                        currentFps = (frameCount * 1000f) / delta
                        onFpsUpdate(currentFps, frameCount)
                        lastFpsCalculationTime = now
                        frameCount = 0
                    }

                    if (frameCount == 1) {
                        log("First preview frame arrived via Camera1 legacy pipeline!", isSuccess = true)
                    }
                }
            }

            cam.startPreview()
            log("Camera1 startPreview() active. Streaming frames...", isSuccess = true)

        } catch (e: RuntimeException) {
            log("Camera1 RuntimeException: ${e.message}. Camera failed to connect.", isError = true)
            onTestCompleted(
                CameraTestResult(
                    cameraId = activeCameraId,
                    apiUsed = "Camera1 (Legacy)",
                    isSuccess = false,
                    errorCode = -1,
                    errorName = "RUNTIME_EXCEPTION",
                    errorExplanation = "Camera.open() failed. Legacy HAL connection refused: ${e.message}",
                    logs = sessionLogs.toList()
                )
            )
        } catch (e: Exception) {
            log("Camera1 unexpected error: ${e.message}", isError = true)
            onTestCompleted(
                CameraTestResult(
                    cameraId = activeCameraId,
                    apiUsed = "Camera1 (Legacy)",
                    isSuccess = false,
                    errorCode = -1,
                    errorName = "EXCEPTION",
                    errorExplanation = e.message,
                    logs = sessionLogs.toList()
                )
            )
        }
    }

    fun stopTest() {
        try {
            camera?.setPreviewCallback(null)
            camera?.stopPreview()
            camera?.release()
            camera = null
        } catch (e: Exception) {
            // Ignore on teardown
        }
    }
}
