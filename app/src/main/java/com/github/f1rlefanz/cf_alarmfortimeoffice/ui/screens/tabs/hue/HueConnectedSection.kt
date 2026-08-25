package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.tabs.hue

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
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
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.theme.success
import kotlinx.coroutines.launch

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
    onNavigateToRuleConfig: () -> Unit,
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
                        text = "Bridge verbunden",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "$enabledRulesCount von $rulesCount Regeln aktiv",
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
                    label = "Regeln",
                    value = rulesCount.toString()
                )
                QuickStatItem(
                    icon = Icons.Default.Lightbulb,
                    label = "Lichter",
                    value = lightsCount.toString()
                )
                QuickStatItem(
                    icon = Icons.Default.CheckCircle,
                    label = "Aktiv",
                    value = enabledRulesCount.toString()
                )
            }

            // Action Buttons
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Primary action - Manage rules
                Button(
                    onClick = onNavigateToSettings,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        // dekorativ: die Knopfbeschriftung daneben sagt es bereits
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Regeln verwalten")
                }

                // Secondary actions
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CompactOutlinedButton(
                        onClick = onNavigateToRuleConfig,
                        text = "Neue Regel",
                        icon = Icons.Default.Add,
                        modifier = Modifier.weight(1f)
                    )

                    CompactOutlinedButton(
                        onClick = onTestConnection,
                        text = "Test",
                        icon = Icons.Default.FlashOn,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
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
    onNavigateToRuleConfig: () -> Unit,
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
                    text = "🎉 Erfolgreich verbunden!",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center
                )
            }

            // Feature Description
            Text(
                text = "Ihre Philips Hue Bridge ist jetzt mit der App verbunden. Sie können Lichtregeln für Ihre Alarme erstellen!",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            // Quick Start Actions
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Was möchten Sie als nächstes tun?",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center
                )

                // Primary Action - Create Rule
                Button(
                    onClick = onNavigateToRuleConfig,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        // dekorativ: die Knopfbeschriftung daneben sagt es bereits
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    // Ohne style-Override: titleMedium (16sp) machte die Beschriftung breiter als
                    // den Knopf und trieb sie in die zweite Zeile. Die Standard-Button-Typografie
                    // passt in eine Zeile - und der Knopf ist als einziger ganzbreiter Primär-
                    // Knopf ohnehin schon deutlich genug hervorgehoben.
                    Text("Erste Hue-Regel erstellen")
                }

                // Secondary Actions
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    CompactOutlinedButton(
                        onClick = onNavigateToSettings,
                        text = "Einstellungen",
                        icon = Icons.Default.Settings,
                        modifier = Modifier.weight(1f)
                    )

                    CompactOutlinedButton(
                        onClick = {
                            // OPTIMIZATION: Manual health check + refresh lights
                            scope.launch {
                                bridgeManager.forceHealthCheck()
                            }
                            onTestConnection()
                        },
                        text = "Test",
                        icon = Icons.Default.Lightbulb,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // Help Text
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                )
            ) {
                Text(
                    text = "💡 Tipp: Erstellen Sie für jede Schicht eine eigene Licht-Regel mit verschiedenen Farben und Helligkeiten.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(16.dp)
                )
            }
        }
    }
}
