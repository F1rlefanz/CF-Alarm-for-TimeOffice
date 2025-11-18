package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens

import android.content.Intent
import androidx.core.net.toUri
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.BatteryOptimizationHelper
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags

/**
 * OEM Warning Screen - Phase 1
 * 
 * Shows manufacturer-specific warnings for aggressive battery management
 * Xiaomi, OnePlus, Huawei, etc.
 */
@Composable
fun OEMWarningScreen(
    oemType: BatteryOptimizationHelper.OEMType,
    onComplete: () -> Unit
) {
    val context = LocalContext.current
    val oemName = BatteryOptimizationHelper.getOEMDisplayName(oemType)
    val helpUrl = BatteryOptimizationHelper.getOEMHelpURL(oemType)
    val aggressiveness = BatteryOptimizationHelper.getOEMAggressivenessDescription(oemType)
    
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
                Icons.Default.Warning,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.tertiary
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Text(
                "$oemName-Gerät erkannt",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Text(
                aggressiveness,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        
        // Info card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(vertical = 24.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.tertiaryContainer
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    "Für maximale Zuverlässigkeit:",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                
                // OEM-specific steps
                when (oemType) {
                    BatteryOptimizationHelper.OEMType.XIAOMI -> {
                        OEMStep("Auto-Start erlauben", "Einstellungen → Apps → CF Alarm → Auto-Start")
                        OEMStep("Akku-Sparmodus", "Keine Einschränkungen für CF Alarm")
                        OEMStep("MIUI Optimization", "Optional deaktivieren für Apps")
                    }
                    BatteryOptimizationHelper.OEMType.ONEPLUS -> {
                        OEMStep("Auto-Start", "CF Alarm in Auto-Start erlauben")
                        OEMStep("Akku-Optimierung", "Nicht optimieren für CF Alarm")
                        OEMStep("Deep Optimization", "Optional deaktivieren")
                    }
                    BatteryOptimizationHelper.OEMType.HUAWEI -> {
                        OEMStep("Launch", "Manuelles Management für CF Alarm")
                        OEMStep("App Launch", "Automatischer Start erlauben")
                        OEMStep("Akku", "Auf Whitelist setzen")
                    }
                    else -> {
                        OEMStep("Background Apps", "CF Alarm erlauben")
                        OEMStep("Akku-Management", "Keine Einschränkungen")
                    }
                }
                
                HorizontalDivider()
                
                Text(
                    "Die App-Berechtigung alleine reicht möglicherweise nicht aus.",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
            }
        }
        
        // Buttons
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(
                onClick = {
                    try {
                        val intent = Intent(Intent.ACTION_VIEW, helpUrl.toUri())
                        context.startActivity(intent)
                    } catch (e: Exception) {
                        Logger.e(LogTags.BATTERY, "Failed to open OEM help URL", e)
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Detaillierte Anleitung")
            }
            
            TextButton(
                onClick = onComplete,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Verstanden")
            }
        }
    }
}

@Composable
private fun OEMStep(title: String, description: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            Icons.Default.CheckCircle,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.tertiary
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
