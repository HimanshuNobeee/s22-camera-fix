package com.s22camerafix.lensisolator.data

import android.os.Build
import com.s22camerafix.lensisolator.data.model.CameraDeviceDetails
import com.s22camerafix.lensisolator.data.model.CameraTestResult
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DiagnosticReportGenerator {

    fun generateReport(
        cameras: List<CameraDeviceDetails>,
        testHistory: List<CameraTestResult>
    ): String {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val timestamp = dateFormat.format(Date())

        val sb = StringBuilder()
        sb.appendLine("=================================================================")
        sb.appendLine("       SAMSUNG GALAXY CAMERA HARDWARE DIAGNOSTIC REPORT          ")
        sb.appendLine("=================================================================")
        sb.appendLine("Generated At: $timestamp")
        sb.appendLine()

        sb.appendLine("--- DEVICE INFORMATION ---")
        sb.appendLine("Manufacturer : ${Build.MANUFACTURER}")
        sb.appendLine("Brand        : ${Build.BRAND}")
        sb.appendLine("Model        : ${Build.MODEL} (${Build.DEVICE})")
        sb.appendLine("Product      : ${Build.PRODUCT}")
        sb.appendLine("Board / Chip : ${Build.BOARD} / ${Build.HARDWARE}")
        sb.appendLine("Android OS   : ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        sb.appendLine("Build ID     : ${Build.DISPLAY}")
        sb.appendLine("Fingerprint  : ${Build.FINGERPRINT}")
        sb.appendLine()

        sb.appendLine("--- ENUMERATED CAMERA SENSORS (${cameras.size} TOTAL) ---")
        for (cam in cameras) {
            sb.appendLine("-----------------------------------------------------------------")
            sb.appendLine("Camera ID: ${cam.id} | ${cam.facing.label} | ${cam.estimatedLensRole}")
            sb.appendLine("  Hardware Level   : ${cam.hardwareLevel.label} (${cam.hardwareLevel.description})")
            sb.appendLine("  Logical Multi-Cam: ${if (cam.isLogicalMultiCamera) "YES" else "NO (Standalone)"}")
            sb.appendLine("  Max Resolution   : ${cam.sensorResolutionMp} MP ${cam.maxPhotoSize?.let { "(${it.first}x${it.second})" } ?: ""}")
            sb.appendLine("  Focal Length(s)  : ${cam.focalLengths.joinToString(", ")} mm")
            if (cam.equivalentFocalLength35mm != null) {
                sb.appendLine("  35mm Equivalent  : ~${cam.equivalentFocalLength35mm} mm")
            }
            sb.appendLine("  Apertures        : ${cam.apertures.joinToString(", ") { "f/$it" }}")
            sb.appendLine("  Optical Stabilizer: ${if (cam.hasOis) "Supported (OIS Coil Present)" else "None"}")
            sb.appendLine("  Autofocus Support : ${if (cam.hasAutofocus) "Supported" else "Fixed Focus"}")
            sb.appendLine("  Max Preview FPS  : ${cam.maxPreviewFps} FPS")
            sb.appendLine("  Capabilities     : ${cam.capabilities.joinToString(", ")}")

            if (cam.isLogicalMultiCamera && cam.physicalSensors.isNotEmpty()) {
                sb.appendLine("  >>> BACKING PHYSICAL SENSORS (WIRED TOGETHER):")
                for (phy in cam.physicalSensors) {
                    sb.appendLine("      * Physical ID [${phy.id}]: ${phy.estimatedLensType}")
                    sb.appendLine("        - Resolution  : ${phy.sensorResolutionMp} MP ${phy.pixelArraySize?.let { "(${it.first}x${it.second})" } ?: ""}")
                    sb.appendLine("        - Focal Length: ${phy.focalLengths.joinToString(", ")} mm (35mm eq: ~${phy.equivalentFocalLength35mm ?: "N/A"} mm)")
                    sb.appendLine("        - Apertures   : ${phy.apertures.joinToString(", ") { "f/$it" }}")
                    sb.appendLine("        - OIS / AF    : OIS=${phy.hasOis}, AF=${phy.hasAutofocus}")
                }
                sb.appendLine("  >>> HARDWARE RISK WARNING:")
                sb.appendLine("      Because ID ${cam.id} is a Logical Multi-Camera, opening it instructs")
                sb.appendLine("      the Samsung Camera HAL to probe all ${cam.physicalCameraIds.size} physical sensors.")
                sb.appendLine("      If water damage broke any one sensor's I2C line, OIS driver, or power rail,")
                sb.appendLine("      initializing this camera will crash cameraserver / HAL.")
            }
        }
        sb.appendLine("-----------------------------------------------------------------")
        sb.appendLine()

        sb.appendLine("--- TEST EXECUTION LOGS & SENSOR RESPONSES ---")
        if (testHistory.isEmpty()) {
            sb.appendLine("No camera test runs executed yet.")
        } else {
            for (test in testHistory) {
                val testTime = dateFormat.format(Date(test.timestamp))
                sb.appendLine("[$testTime] Camera ID: ${test.cameraId} via ${test.apiUsed}")
                if (test.physicalStreamId != null) {
                    sb.appendLine("  Target Physical Stream: ${test.physicalStreamId}")
                }
                sb.appendLine("  Result: ${if (test.isSuccess) "ONLINE / WORKING (Delivering ${test.fps} FPS, ${test.framesDelivered} frames)" else "FAILED"}")
                if (!test.isSuccess) {
                    sb.appendLine("  Error Code: ${test.errorCode} (${test.errorName ?: "UNKNOWN"})")
                    sb.appendLine("  Explanation: ${test.errorExplanation ?: "No details"}")
                }
                if (test.logs.isNotEmpty()) {
                    sb.appendLine("  HAL Event Trace:")
                    test.logs.takeLast(10).forEach { log ->
                        sb.appendLine("    $log")
                    }
                }
                sb.appendLine()
            }
        }

        sb.appendLine("--- WATER DAMAGE DIAGNOSTIC INTERPRETATION ---")
        sb.appendLine("1. Why 1x QR Scanner works while standard Camera App crashes:")
        sb.appendLine("   - Standard camera apps (Samsung Camera, WhatsApp, Instagram) open Camera ID 0")
        sb.appendLine("     as a LOGICAL multi-camera device. The HAL boots all 3 rear sensors (Wide,")
        sb.appendLine("     Telephoto 3x, Ultra-wide 0.6x) for smooth zoom & autofocus assist.")
        sb.appendLine("   - Water damage typically shorts or corrodes the I2C control bus or power rail")
        sb.appendLine("     of one auxiliary sensor (usually the Telephoto OIS module or Ultra-wide).")
        sb.appendLine("   - QR scanners often use the legacy Camera1 API (Camera.open(0)) or basic stream")
        sb.appendLine("     profiles that only initialize the primary wide sensor, bypassing the broken lens.")
        sb.appendLine("2. Suggested Action:")
        sb.appendLine("   - If Camera1 Legacy or a direct physical stream works, the 50MP main sensor is intact.")
        sb.appendLine("   - The rear camera module on Samsung S22 is a unified triple-camera assembly.")
        sb.appendLine("     Replacing the rear camera module (Samsung Part # GH96-14769A or variant)")
        sb.appendLine("     will fully restore all camera functionality.")
        sb.appendLine("=================================================================")

        return sb.toString()
    }
}
