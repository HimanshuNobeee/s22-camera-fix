package com.s22camerafix.lensisolator.engine

import android.content.Context
import android.view.Surface
import com.s22camerafix.lensisolator.data.model.CameraTestResult

class ConcurrentCameraTester(
    private val context: Context,
    private val onLogA: (LogEntry) -> Unit,
    private val onLogB: (LogEntry) -> Unit,
    private val onFpsUpdateA: (Float, Int) -> Unit,
    private val onFpsUpdateB: (Float, Int) -> Unit,
    private val onResultA: (CameraTestResult) -> Unit,
    private val onResultB: (CameraTestResult) -> Unit
) {
    private val testerA = Camera2Tester(context, onLogA, onFpsUpdateA, onResultA)
    private val testerB = Camera2Tester(context, onLogB, onFpsUpdateB, onResultB)

    fun startConcurrentTest(
        cameraIdA: String,
        surfaceA: Surface,
        cameraIdB: String,
        surfaceB: Surface
    ) {
        testerA.startTest(cameraIdA, surfaceA)
        testerB.startTest(cameraIdB, surfaceB)
    }

    fun stopConcurrentTest() {
        testerA.stopTest()
        testerB.stopTest()
    }
}
