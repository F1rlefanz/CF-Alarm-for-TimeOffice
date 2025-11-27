package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens

import android.content.Intent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.BatteryOptimizationHelper

/**
 * Battery Onboarding Screen - Phase 1
 *
 * Visual guide for battery exemption with animation
 * Shows steps to enable "Unrestricted" battery mode
 * Includes OEM-specific warnings for problematic manufacturers
 */
@Composable
fun BatteryOnboardingScreen(
    onComplete: () -> Unit,
    onRequestExemption: () -> Unit
) {
    var currentStep by remember { mutableIntStateOf(0) }
    var showOEMWarning by remember { mutableStateOf(false) }
    LocalContext.current
    val oemType = remember { BatteryOptimizationHelper.getOEMType() }

    // Check if OEM warning should be shown
    LaunchedEffect(Unit) {
        if (BatteryOptimizationHelper.shouldShowOEMWarning(oemType)) {
            showOEMWarning = true
        }
    }
    val steps = listOf(
        "Einstellungen öffnen sich",
        "CF Alarm in Liste finden",
        "App antippen",
        "\"Uneingeschränkt\" wählen"
    )

    // Animation for step indicator
    val infiniteTransition = rememberInfiniteTransition(label = "step")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha"
    )

    LaunchedEffect(Unit) {
        // Auto-advance steps for demonstration
        while (currentStep < steps.size - 1) {
            kotlinx.coroutines.delay(2000)
            currentStep = (currentStep + 1) % steps.size
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // Header
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(top = 32.dp)
        ) {
            Icon(
                Icons.Default.BatteryChargingFull,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                "Zuverlässige Hintergrund-Wecker",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                "Damit CF Alarm auch nach Tagen ohne App-Öffnen noch Wecker erstellt",
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Animated steps visualization
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(vertical = 24.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                steps.forEachIndexed { index, step ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp)
                            .alpha(if (index == currentStep) alpha else 0.5f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Step number
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = if (index == currentStep)
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.outline,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier.fillMaxSize()
                            ) {
                                Text(
                                    text = "${index + 1}",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = if (index == currentStep)
                                        MaterialTheme.colorScheme.onPrimary
                                    else
                                        MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(16.dp))

                        // Step text
                        Text(
                            text = step,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = if (index == currentStep) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }
        }

        // Info text
        Text(
            "Die App öffnet gleich die Android-Einstellungen.\nWähle dort \"Uneingeschränkt\".",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Buttons
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(
                onClick = onRequestExemption,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Settings, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Zu Einstellungen")
            }

            TextButton(
                onClick = onComplete,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Warum ist das nötig?")
            }
        }
    }

    // Show OEM-specific warning if needed
    if (showOEMWarning) {
        OEMWarningDialog(
            oemType = oemType,
            onDismiss = {
                showOEMWarning = false
                onComplete()
            }
        )
    }
}

/**
 * Shows OEM-specific warning dialog
 */
@Composable
fun OEMWarningDialog(
    oemType: BatteryOptimizationHelper.OEMType,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(Icons.Default.Warning, contentDescription = null)
        },
        title = {
            Text("⚠️ ${oemType.name}-Gerät erkannt")
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "${oemType.name}-Geräte blockieren oft Apps im Hintergrund.",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "Bitte stelle zusätzlich sicher:",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    "• CF Alarm ist in 'Auto-Start' erlaubt",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    "• Akku-Sparmodus: Keine Einschränkungen",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    "• App ist vom Task-Killer ausgenommen",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                // Open dontkillmyapp.com for detailed instructions
                val url = BatteryOptimizationHelper.getOEMHelpURL(oemType)
                val intent = Intent(Intent.ACTION_VIEW, url.toUri())
                context.startActivity(intent)
                onDismiss()
            }) {
                Text("Detaillierte Anleitung")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Verstanden")
            }
        }
    )
}

/**
 * Shows educational dialog about battery exemption
 */
@Composable
fun BatteryEducationalDialog(
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(Icons.Default.Info, contentDescription = null)
        },
        title = {
            Text("Warum diese Berechtigung?")
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "CF Alarm benötigt uneingeschränkte Akku-Nutzung, um auch nach mehreren Tagen ohne App-Öffnen noch automatisch Wecker zu erstellen.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    "Ohne diese Berechtigung stoppt Android Background-Jobs nach einiger Zeit, und Wecker werden nicht mehr automatisch erstellt.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    "Ähnlich wie Podcast Addict oder Tasker funktioniert die App dann zuverlässig im Hintergrund.",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Verstanden")
            }
        }
    )
}
