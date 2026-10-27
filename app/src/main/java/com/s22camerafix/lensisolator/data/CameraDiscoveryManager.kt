package com.s22camerafix.lensisolator.data

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.os.Build
import android.util.SizeF
import com.s22camerafix.lensisolator.data.model.CameraDeviceDetails
import com.s22camerafix.lensisolator.data.model.HardwareLevel
import com.s22camerafix.lensisolator.data.model.LensFacing
import com.s22camerafix.lensisolator.data.model.PhysicalSensorInfo
import kotlin.math.roundToInt
import kotlin.math.sqrt

class CameraDiscoveryManager(context: Context) {

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    /**
     * Completely passive discovery: reads cached OS camera characteristics.
     * Does NOT open any camera hardware or activate power rails.
     */
    fun discoverCameras(): List<CameraDeviceDetails> {
        val result = mutableListOf<CameraDeviceDetails>()
        val concurrentCombinations = getConcurrentCameraCombinations()

        try {
            val cameraIds = cameraManager.cameraIdList
            for (id in cameraIds) {
                try {
                    val characteristics = cameraManager.getCameraCharacteristics(id)
                    val details = parseCameraDetails(id, characteristics, concurrentCombinations)
                    result.add(details)
                } catch (e: Exception) {
                    // Even if one corrupted camera fails metadata read, continue discovering others
                    result.add(
                        CameraDeviceDetails(
                            id = id,
                            facing = LensFacing.UNKNOWN,
                            hardwareLevel = HardwareLevel.UNKNOWN,
                            isLogicalMultiCamera = false,
                            physicalCameraIds = emptySet(),
                            physicalSensors = emptyList(),
                            focalLengths = emptyList(),
                            apertures = emptyList(),
                            equivalentFocalLength35mm = null,
                            sensorResolutionMp = 0f,
                            maxPhotoSize = null,
                            maxPreviewFps = 0,
                            hasOis = false,
                            hasAutofocus = false,
                            capabilities = emptyList(),
                            isConcurrentCapable = false,
                            lastError = "Failed to query characteristics: ${e.message}"
                        )
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return result
    }

    private fun parseCameraDetails(
        id: String,
        characteristics: CameraCharacteristics,
        concurrentCombinations: Set<Set<String>>
    ): CameraDeviceDetails {
        val facingInt = characteristics.get(CameraCharacteristics.LENS_FACING)
        val facing = when (facingInt) {
            CameraMetadata.LENS_FACING_BACK -> LensFacing.BACK
            CameraMetadata.LENS_FACING_FRONT -> LensFacing.FRONT
            CameraMetadata.LENS_FACING_EXTERNAL -> LensFacing.EXTERNAL
            else -> LensFacing.UNKNOWN
        }

        val hwLevelInt = characteristics.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
        val hardwareLevel = when (hwLevelInt) {
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> HardwareLevel.LEGACY
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> HardwareLevel.LIMITED
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> HardwareLevel.FULL
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> HardwareLevel.LEVEL_3
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> HardwareLevel.EXTERNAL
            else -> HardwareLevel.UNKNOWN
        }

        val capabilitiesList = mutableListOf<String>()
        val capabilities = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
        var isLogicalMultiCam = false

        for (cap in capabilities) {
            when (cap) {
                CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE -> capabilitiesList.add("Backward Compatible")
                CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR -> capabilitiesList.add("Manual Sensor")
                CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING -> capabilitiesList.add("Manual Post-Processing")
                CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW -> capabilitiesList.add("RAW Support")
                CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_PRIVATE_REPROCESSING -> capabilitiesList.add("Private Reprocessing")
                CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_READ_SENSOR_SETTINGS -> capabilitiesList.add("Read Sensor Settings")
                CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BURST_CAPTURE -> capabilitiesList.add("Burst Capture")
                CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_YUV_REPROCESSING -> capabilitiesList.add("YUV Reprocessing")
                CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_DEPTH_OUTPUT -> capabilitiesList.add("Depth Output")
                CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO -> capabilitiesList.add("High Speed Video")
                CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MOTION_TRACKING -> capabilitiesList.add("Motion Tracking")
                CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA -> {
                    isLogicalMultiCam = true
                    capabilitiesList.add("Logical Multi-Camera")
                }
                CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MONOCHROME -> capabilitiesList.add("Monochrome")
                CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_SECURE_IMAGE_DATA -> capabilitiesList.add("Secure Image Data")
                CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_SYSTEM_CAMERA -> capabilitiesList.add("System Camera")
                CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_OFFLINE_PROCESSING -> capabilitiesList.add("Offline Processing")
                CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_ULTRA_HIGH_RESOLUTION_SENSOR -> capabilitiesList.add("Ultra High-Res Sensor")
                CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_REMOSAIC_REPROCESSING -> capabilitiesList.add("Remosaic Reprocessing")
                CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_DYNAMIC_RANGE_TEN_BIT -> capabilitiesList.add("10-Bit HDR")
                CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_STREAM_USE_CASE -> capabilitiesList.add("Stream Use Case")
            }
        }

        // Query physical sub-cameras if logical multi-camera
        val physicalIds = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            characteristics.physicalCameraIds ?: emptySet()
        } else {
            emptySet()
        }

        val physicalSensors = mutableListOf<PhysicalSensorInfo>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && isLogicalMultiCam) {
            for (physicalId in physicalIds) {
                try {
                    val physicalChar = characteristics.getPhysicalCameraCharacteristics(physicalId)
                    if (physicalChar != null) {
                        physicalSensors.add(parsePhysicalSensor(physicalId, physicalChar, facing))
                    }
                } catch (e: Exception) {
                    // If reading physical characteristics fails, log basic entry
                    physicalSensors.add(
                        PhysicalSensorInfo(
                            id = physicalId,
                            focalLengths = emptyList(),
                            apertures = emptyList(),
                            equivalentFocalLength35mm = null,
                            sensorResolutionMp = 0f,
                            pixelArraySize = null,
                            hasOis = false,
                            hasAutofocus = false,
                            estimatedLensType = "Physical Sensor $physicalId (Unqueried)"
                        )
                    )
                }
            }
        }

        val focalLengths = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.toList() ?: emptyList()
        val apertures = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)?.toList() ?: emptyList()
        val sensorSize = characteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        val pixelArraySize = characteristics.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)

        val resolutionMp = if (pixelArraySize != null) {
            (pixelArraySize.width.toLong() * pixelArraySize.height.toLong()) / 1_000_000f
        } else 0f

        val eqFocal = calculate35mmEquivalent(focalLengths.firstOrNull(), sensorSize)

        // Optical Image Stabilization (OIS)
        val oisModes = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION) ?: intArrayOf()
        val hasOis = oisModes.contains(CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON)

        // Auto Focus
        val afModes = characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: intArrayOf()
        val hasAf = afModes.any { it != CameraMetadata.CONTROL_AF_MODE_OFF }

        // Max Photo Size
        val streamConfigMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val photoSizes = streamConfigMap?.getOutputSizes(ImageFormat.JPEG)
        val maxPhotoSize = photoSizes?.maxByOrNull { it.width * it.height }?.let { Pair(it.width, it.height) }

        // Max preview FPS
        val fpsRanges = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: emptyArray()
        val maxFps = fpsRanges.maxOfOrNull { it.upper } ?: 30

        val isConcurrent = concurrentCombinations.any { it.contains(id) }

        return CameraDeviceDetails(
            id = id,
            facing = facing,
            hardwareLevel = hardwareLevel,
            isLogicalMultiCamera = isLogicalMultiCam,
            physicalCameraIds = physicalIds,
            physicalSensors = physicalSensors,
            focalLengths = focalLengths,
            apertures = apertures,
            equivalentFocalLength35mm = eqFocal,
            sensorResolutionMp = (resolutionMp * 10).roundToInt() / 10f,
            maxPhotoSize = maxPhotoSize,
            maxPreviewFps = maxFps,
            hasOis = hasOis,
            hasAutofocus = hasAf,
            capabilities = capabilitiesList,
            isConcurrentCapable = isConcurrent
        )
    }

    private fun parsePhysicalSensor(
        id: String,
        characteristics: CameraCharacteristics,
        parentFacing: LensFacing
    ): PhysicalSensorInfo {
        val focalLengths = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.toList() ?: emptyList()
        val apertures = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)?.toList() ?: emptyList()
        val sensorSize = characteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        val pixelArraySize = characteristics.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)

        val resolutionMp = if (pixelArraySize != null) {
            (pixelArraySize.width.toLong() * pixelArraySize.height.toLong()) / 1_000_000f
        } else 0f

        val eqFocal = calculate35mmEquivalent(focalLengths.firstOrNull(), sensorSize)

        val oisModes = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION) ?: intArrayOf()
        val hasOis = oisModes.contains(CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON)

        val afModes = characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: intArrayOf()
        val hasAf = afModes.any { it != CameraMetadata.CONTROL_AF_MODE_OFF }

        val lensType = when {
            parentFacing == LensFacing.FRONT -> "Front / Selfie Lens"
            eqFocal != null && eqFocal < 18f -> "Ultra-Wide (~0.6x, ~${eqFocal.roundToInt()}mm)"
            eqFocal != null && eqFocal in 18f..35f -> "Primary Wide (~1x, ~${eqFocal.roundToInt()}mm)"
            eqFocal != null && eqFocal > 50f -> "Telephoto (~3x, ~${eqFocal.roundToInt()}mm)"
            focalLengths.isNotEmpty() && focalLengths.first() < 3.0f -> "Ultra-Wide (~0.6x)"
            focalLengths.isNotEmpty() && focalLengths.first() in 3.0f..6.5f -> "Primary Wide (~1x)"
            focalLengths.isNotEmpty() && focalLengths.first() > 6.5f -> "Telephoto (~3x)"
            else -> "Physical Sensor $id"
        }

        return PhysicalSensorInfo(
            id = id,
            focalLengths = focalLengths,
            apertures = apertures,
            equivalentFocalLength35mm = eqFocal,
            sensorResolutionMp = (resolutionMp * 10).roundToInt() / 10f,
            pixelArraySize = pixelArraySize?.let { Pair(it.width, it.height) },
            hasOis = hasOis,
            hasAutofocus = hasAf,
            estimatedLensType = lensType
        )
    }

    private fun calculate35mmEquivalent(focalLength: Float?, sensorSize: SizeF?): Float? {
        if (focalLength == null || sensorSize == null || sensorSize.width <= 0f || sensorSize.height <= 0f) return null
        val sensorDiagonal = sqrt((sensorSize.width * sensorSize.width + sensorSize.height * sensorSize.height).toDouble()).toFloat()
        // 35mm full frame film diagonal is ~43.27 mm
        val fullFrameDiagonal = 43.2666f
        val cropFactor = fullFrameDiagonal / sensorDiagonal
        return (focalLength * cropFactor * 10).roundToInt() / 10f
    }

    private fun getConcurrentCameraCombinations(): Set<Set<String>> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                cameraManager.concurrentCameraIds
            } catch (e: Exception) {
                emptySet()
            }
        } else {
            emptySet()
        }
    }
}
