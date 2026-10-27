package com.s22camerafix.lensisolator

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.s22camerafix.lensisolator.data.CameraPreferences
import com.s22camerafix.lensisolator.ui.screens.CameraAppScreen
import com.s22camerafix.lensisolator.ui.screens.DiagnosticReportScreen
import com.s22camerafix.lensisolator.ui.screens.HomeScreen
import com.s22camerafix.lensisolator.ui.screens.MultiCameraTestScreen
import com.s22camerafix.lensisolator.ui.screens.SingleCameraTestScreen
import com.s22camerafix.lensisolator.ui.theme.AccentCyan
import com.s22camerafix.lensisolator.ui.theme.DarkBackground
import com.s22camerafix.lensisolator.ui.theme.DarkSurface
import com.s22camerafix.lensisolator.ui.theme.S22CameraFixTheme
import com.s22camerafix.lensisolator.ui.theme.TextSecondary
import com.s22camerafix.lensisolator.viewmodel.CameraViewModel

sealed class AppScreen {
    object CameraApp : AppScreen()
    object DiagnosticsHome : AppScreen()
    data class SingleTest(val initialLegacyMode: Boolean = false) : AppScreen()
    object MultiTest : AppScreen()
    object Report : AppScreen()
}

class MainActivity : ComponentActivity() {

    private val viewModel: CameraViewModel by viewModels()
    private var hasCameraPermission by mutableStateOf(false)
    private var currentScreen by mutableStateOf<AppScreen>(AppScreen.CameraApp)
    private lateinit var preferences: CameraPreferences

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        hasCameraPermission = isGranted
        if (isGranted) {
            viewModel.refreshCameras()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preferences = CameraPreferences(this)
        checkCameraPermission()

        setContent {
            S22CameraFixTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = DarkBackground
                ) {
                    if (!hasCameraPermission) {
                        PermissionRequiredScreen(
                            onRequestPermission = {
                                requestPermissionLauncher.launch(Manifest.permission.CAMERA)
                            }
                        )
                    } else {
                        val cameras by viewModel.cameras.collectAsState()

                        when (val screen = currentScreen) {
                            is AppScreen.CameraApp -> {
                                CameraAppScreen(
                                    allCameras = cameras,
                                    preferences = preferences,
                                    onOpenDiagnostics = {
                                        viewModel.stopCurrentTest()
                                        currentScreen = AppScreen.DiagnosticsHome
                                    }
                                )
                            }
                            is AppScreen.DiagnosticsHome -> {
                                HomeScreen(
                                    viewModel = viewModel,
                                    onNavigateToCameraApp = {
                                        viewModel.stopCurrentTest()
                                        currentScreen = AppScreen.CameraApp
                                    },
                                    onNavigateToSingleTest = { _, isLegacy ->
                                        currentScreen = AppScreen.SingleTest(isLegacy)
                                    },
                                    onNavigateToMultiTest = {
                                        currentScreen = AppScreen.MultiTest
                                    },
                                    onNavigateToReport = {
                                        currentScreen = AppScreen.Report
                                    }
                                )
                            }
                            is AppScreen.SingleTest -> {
                                SingleCameraTestScreen(
                                    viewModel = viewModel,
                                    initialLegacyMode = screen.initialLegacyMode,
                                    onBack = { currentScreen = AppScreen.DiagnosticsHome }
                                )
                            }
                            is AppScreen.MultiTest -> {
                                MultiCameraTestScreen(
                                    viewModel = viewModel,
                                    onBack = { currentScreen = AppScreen.DiagnosticsHome }
                                )
                            }
                            is AppScreen.Report -> {
                                DiagnosticReportScreen(
                                    viewModel = viewModel,
                                    onBack = { currentScreen = AppScreen.DiagnosticsHome }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private fun checkCameraPermission() {
        hasCameraPermission = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when (currentScreen) {
            is AppScreen.CameraApp -> super.onBackPressed()
            is AppScreen.DiagnosticsHome -> currentScreen = AppScreen.CameraApp
            else -> {
                viewModel.stopCurrentTest()
                currentScreen = AppScreen.DiagnosticsHome
            }
        }
    }
}

@Composable
fun PermissionRequiredScreen(onRequestPermission: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(DarkSurface)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Default.CameraAlt,
                contentDescription = null,
                tint = AccentCyan,
                modifier = Modifier.size(48.dp)
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Camera Permission Needed",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Lens Isolator requires Camera permission to operate the camera lenses. You will be able to select which lenses are enabled.",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(20.dp))

            Button(
                onClick = onRequestPermission,
                colors = ButtonDefaults.buttonColors(containerColor = AccentCyan),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    text = "GRANT PERMISSION",
                    color = DarkBackground,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp
                )
            }
        }
    }
}
