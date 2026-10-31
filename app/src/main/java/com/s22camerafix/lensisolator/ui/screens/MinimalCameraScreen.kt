package com.s22camerafix.lensisolator.ui.screens

import android.content.Intent
import android.provider.MediaStore
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.s22camerafix.lensisolator.engine.CameraMode
import com.s22camerafix.lensisolator.engine.MinimalCameraEngine
import com.s22camerafix.lensisolator.engine.TargetLens
import com.s22camerafix.lensisolator.ui.components.AutoFitTextureView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
fun MinimalCameraScreen(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val cameraEngine = remember { MinimalCameraEngine(context, scope) }

    val currentLens by cameraEngine.currentLens.collectAsState()
    val cameraMode by cameraEngine.cameraMode.collectAsState()
    val isFlashOn by cameraEngine.isFlashOn.collectAsState()
    val isCapturing by cameraEngine.isCapturing.collectAsState()
    val isRecording by cameraEngine.isRecording.collectAsState()
    val recordingSeconds by cameraEngine.recordingSeconds.collectAsState()
    val isLoading by cameraEngine.isLoading.collectAsState()
    val lastThumbnail by cameraEngine.lastSavedThumbnail.collectAsState()
    val lastUri by cameraEngine.lastSavedUri.collectAsState()
    val errorMessage by cameraEngine.errorMessage.collectAsState()

    var showFlashOverlay by remember { mutableStateOf(false) }

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
        // 1. Center 4:3 Viewfinder
        BoxWithConstraints(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(3f / 4f)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFF0F172A))
            ) {
                AutoFitTextureView(
                    onSurfaceAvailable = { _, surfaceTexture ->
                        cameraEngine.startCamera(surfaceTexture)
                    },
                    onSurfaceDestroyed = {
                        cameraEngine.stopCurrentSession()
                    }
                )

                // Lens Loading Spinner
                if (isLoading) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.45f)),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            color = Color(0xFFFBBF24),
                            modifier = Modifier.size(40.dp)
                        )
                    }
                }

                // Shutter White Flash Animation
                AnimatedVisibility(
                    visible = showFlashOverlay,
                    enter = fadeIn(animationSpec = tween(40)),
                    exit = fadeOut(animationSpec = tween(140))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.White)
                    )
                }
            }
        }

        // 2. Top Bar: Flash Toggle & Recording Indicator
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .padding(top = 48.dp, start = 20.dp, end = 20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Flash Toggle Pill
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(24.dp))
                    .background(if (isFlashOn) Color(0xFFFBBF24) else Color(0xFF1E293B).copy(alpha = 0.85f))
                    .clickable { cameraEngine.toggleFlash() }
                    .padding(horizontal = 14.dp, vertical = 7.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (isFlashOn) Icons.Default.FlashOn else Icons.Default.FlashOff,
                        contentDescription = "Flash",
                        tint = if (isFlashOn) Color.Black else Color.White,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (isFlashOn) "FLASH ON" else "FLASH OFF",
                        color = if (isFlashOn) Color.Black else Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.SansSerif
                    )
                }
            }

            // Video Recording Timer Pill
            if (isRecording) {
                val infiniteTransition = rememberInfiniteTransition(label = "pulse")
                val alpha by infiniteTransition.animateFloat(
                    initialValue = 1.0f,
                    targetValue = 0.2f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(600, easing = FastOutSlowInEasing),
                        repeatMode = RepeatMode.Reverse
                    ),
                    label = "recordingPulse"
                )

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color(0xFF991B1B).copy(alpha = 0.95f))
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .alpha(alpha)
                                .clip(CircleShape)
                                .background(Color.White)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        val minutes = recordingSeconds / 60
                        val seconds = recordingSeconds % 60
                        Text(
                            text = String.format(Locale.US, "%02d:%02d", minutes, seconds),
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            } else {
                Spacer(modifier = Modifier.width(1.dp))
            }
        }

        // Error message banner if any
        if (errorMessage != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 96.dp, start = 20.dp, end = 20.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF991B1B).copy(alpha = 0.95f))
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Text(text = errorMessage!!, color = Color.White, fontSize = 12.sp)
            }
        }

        // 3. Bottom Controls (Lenses: .6x, 1x, 10x | Mode Switcher: PHOTO / VIDEO | Shutter | Gallery)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .background(Color.Black.copy(alpha = 0.88f))
                .padding(bottom = 32.dp, top = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Three Lenses: [.6x] [1x] [10x]
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color(0xFF1E1E1E))
                    .padding(horizontal = 6.dp, vertical = 5.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TargetLens.values().forEach { lens ->
                    val isSelected = currentLens == lens

                    Box(
                        modifier = Modifier
                            .size(width = 46.dp, height = 34.dp)
                            .clip(RoundedCornerShape(17.dp))
                            .background(if (isSelected) Color(0xFFFBBF24) else Color.Transparent)
                            .clickable(enabled = !isRecording) {
                                cameraEngine.switchLens(lens)
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = lens.label,
                            color = if (isSelected) Color.Black else if (isRecording) Color.DarkGray else Color.White,
                            fontWeight = FontWeight.Black,
                            fontSize = 13.sp,
                            fontFamily = FontFamily.SansSerif
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Mode Selector: [ PHOTO ]  [ VIDEO ]
            Row(
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val isPhotoMode = cameraMode == CameraMode.PHOTO
                Text(
                    text = "PHOTO",
                    color = if (isPhotoMode) Color(0xFFFBBF24) else Color.Gray,
                    fontWeight = if (isPhotoMode) FontWeight.Bold else FontWeight.Medium,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.SansSerif,
                    modifier = Modifier.clickable(enabled = !isRecording) {
                        cameraEngine.setMode(CameraMode.PHOTO)
                    }
                )

                val isVideoMode = cameraMode == CameraMode.VIDEO
                Text(
                    text = "VIDEO",
                    color = if (isVideoMode) Color(0xFFFBBF24) else Color.Gray,
                    fontWeight = if (isVideoMode) FontWeight.Bold else FontWeight.Medium,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.SansSerif,
                    modifier = Modifier.clickable(enabled = !isRecording) {
                        cameraEngine.setMode(CameraMode.VIDEO)
                    }
                )
            }

            Spacer(modifier = Modifier.height(18.dp))

            // Shutter / Record Button Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 40.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Gallery Thumbnail (Tap to open image/video)
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF1E293B))
                        .border(1.5.dp, Color.White.copy(alpha = 0.4f), CircleShape)
                        .clickable(enabled = !isRecording) {
                            val uri = lastUri ?: MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                            val intent = Intent(Intent.ACTION_VIEW).apply {
                                setDataAndType(uri, if (cameraMode == CameraMode.VIDEO) "video/*" else "image/*")
                                flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                            }
                            try {
                                context.startActivity(intent)
                            } catch (e: Exception) {}
                        },
                    contentAlignment = Alignment.Center
                ) {
                    if (lastThumbnail != null) {
                        Image(
                            bitmap = lastThumbnail!!.asImageBitmap(),
                            contentDescription = "Gallery",
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(CircleShape)
                        )
                    } else {
                        Icon(
                            imageVector = if (cameraMode == CameraMode.VIDEO) Icons.Default.Videocam else Icons.Default.Photo,
                            contentDescription = "Gallery",
                            tint = Color.Gray,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }

                // Shutter / Record Button
                if (cameraMode == CameraMode.PHOTO) {
                    MinimalShutterButton(
                        isCapturing = isCapturing,
                        onClick = {
                            scope.launch {
                                showFlashOverlay = true
                                cameraEngine.takePicture()
                                delay(120)
                                showFlashOverlay = false
                            }
                        }
                    )
                } else {
                    MinimalRecordButton(
                        isRecording = isRecording,
                        onClick = {
                            if (isRecording) {
                                cameraEngine.stopRecordingVideo()
                            } else {
                                cameraEngine.startRecordingVideo()
                            }
                        }
                    )
                }

                // Balanced invisible placeholder for perfect centering
                Spacer(modifier = Modifier.size(52.dp))
            }
        }
    }
}

@Composable
fun MinimalShutterButton(
    isCapturing: Boolean,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed || isCapturing) 0.88f else 1.0f,
        label = "shutterScale"
    )

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
fun MinimalRecordButton(
    isRecording: Boolean,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.90f else 1.0f,
        label = "recordScale"
    )

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
        if (isRecording) {
            // Stop button: red square with rounded corners
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFFEF4444))
            )
        } else {
            // Record button: red circle
            Box(
                modifier = Modifier
                    .size(60.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFEF4444))
            )
        }
    }
}