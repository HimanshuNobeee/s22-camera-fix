package com.s22camerafix.lensisolator.data.model

enum class LensFacing(val label: String) {
    BACK("Rear / Back"),
    FRONT("Front / Selfie"),
    EXTERNAL("External / USB"),
    UNKNOWN("Unknown")
}

enum class HardwareLevel(val label: String, val description: String) {
    LEGACY("LEGACY", "Old Camera1 compatibility layer"),
    LIMITED("LIMITED", "Basic Camera2 features"),
    FULL("FULL", "Advanced per-frame control"),
    LEVEL_3("LEVEL_3", "Highest tier with RAW & YUV re-processing"),
    EXTERNAL("EXTERNAL", "External camera device"),
    UNKNOWN("UNKNOWN", "Unspecified level")
}

enum class CameraTestStatus {
    UNTESTED,
    TESTING,
    SUCCESS,
    FAILED
}

data class PhysicalSensorInfo(
    val id: String,
    val focalLengths: List<Float>,
    val apertures: List<Float>,
    val equivalentFocalLength35mm: Float?,
    val sensorResolutionMp: Float,
    val pixelArraySize: Pair<Int, Int>?,
    val hasOis: Boolean,
    val hasAutofocus: Boolean,
    val estimatedLensType: String
)

data class CameraDeviceDetails(
    val id: String,
    val facing: LensFacing,
    val hardwareLevel: HardwareLevel,
    val isLogicalMultiCamera: Boolean,
    val physicalCameraIds: Set<String>,
    val physicalSensors: List<PhysicalSensorInfo>,
    val focalLengths: List<Float>,
    val apertures: List<Float>,
    val equivalentFocalLength35mm: Float?,
    val sensorResolutionMp: Float,
    val maxPhotoSize: Pair<Int, Int>?,
    val maxPreviewFps: Int,
    val hasOis: Boolean,
    val hasAutofocus: Boolean,
    val capabilities: List<String>,
    val isConcurrentCapable: Boolean,
    val testStatus: CameraTestStatus = CameraTestStatus.UNTESTED,
    val lastError: String? = null
) {
    val estimatedLensRole: String
        get() {
            if (facing == LensFacing.FRONT) return "Selfie / Front"
            if (isLogicalMultiCamera) return "Logical Multi-Camera (Combined)"
            val eq = equivalentFocalLength35mm
            return when {
                eq != null && eq < 18f -> "Ultra-Wide (~0.6x)"
                eq != null && eq in 18f..35f -> "Primary Wide (~1x)"
                eq != null && eq > 50f -> "Telephoto (~3x)"
                focalLengths.isNotEmpty() && focalLengths.first() < 3.0f -> "Ultra-Wide (~0.6x)"
                focalLengths.isNotEmpty() && focalLengths.first() in 3.0f..6.5f -> "Primary Wide (~1x)"
                focalLengths.isNotEmpty() && focalLengths.first() > 6.5f -> "Telephoto (~3x)"
                else -> "Rear Sensor ($id)"
            }
        }

    val wiringWarning: String?
        get() {
            return if (isLogicalMultiCamera && physicalCameraIds.isNotEmpty()) {
                "⚠️ WIRED MULTI-CAMERA: Device ID \"$id\" orchestrates physical sensors [${physicalCameraIds.joinToString(", ")}]. " +
                        "Opening this camera triggers the Samsung HAL to probe ALL underlying sensors. " +
                        "If any sub-sensor or shared bus is water-damaged, opening ID \"$id\" will crash the camera daemon."
            } else null
        }
}

data class CameraTestResult(
    val cameraId: String,
    val apiUsed: String,
    val physicalStreamId: String? = null,
    val isSuccess: Boolean,
    val fps: Float = 0f,
    val framesDelivered: Int = 0,
    val errorCode: Int? = null,
    val errorName: String? = null,
    val errorExplanation: String? = null,
    val logs: List<String> = emptyList(),
    val timestamp: Long = System.currentTimeMillis()
)
