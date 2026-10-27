package com.s22camerafix.lensisolator.engine

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.MeteringRectangle
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
import com.s22camerafix.lensisolator.data.model.CameraDeviceDetails
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class SamsungStyleCameraEngine(
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

    private var activeCameraDetails: CameraDeviceDetails? = null
    private var previewSurface: Surface? = null
    private var currentSurfaceTexture: SurfaceTexture? = null

    // State
    private val _isSessionReady = MutableStateFlow(false)
    val isSessionReady: StateFlow<Boolean> = _isSessionReady.asStateFlow()

    private val _isCapturing = MutableStateFlow(false)
    val isCapturing: StateFlow<Boolean> = _isCapturing.asStateFlow()

    private val _lastCapturedUri = MutableStateFlow<Uri?>(null)
    val lastCapturedUri: StateFlow<Uri?> = _lastCapturedUri.asStateFlow()

    private val _lastCapturedThumbnail = MutableStateFlow<Bitmap?>(null)
    val lastCapturedThumbnail: StateFlow<Bitmap?> = _lastCapturedThumbnail.asStateFlow()

    private val _currentZoomRatio = MutableStateFlow(1.0f)
    val currentZoomRatio: StateFlow<Float> = _currentZoomRatio.asStateFlow()

    private val _maxZoomRatio = MutableStateFlow(10.0f)
    val maxZoomRatio: StateFlow<Float> = _maxZoomRatio.asStateFlow()

    private val _flashMode = MutableStateFlow("OFF")
    val flashMode: StateFlow<String> = _flashMode.asStateFlow()

    private val _hasHardwareFlash = MutableStateFlow(false)
    val hasHardwareFlash: StateFlow<Boolean> = _hasHardwareFlash.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

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
            backgroundThread = HandlerThread("SamsungCameraEngineThread").apply {
                start()
                backgroundHandler = Handler(looper)
            }
        }
    }

    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join(500)
            backgroundThread = null
            backgroundHandler = null
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun setFlashMode(mode: String) {
        _flashMode.value = mode
        applyFlashMode()
    }

    private fun applyFlashMode() {
        val builder = previewRequestBuilder ?: return
        val session = captureSession ?: return
        val handler = backgroundHandler ?: return

        when (_flashMode.value) {
            "ON" -> {
                // Torch mode keeps physical LED turned ON for preview & illumination
                if (_hasHardwareFlash.value) {
                    builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                    builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH)
                } else {
                    builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                    builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
                }
            }
            "AUTO" -> {
                if (_hasHardwareFlash.value) {
                    builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH)
                    builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
                } else {
                    builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                    builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
                }
            }
            else -> {
                builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
            }
        }
        try {
            session.setRepeatingRequest(builder.build(), null, handler)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    @SuppressLint("MissingPermission")
    fun openCamera(
        cameraDetails: CameraDeviceDetails,
        surfaceTexture: SurfaceTexture,
        aspectRatio: String = "4:3"
    ) {
        stopCurrentSession()
        startBackgroundThread()

        activeCameraDetails = cameraDetails
        currentSurfaceTexture = surfaceTexture
        _errorMessage.value = null
        _isSessionReady.value = false
        _currentZoomRatio.value = 1.0f

        val handler = backgroundHandler ?: return

        try {
            // If physical sub-camera, characteristics might come from physical ID
            val characteristics = cameraManager.getCameraCharacteristics(cameraDetails.id)
            val maxZoom = characteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 10.0f
            _maxZoomRatio.value = maxZoom.coerceIn(1.0f, 10.0f)

            val flashAvailable = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) ?: false
            _hasHardwareFlash.value = flashAvailable

            // Calculate optimal preview & still capture sizes
            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val previewSize = chooseOptimalPreviewSize(map?.getOutputSizes(SurfaceTexture::class.java) ?: emptyArray(), aspectRatio)
            val captureSize = chooseOptimalCaptureSize(map?.getOutputSizes(ImageFormat.JPEG) ?: emptyArray(), aspectRatio)

            surfaceTexture.setDefaultBufferSize(previewSize.width, previewSize.height)
            previewSurface = Surface(surfaceTexture)

            // Setup ImageReader for high-resolution stills
            imageReader = ImageReader.newInstance(captureSize.width, captureSize.height, ImageFormat.JPEG, 2).apply {
                setOnImageAvailableListener({ reader ->
                    val image = reader?.acquireLatestImage()
                    if (image != null) {
                        processAndSaveImage(image, characteristics)
                    }
                }, handler)
            }

            // Determine open strategy:
            // 1. Direct open of cameraDetails.id
            // 2. If it is a physical sub-camera not openable directly, open parent logical camera targeting physical ID
            attemptCameraOpen(cameraDetails, previewSurface!!, imageReader!!.surface, handler)

        } catch (e: Exception) {
            _errorMessage.value = "Failed to initialize camera ${cameraDetails.id}: ${e.message}"
            e.printStackTrace()
        }
    }

    @SuppressLint("MissingPermission")
    private fun attemptCameraOpen(
        cameraDetails: CameraDeviceDetails,
        previewSurf: Surface,
        imageSurf: Surface,
        handler: Handler
    ) {
        val targetPhysicalId = if (cameraDetails.isPhysicalSubCamera) cameraDetails.id else null
        val idToOpen = if (cameraDetails.isPhysicalSubCamera) {
            // First try opening physical ID directly
            cameraDetails.id
        } else {
            cameraDetails.id
        }

        try {
            cameraManager.openCamera(idToOpen, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    createCaptureSession(camera, previewSurf, imageSurf, null, handler)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    cameraDevice = null
                    _isSessionReady.value = false
                    _errorMessage.value = "Camera ${camera.id} disconnected."
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    cameraDevice = null
                    _isSessionReady.value = false

                    // If direct physical open failed on HAL, fallback to parent logical camera with targeted physical stream
                    if (cameraDetails.isPhysicalSubCamera && cameraDetails.parentLogicalCameraId != null) {
                        fallbackToLogicalPhysicalTarget(
                            cameraDetails.parentLogicalCameraId,
                            cameraDetails.id,
                            previewSurf,
                            imageSurf,
                            handler
                        )
                    } else {
                        _errorMessage.value = "Camera error code: $error on ID ${cameraDetails.id}."
                    }
                }
            }, handler)
        } catch (e: IllegalArgumentException) {
            // Physical ID not openable directly, open parent logical camera with targeted physical stream
            if (cameraDetails.isPhysicalSubCamera && cameraDetails.parentLogicalCameraId != null) {
                fallbackToLogicalPhysicalTarget(
                    cameraDetails.parentLogicalCameraId,
                    cameraDetails.id,
                    previewSurf,
                    imageSurf,
                    handler
                )
            } else {
                _errorMessage.value = "Open failed: ${e.message}"
            }
        } catch (e: Exception) {
            _errorMessage.value = "Failed to open camera: ${e.message}"
        }
    }

    @SuppressLint("MissingPermission")
    private fun fallbackToLogicalPhysicalTarget(
        logicalId: String,
        physicalId: String,
        previewSurf: Surface,
        imageSurf: Surface,
        handler: Handler
    ) {
        try {
            cameraManager.openCamera(logicalId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    createCaptureSession(camera, previewSurf, imageSurf, physicalId, handler)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    cameraDevice = null
                    _isSessionReady.value = false
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    cameraDevice = null
                    _isSessionReady.value = false
                    _errorMessage.value = "Logical camera probe error $error."
                }
            }, handler)
        } catch (e: Exception) {
            _errorMessage.value = "Fallback to logical camera failed: ${e.message}"
        }
    }

    private fun createCaptureSession(
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
                            _errorMessage.value = "Capture session configuration rejected by HAL."
                            _isSessionReady.value = false
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
                            _errorMessage.value = "Capture session rejected."
                            _isSessionReady.value = false
                        }
                    },
                    handler
                )
            }
        } catch (e: Exception) {
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
            applyFlashMode()
            session.setRepeatingRequest(builder.build(), null, handler)
            _isSessionReady.value = true
        } catch (e: Exception) {
            _errorMessage.value = "Repeating request failed: ${e.message}"
        }
    }

    fun takePicture() {
        val camera = cameraDevice ?: return
        val session = captureSession ?: return
        val reader = imageReader ?: return
        val handler = backgroundHandler ?: return
        if (_isCapturing.value) return

        _isCapturing.value = true
        triggerHapticFeedback()
        try {
            mediaActionSound.play(MediaActionSound.SHUTTER_CLICK)
        } catch (e: Exception) {}

        try {
            val captureBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(reader.surface)
                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)

                // Flash for capture
                when (_flashMode.value) {
                    "ON" -> {
                        if (_hasHardwareFlash.value) {
                            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH)
                            set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_SINGLE)
                        }
                    }
                    "AUTO" -> {
                        if (_hasHardwareFlash.value) {
                            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH)
                        }
                    }
                    else -> {
                        set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                        set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
                    }
                }

                // Apply zoom crop
                previewRequestBuilder?.get(CaptureRequest.SCALER_CROP_REGION)?.let {
                    set(CaptureRequest.SCALER_CROP_REGION, it)
                }

                // Orientation
                val characteristics = cameraManager.getCameraCharacteristics(camera.id)
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

    private fun processAndSaveImage(image: Image, characteristics: CameraCharacteristics) {
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

                    _lastCapturedUri.value = uri

                    // Generate thumbnail
                    val rawBitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    if (rawBitmap != null) {
                        val thumb = Bitmap.createScaledBitmap(rawBitmap, 128, 128, true)
                        _lastCapturedThumbnail.value = thumb
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun setZoomRatio(ratio: Float) {
        val clampedRatio = ratio.coerceIn(1.0f, _maxZoomRatio.value)
        _currentZoomRatio.value = clampedRatio

        val camera = cameraDevice ?: return
        val builder = previewRequestBuilder ?: return
        val session = captureSession ?: return
        val handler = backgroundHandler ?: return

        try {
            val characteristics = cameraManager.getCameraCharacteristics(camera.id)
            val activeArray = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return

            val cropWidth = (activeArray.width() / clampedRatio).toInt()
            val cropHeight = (activeArray.height() / clampedRatio).toInt()
            val cropLeft = (activeArray.width() - cropWidth) / 2
            val cropTop = (activeArray.height() - cropHeight) / 2

            val cropRect = Rect(cropLeft, cropTop, cropLeft + cropWidth, cropTop + cropHeight)
            builder.set(CaptureRequest.SCALER_CROP_REGION, cropRect)
            session.setRepeatingRequest(builder.build(), null, handler)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun tapToFocus(normalizedX: Float, normalizedY: Float) {
        val camera = cameraDevice ?: return
        val builder = previewRequestBuilder ?: return
        val session = captureSession ?: return
        val handler = backgroundHandler ?: return

        try {
            val characteristics = cameraManager.getCameraCharacteristics(camera.id)
            val activeArray = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return

            val targetX = (normalizedX * activeArray.width()).toInt().coerceIn(0, activeArray.width())
            val targetY = (normalizedY * activeArray.height()).toInt().coerceIn(0, activeArray.height())

            val halfSize = 150
            val focusRect = Rect(
                max(0, targetX - halfSize),
                max(0, targetY - halfSize),
                min(activeArray.width(), targetX + halfSize),
                min(activeArray.height(), targetY + halfSize)
            )

            val meteringRect = MeteringRectangle(focusRect, 1000)
            builder.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(meteringRect))
            builder.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(meteringRect))
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START)

            session.capture(builder.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                    builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE)
                    session.setRepeatingRequest(builder.build(), null, handler)
                }
            }, handler)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun chooseOptimalPreviewSize(choices: Array<Size>, targetRatio: String): Size {
        val desiredRatio = when (targetRatio) {
            "16:9" -> 16f / 9f
            "1:1" -> 1.0f
            else -> 4f / 3f
        }
        return choices.filter { abs((it.width.toFloat() / it.height.toFloat()) - desiredRatio) < 0.1f }
            .maxByOrNull { it.width * it.height }
            ?: choices.firstOrNull() ?: Size(1920, 1080)
    }

    private fun chooseOptimalCaptureSize(choices: Array<Size>, targetRatio: String): Size {
        val desiredRatio = when (targetRatio) {
            "16:9" -> 16f / 9f
            "1:1" -> 1.0f
            else -> 4f / 3f
        }
        return choices.filter { abs((it.width.toFloat() / it.height.toFloat()) - desiredRatio) < 0.1f }
            .maxByOrNull { it.width * it.height }
            ?: choices.firstOrNull() ?: Size(4000, 3000)
    }

    private fun triggerHapticFeedback() {
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
                        _lastCapturedUri.value = uri
                        val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            context.contentResolver.loadThumbnail(uri, android.util.Size(128, 128), null)
                        } else null
                        if (bitmap != null) {
                            _lastCapturedThumbnail.value = bitmap
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

        previewSurface?.release()
        previewSurface = null
        _isSessionReady.value = false
        stopBackgroundThread()

        // Short sleep to ensure the hardware HAL daemon frees the device handle
        try {
            Thread.sleep(60)
        } catch (e: Exception) {}
    }

    fun release() {
        stopCurrentSession()
        orientationEventListener.disable()
        mediaActionSound.release()
    }
}
