package com.s22camerafix.lensisolator.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.s22camerafix.lensisolator.data.model.CameraDeviceDetails
import com.s22camerafix.lensisolator.ui.components.SensorCard
import com.s22camerafix.lensisolator.ui.components.WiringWarningCard
import com.s22camerafix.lensisolator.ui.theme.AccentBlue
import com.s22camerafix.lensisolator.ui.theme.AccentCyan
import com.s22camerafix.lensisolator.ui.theme.DarkBackground
import com.s22camerafix.lensisolator.ui.theme.DarkSurface
import com.s22camerafix.lensisolator.ui.theme.DarkSurfaceVariant
import com.s22camerafix.lensisolator.ui.theme.SuccessGreen
import com.s22camerafix.lensisolator.ui.theme.TextMuted
import com.s22camerafix.lensisolator.ui.theme.TextSecondary
import com.s22camerafix.lensisolator.viewmodel.CameraViewModel

@Composable
fun HomeScreen(
    viewModel: CameraViewModel,
    onNavigateToCameraApp: () -> Unit,
    onNavigateToSingleTest: (CameraDeviceDetails, Boolean) -> Unit,
    onNavigateToMultiTest: () -> Unit,
    onNavigateToReport: () -> Unit,
    modifier: Modifier = Modifier
) {
    val cameras by viewModel.cameras.collectAsState()
    val selectedCamera by viewModel.selectedCamera.collectAsState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(DarkBackground)
            .padding(16.dp)
    ) {
        // App Bar / Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "LENS ISOLATOR",
                    style = MaterialTheme.typography.headlineMedium,
                    color = Color.White,
                    fontWeight = FontWeight.Black
                )
                Text(
                    text = "S22 Camera Hardware Diagnostic & Lens Isolation",
                    style = MaterialTheme.typography.bodySmall,
                    color = AccentCyan
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = onNavigateToCameraApp,
                    colors = ButtonDefaults.buttonColors(containerColor = AccentCyan),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Icon(imageVector = Icons.Default.CameraAlt, contentDescription = null, tint = DarkBackground, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(text = "Camera", color = DarkBackground, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }

                Spacer(modifier = Modifier.width(8.dp))

                IconButton(
                    onClick = { viewModel.refreshCameras() },
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(DarkSurfaceVariant)
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Refresh Sensors",
                        tint = Color.White
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Zero Auto-Open Safety Banner
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(SuccessGreen.copy(alpha = 0.1f))
                .border(1.dp, SuccessGreen.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Shield,
                contentDescription = "Safe",
                tint = SuccessGreen,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Zero Auto-Open Policy: No cameras or sensors are running in the background. System HAL remains idle until you trigger a test.",
                style = MaterialTheme.typography.bodySmall,
                color = SuccessGreen,
                fontSize = 11.sp
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Navigation Tabs / Quick Tools
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = onNavigateToMultiTest,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = DarkSurface),
                shape = RoundedCornerShape(8.dp)
            ) {
                Icon(imageVector = Icons.Default.GridView, contentDescription = null, tint = AccentCyan, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text(text = "Multi-Lens Test", color = Color.White, fontSize = 12.sp)
            }

            Button(
                onClick = onNavigateToReport,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = DarkSurface),
                shape = RoundedCornerShape(8.dp)
            ) {
                Icon(imageVector = Icons.Default.Assessment, contentDescription = null, tint = AccentBlue, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text(text = "Diagnostics Report", color = Color.White, fontSize = 12.sp)
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Selected Camera Dependency Alert (if any)
        selectedCamera?.let { cam ->
            if (cam.isLogicalMultiCamera) {
                WiringWarningCard(camera = cam)
                Spacer(modifier = Modifier.height(12.dp))
            }
        }

        Text(
            text = "DISCOVERED SENSORS (${cameras.size})",
            style = MaterialTheme.typography.titleMedium,
            color = TextSecondary,
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Sensor List
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(bottom = 16.dp)
        ) {
            items(cameras) { cam ->
                SensorCard(
                    camera = cam,
                    isSelected = selectedCamera?.id == cam.id,
                    onSelect = { viewModel.selectCamera(cam) },
                    onTestCamera2 = {
                        viewModel.selectCamera(cam)
                        onNavigateToSingleTest(cam, false)
                    },
                    onTestLegacy = {
                        viewModel.selectCamera(cam)
                        onNavigateToSingleTest(cam, true)
                    }
                )
            }
        }
    }
}
