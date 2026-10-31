package com.s22camerafix.lensisolator.engine

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.ImageReader
import android.media.MediaActionSound
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import android.view.OrientationEventListener
import android.view.Surface
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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

enum class CameraMode {
    PHOTO,
    VIDEO
}

class MinimalCameraEngine(
    private val context: Context,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "MinimalCameraEngine"
        // Blacklist faulty 3x telephoto sensor (shorts I2C/power bus on water damage)
        private val FAULTY_3X_IDS = setOf("6", "52")
    }

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val mediaActionSound = MediaActionSound().apply {
        load(MediaActionSound.SHUTTER_CLICK)
        load(MediaActionSound.START_VIDEO_RECORDING)
        load(MediaActionSound.STOP_VIDEO_RECORDING)
    }

    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var previewRequestBuilder: CaptureRequest.Builder? = null
    private var imageReader: ImageReader? = null

    // Video Recording
    private var mediaRecorder: MediaRecorder? = null
    private var recordingTimerJob: Job? = null
    private var currentVideoUri: Uri? = null
    private var currentVideoPfd: ParcelFileDescriptor? = null

    private var currentSurfaceTexture: SurfaceTexture? = null
    private var previewSurface: Surface? = null
    private var activeCharacteristics: CameraCharacteristics? = null
    private var activeCameraId: String? = null

    // StateFlows
    private val _currentLens = MutableStateFlow(TargetLens.WIDE)
    val currentLens: StateFlow<TargetLens> = _currentLens.asStateFlow()

    private val _cameraMode = MutableStateFlow(CameraMode.PHOTO)
    val cameraMode: StateFlow<CameraMode> = _cameraMode.asStateFlow()

    private val _isFlashOn = MutableStateFlow(false)
    val isFlashOn: StateFlow<Boolean> = _isFlashOn.asStateFlow()

    private val _isCapturing = MutableStateFlow(false)
    val isCapturing: StateFlow<Boolean> = _isCapturing.asStateFlow()

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _recordingSeconds = MutableStateFlow(0)
    val recordingSeconds: StateFlow<Int> = _recordingSeconds.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _lastSavedThumbnail = MutableStateFlow<Bitmap?>(null)
    val lastSavedThumbnail: StateFlow<Bitmap?> = _lastSavedThumbnail.asStateFlow()

    private val _lastSavedUri = MutableStateFlow<Uri?>(null)
    val lastSavedUri: StateFlow<Uri?> = _lastSavedUri.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    // Orientation tracking for proper JPEG and Video Exif rotation
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
        loadLatestGalleryMedia()
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
            backgroundThread?.join(350)
            backgroundThread = null
            backgroundHandler = null
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Resolves the list of candidate Camera IDs for each target lens on S22 Ultra:
     * - Ultra-Wide (.6x): ID "2", "58" (focal length ~2.2mm)
     * - Main Wide (1x): ID "56", "5", "0" (focal length ~6.4mm)
     * - 10x Periscope: ID "54", "7" (focal length ~27.2mm)
     * (Sensor 6 / 52 with focal length ~7.9mm is strictly excluded!)
     */
    private fun getCandidatesForLens(lens: TargetLens): List<String> {
        val allIds = try {
            cameraManager.cameraIdList.toList()
        } catch (e: Exception) {
            emptyList()
        }

        // Filter out known broken 3x telephoto IDs
        val safeIds = allIds.filterNot { FAULTY_3X_IDS.contains(it) }

        return when (lens) {
            TargetLens.ULTRA_WIDE -> {
                // Priority: "2", "58", or any back camera with focal length < 3.0mm
                val matched = safeIds.filter { id ->
                    try {
                        val chars = cameraManager.getCameraCharacteristics(id)
                        val facing = chars.get(CameraCharacteristics.LENS_FACING)
                        val focals = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                        facing == CameraCharacteristics.LENS_FACING_BACK &&
                                focals != null && focals.any { it < 3.0f }
                    } catch (e: Exception) { false }
                }
                val ordered = mutableListOf<String>()
                if (safeIds.contains("2")) ordered.add("2")
                ordered.addAll(matched.filter { it != "2" })
                if (safeIds.contains("58") && !ordered.contains("58")) ordered.add("58")
                if (ordered.isEmpty()) listOf("2") else ordered.distinct()
            }
            TargetLens.WIDE -> {
                // Priority: "56" (S22 Ultra main wide), "5", or focal length 5.0-7.0mm
                val matched = safeIds.filter { id ->
                    try {
                        val chars = cameraManager.getCameraCharacteristics(id)
                        val facing = chars.get(CameraCharacteristics.LENS_FACING)
                        val focals = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                        facing == CameraCharacteristics.LENS_FACING_BACK &&
                                focals != null && focals.any { it in 5.0f..7.0f }
                    } catch (e: Exception) { false }
                }
                val ordered = mutableListOf<String>()
                if (safeIds.contains("56")) ordered.add("56")
                if (safeIds.contains("5")) ordered.add("5")
                ordered.addAll(matched.filter { it != "56" && it != "5" })
                if (ordered.isEmpty()) listOf("56", "5", "0") else ordered.distinct()
            }
            TargetLens.TELE_10X -> {
                // Priority: "54" (S22 Ultra 10x periscope), "7", or focal length > 20.0mm
                val matched = safeIds.filter { id ->
                    try {
                        val chars = cameraManager.getCameraCharacteristics(id)
                        val facing = chars.get(CameraCharacteristics.LENS_FACING)
                        val focals = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                        facing == CameraCharacteristics.LENS_FACING_BACK &&
                                focals != null && focals.any { it > 20.0f }
                    } catch (e: Exception) { false }
                }
                val ordered = mutableListOf<String>()
                if (safeIds.contains("54")) ordered.add("54")
                if (safeIds.contains("7")) ordered.add("7")
                ordered.addAll(matched.filter { it != "54" && it != "7" })
                if (ordered.isEmpty()) listOf("54", "7") else ordered.distinct()
            }
        }
    }

    fun startCamera(surfaceTexture: SurfaceTexture, lens: TargetLens = _currentLens.value) {
        currentSurfaceTexture = surfaceTexture
        _currentLens.value = lens
        val candidates = getCandidatesForLens(lens)
        openCandidateWithFallback(candidates, 0, lens, surfaceTexture)
    }

    fun switchLens(targetLens: TargetLens) {
        if (_isRecording.value) return // Prevent switching lens while recording video
        if (_currentLens.value == targetLens && cameraDevice != null && captureSession != null) return
        val st = currentSurfaceTexture ?: return

        _isLoading.value = true
        _currentLens.value = targetLens

        scope.launch(Dispatchers.Main) {
            stopCurrentSession()
            withContext(Dispatchers.IO) {
                try { Thread.sleep(80) } catch (e: Exception) {}
            }
            val candidates = getCandidatesForLens(targetLens)
            openCandidateWithFallback(candidates, 0, targetLens, st)
        }
    }

    fun setMode(mode: CameraMode) {
        if (_isRecording.value) return
        if (_cameraMode.value == mode) return
        _cameraMode.value = mode
    }

    fun toggleFlash() {
        val next = !_isFlashOn.value
        _isFlashOn.value = next
        // In video mode, flash immediately controls the video torch
        if (_cameraMode.value == CameraMode.VIDEO && _isRecording.value) {
            try {
                cameraManager.setTorchMode("0", next)
            } catch (e: Exception) {}
        }
    }

    @SuppressLint("MissingPermission")
    private fun openCandidateWithFallback(
        candidates: List<String>,
        index: Int,
        lens: TargetLens,
        surfaceTexture: SurfaceTexture
    ) {
        if (index >= candidates.size) {
            _isLoading.value = false
            _errorMessage.value = "Unable to open ${lens.label} (tried: ${candidates.joinToString()})"
            return
        }

        val targetId = candidates[index]
        startBackgroundThread()
        val handler = backgroundHandler ?: return
        _errorMessage.value = null
        _isLoading.value = true

        try {
            val characteristics = cameraManager.getCameraCharacteristics(targetId)
            activeCharacteristics = characteristics
            activeCameraId = targetId

            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)

            // Preview size
            val previewChoices = map?.getOutputSizes(SurfaceTexture::class.java) ?: emptyArray()
            val previewSize = chooseBestSize(previewChoices, 4f / 3f, Size(1920, 1440))
            surfaceTexture.setDefaultBufferSize(previewSize.width, previewSize.height)
            previewSurface = Surface(surfaceTexture)

            // Still capture size
            val captureChoices = map?.getOutputSizes(ImageFormat.JPEG) ?: emptyArray()
            val captureSize = chooseBestSize(captureChoices, 4f / 3f, Size(4000, 3000))

            imageReader?.close()
            imageReader = ImageReader.newInstance(captureSize.width, captureSize.height, ImageFormat.JPEG, 2).apply {
                setOnImageAvailableListener({ reader ->
                    val image = reader?.acquireLatestImage()
                    if (image != null) {
                        saveCapturedImage(image)
                    }
                }, handler)
            }

            Log.d(TAG, "Opening Camera ID: $targetId for ${lens.label} (candidate $index of ${candidates.size})")

            cameraManager.openCamera(targetId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    createPhotoSession(camera, previewSurface!!, imageReader!!.surface, handler)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    cameraDevice = null
                    _isLoading.value = false
                    _errorMessage.value = "Camera disconnected."
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    Log.w(TAG, "Camera ID $targetId failed with error $error. Trying next candidate...")
                    camera.close()
                    cameraDevice = null
                    // Try next candidate
                    openCandidateWithFallback(candidates, index + 1, lens, surfaceTexture)
                }
            }, handler)

        } catch (e: Exception) {
            Log.w(TAG, "Exception opening Camera ID $targetId: ${e.message}. Trying next candidate...")
            openCandidateWithFallback(candidates, index + 1, lens, surfaceTexture)
        }
    }

    private fun createPhotoSession(
        camera: CameraDevice,
        previewSurf: Surface,
        imageSurf: Surface,
        handler: Handler
    ) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val previewOutput = OutputConfiguration(previewSurf)
                val imageOutput = OutputConfiguration(imageSurf)

                val sessionConfig = SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    listOf(previewOutput, imageOutput),
                    Executors.newSingleThreadExecutor(),
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: CameraCaptureSession) {
                            captureSession = session
                            startRepeatingPreview(camera, previewSurf, session, handler)
                        }

                        override fun onConfigureFailed(session: CameraCaptureSession) {
                            _isLoading.value = false
                            _errorMessage.value = "Viewfinder configuration failed."
                        }
                    }
                )
                camera.createCaptureSession(sessionConfig)
            } else {
                @Suppress("DEPRECATION")
                camera.createCaptureSession(
                    listOf(previewSurf, imageSurf),
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: CameraCaptureSession) {
                            captureSession = session
                            startRepeatingPreview(camera, previewSurf, session, handler)
                        }

                        override fun onConfigureFailed(session: CameraCaptureSession) {
                            _isLoading.value = false
                            _errorMessage.value = "Session setup failed."
                        }
                    },
                    handler
                )
            }
        } catch (e: Exception) {
            _isLoading.value = false
            _errorMessage.value = "Session creation error: ${e.message}"
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
                // PREVIEW FLASH IS ALWAYS OFF (No permanent torch during preview!)
                set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
            }
            previewRequestBuilder = builder
            session.setRepeatingRequest(builder.build(), null, handler)
            _isLoading.value = false
        } catch (e: Exception) {
            _isLoading.value = false
            _errorMessage.value = "Preview request error: ${e.message}"
        }
    }

    /**
     * Captures a still image.
     * Flash behavior:
     * - If Flash is OFF: no flash.
     * - If Flash is ON: strobes flash ONLY for the picture capture (never permanently on).
     */
    fun takePicture() {
        val camera = cameraDevice ?: return
        val session = captureSession ?: return
        val reader = imageReader ?: return
        val handler = backgroundHandler ?: return
        if (_isCapturing.value || _isRecording.value) return

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

                // Orientation
                val characteristics = activeCharacteristics ?: cameraManager.getCameraCharacteristics(camera.id)
                val sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
                val jpegOrientation = (sensorOrientation + deviceRotationDegrees + 360) % 360
                set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation)
            }

            val hasNativeFlash = activeCharacteristics?.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true

            if (_isFlashOn.value) {
                if (hasNativeFlash) {
                    // Sensor supports native strobe flash
                    captureBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH)
                    captureBuilder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_SINGLE)
                    session.capture(captureBuilder.build(), object : CameraCaptureSession.CaptureCallback() {
                        override fun onCaptureCompleted(
                            session: CameraCaptureSession,
                            request: CaptureRequest,
                            result: TotalCaptureResult
                        ) {
                            _isCapturing.value = false
                        }
                        override fun onCaptureFailed(
                            session: CameraCaptureSession,
                            request: CaptureRequest,
                            failure: CaptureFailure
                        ) {
                            _isCapturing.value = false
                        }
                    }, handler)
                } else {
                    // Auxiliary lens fallback: brief strobe right when shutter clicks!
                    scope.launch(Dispatchers.IO) {
                        try {
                            cameraManager.setTorchMode("0", true)
                        } catch (e: Exception) {}
                        delay(120) // Brief illumination for exposure metering
                        withContext(Dispatchers.Main) {
                            try {
                                session.capture(captureBuilder.build(), object : CameraCaptureSession.CaptureCallback() {
                                    override fun onCaptureCompleted(
                                        session: CameraCaptureSession,
                                        request: CaptureRequest,
                                        result: TotalCaptureResult
                                    ) {
                                        try { cameraManager.setTorchMode("0", false) } catch (e: Exception) {}
                                        _isCapturing.value = false
                                    }

                                    override fun onCaptureFailed(
                                        session: CameraCaptureSession,
                                        request: CaptureRequest,
                                        failure: CaptureFailure
                                    ) {
                                        try { cameraManager.setTorchMode("0", false) } catch (e: Exception) {}
                                        _isCapturing.value = false
                                    }
                                }, handler)
                            } catch (e: Exception) {
                                try { cameraManager.setTorchMode("0", false) } catch (ex: Exception) {}
                                _isCapturing.value = false
                            }
                        }
                    }
                }
            } else {
                // Flash is OFF
                captureBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                captureBuilder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
                session.capture(captureBuilder.build(), object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        result: TotalCaptureResult
                    ) {
                        _isCapturing.value = false
                    }
                    override fun onCaptureFailed(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        failure: CaptureFailure
                    ) {
                        _isCapturing.value = false
                    }
                }, handler)
            }

        } catch (e: Exception) {
            _isCapturing.value = false
            _errorMessage.value = "Capture failed: ${e.message}"
        }
    }

    private fun saveCapturedImage(image: Image) {
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

    /**
     * Video Recording Implementation using MediaRecorder & Camera2
     */
    fun startRecordingVideo() {
        val camera = cameraDevice ?: return
        val previewSurf = previewSurface ?: return
        val handler = backgroundHandler ?: return
        if (_isRecording.value) return

        triggerHaptic()
        try {
            mediaActionSound.play(MediaActionSound.START_VIDEO_RECORDING)
        } catch (e: Exception) {}

        try {
            val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            mediaRecorder = recorder

            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val filename = "VID_${timeStamp}.mp4"

            val contentValues = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, filename)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Video.Media.RELATIVE_PATH, "DCIM/Camera")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
            }

            val uri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, contentValues)
                ?: throw IllegalStateException("Cannot create video MediaStore record")
            currentVideoUri = uri

            val pfd = context.contentResolver.openFileDescriptor(uri, "rw")
                ?: throw IllegalStateException("Cannot open video file descriptor")
            currentVideoPfd = pfd

            val hasAudioPermission = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED

            if (hasAudioPermission) {
                recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            }
            recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recorder.setOutputFile(pfd.fileDescriptor)

            // Video settings (1080p Full HD)
            recorder.setVideoEncodingBitRate(14_000_000)
            recorder.setVideoFrameRate(30)
            recorder.setVideoSize(1920, 1080)
            recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264)

            if (hasAudioPermission) {
                recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                recorder.setAudioEncodingBitRate(192000)
                recorder.setAudioSamplingRate(48000)
            }

            val characteristics = activeCharacteristics ?: cameraManager.getCameraCharacteristics(camera.id)
            val sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
            val videoOrientation = (sensorOrientation + deviceRotationDegrees + 360) % 360
            recorder.setOrientationHint(videoOrientation)

            recorder.prepare()
            val recorderSurface = recorder.surface

            // Recreate session with preview + recorder surfaces
            captureSession?.stopRepeating()
            captureSession?.close()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val previewOutput = OutputConfiguration(previewSurf)
                val recordOutput = OutputConfiguration(recorderSurface)

                val sessionConfig = SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    listOf(previewOutput, recordOutput),
                    Executors.newSingleThreadExecutor(),
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: CameraCaptureSession) {
                            captureSession = session
                            try {
                                val recordBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                                    addTarget(previewSurf)
                                    addTarget(recorderSurface)
                                    set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                                    set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                                }
                                session.setRepeatingRequest(recordBuilder.build(), null, handler)

                                // Turn on video torch if flash toggle is enabled
                                if (_isFlashOn.value) {
                                    try { cameraManager.setTorchMode("0", true) } catch (e: Exception) {}
                                }

                                recorder.start()
                                _isRecording.value = true
                                _recordingSeconds.value = 0

                                recordingTimerJob = scope.launch(Dispatchers.Main) {
                                    while (_isRecording.value) {
                                        delay(1000)
                                        _recordingSeconds.value += 1
                                    }
                                }
                            } catch (e: Exception) {
                                _errorMessage.value = "Record start error: ${e.message}"
                                stopRecordingVideo()
                            }
                        }

                        override fun onConfigureFailed(session: CameraCaptureSession) {
                            _errorMessage.value = "Failed to configure video session."
                            stopRecordingVideo()
                        }
                    }
                )
                camera.createCaptureSession(sessionConfig)
            }
        } catch (e: Exception) {
            _errorMessage.value = "Video setup error: ${e.message}"
            stopRecordingVideo()
        }
    }

    fun stopRecordingVideo() {
        if (!_isRecording.value && mediaRecorder == null) return
        _isRecording.value = false
        recordingTimerJob?.cancel()
        recordingTimerJob = null

        triggerHaptic()
        try {
            mediaActionSound.play(MediaActionSound.STOP_VIDEO_RECORDING)
        } catch (e: Exception) {}

        try {
            cameraManager.setTorchMode("0", false)
        } catch (e: Exception) {}

        try {
            mediaRecorder?.stop()
        } catch (e: Exception) {
            e.printStackTrace()
        }

        try {
            mediaRecorder?.reset()
            mediaRecorder?.release()
            mediaRecorder = null
        } catch (e: Exception) {}

        try {
            currentVideoPfd?.close()
            currentVideoPfd = null
        } catch (e: Exception) {}

        val uri = currentVideoUri
        if (uri != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.IS_PENDING, 0)
                }
                context.contentResolver.update(uri, values, null, null)
            }
            _lastSavedUri.value = uri

            scope.launch(Dispatchers.IO) {
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val thumb = context.contentResolver.loadThumbnail(uri, Size(128, 128), null)
                        _lastSavedThumbnail.value = thumb
                    }
                } catch (e: Exception) {}
            }
        }

        // Restore photo preview session
        val camera = cameraDevice ?: return
        val previewSurf = previewSurface ?: return
        val imgSurf = imageReader?.surface ?: return
        val handler = backgroundHandler ?: return
        createPhotoSession(camera, previewSurf, imgSurf, handler)
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
                    v.vibrate(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    v.vibrate(40)
                }
            }
        } catch (e: Exception) {}
    }

    private fun loadLatestGalleryMedia() {
        scope.launch(Dispatchers.IO) {
            try {
                // Check latest image first
                val projection = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_ADDED)
                val cursor = context.contentResolver.query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    projection,
                    null,
                    null,
                    "${MediaStore.Images.Media.DATE_ADDED} DESC"
                )
                cursor?.use {
                    if (it.moveToFirst()) {
                        val id = it.getLong(it.getColumnIndexOrThrow(MediaStore.Images.Media._ID))
                        val uri = Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id.toString())
                        _lastSavedUri.value = uri
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            val bitmap = context.contentResolver.loadThumbnail(uri, Size(128, 128), null)
                            if (bitmap != null) {
                                _lastSavedThumbnail.value = bitmap
                            }
                        }
                    }
                }
            } catch (e: Exception) {}
        }
    }

    fun stopCurrentSession() {
        try {
            if (_isRecording.value) {
                stopRecordingVideo()
            }
        } catch (e: Exception) {}

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