package com.s22camerafix.lensisolator.engine

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.ImageReader
import android.media.MediaActionSound
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.MediaStore
import android.util.Size
import android.view.OrientationEventListener
import android.view.Surface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs

enum class TargetLens(val label: String) {
    ULTRA_WIDE(".6x"),
    WIDE("1x"),
    TELE_10X("10x")
}

data class ResolvedSensorConfig(
    val lens: TargetLens,
    val cameraIdToOpen: String,     // e.g., "2" for standalone or "0" for multi-cam
    val physicalCameraId: String?,  // e.g., "5", "7" or null
    val characteristics: CameraCharacteristics
)

class MinimalCameraEngine(
    private val context: Context,
    private val scope: CoroutineScope
) {
    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val mediaActionSound = MediaActionSound().apply { load(MediaActionSound.SHUTTER_CLICK) }

    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var previewRequestBuilder: CaptureRequest.Builder? = null
    private var imageReader: ImageReader? = null

    private var currentSurfaceTexture: SurfaceTexture? = null
    private var previewSurface: Surface? = null

    // StateFlows
    private val _currentLens = MutableStateFlow(TargetLens.WIDE)
    val currentLens: StateFlow<TargetLens> = _currentLens.asStateFlow()

    private val _isFlashOn = MutableStateFlow(false)
    val isFlashOn: StateFlow<Boolean> = _isFlashOn.asStateFlow()

    private val _isCapturing = MutableStateFlow(false)
    val isCapturing: StateFlow<Boolean> = _isCapturing.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _lastSavedThumbnail = MutableStateFlow<Bitmap?>(null)
    val lastSavedThumbnail: StateFlow<Bitmap?> = _lastSavedThumbnail.asStateFlow()

    private val _lastSavedUri = MutableStateFlow<Uri?>(null)
    val lastSavedUri: StateFlow<Uri?> = _lastSavedUri.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private var activeConfig: ResolvedSensorConfig? = null

    // Orientation tracking for proper JPEG Exif rotation
    private var deviceRotationDegrees = 0
    private val orientationEventListener = object : OrientationEventListener(context) {
        override fun onOrientationChanged(orientation: Int) {
            if (orientation == ORIENTATION_UNKNOWN) return
            deviceRotationDegrees = when (orientation) {
                in 45..134 -> 270
                in 135..224 -> 180
                in 225..314 -> 90
                else -> 0
            }
        }
    }

    init {
        orientationEventListener.enable()
        loadLatestGalleryThumbnail()
    }

    private fun startBackgroundThread() {
        if (backgroundThread == null) {
            backgroundThread = HandlerThread("MinimalCameraThread").apply {
                start()
                backgroundHandler = Handler(looper)
            }
        }
    }

    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join(400)
            backgroundThread = null
            backgroundHandler = null
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Resolves the hardware camera IDs on Samsung S22 Ultra:
     * - .6x: Sensor 2 (standalone back ultra-wide)
     * - 1x: Sensor 5 (main 108MP/12MP wide)
     * - 10x: Sensor 7 (periscope telephoto)
     * (Sensor 6 is the water-damaged 3x sensor and is strictly ignored)
     */
    private fun resolveConfigForLens(lens: TargetLens): ResolvedSensorConfig {
        return when (lens) {
            TargetLens.ULTRA_WIDE -> {
                // Sensor 2 is exposed directly in cameraIdList as a standalone camera
                val char = try {
                    cameraManager.getCameraCharacteristics("2")
                } catch (e: Exception) {
                    cameraManager.getCameraCharacteristics("0")
                }
                ResolvedSensorConfig(
                    lens = TargetLens.ULTRA_WIDE,
                    cameraIdToOpen = "2",
                    physicalCameraId = null,
                    characteristics = char
                )
            }
            TargetLens.WIDE -> {
                // Sensor 5 is the primary wide lens (6.4mm / 23mm eq)
                val char = try {
                    cameraManager.getCameraCharacteristics("5")
                } catch (e: Exception) {
                    cameraManager.getCameraCharacteristics("0")
                }
                ResolvedSensorConfig(
                    lens = TargetLens.WIDE,
                    cameraIdToOpen = "5", // We attempt direct open "5" first, fallback to "0" with physical "5"
                    physicalCameraId = "5",
                    characteristics = char
                )
            }
            TargetLens.TELE_10X -> {
                // Sensor 7 is the 10x periscope lens (27.2mm / 230mm eq)
                val char = try {
                    cameraManager.getCameraCharacteristics("7")
                } catch (e: Exception) {
                    cameraManager.getCameraCharacteristics("0")
                }
                ResolvedSensorConfig(
                    lens = TargetLens.TELE_10X,
                    cameraIdToOpen = "7", // We attempt direct open "7" first, fallback to "0" with physical "7"
                    physicalCameraId = "7",
                    characteristics = char
                )
            }
        }
    }

    /**
     * Start camera with SurfaceTexture.
     */
    fun startCamera(surfaceTexture: SurfaceTexture, lens: TargetLens = _currentLens.value) {
        currentSurfaceTexture = surfaceTexture
        _currentLens.value = lens
        val config = resolveConfigForLens(lens)
        activeConfig = config
        openCameraWithConfig(config, surfaceTexture)
    }

    fun switchLens(targetLens: TargetLens) {
        if (_currentLens.value == targetLens && cameraDevice != null && captureSession != null) return
        val st = currentSurfaceTexture ?: return

        _isLoading.value = true
        _currentLens.value = targetLens

        scope.launch(Dispatchers.Main) {
            stopCurrentSession()
            // Short delay to ensure HAL clears the active camera handle
            withContext(Dispatchers.IO) {
                try { Thread.sleep(90) } catch (e: Exception) {}
            }
            val config = resolveConfigForLens(targetLens)
            activeConfig = config
            openCameraWithConfig(config, st)
        }
    }

    fun toggleFlash() {
        val next = !_isFlashOn.value
        _isFlashOn.value = next
        applyFlashState(next)
    }

    private fun applyFlashState(enabled: Boolean) {
        val builder = previewRequestBuilder ?: return
        val session = captureSession ?: return
        val handler = backgroundHandler ?: return

        if (enabled) {
            // 1. In-session preview torch illumination
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH)

            // 2. Also try CameraManager.setTorchMode("0", true) if open camera is not Camera 0
            if (activeConfig?.cameraIdToOpen != "0") {
                try {
                    cameraManager.setTorchMode("0", true)
                } catch (e: Exception) {
                    // Ignore if HAL locks it
                }
            }
        } else {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)

            try {
                cameraManager.setTorchMode("0", false)
            } catch (e: Exception) {}
        }

        try {
            session.setRepeatingRequest(builder.build(), null, handler)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    @SuppressLint("MissingPermission")
    private fun openCameraWithConfig(config: ResolvedSensorConfig, surfaceTexture: SurfaceTexture) {
        startBackgroundThread()
        val handler = backgroundHandler ?: return
        _errorMessage.value = null
        _isLoading.value = true

        try {
            val characteristics = config.characteristics
            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)

            // Preview size (4:3 preferred)
            val previewChoices = map?.getOutputSizes(SurfaceTexture::class.java) ?: emptyArray()
            val previewSize = chooseBestSize(previewChoices, 4f / 3f, Size(1920, 1440))
            surfaceTexture.setDefaultBufferSize(previewSize.width, previewSize.height)
            previewSurface = Surface(surfaceTexture)

            // Still capture size (Max resolution available)
            val captureChoices = map?.getOutputSizes(ImageFormat.JPEG) ?: emptyArray()
            val captureSize = chooseBestSize(captureChoices, 4f / 3f, Size(4000, 3000))

            imageReader = ImageReader.newInstance(captureSize.width, captureSize.height, ImageFormat.JPEG, 2).apply {
                setOnImageAvailableListener({ reader ->
                    val image = reader?.acquireLatestImage()
                    if (image != null) {
                        saveCapturedImage(image, characteristics)
                    }
                }, handler)
            }

            // Strategy: Attempt to open direct ID (e.g. "2", "5", "7")
            // If direct open of "5" or "7" throws, fallback to opening "0" targeting physical ID "5" or "7"
            attemptOpen(config, previewSurface!!, imageReader!!.surface, handler)

        } catch (e: Exception) {
            _isLoading.value = false
            _errorMessage.value = "Failed to start ${config.lens.label}: ${e.message}"
            e.printStackTrace()
        }
    }

    @SuppressLint("MissingPermission")
    private fun attemptOpen(
        config: ResolvedSensorConfig,
        previewSurf: Surface,
        imageSurf: Surface,
        handler: Handler
    ) {
        val targetId = config.cameraIdToOpen

        try {
            cameraManager.openCamera(targetId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    createSession(camera, previewSurf, imageSurf, null, handler)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    cameraDevice = null
                    _isLoading.value = false
                    _errorMessage.value = "Camera disconnected."
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    cameraDevice = null

                    // If direct open of physical camera 5 or 7 failed, fallback to Camera 0 with setPhysicalCameraId
                    if (config.physicalCameraId != null && targetId != "0") {
                        fallbackToLogicalMultiCamera(config, previewSurf, imageSurf, handler)
                    } else {
                        _isLoading.value = false
                        _errorMessage.value = "Camera error: $error on ${config.lens.label}"
                    }
                }
            }, handler)
        } catch (e: IllegalArgumentException) {
            // Direct physical camera ID open refused by Android OS -> fallback to Camera 0
            if (config.physicalCameraId != null && targetId != "0") {
                fallbackToLogicalMultiCamera(config, previewSurf, imageSurf, handler)
            } else {
                _isLoading.value = false
                _errorMessage.value = "Cannot open camera: ${e.message}"
            }
        } catch (e: Exception) {
            _isLoading.value = false
            _errorMessage.value = "Camera error: ${e.message}"
        }
    }

    @SuppressLint("MissingPermission")
    private fun fallbackToLogicalMultiCamera(
        config: ResolvedSensorConfig,
        previewSurf: Surface,
        imageSurf: Surface,
        handler: Handler
    ) {
        try {
            cameraManager.openCamera("0", object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    createSession(camera, previewSurf, imageSurf, config.physicalCameraId, handler)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    cameraDevice = null
                    _isLoading.value = false
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    cameraDevice = null
                    _isLoading.value = false
                    _errorMessage.value = "Camera 0 error $error while loading ${config.lens.label}"
                }
            }, handler)
        } catch (e: Exception) {
            _isLoading.value = false
            _errorMessage.value = "Logical camera fallback failed: ${e.message}"
        }
    }

    private fun createSession(
        camera: CameraDevice,
        previewSurface: Surface,
        imageSurface: Surface,
        physicalCameraId: String?,
        handler: Handler
    ) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val previewOutput = OutputConfiguration(previewSurface).apply {
                    if (physicalCameraId != null) setPhysicalCameraId(physicalCameraId)
                }
                val imageOutput = OutputConfiguration(imageSurface).apply {
                    if (physicalCameraId != null) setPhysicalCameraId(physicalCameraId)
                }

                val sessionConfig = SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    listOf(previewOutput, imageOutput),
                    Executors.newSingleThreadExecutor(),
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: CameraCaptureSession) {
                            captureSession = session
                            startRepeatingPreview(camera, previewSurface, session, handler)
                        }

                        override fun onConfigureFailed(session: CameraCaptureSession) {
                            _isLoading.value = false
                            _errorMessage.value = "Stream configuration failed."
                        }
                    }
                )
                camera.createCaptureSession(sessionConfig)
            } else {
                @Suppress("DEPRECATION")
                camera.createCaptureSession(
                    listOf(previewSurface, imageSurface),
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: CameraCaptureSession) {
                            captureSession = session
                            startRepeatingPreview(camera, previewSurface, session, handler)
                        }

                        override fun onConfigureFailed(session: CameraCaptureSession) {
                            _isLoading.value = false
                            _errorMessage.value = "Session configure failed."
                        }
                    },
                    handler
                )
            }
        } catch (e: Exception) {
            _isLoading.value = false
            _errorMessage.value = "Session setup error: ${e.message}"
        }
    }

    private fun startRepeatingPreview(
        camera: CameraDevice,
        previewSurface: Surface,
        session: CameraCaptureSession,
        handler: Handler
    ) {
        try {
            val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(previewSurface)
                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            }
            previewRequestBuilder = builder
            applyFlashState(_isFlashOn.value)
            session.setRepeatingRequest(builder.build(), null, handler)
            _isLoading.value = false
        } catch (e: Exception) {
            _isLoading.value = false
            _errorMessage.value = "Preview request error: ${e.message}"
        }
    }

    fun takePicture() {
        val camera = cameraDevice ?: return
        val session = captureSession ?: return
        val reader = imageReader ?: return
        val handler = backgroundHandler ?: return
        if (_isCapturing.value) return

        _isCapturing.value = true
        triggerHaptic()
        try {
            mediaActionSound.play(MediaActionSound.SHUTTER_CLICK)
        } catch (e: Exception) {}

        try {
            val captureBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(reader.surface)
                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)

                // Flash for still capture
                if (_isFlashOn.value) {
                    set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH)
                    set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_SINGLE)
                } else {
                    set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                    set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
                }

                // Orientation
                val characteristics = activeConfig?.characteristics ?: cameraManager.getCameraCharacteristics(camera.id)
                val sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
                val jpegOrientation = (sensorOrientation + deviceRotationDegrees + 360) % 360
                set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation)
            }

            session.capture(captureBuilder.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult
                ) {
                    _isCapturing.value = false
                }
            }, handler)

        } catch (e: Exception) {
            _isCapturing.value = false
            _errorMessage.value = "Capture failed: ${e.message}"
        }
    }

    private fun saveCapturedImage(image: Image, characteristics: CameraCharacteristics) {
        scope.launch(Dispatchers.IO) {
            try {
                val buffer = image.planes[0].buffer
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                image.close()

                val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val filename = "IMG_${timeStamp}.jpg"

                val contentValues = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/Camera")
                        put(MediaStore.Images.Media.IS_PENDING, 1)
                    }
                }

                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)

                if (uri != null) {
                    resolver.openOutputStream(uri)?.use { stream: OutputStream ->
                        stream.write(bytes)
                    }

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        contentValues.clear()
                        contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                        resolver.update(uri, contentValues, null, null)
                    }

                    _lastSavedUri.value = uri

                    // Decode small thumbnail for the UI preview circle
                    val rawBitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    if (rawBitmap != null) {
                        val thumb = Bitmap.createScaledBitmap(rawBitmap, 128, 128, true)
                        _lastSavedThumbnail.value = thumb
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun chooseBestSize(choices: Array<Size>, desiredRatio: Float, fallback: Size): Size {
        val matches = choices.filter { abs((it.width.toFloat() / it.height.toFloat()) - desiredRatio) < 0.12f }
        return matches.maxByOrNull { it.width * it.height }
            ?: choices.maxByOrNull { it.width * it.height }
            ?: fallback
    }

    private fun triggerHaptic() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vibratorManager.defaultVibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
            } else {
                @Suppress("DEPRECATION")
                val v = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    v.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    v.vibrate(50)
                }
            }
        } catch (e: Exception) {}
    }

    private fun loadLatestGalleryThumbnail() {
        scope.launch(Dispatchers.IO) {
            try {
                val projection = arrayOf(MediaStore.Images.Media._ID)
                val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"
                val cursor = context.contentResolver.query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    projection,
                    null,
                    null,
                    sortOrder
                )
                cursor?.use {
                    if (it.moveToFirst()) {
                        val id = it.getLong(it.getColumnIndexOrThrow(MediaStore.Images.Media._ID))
                        val uri = Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id.toString())
                        _lastSavedUri.value = uri
                        val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            context.contentResolver.loadThumbnail(uri, android.util.Size(128, 128), null)
                        } else null
                        if (bitmap != null) {
                            _lastSavedThumbnail.value = bitmap
                        }
                    }
                }
            } catch (e: Exception) {}
        }
    }

    fun stopCurrentSession() {
        try {
            captureSession?.stopRepeating()
            captureSession?.close()
            captureSession = null
        } catch (e: Exception) {}

        try {
            cameraDevice?.close()
            cameraDevice = null
        } catch (e: Exception) {}

        try {
            imageReader?.close()
            imageReader = null
        } catch (e: Exception) {}

        try {
            cameraManager.setTorchMode("0", false)
        } catch (e: Exception) {}

        previewSurface?.release()
        previewSurface = null
        stopBackgroundThread()
    }

    fun release() {
        stopCurrentSession()
        orientationEventListener.disable()
        mediaActionSound.release()
    }
}
