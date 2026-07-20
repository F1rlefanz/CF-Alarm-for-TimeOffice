package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Bittet darum, "App bei Nichtnutzung pausieren" abzuschalten.
 *
 * ANDERS ALS [BatteryOnboardingScreen]: Dort loest der Knopf einen einzigen Bestaetigungs-Dialog
 * aus ("Zulassen?"). Hier oeffnet Android eine echte, mehrstufige Einstellungsseite - der Nutzer
 * muss dort selbst den Schalter umlegen und zurueckkommen. Der Text darf das nicht verschweigen
 * (dieselbe Regel wie beim Akku-Screen: keinen Ablauf versprechen, den es nicht gibt - hier nur
 * umgekehrt, siehe CLAUDE.md "UI-Texte").
 *
 * WARUM DAS UEBERHAUPT NOETIG IST: Live am 20.07.2026 gegen ein echtes Fairphone 6 bewiesen -
 * dieser Schalter killt die App per Force-Stop, sobald sie eine Weile nicht geoeffnet wurde, und
 * loescht dabei lautlos alle gesetzten Wecker-Alarme. Fuer eine App, deren Sinn ist, dass man sie
 * NICHT taeglich oeffnen muss, ist genau das der Normalfall, nicht die Ausnahme.
 */
@Composable
fun UnusedAppRestrictionsOnboardingScreen(
    onOpenSettings: () -> Unit,
    onSkip: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Default.HourglassEmpty,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            "Noch ein Schritt für zuverlässige Wecker",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            "Android pausiert Apps, die eine Weile nicht geöffnet wurden — dabei gehen alle " +
                "bereits gestellten Wecker verloren, ohne jeden Hinweis. CF Alarm ist bewusst so " +
                "gebaut, dass du sie nicht täglich öffnen musst — genau das kann diesen " +
                "Mechanismus auslösen.",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(32.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Info,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    "Android öffnet jetzt eine Einstellungsseite (kein einzelner Bestätigungs-" +
                        "Dialog wie eben bei der Akku-Freigabe). Schalte dort „App bei " +
                        "Nichtnutzung pausieren“ aus und geh zurück in die App.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(
                onClick = onOpenSettings,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Einstellung öffnen")
            }

            TextButton(
                onClick = onSkip,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Später")
            }
        }
    }
}
