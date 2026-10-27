package com.s22camerafix.lensisolator.engine

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import com.s22camerafix.lensisolator.data.model.CameraTestResult
import java.util.concurrent.Executors

class Camera2Tester(
    private val context: Context,
    private val onLog: (LogEntry) -> Unit,
    private val onFpsUpdate: (Float, Int) -> Unit,
    private val onTestCompleted: (CameraTestResult) -> Unit
) {
    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null

    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    private var frameCount = 0
    private var lastFpsCalculationTime = 0L
    private var currentFps = 0f
    private val sessionLogs = mutableListOf<String>()

    private var activeCameraId: String = ""
    private var activePhysicalId: String? = null

    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("Camera2TesterThread").apply {
            start()
            backgroundHandler = Handler(looper)
        }
    }

    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join(1000)
            backgroundThread = null
            backgroundHandler = null
        } catch (e: InterruptedException) {
            e.printStackTrace()
        }
    }

    private fun log(message: String, isError: Boolean = false, isWarning: Boolean = false, isSuccess: Boolean = false) {
        val entry = LogEntry.create(message, isError, isWarning, isSuccess)
        sessionLogs.add("[${entry.timestamp}] $message")
        onLog(entry)
    }

    @SuppressLint("MissingPermission")
    fun startTest(cameraId: String, previewSurface: Surface, targetPhysicalCameraId: String? = null) {
        stopTest() // Ensure previous session is closed

        activeCameraId = cameraId
        activePhysicalId = targetPhysicalCameraId
        frameCount = 0
        currentFps = 0f
        sessionLogs.clear()
        startBackgroundThread()

        log("Initiating test for Camera ID: $cameraId" +
                if (targetPhysicalCameraId != null) " (Targeting Physical Stream: $targetPhysicalCameraId)" else "")

        // Read characteristics to check if logical
        try {
            val char = cameraManager.getCameraCharacteristics(cameraId)
            val physicalIds = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) char.physicalCameraIds else emptySet()
            if (physicalIds.isNotEmpty()) {
                log("NOTE: ID $cameraId is a Logical Multi-Camera tied to physical sensors: [${physicalIds.joinToString()}].", isWarning = true)
            }
        } catch (e: Exception) {
            log("Characteristics probe note: ${e.message}", isWarning = true)
        }

        val handler = backgroundHandler ?: return

        try {
            log("Calling CameraManager.openCamera(\"$cameraId\")...")
            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    log("StateCallback: onOpened -> Successfully acquired CameraDevice handle (ID: ${camera.id})", isSuccess = true)
                    setupPreviewSession(camera, previewSurface, targetPhysicalCameraId, handler)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    log("StateCallback: onDisconnected -> Camera device was disconnected.", isWarning = true)
                    camera.close()
                    cameraDevice = null
                    onTestCompleted(
                        CameraTestResult(
                            cameraId = activeCameraId,
                            apiUsed = "Camera2",
                            physicalStreamId = activePhysicalId,
                            isSuccess = false,
                            errorName = "DISCONNECTED",
                            errorExplanation = "Camera device disconnected unexpectedly.",
                            logs = sessionLogs.toList()
                        )
                    )
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    val (name, explanation) = decodeCameraError(error)
                    log("StateCallback: onError -> Code $error ($name): $explanation", isError = true)
                    camera.close()
                    cameraDevice = null
                    onTestCompleted(
                        CameraTestResult(
                            cameraId = activeCameraId,
                            apiUsed = "Camera2",
                            physicalStreamId = activePhysicalId,
                            isSuccess = false,
                            fps = currentFps,
                            framesDelivered = frameCount,
                            errorCode = error,
                            errorName = name,
                            errorExplanation = explanation,
                            logs = sessionLogs.toList()
                        )
                    )
                }
            }, handler)
        } catch (e: SecurityException) {
            log("SecurityException: Camera permission missing! ${e.message}", isError = true)
            onTestCompleted(createFailedResult("SECURITY_EXCEPTION", e.message))
        } catch (e: IllegalArgumentException) {
            log("IllegalArgumentException: Invalid camera ID \"$cameraId\" or sensor not directly accessible by HAL: ${e.message}", isError = true)
            onTestCompleted(createFailedResult("ILLEGAL_ARGUMENT", e.message))
        } catch (e: Exception) {
            log("Fatal Exception during openCamera: ${e.javaClass.simpleName}: ${e.message}", isError = true)
            onTestCompleted(createFailedResult("FATAL_EXCEPTION", e.message))
        }
    }

    private fun setupPreviewSession(
        camera: CameraDevice,
        previewSurface: Surface,
        targetPhysicalCameraId: String?,
        handler: Handler
    ) {
        try {
            log("Configuring capture session for preview...")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val outputConfig = OutputConfiguration(previewSurface)
                if (targetPhysicalCameraId != null) {
                    log("Applying OutputConfiguration.setPhysicalCameraId(\"$targetPhysicalCameraId\")...")
                    outputConfig.setPhysicalCameraId(targetPhysicalCameraId)
                }

                val sessionConfig = SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    listOf(outputConfig),
                    Executors.newSingleThreadExecutor(),
                    createSessionStateCallback(camera, previewSurface, handler)
                )
                camera.createCaptureSession(sessionConfig)
            } else {
                @Suppress("DEPRECATION")
                camera.createCaptureSession(
                    listOf(previewSurface),
                    createSessionStateCallback(camera, previewSurface, handler),
                    handler
                )
            }
        } catch (e: Exception) {
            log("Failed to create capture session: ${e.message}", isError = true)
            onTestCompleted(createFailedResult("SESSION_CREATION_FAILED", e.message))
        }
    }

    private fun createSessionStateCallback(
        camera: CameraDevice,
        previewSurface: Surface,
        handler: Handler
    ): CameraCaptureSession.StateCallback {
        return object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                captureSession = session
                log("CaptureSession: onConfigured -> Session ready. Submitting repeating preview request...", isSuccess = true)

                try {
                    val requestBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                        addTarget(previewSurface)
                        set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                        set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                    }

                    lastFpsCalculationTime = System.currentTimeMillis()

                    session.setRepeatingRequest(requestBuilder.build(), object : CameraCaptureSession.CaptureCallback() {
                        override fun onCaptureCompleted(
                            session: CameraCaptureSession,
                            request: CaptureRequest,
                            result: TotalCaptureResult
                        ) {
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
                                log("First preview frame successfully received from sensor!", isSuccess = true)
                            }
                        }
                    }, handler)

                    log("Live stream active. Monitoring frame delivery...", isSuccess = true)
                } catch (e: Exception) {
                    log("Failed to submit repeating request: ${e.message}", isError = true)
                    onTestCompleted(createFailedResult("REPEATING_REQUEST_FAILED", e.message))
                }
            }

            override fun onConfigureFailed(session: CameraCaptureSession) {
                log("CaptureSession: onConfigureFailed -> HAL rejected the requested stream configuration.", isError = true)
                onTestCompleted(createFailedResult("CONFIGURE_FAILED", "HAL rejected the stream configuration for this camera/sensor."))
            }
        }
    }

    fun stopTest() {
        try {
            captureSession?.stopRepeating()
            captureSession?.close()
            captureSession = null
        } catch (e: Exception) {
            // Ignore on teardown
        }

        try {
            cameraDevice?.close()
            cameraDevice = null
        } catch (e: Exception) {
            // Ignore on teardown
        }

        stopBackgroundThread()
    }

    private fun createFailedResult(name: String, msg: String?): CameraTestResult {
        return CameraTestResult(
            cameraId = activeCameraId,
            apiUsed = "Camera2",
            physicalStreamId = activePhysicalId,
            isSuccess = false,
            errorName = name,
            errorExplanation = msg,
            logs = sessionLogs.toList()
        )
    }

    private fun decodeCameraError(error: Int): Pair<String, String> {
        return when (error) {
            CameraDevice.StateCallback.ERROR_CAMERA_IN_USE ->
                Pair("ERROR_CAMERA_IN_USE", "Camera is already in use by another foreground/background process.")
            CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE ->
                Pair("ERROR_MAX_CAMERAS_IN_USE", "System limit reached for simultaneously open camera devices.")
            CameraDevice.StateCallback.ERROR_CAMERA_DISABLED ->
                Pair("ERROR_CAMERA_DISABLED", "Camera is disabled due to device policy or thermal throttling.")
            CameraDevice.StateCallback.ERROR_CAMERA_DEVICE ->
                Pair(
                    "ERROR_CAMERA_DEVICE (Fatal Hardware Fault)",
                    "The camera sensor, OIS coil, or shared I2C/power bus failed to respond to the driver. " +
                            "This is the hallmark symptom of water damage causing corrosion or a short on the sensor submodule."
                )
            CameraDevice.StateCallback.ERROR_CAMERA_SERVICE ->
                Pair(
                    "ERROR_CAMERA_SERVICE (CameraServer Crash)",
                    "The Android camera server daemon (cameraserver / vendor HAL) crashed while trying to probe or power on the sensor."
                )
            else -> Pair("UNKNOWN_ERROR_$error", "Unspecified camera driver error code: $error")
        }
    }
}
