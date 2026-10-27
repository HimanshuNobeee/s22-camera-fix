package com.s22camerafix.lensisolator.viewmodel

import android.app.Application
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.SurfaceHolder
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.s22camerafix.lensisolator.data.CameraDiscoveryManager
import com.s22camerafix.lensisolator.data.DiagnosticReportGenerator
import com.s22camerafix.lensisolator.data.model.CameraDeviceDetails
import com.s22camerafix.lensisolator.data.model.CameraTestResult
import com.s22camerafix.lensisolator.data.model.CameraTestStatus
import com.s22camerafix.lensisolator.engine.Camera1LegacyTester
import com.s22camerafix.lensisolator.engine.Camera2Tester
import com.s22camerafix.lensisolator.engine.ConcurrentCameraTester
import com.s22camerafix.lensisolator.engine.LogEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class CameraViewModel(application: Application) : AndroidViewModel(application) {

    private val discoveryManager = CameraDiscoveryManager(application)

    private val _cameras = MutableStateFlow<List<CameraDeviceDetails>>(emptyList())
    val cameras: StateFlow<List<CameraDeviceDetails>> = _cameras.asStateFlow()

    private val _selectedCamera = MutableStateFlow<CameraDeviceDetails?>(null)
    val selectedCamera: StateFlow<CameraDeviceDetails?> = _selectedCamera.asStateFlow()

    private val _selectedPhysicalStreamId = MutableStateFlow<String?>(null)
    val selectedPhysicalStreamId: StateFlow<String?> = _selectedPhysicalStreamId.asStateFlow()

    private val _isTesting = MutableStateFlow(false)
    val isTesting: StateFlow<Boolean> = _isTesting.asStateFlow()

    private val _activeApiMode = MutableStateFlow("None")
    val activeApiMode: StateFlow<String> = _activeApiMode.asStateFlow()

    private val _liveLogs = MutableStateFlow<List<LogEntry>>(emptyList())
    val liveLogs: StateFlow<List<LogEntry>> = _liveLogs.asStateFlow()

    private val _fps = MutableStateFlow(0f)
    val fps: StateFlow<Float> = _fps.asStateFlow()

    private val _fpsA = MutableStateFlow(0f)
    val fpsA: StateFlow<Float> = _fpsA.asStateFlow()

    private val _fpsB = MutableStateFlow(0f)
    val fpsB: StateFlow<Float> = _fpsB.asStateFlow()

    private val _testHistory = MutableStateFlow<List<CameraTestResult>>(emptyList())
    val testHistory: StateFlow<List<CameraTestResult>> = _testHistory.asStateFlow()

    // Engines
    private var camera2Tester: Camera2Tester? = null
    private var camera1Tester: Camera1LegacyTester? = null
    private var concurrentTester: ConcurrentCameraTester? = null

    init {
        refreshCameras()
    }

    fun refreshCameras() {
        viewModelScope.launch {
            val list = discoveryManager.discoverCameras()
            _cameras.value = list
            if (_selectedCamera.value == null && list.isNotEmpty()) {
                _selectedCamera.value = list.first()
            }
        }
    }

    fun selectCamera(camera: CameraDeviceDetails) {
        if (_isTesting.value) stopCurrentTest()
        _selectedCamera.value = camera
        _selectedPhysicalStreamId.value = null
    }

    fun selectPhysicalStream(physicalId: String?) {
        _selectedPhysicalStreamId.value = physicalId
    }

    fun startCamera2Test(surface: Surface) {
        val cam = _selectedCamera.value ?: return
        stopCurrentTest()

        _isTesting.value = true
        _activeApiMode.value = "Camera2"
        _liveLogs.value = emptyList()
        _fps.value = 0f

        updateCameraStatus(cam.id, CameraTestStatus.TESTING)

        camera2Tester = Camera2Tester(
            context = getApplication(),
            onLog = { entry ->
                _liveLogs.value = _liveLogs.value + entry
            },
            onFpsUpdate = { currentFps, _ ->
                _fps.value = currentFps
                updateCameraStatus(cam.id, CameraTestStatus.SUCCESS)
            },
            onTestCompleted = { result ->
                recordTestResult(result)
            }
        ).apply {
            startTest(cam.id, surface, _selectedPhysicalStreamId.value)
        }
    }

    fun startCamera1LegacyTest(holder: SurfaceHolder? = null, texture: SurfaceTexture? = null) {
        val cam = _selectedCamera.value ?: return
        stopCurrentTest()

        _isTesting.value = true
        _activeApiMode.value = "Camera1 (Legacy)"
        _liveLogs.value = emptyList()
        _fps.value = 0f

        updateCameraStatus(cam.id, CameraTestStatus.TESTING)

        camera1Tester = Camera1LegacyTester(
            onLog = { entry ->
                _liveLogs.value = _liveLogs.value + entry
            },
            onFpsUpdate = { currentFps, _ ->
                _fps.value = currentFps
                updateCameraStatus(cam.id, CameraTestStatus.SUCCESS)
            },
            onTestCompleted = { result ->
                recordTestResult(result)
            }
        ).apply {
            if (holder != null) {
                startTestWithSurfaceHolder(cam.id, holder)
            } else if (texture != null) {
                startTestWithSurfaceTexture(cam.id, texture)
            }
        }
    }

    fun startConcurrentTest(
        cameraA: CameraDeviceDetails,
        surfaceA: Surface,
        cameraB: CameraDeviceDetails,
        surfaceB: Surface
    ) {
        stopCurrentTest()

        _isTesting.value = true
        _activeApiMode.value = "Concurrent Multi-Camera"
        _liveLogs.value = emptyList()
        _fpsA.value = 0f
        _fpsB.value = 0f

        concurrentTester = ConcurrentCameraTester(
            context = getApplication(),
            onLogA = { entry ->
                _liveLogs.value = _liveLogs.value + entry.copy(message = "[Cam ${cameraA.id}] ${entry.message}")
            },
            onLogB = { entry ->
                _liveLogs.value = _liveLogs.value + entry.copy(message = "[Cam ${cameraB.id}] ${entry.message}")
            },
            onFpsUpdateA = { f, _ -> _fpsA.value = f },
            onFpsUpdateB = { f, _ -> _fpsB.value = f },
            onResultA = { result -> recordTestResult(result) },
            onResultB = { result -> recordTestResult(result) }
        ).apply {
            startConcurrentTest(cameraA.id, surfaceA, cameraB.id, surfaceB)
        }
    }

    fun stopCurrentTest() {
        camera2Tester?.stopTest()
        camera2Tester = null

        camera1Tester?.stopTest()
        camera1Tester = null

        concurrentTester?.stopConcurrentTest()
        concurrentTester = null

        _isTesting.value = false
        _activeApiMode.value = "Idle"
    }

    private fun recordTestResult(result: CameraTestResult) {
        _testHistory.value = listOf(result) + _testHistory.value
        updateCameraStatus(
            result.cameraId,
            if (result.isSuccess) CameraTestStatus.SUCCESS else CameraTestStatus.FAILED,
            result.errorExplanation
        )
    }

    private fun updateCameraStatus(cameraId: String, status: CameraTestStatus, error: String? = null) {
        _cameras.value = _cameras.value.map {
            if (it.id == cameraId) {
                it.copy(testStatus = status, lastError = error)
            } else it
        }
        if (_selectedCamera.value?.id == cameraId) {
            _selectedCamera.value = _selectedCamera.value?.copy(testStatus = status, lastError = error)
        }
    }

    fun generateFullReport(): String {
        return DiagnosticReportGenerator.generateReport(_cameras.value, _testHistory.value)
    }

    fun clearLogs() {
        _liveLogs.value = emptyList()
    }

    override fun onCleared() {
        super.onCleared()
        stopCurrentTest()
    }
}
