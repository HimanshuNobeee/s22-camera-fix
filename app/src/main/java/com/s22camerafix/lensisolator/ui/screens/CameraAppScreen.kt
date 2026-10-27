package com.s22camerafix.lensisolator.ui.screens

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Cached
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.FlashAuto
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Timer10
import androidx.compose.material.icons.filled.Timer3
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.s22camerafix.lensisolator.data.CameraPreferences
import com.s22camerafix.lensisolator.data.model.CameraDeviceDetails
import com.s22camerafix.lensisolator.data.model.LensFacing
import com.s22camerafix.lensisolator.engine.SamsungStyleCameraEngine
import com.s22camerafix.lensisolator.ui.components.AutoFitTextureView
import com.s22camerafix.lensisolator.ui.dialogs.CameraSettingsDialog
import com.s22camerafix.lensisolator.ui.dialogs.LensSetupDialog
import com.s22camerafix.lensisolator.ui.theme.AccentCyan
import com.s22camerafix.lensisolator.ui.theme.DarkBackground
import com.s22camerafix.lensisolator.ui.theme.TextMuted
import com.s22camerafix.lensisolator.ui.theme.TextSecondary
import com.s22camerafix.lensisolator.ui.theme.WarningAmber
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun CameraAppScreen(
    allCameras: List<CameraDeviceDetails>,
    preferences: CameraPreferences,
    onOpenDiagnostics: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val cameraEngine = remember { SamsungStyleCameraEngine(context, scope) }

    // Saved preferences
    var enabledCameraIds by remember { mutableStateOf(preferences.enabledCameraIds) }
    var activeCameraId by remember {
        mutableStateOf(
            preferences.defaultCameraId ?: enabledCameraIds.firstOrNull() ?: allCameras.firstOrNull()?.id ?: "0"
        )
    }
    var aspectRatio by remember { mutableStateOf(preferences.aspectRatio) }
    var flashMode by remember { mutableStateOf(preferences.flashMode) }
    var timerSeconds by remember { mutableIntStateOf(preferences.timerSeconds) }
    var showGridLines by remember { mutableStateOf(preferences.showGridLines) }

    // Dialogs
    var showSetupDialog by remember { mutableStateOf(!preferences.isSetupCompleted) }
    var showSettingsDialog by remember { mutableStateOf(false) }

    // Engine States
    val isSessionReady by cameraEngine.isSessionReady.collectAsState()
    val isCapturing by cameraEngine.isCapturing.collectAsState()
    val lastCapturedThumbnail by cameraEngine.lastCapturedThumbnail.collectAsState()
    val lastCapturedUri by cameraEngine.lastCapturedUri.collectAsState()
    val zoomRatio by cameraEngine.currentZoomRatio.collectAsState()
    val maxZoom by cameraEngine.maxZoomRatio.collectAsState()
    val errorMessage by cameraEngine.errorMessage.collectAsState()

    var activeSurfaceTexture by remember { mutableStateOf<SurfaceTexture?>(null) }

    // Tap-to-focus animation state
    var focusPoint by remember { mutableStateOf<Offset?>(null) }
    var showFocusRing by remember { mutableStateOf(false) }

    // Timer countdown state
    var countdownValue by remember { mutableIntStateOf(0) }
    var isCountingDown by remember { mutableStateOf(false) }

    // Flash screen effect
    var showFlashOverlay by remember { mutableStateOf(false) }

    // Filter available cameras based on user's enabled selection
    val availableCameras = remember(allCameras, enabledCameraIds) {
        allCameras.filter { enabledCameraIds.contains(it.id) }
    }

    val activeCamera = remember(availableCameras, activeCameraId) {
        availableCameras.find { it.id == activeCameraId } ?: availableCameras.firstOrNull()
    }

    // Rear vs Front cameras
    val rearCameras = remember(availableCameras) {
        availableCameras.filter { it.facing == LensFacing.BACK }
    }
    val frontCameras = remember(availableCameras) {
        availableCameras.filter { it.facing == LensFacing.FRONT }
    }

    // Switch camera helper
    fun switchCamera(newId: String) {
        activeCameraId = newId
        val target = availableCameras.find { it.id == newId }
        val st = activeSurfaceTexture
        if (target != null && st != null) {
            cameraEngine.openCamera(target, st, aspectRatio)
        }
    }

    // Trigger Photo with optional timer
    fun triggerPhotoCapture() {
        if (isCountingDown || isCapturing) return

        if (timerSeconds > 0) {
            isCountingDown = true
            countdownValue = timerSeconds
            scope.launch {
                while (countdownValue > 0) {
                    delay(1000)
                    countdownValue--
                }
                isCountingDown = false
                showFlashOverlay = true
                cameraEngine.takePicture()
                delay(150)
                showFlashOverlay = false
            }
        } else {
            scope.launch {
                showFlashOverlay = true
                cameraEngine.takePicture()
                delay(120)
                showFlashOverlay = false
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            cameraEngine.release()
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // 1. Full-screen Camera Viewfinder
        BoxWithConstraints(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            val ratioFloat = when (aspectRatio) {
                "16:9" -> 9f / 16f
                "1:1" -> 1.0f
                "FULL" -> maxWidth / maxHeight
                else -> 3f / 4f // 4:3
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(ratioFloat)
                    .clip(RoundedCornerShape(if (aspectRatio == "FULL") 0.dp else 16.dp))
                    .background(Color.Black)
                    .pointerInput(Unit) {
                        detectTapGestures { offset ->
                            focusPoint = offset
                            showFocusRing = true
                            val normalizedX = (offset.x / size.width).coerceIn(0f, 1f)
                            val normalizedY = (offset.y / size.height).coerceIn(0f, 1f)
                            cameraEngine.tapToFocus(normalizedX, normalizedY)
                            scope.launch {
                                delay(1600)
                                showFocusRing = false
                            }
                        }
                    }
                    .pointerInput(zoomRatio, maxZoom) {
                        detectTransformGestures { _, _, zoom, _ ->
                            val newZoom = (zoomRatio * zoom).coerceIn(1.0f, maxZoom)
                            cameraEngine.setZoomRatio(newZoom)
                        }
                    }
            ) {
                AutoFitTextureView(
                    onSurfaceAvailable = { _, st ->
                        activeSurfaceTexture = st
                        activeCamera?.let { cam ->
                            cameraEngine.openCamera(cam, st, aspectRatio)
                        }
                    },
                    onSurfaceDestroyed = {
                        activeSurfaceTexture = null
                        cameraEngine.stopCurrentSession()
                    }
                )

                // 3x3 Grid Lines Overlay
                if (showGridLines) {
                    GridLinesOverlay()
                }

                // Tap-to-Focus Ring Overlay
                if (showFocusRing && focusPoint != null) {
                    val p = focusPoint!!
                    Box(
                        modifier = Modifier
                            .offset { IntOffset(p.x.roundToInt() - 36, p.y.roundToInt() - 36) }
                            .size(72.dp)
                            .border(2.dp, Color(0xFFFBBF24), CircleShape)
                    )
                }

                // Shutter White Flash Animation
                AnimatedVisibility(
                    visible = showFlashOverlay,
                    enter = fadeIn(animationSpec = tween(50)),
                    exit = fadeOut(animationSpec = tween(150))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.White)
                    )
                }
            }
        }

        // 2. Timer Countdown Overlay
        if (isCountingDown && countdownValue > 0) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "$countdownValue",
                    color = Color.White,
                    fontSize = 96.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Monospace
                )
            }
        }

        // 3. Top Control Bar (Settings, Flash, Timer, Ratio)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .padding(top = 40.dp, start = 16.dp, end = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Settings Gear
            IconButton(
                onClick = { showSettingsDialog = true },
                modifier = Modifier
                    .size(40.dp)
                    .background(Color.Black.copy(alpha = 0.5f), CircleShape)
            ) {
                Icon(imageVector = Icons.Default.Settings, contentDescription = "Settings", tint = Color.White, modifier = Modifier.size(20.dp))
            }

            // Flash Cycle (OFF -> AUTO -> ON)
            IconButton(
                onClick = {
                    val nextFlash = when (flashMode) {
                        "OFF" -> "AUTO"
                        "AUTO" -> "ON"
                        else -> "OFF"
                    }
                    flashMode = nextFlash
                    preferences.flashMode = nextFlash
                    cameraEngine.setFlashMode(nextFlash)
                },
                modifier = Modifier
                    .size(40.dp)
                    .background(Color.Black.copy(alpha = 0.5f), CircleShape)
            ) {
                val (flashIcon, flashColor) = when (flashMode) {
                    "ON" -> Pair(Icons.Default.FlashOn, Color(0xFFFBBF24))
                    "AUTO" -> Pair(Icons.Default.FlashAuto, Color.White)
                    else -> Pair(Icons.Default.FlashOff, TextMuted)
                }
                Icon(imageVector = flashIcon, contentDescription = "Flash", tint = flashColor, modifier = Modifier.size(20.dp))
            }

            // Timer Cycle (0 -> 2s -> 5s -> 10s)
            IconButton(
                onClick = {
                    val nextTimer = when (timerSeconds) {
                        0 -> 2
                        2 -> 5
                        5 -> 10
                        else -> 0
                    }
                    timerSeconds = nextTimer
                    preferences.timerSeconds = nextTimer
                },
                modifier = Modifier
                    .size(40.dp)
                    .background(Color.Black.copy(alpha = 0.5f), CircleShape)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (timerSeconds > 0) Icons.Default.Timer else Icons.Default.Timer,
                        contentDescription = "Timer",
                        tint = if (timerSeconds > 0) Color(0xFFFBBF24) else TextMuted,
                        modifier = Modifier.size(18.dp)
                    )
                    if (timerSeconds > 0) {
                        Text(text = "${timerSeconds}s", color = Color(0xFFFBBF24), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            // Aspect Ratio Cycle (4:3 -> 16:9 -> 1:1 -> FULL)
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.5f))
                    .clickable {
                        val nextRatio = when (aspectRatio) {
                            "4:3" -> "16:9"
                            "16:9" -> "1:1"
                            "1:1" -> "FULL"
                            else -> "4:3"
                        }
                        aspectRatio = nextRatio
                        preferences.aspectRatio = nextRatio
                        activeCamera?.let { cam ->
                            activeSurfaceTexture?.let { st ->
                                cameraEngine.openCamera(cam, st, nextRatio)
                            }
                        }
                    }
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Text(text = aspectRatio, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }

        // Error message if any
        if (errorMessage != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 96.dp, start = 20.dp, end = 20.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF7F1D1D).copy(alpha = 0.9f))
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Text(text = errorMessage!!, color = Color.White, fontSize = 12.sp)
            }
        }

        // 4. Bottom Controls Container
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .background(Color.Black.copy(alpha = 0.7f))
                .padding(bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Samsung-Style Zoom Lens Pills (Only for enabled rear cameras)
            if (activeCamera?.facing == LensFacing.BACK && rearCameras.isNotEmpty()) {
                SamsungLensPillSelector(
                    rearCameras = rearCameras,
                    activeCameraId = activeCameraId,
                    onSelectCamera = { newId -> switchCamera(newId) }
                )
                Spacer(modifier = Modifier.height(14.dp))
            } else {
                Spacer(modifier = Modifier.height(8.dp))
            }

            // Mode Selector: PHOTO vs DIAGNOSTICS
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "PHOTO",
                    color = Color(0xFFFBBF24),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Black,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )

                Text(
                    text = "DIAGNOSTICS",
                    color = TextSecondary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clickable { onOpenDiagnostics() }
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }

            Spacer(modifier = Modifier.height(18.dp))

            // Shutter Row: Gallery Thumbnail | Shutter Button | Flip Camera
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 32.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 1. Gallery Thumbnail Circle
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF262626))
                        .border(1.5.dp, Color.White.copy(alpha = 0.5f), CircleShape)
                        .clickable {
                            val uri = lastCapturedUri ?: MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                            val intent = Intent(Intent.ACTION_VIEW).apply {
                                setDataAndType(uri, "image/*")
                                flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                            }
                            try {
                                context.startActivity(intent)
                            } catch (e: Exception) {}
                        },
                    contentAlignment = Alignment.Center
                ) {
                    if (lastCapturedThumbnail != null) {
                        Image(
                            bitmap = lastCapturedThumbnail!!.asImageBitmap(),
                            contentDescription = "Gallery",
                            modifier = Modifier.fillMaxSize().clip(CircleShape)
                        )
                    } else {
                        Icon(imageVector = Icons.Default.Photo, contentDescription = "Gallery", tint = TextMuted)
                    }
                }

                // 2. Samsung Shutter Button
                SamsungShutterButton(
                    isCapturing = isCapturing,
                    onClick = { triggerPhotoCapture() }
                )

                // 3. Flip Camera Button (Front <-> Active Rear)
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF262626))
                        .border(1.5.dp, Color.White.copy(alpha = 0.5f), CircleShape)
                        .clickable {
                            val isCurrentFront = activeCamera?.facing == LensFacing.FRONT
                            val targetId = if (isCurrentFront) {
                                rearCameras.firstOrNull()?.id ?: "0"
                            } else {
                                frontCameras.firstOrNull()?.id ?: "1"
                            }
                            switchCamera(targetId)
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Cameraswitch,
                        contentDescription = "Flip Camera",
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }

        // Initial Setup / Lens Selection Dialog
        if (showSetupDialog) {
            LensSetupDialog(
                allCameras = allCameras,
                initialEnabledIds = enabledCameraIds,
                initialDefaultId = preferences.defaultCameraId,
                onSavePreferences = { newEnabled, defaultId ->
                    enabledCameraIds = newEnabled
                    preferences.enabledCameraIds = newEnabled
                    preferences.defaultCameraId = defaultId
                    preferences.isSetupCompleted = true
                    showSetupDialog = false
                    switchCamera(defaultId)
                },
                onDismiss = if (preferences.isSetupCompleted) { { showSetupDialog = false } } else null
            )
        }

        // Camera Settings Dialog
        if (showSettingsDialog) {
            CameraSettingsDialog(
                showGridLines = showGridLines,
                onToggleGridLines = { enabled ->
                    showGridLines = enabled
                    preferences.showGridLines = enabled
                },
                onOpenLensSetup = {
                    showSettingsDialog = false
                    showSetupDialog = true
                },
                onOpenDiagnostics = {
                    showSettingsDialog = false
                    onOpenDiagnostics()
                },
                onDismiss = { showSettingsDialog = false }
            )
        }
    }
}

@Composable
fun SamsungLensPillSelector(
    rearCameras: List<CameraDeviceDetails>,
    activeCameraId: String,
    onSelectCamera: (String) -> Unit
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(24.dp))
            .background(Color(0xFF1E1E1E).copy(alpha = 0.85f))
            .padding(horizontal = 4.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        rearCameras.forEach { cam ->
            val isSelected = cam.id == activeCameraId
            val label = when {
                cam.estimatedLensRole.contains("Ultra-Wide", ignoreCase = true) -> ".6"
                cam.estimatedLensRole.contains("Telephoto", ignoreCase = true) -> "3"
                else -> "1"
            }

            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(if (isSelected) Color(0xFFFBBF24) else Color.Transparent)
                    .clickable { onSelectCamera(cam.id) },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = label,
                    color = if (isSelected) Color.Black else Color.White,
                    fontWeight = FontWeight.Black,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.SansSerif
                )
            }
        }
    }
}

@Composable
fun SamsungShutterButton(
    isCapturing: Boolean,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(targetValue = if (isPressed || isCapturing) 0.88f else 1.0f, label = "shutterScale")

    Box(
        modifier = Modifier
            .size(76.dp)
            .scale(scale)
            .clip(CircleShape)
            .border(4.dp, Color.White, CircleShape)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(62.dp)
                .clip(CircleShape)
                .background(Color.White)
        )
    }
}

@Composable
fun GridLinesOverlay() {
    Column(modifier = Modifier.fillMaxSize()) {
        Spacer(modifier = Modifier.weight(1f))
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.25f)))
        Spacer(modifier = Modifier.weight(1f))
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.25f)))
        Spacer(modifier = Modifier.weight(1f))
    }
    Row(modifier = Modifier.fillMaxSize()) {
        Spacer(modifier = Modifier.weight(1f))
        Box(modifier = Modifier.fillMaxHeight().width(1.dp).background(Color.White.copy(alpha = 0.25f)))
        Spacer(modifier = Modifier.weight(1f))
        Box(modifier = Modifier.fillMaxHeight().width(1.dp).background(Color.White.copy(alpha = 0.25f)))
        Spacer(modifier = Modifier.weight(1f))
    }
}
