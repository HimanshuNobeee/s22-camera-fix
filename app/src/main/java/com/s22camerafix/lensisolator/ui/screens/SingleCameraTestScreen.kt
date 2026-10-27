package com.s22camerafix.lensisolator.ui.screens

import android.graphics.SurfaceTexture
import android.view.Surface
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.s22camerafix.lensisolator.data.model.CameraDeviceDetails
import com.s22camerafix.lensisolator.data.model.CameraTestStatus
import com.s22camerafix.lensisolator.ui.components.AutoFitTextureView
import com.s22camerafix.lensisolator.ui.components.LogTerminalView
import com.s22camerafix.lensisolator.ui.theme.AccentCyan
import com.s22camerafix.lensisolator.ui.theme.DarkBackground
import com.s22camerafix.lensisolator.ui.theme.DarkSurface
import com.s22camerafix.lensisolator.ui.theme.DarkSurfaceVariant
import com.s22camerafix.lensisolator.ui.theme.ErrorRed
import com.s22camerafix.lensisolator.ui.theme.SuccessGreen
import com.s22camerafix.lensisolator.ui.theme.TextMuted
import com.s22camerafix.lensisolator.ui.theme.TextSecondary
import com.s22camerafix.lensisolator.ui.theme.WarningAmber
import com.s22camerafix.lensisolator.viewmodel.CameraViewModel

@Composable
fun SingleCameraTestScreen(
    viewModel: CameraViewModel,
    initialLegacyMode: Boolean = false,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val selectedCamera by viewModel.selectedCamera.collectAsState()
    val isTesting by viewModel.isTesting.collectAsState()
    val activeApiMode by viewModel.activeApiMode.collectAsState()
    val logs by viewModel.liveLogs.collectAsState()
    val fps by viewModel.fps.collectAsState()
    val selectedPhysicalStreamId by viewModel.selectedPhysicalStreamId.collectAsState()

    var useLegacyApi by remember { mutableStateOf(initialLegacyMode) }
    var activeSurface by remember { mutableStateOf<Surface?>(null) }
    var activeSurfaceTexture by remember { mutableStateOf<SurfaceTexture?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            viewModel.stopCurrentTest()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(DarkBackground)
            .padding(16.dp)
    ) {
        // Top Bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = {
                    viewModel.stopCurrentTest()
                    onBack()
                },
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(DarkSurfaceVariant)
            ) {
                Icon(
                    imageVector = Icons.Default.ArrowBack,
                    contentDescription = "Back",
                    tint = Color.White
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Testing Camera ${selectedCamera?.id ?: ""}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Text(
                    text = "${selectedCamera?.facing?.label ?: ""} • ${selectedCamera?.estimatedLensRole ?: ""}",
                    style = MaterialTheme.typography.bodySmall,
                    color = AccentCyan
                )
            }

            if (isTesting) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(SuccessGreen)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "STREAMING",
                    color = SuccessGreen,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Live Preview Box (with overlay)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(16.dp))
                .background(Color.Black)
                .border(1.dp, if (isTesting) AccentCyan else DarkSurfaceVariant, RoundedCornerShape(16.dp))
        ) {
            AutoFitTextureView(
                onSurfaceAvailable = { surface, st ->
                    activeSurface = surface
                    activeSurfaceTexture = st
                },
                onSurfaceDestroyed = {
                    activeSurface = null
                    activeSurfaceTexture = null
                }
            )

            // Idle or Testing Status Overlay
            if (!isTesting) {
                Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Camera is Currently Idle",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Zero hardware power draw. Click 'Start Test' below to probe this lens.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                        fontSize = 12.sp
                    )
                }
            } else {
                // Top Overlay: API & FPS
                Row(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(10.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.Black.copy(alpha = 0.7f))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "$activeApiMode | ",
                        color = TextSecondary,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = String.format("%.1f FPS", fps),
                        color = if (fps > 0) SuccessGreen else WarningAmber,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Controls Section
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(DarkSurface)
                .padding(12.dp)
        ) {
            // Mode Selector: Camera2 vs Camera1 Legacy
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = !useLegacyApi,
                    onClick = {
                        if (isTesting) viewModel.stopCurrentTest()
                        useLegacyApi = false
                    },
                    label = { Text("Modern Camera2 API", fontSize = 12.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = AccentCyan,
                        selectedLabelColor = DarkBackground
                    ),
                    modifier = Modifier.weight(1f)
                )

                FilterChip(
                    selected = useLegacyApi,
                    onClick = {
                        if (isTesting) viewModel.stopCurrentTest()
                        useLegacyApi = true
                    },
                    label = { Text("Legacy Camera1 (QR Mode)", fontSize = 12.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = AccentCyan,
                        selectedLabelColor = DarkBackground
                    ),
                    modifier = Modifier.weight(1f)
                )
            }

            // Physical Stream Selector (if Camera2 on Logical Multi-Camera)
            if (!useLegacyApi && selectedCamera?.isLogicalMultiCamera == true && selectedCamera!!.physicalSensors.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "TARGET PHYSICAL SENSOR (CAMERA2 STREAM):",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    item {
                        FilterChip(
                            selected = selectedPhysicalStreamId == null,
                            onClick = {
                                if (isTesting) viewModel.stopCurrentTest()
                                viewModel.selectPhysicalStream(null)
                            },
                            label = { Text("Default (All / Fusion)", fontSize = 11.sp) }
                        )
                    }
                    items(selectedCamera!!.physicalSensors) { sensor ->
                        FilterChip(
                            selected = selectedPhysicalStreamId == sensor.id,
                            onClick = {
                                if (isTesting) viewModel.stopCurrentTest()
                                viewModel.selectPhysicalStream(sensor.id)
                            },
                            label = { Text("ID ${sensor.id}: ${sensor.estimatedLensType}", fontSize = 11.sp) }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Start / Stop Action Button
            if (!isTesting) {
                Button(
                    onClick = {
                        activeSurface?.let { surface ->
                            if (useLegacyApi) {
                                activeSurfaceTexture?.let { st -> viewModel.startCamera1LegacyTest(texture = st) }
                            } else {
                                viewModel.startCamera2Test(surface)
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = AccentCyan),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = "Start",
                        tint = DarkBackground,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (useLegacyApi) "START CAMERA1 LEGACY TEST" else "START CAMERA2 SENSOR TEST",
                        color = DarkBackground,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                }
            } else {
                Button(
                    onClick = { viewModel.stopCurrentTest() },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = ErrorRed),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Stop,
                        contentDescription = "Stop",
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "STOP TEST / SHUT DOWN SENSOR",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Real-Time Log Terminal
        LogTerminalView(
            logs = logs,
            onClearLogs = { viewModel.clearLogs() },
            modifier = Modifier.height(150.dp)
        )
    }
}
