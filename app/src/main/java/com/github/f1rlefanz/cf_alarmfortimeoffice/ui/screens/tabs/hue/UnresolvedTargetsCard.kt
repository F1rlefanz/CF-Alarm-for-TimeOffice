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
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Warnkarte fuer Regel-Ziele, die auf DIESER Bridge nicht existieren.
 *
 * Aus `HueTabContent` ausgelagert (reine Verschiebung). Die Beschriftung kommt aus
 * `UnresolvedRuleTarget.label` - der einzigen Beschriftungsquelle fuer nicht zuordenbare Ziele,
 * damit Regel-Liste, Editor und Import-Dialog nicht drei Formulierungen fuehren.
 */
@Composable
internal fun UnresolvedTargetsCard(
    affectedRules: List<String>,
    targetCount: Int,
    onNavigateToSettings: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // dekorativ: die Ueberschrift daneben sagt den Zustand aus
                Icon(
                    Icons.Default.Error,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(28.dp)
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    "$targetCount Regel-Ziel(e) auf dieser Bridge unbekannt",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            Text(
                "Betroffen: ${affectedRules.joinToString()}. Die Lampen stammen von einer anderen " +
                    "Bridge – diese Regeln schalten dafür nichts. Was sich über den Namen " +
                    "wiederfinden ließ, wurde bereits automatisch zugeordnet.",
                style = MaterialTheme.typography.bodyMedium
            )
            Button(onClick = onNavigateToSettings, modifier = Modifier.fillMaxWidth()) {
                Text("Regeln prüfen")
            }
        }
    }
}
