package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.tabs.hue

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.connection.HueBridgeConnectionManager
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.components.CompactOutlinedButton
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.components.KnopfZeile
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.theme.success
import kotlinx.coroutines.launch
import com.github.f1rlefanz.cf_alarmfortimeoffice.R
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource

/**
 * Die Karten fuer eine EINGERICHTETE Bridge: Verwaltung (Statistik, Knoepfe) und die
 * Erst-Einrichtungs-Erfolgskarte.
 *
 * Aus `HueTabContent` ausgelagert (reine Verschiebung - die Sichtbarkeitsbedingungen bleiben beim
 * Aufrufer, ueber jeder von ihnen steht dort ein Hergangskommentar zu einer gemeldeten Doppelung).
 */
@Composable
internal fun ConnectedManagementCard(
    rulesCount: Int,
    enabledRulesCount: Int,
    lightsCount: Int,
    onShowShiftConfig: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onTestConnection: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    // dekorativ: "Bridge verbunden" steht direkt daneben
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.success,
                    modifier = Modifier.size(32.dp)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.hue_connected_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = pluralStringResource(R.plurals.hue_connected_rules, rulesCount, enabledRulesCount, rulesCount),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Quick Stats
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                QuickStatItem(
                    icon = Icons.Default.Schedule,
                    label = stringResource(R.string.hue_stats_rules),
                    value = rulesCount.toString()
                )
                QuickStatItem(
                    icon = Icons.Default.Lightbulb,
                    label = stringResource(R.string.hue_stats_lights),
                    value = lightsCount.toString()
                )
                QuickStatItem(
                    icon = Icons.Default.CheckCircle,
                    label = stringResource(R.string.hue_rulecard_active),
                    value = enabledRulesCount.toString()
                )
            }

            // Lichtregeln gibt es nur am Schichttyp (#255) - hier steht der Weg dorthin.
            LichtregelnWeg(onShowShiftConfig)

            // KnopfZeile statt weight(1f): kuerzte die Beschriftungen bei 320 dp mit Auslassung
            KnopfZeile(modifier = Modifier.fillMaxWidth()) {
                CompactOutlinedButton(
                    onClick = onNavigateToSettings,
                    text = stringResource(R.string.hue_connected_bridge_lights),
                    icon = Icons.Default.Settings
                )

                CompactOutlinedButton(
                    onClick = onTestConnection,
                    text = stringResource(R.string.hue_bridge_test),
                    icon = Icons.Default.FlashOn
                )
            }
        }
    }
}

/**
 * Der Weg zu den Lichtregeln (#255, SPEC E8): ein Satz und der Knopf zur Typ-Liste. Eine Stelle fuer
 * den Hue-Tab (beide Karten) und die Seite „Hue-Einstellungen“ (`HueSettingsScreen`).
 */
@Composable
internal fun LichtregelnWeg(
    onShowShiftConfig: () -> Unit,
    textAlign: TextAlign = TextAlign.Start
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = stringResource(R.string.hue_rules_at_type),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = textAlign,
            modifier = Modifier.fillMaxWidth()
        )
        Button(onClick = onShowShiftConfig, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.hue_rules_open_types))
        }
    }
}

@Composable
internal fun QuickStatItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(
            imageVector = icon,
            // dekorativ: Wert und Beschriftung stehen direkt darunter
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}


@Composable
internal fun ConnectedFeaturesCard(
    onShowShiftConfig: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onTestConnection: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val bridgeManager = remember { HueBridgeConnectionManager.getInstance(context) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp), // Slightly more padding for celebration
            verticalArrangement = Arrangement.spacedBy(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Celebration Header
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    // dekorativ: "🎉 Erfolgreich verbunden!" steht direkt darunter
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.success,
                    modifier = Modifier.size(48.dp) // Bigger celebration icon
                )
                Text(
                    text = stringResource(R.string.hue_setup_done_title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center
                )
            }

            // Feature Description
            Text(
                text = stringResource(R.string.hue_setup_done_body),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            // Quick Start Actions
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = stringResource(R.string.hue_setup_done_next),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center
                )

                LichtregelnWeg(onShowShiftConfig, textAlign = TextAlign.Center)

                // Secondary Actions
                KnopfZeile(modifier = Modifier.fillMaxWidth(), abstand = 12.dp) {
                    CompactOutlinedButton(
                        onClick = onNavigateToSettings,
                        text = stringResource(R.string.hue_connected_bridge_lights),
                        icon = Icons.Default.Settings
                    )

                    CompactOutlinedButton(
                        onClick = {
                            // OPTIMIZATION: Manual health check + refresh lights
                            scope.launch {
                                bridgeManager.forceHealthCheck()
                            }
                            onTestConnection()
                        },
                        text = stringResource(R.string.hue_bridge_test),
                        icon = Icons.Default.Lightbulb
                    )
                }
            }

        }
    }
}
