package com.s22camerafix.lensisolator.ui.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.s22camerafix.lensisolator.ui.theme.AccentCyan
import com.s22camerafix.lensisolator.ui.theme.DarkBackground
import com.s22camerafix.lensisolator.ui.theme.DarkSurface
import com.s22camerafix.lensisolator.ui.theme.DarkSurfaceVariant
import com.s22camerafix.lensisolator.ui.theme.TextMuted
import com.s22camerafix.lensisolator.ui.theme.TextSecondary

@Composable
fun CameraSettingsDialog(
    showGridLines: Boolean,
    onToggleGridLines: (Boolean) -> Unit,
    onOpenLensSetup: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp)),
            color = DarkSurface
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(AccentCyan.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Settings,
                                contentDescription = null,
                                tint = AccentCyan,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "Camera Settings",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }

                    IconButton(onClick = onDismiss) {
                        Icon(imageVector = Icons.Default.Close, contentDescription = "Close", tint = TextSecondary)
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Configure Lenses Row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(DarkBackground)
                        .clickable {
                            onDismiss()
                            onOpenLensSetup()
                        }
                        .padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(imageVector = Icons.Default.PhotoCamera, contentDescription = null, tint = AccentCyan)
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(text = "Configure Active Lenses", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                            Text(text = "Enable / disable individual sensors", color = TextSecondary, fontSize = 11.sp)
                        }
                    }
                    Text(text = "Change", color = AccentCyan, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Grid Lines Toggle Row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(DarkBackground)
                        .padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(imageVector = Icons.Default.GridOn, contentDescription = null, tint = AccentCyan)
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(text = "Grid Lines (3x3)", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                            Text(text = "Display framing guide lines", color = TextSecondary, fontSize = 11.sp)
                        }
                    }
                    Switch(
                        checked = showGridLines,
                        onCheckedChange = onToggleGridLines,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = AccentCyan,
                            uncheckedThumbColor = TextMuted,
                            uncheckedTrackColor = DarkSurfaceVariant
                        )
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Open Diagnostics Bench
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(DarkBackground)
                        .clickable {
                            onDismiss()
                            onOpenDiagnostics()
                        }
                        .padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(imageVector = Icons.Default.Build, contentDescription = null, tint = Color(0xFFFBBF24))
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(text = "Hardware Diagnostic Bench", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                            Text(text = "Low-level HAL testing & error logs", color = TextSecondary, fontSize = 11.sp)
                        }
                    }
                    Text(text = "Open", color = Color(0xFFFBBF24), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
