package com.s22camerafix.lensisolator.ui.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.s22camerafix.lensisolator.data.model.CameraDeviceDetails
import com.s22camerafix.lensisolator.ui.theme.AccentCyan
import com.s22camerafix.lensisolator.ui.theme.DarkBackground
import com.s22camerafix.lensisolator.ui.theme.DarkSurface
import com.s22camerafix.lensisolator.ui.theme.DarkSurfaceVariant
import com.s22camerafix.lensisolator.ui.theme.ErrorRed
import com.s22camerafix.lensisolator.ui.theme.SuccessGreen
import com.s22camerafix.lensisolator.ui.theme.TextMuted
import com.s22camerafix.lensisolator.ui.theme.TextSecondary
import com.s22camerafix.lensisolator.ui.theme.WarningAmber

@Composable
fun LensSetupDialog(
    allCameras: List<CameraDeviceDetails>,
    initialEnabledIds: Set<String>,
    initialDefaultId: String?,
    onSavePreferences: (enabledIds: Set<String>, defaultId: String) -> Unit,
    onDismiss: (() -> Unit)? = null
) {
    var enabledIds by remember {
        mutableStateOf(
            if (initialEnabledIds.isNotEmpty()) initialEnabledIds
            else {
                // Auto-exclude telephoto if it's the known damaged sensor
                allCameras.filter { !it.estimatedLensRole.contains("Telephoto", ignoreCase = true) }
                    .map { it.id }.toSet()
            }
        )
    }

    var selectedDefaultId by remember {
        mutableStateOf(
            initialDefaultId ?: enabledIds.firstOrNull() ?: allCameras.firstOrNull()?.id ?: "0"
        )
    }

    Dialog(
        onDismissRequest = { onDismiss?.invoke() },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .clip(RoundedCornerShape(20.dp)),
            color = DarkSurface
        ) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
            ) {
                // Header
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(AccentCyan.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Security,
                            contentDescription = "Setup",
                            tint = AccentCyan,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "Camera Sensor Setup",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Text(
                            text = "Select working lenses to include",
                            style = MaterialTheme.typography.bodySmall,
                            color = AccentCyan
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Explanatory Banner
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(WarningAmber.copy(alpha = 0.1f))
                        .border(1.dp, WarningAmber.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = "Warning",
                        tint = WarningAmber,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Uncheck the damaged 3x Telephoto lens. The camera app will completely bypass it and only use your healthy lenses.",
                        color = WarningAmber,
                        fontSize = 11.sp,
                        lineHeight = 15.sp
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                Text(
                    text = "AVAILABLE SENSORS (${allCameras.size}):",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Camera List with Switches
                LazyColumn(
                    modifier = Modifier.weight(1f, fill = false),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(allCameras) { cam ->
                        val isEnabled = enabledIds.contains(cam.id)
                        val isTelephoto = cam.estimatedLensRole.contains("Telephoto", ignoreCase = true)
                        val isDefault = selectedDefaultId == cam.id

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(DarkBackground)
                                .border(
                                    1.dp,
                                    if (isEnabled) AccentCyan.copy(alpha = 0.4f) else DarkSurfaceVariant,
                                    RoundedCornerShape(12.dp)
                                )
                                .padding(12.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = "Camera ID: ${cam.id} — ${cam.estimatedLensRole}",
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = if (isEnabled) Color.White else TextMuted
                                        )
                                    }

                                    Spacer(modifier = Modifier.height(2.dp))

                                    Text(
                                        text = "${cam.facing.label} • ${cam.sensorResolutionMp} MP • f/${cam.apertures.firstOrNull() ?: "?"}" +
                                                (cam.equivalentFocalLength35mm?.let { " • ~${it}mm" } ?: ""),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (isEnabled) AccentCyan else TextMuted,
                                        fontSize = 11.sp
                                    )

                                    if (isTelephoto) {
                                        Text(
                                            text = "⚠️ Suspected Water-Damaged 3x Telephoto",
                                            color = ErrorRed,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }

                                Switch(
                                    checked = isEnabled,
                                    onCheckedChange = { checked ->
                                        enabledIds = if (checked) enabledIds + cam.id else enabledIds - cam.id
                                        if (!checked && selectedDefaultId == cam.id) {
                                            selectedDefaultId = enabledIds.firstOrNull() ?: ""
                                        }
                                    },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = Color.White,
                                        checkedTrackColor = AccentCyan,
                                        uncheckedThumbColor = TextMuted,
                                        uncheckedTrackColor = DarkSurfaceVariant
                                    )
                                )
                            }

                            // Option to set as default launch lens
                            if (isEnabled) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { selectedDefaultId = cam.id }
                                        .padding(vertical = 2.dp)
                                ) {
                                    RadioButton(
                                        selected = isDefault,
                                        onClick = { selectedDefaultId = cam.id },
                                        colors = RadioButtonDefaults.colors(selectedColor = AccentCyan)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = if (isDefault) "Default Launch Camera (Primary)" else "Set as default launch camera",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (isDefault) AccentCyan else TextSecondary,
                                        fontSize = 11.sp
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Action Buttons
                Button(
                    onClick = {
                        val finalDefault = if (enabledIds.contains(selectedDefaultId)) selectedDefaultId
                        else enabledIds.firstOrNull() ?: allCameras.firstOrNull()?.id ?: "0"
                        onSavePreferences(enabledIds, finalDefault)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = enabledIds.isNotEmpty(),
                    colors = ButtonDefaults.buttonColors(containerColor = AccentCyan),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = DarkBackground,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "SAVE & LAUNCH CAMERA (${enabledIds.size} Lenses Active)",
                        color = DarkBackground,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp
                    )
                }
            }
        }
    }
}
