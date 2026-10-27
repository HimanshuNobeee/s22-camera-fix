package com.s22camerafix.lensisolator.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.s22camerafix.lensisolator.data.model.CameraDeviceDetails
import com.s22camerafix.lensisolator.ui.theme.DarkBackground
import com.s22camerafix.lensisolator.ui.theme.WarningAmber

@Composable
fun WiringWarningCard(camera: CameraDeviceDetails, modifier: Modifier = Modifier) {
    if (!camera.isLogicalMultiCamera || camera.physicalCameraIds.isEmpty()) return

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(WarningAmber.copy(alpha = 0.12f))
            .border(1.dp, WarningAmber.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = "Warning",
                tint = WarningAmber,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "WIRED MULTI-CAMERA DEPENDENCY",
                style = MaterialTheme.typography.titleMedium,
                color = WarningAmber,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Camera ID \"${camera.id}\" is a Logical Multi-Camera that controls ${camera.physicalCameraIds.size} physical sensors: [${camera.physicalCameraIds.joinToString(", ")}].",
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White
        )

        Spacer(modifier = Modifier.height(6.dp))

        camera.physicalSensors.forEach { sensor ->
            Text(
                text = "• Sensor [ID ${sensor.id}]: ${sensor.estimatedLensType} (${sensor.sensorResolutionMp} MP, f/${sensor.apertures.firstOrNull() ?: "?"})",
                style = MaterialTheme.typography.bodySmall,
                color = WarningAmber.copy(alpha = 0.9f)
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "⚠️ Water Damage Alert: When an app opens Camera ID ${camera.id}, the Samsung HAL sends power and I2C commands to ALL underlying sensors. If water shorted the telephoto or ultra-wide sensor, opening this camera will cause a fatal HAL crash (ERROR_CAMERA_DEVICE).",
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFFFDE68A)
        )
    }
}
