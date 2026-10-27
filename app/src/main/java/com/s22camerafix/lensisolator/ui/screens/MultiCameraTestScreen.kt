package com.s22camerafix.lensisolator.ui.screens

import android.view.Surface
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import com.s22camerafix.lensisolator.viewmodel.CameraViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MultiCameraTestScreen(
    viewModel: CameraViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val cameras by viewModel.cameras.collectAsState()
    val isTesting by viewModel.isTesting.collectAsState()
    val logs by viewModel.liveLogs.collectAsState()
    val fpsA by viewModel.fpsA.collectAsState()
    val fpsB by viewModel.fpsB.collectAsState()

    var selectedCamA by remember(cameras) { mutableStateOf(cameras.firstOrNull()) }
    var selectedCamB by remember(cameras) { mutableStateOf(cameras.getOrNull(1) ?: cameras.firstOrNull()) }

    var surfaceA by remember { mutableStateOf<Surface?>(null) }
    var surfaceB by remember { mutableStateOf<Surface?>(null) }

    var expandedA by remember { mutableStateOf(false) }
    var expandedB by remember { mutableStateOf(false) }

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
        // Header
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

            Column {
                Text(
                    text = "Concurrent Multi-Lens Test",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Text(
                    text = "Test 2 sensors simultaneously to isolate bus collisions",
                    style = MaterialTheme.typography.bodySmall,
                    color = AccentCyan
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Split Previews (Top: Camera A, Bottom: Camera B)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Viewport A
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Black)
                    .border(1.dp, if (isTesting) AccentCyan else DarkSurfaceVariant, RoundedCornerShape(12.dp))
            ) {
                AutoFitTextureView(
                    onSurfaceAvailable = { s, _ -> surfaceA = s },
                    onSurfaceDestroyed = { surfaceA = null }
                )

                // Overlay
                Column(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.Black.copy(alpha = 0.75f))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = "Camera A (ID: ${selectedCamA?.id ?: "None"})",
                        color = Color.White,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                    if (isTesting) {
                        Text(
                            text = String.format("%.1f FPS", fpsA),
                            color = if (fpsA > 0) SuccessGreen else ErrorRed,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // Viewport B
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Black)
                    .border(1.dp, if (isTesting) AccentCyan else DarkSurfaceVariant, RoundedCornerShape(12.dp))
            ) {
                AutoFitTextureView(
                    onSurfaceAvailable = { s, _ -> surfaceB = s },
                    onSurfaceDestroyed = { surfaceB = null }
                )

                // Overlay
                Column(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.Black.copy(alpha = 0.75f))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = "Camera B (ID: ${selectedCamB?.id ?: "None"})",
                        color = Color.White,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                    if (isTesting) {
                        Text(
                            text = String.format("%.1f FPS", fpsB),
                            color = if (fpsB > 0) SuccessGreen else ErrorRed,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Selectors Section
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Camera A Dropdown
            ExposedDropdownMenuBox(
                expanded = expandedA,
                onExpandedChange = { if (!isTesting) expandedA = !expandedA },
                modifier = Modifier.weight(1f)
            ) {
                OutlinedTextField(
                    value = "A: ID ${selectedCamA?.id ?: ""} (${selectedCamA?.estimatedLensRole ?: ""})",
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Camera A", fontSize = 11.sp) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedA) },
                    modifier = Modifier.menuAnchor(),
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp)
                )
                ExposedDropdownMenu(
                    expanded = expandedA,
                    onDismissRequest = { expandedA = false }
                ) {
                    cameras.forEach { cam ->
                        DropdownMenuItem(
                            text = { Text("ID ${cam.id}: ${cam.facing.label} (${cam.estimatedLensRole})", fontSize = 12.sp) },
                            onClick = {
                                selectedCamA = cam
                                expandedA = false
                            }
                        )
                    }
                }
            }

            // Camera B Dropdown
            ExposedDropdownMenuBox(
                expanded = expandedB,
                onExpandedChange = { if (!isTesting) expandedB = !expandedB },
                modifier = Modifier.weight(1f)
            ) {
                OutlinedTextField(
                    value = "B: ID ${selectedCamB?.id ?: ""} (${selectedCamB?.estimatedLensRole ?: ""})",
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Camera B", fontSize = 11.sp) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedB) },
                    modifier = Modifier.menuAnchor(),
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp)
                )
                ExposedDropdownMenu(
                    expanded = expandedB,
                    onDismissRequest = { expandedB = false }
                ) {
                    cameras.forEach { cam ->
                        DropdownMenuItem(
                            text = { Text("ID ${cam.id}: ${cam.facing.label} (${cam.estimatedLensRole})", fontSize = 12.sp) },
                            onClick = {
                                selectedCamB = cam
                                expandedB = false
                            }
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Start / Stop Button
        if (!isTesting) {
            Button(
                onClick = {
                    val camA = selectedCamA
                    val camB = selectedCamB
                    val sA = surfaceA
                    val sB = surfaceB
                    if (camA != null && camB != null && sA != null && sB != null) {
                        viewModel.startConcurrentTest(camA, sA, camB, sB)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = AccentCyan),
                shape = RoundedCornerShape(8.dp)
            ) {
                Icon(imageVector = Icons.Default.PlayArrow, contentDescription = null, tint = DarkBackground, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "RUN DUAL SENSOR CONCURRENT TEST",
                    color = DarkBackground,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp
                )
            }
        } else {
            Button(
                onClick = { viewModel.stopCurrentTest() },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = ErrorRed),
                shape = RoundedCornerShape(8.dp)
            ) {
                Icon(imageVector = Icons.Default.Stop, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "STOP CONCURRENT SENSORS",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Log Terminal
        LogTerminalView(
            logs = logs,
            onClearLogs = { viewModel.clearLogs() },
            modifier = Modifier.height(140.dp)
        )
    }
}
