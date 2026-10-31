package com.s22camerafix.lensisolator.ui.screens

import android.content.Intent
import android.graphics.SurfaceTexture
import android.provider.MediaStore
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.s22camerafix.lensisolator.engine.MinimalCameraEngine
import com.s22camerafix.lensisolator.engine.TargetLens
import com.s22camerafix.lensisolator.ui.components.AutoFitTextureView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun MinimalCameraScreen(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val cameraEngine = remember { MinimalCameraEngine(context, scope) }

    val currentLens by cameraEngine.currentLens.collectAsState()
    val isFlashOn by cameraEngine.isFlashOn.collectAsState()
    val isCapturing by cameraEngine.isCapturing.collectAsState()
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
                            .background(Color.Black.copy(alpha = 0.4f)),
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
                    exit = fadeOut(animationSpec = tween(120))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.White)
                    )
                }
            }
        }

        // 2. Top Bar: Flash Toggle Button Only
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .padding(top = 48.dp, start = 20.dp, end = 20.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(24.dp))
                    .background(if (isFlashOn) Color(0xFFFBBF24) else Color(0xFF1E293B).copy(alpha = 0.85f))
                    .clickable { cameraEngine.toggleFlash() }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (isFlashOn) Icons.Default.FlashOn else Icons.Default.FlashOff,
                        contentDescription = "Flash",
                        tint = if (isFlashOn) Color.Black else Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (isFlashOn) "FLASH ON" else "FLASH OFF",
                        color = if (isFlashOn) Color.Black else Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.SansSerif
                    )
                }
            }
        }

        // Error message banner if any
        if (errorMessage != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 100.dp, start = 20.dp, end = 20.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF991B1B).copy(alpha = 0.95f))
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Text(text = errorMessage!!, color = Color.White, fontSize = 12.sp)
            }
        }

        // 3. Bottom Controls (Lenses: .6x, 1x, 10x | Shutter | Gallery)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .background(Color.Black.copy(alpha = 0.85f))
                .padding(bottom = 36.dp, top = 16.dp),
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
                            .size(width = 46.dp, height = 36.dp)
                            .clip(RoundedCornerShape(18.dp))
                            .background(if (isSelected) Color(0xFFFBBF24) else Color.Transparent)
                            .clickable {
                                cameraEngine.switchLens(lens)
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = lens.label,
                            color = if (isSelected) Color.Black else Color.White,
                            fontWeight = FontWeight.Black,
                            fontSize = 13.sp,
                            fontFamily = FontFamily.SansSerif
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Shutter Button Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 40.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Gallery Thumbnail (Tap to open in Samsung Gallery)
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF1E293B))
                        .border(1.5.dp, Color.White.copy(alpha = 0.4f), CircleShape)
                        .clickable {
                            val uri = lastUri ?: MediaStore.Images.Media.EXTERNAL_CONTENT_URI
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
                            imageVector = Icons.Default.Photo,
                            contentDescription = "Gallery",
                            tint = Color.Gray,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }

                // Shutter Button
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
