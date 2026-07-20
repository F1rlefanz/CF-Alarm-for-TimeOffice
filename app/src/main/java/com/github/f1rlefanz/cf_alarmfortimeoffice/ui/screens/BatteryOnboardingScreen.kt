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
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
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
 * Battery Onboarding Screen
 *
 * Bittet um die Akku-Freigabe, ohne die Android die App im Hintergrund einfrieren darf.
 *
 * WAS HIER FRÜHER STAND — UND WARUM ES WEG IST: Der Screen zeigte eine animierte Vier-Schritt-
 * Anleitung ("Einstellungen öffnen sich" → "CF Alarm in Liste finden" → "App antippen" →
 * "Uneingeschränkt wählen") und darunter "Die App öffnet gleich die Android-Einstellungen".
 * Nichts davon passiert. [MainScreen] feuert `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` mit
 * `package:`-Data — das ist Androids **Systemdialog** ("Zulassen, dass die App immer im
 * Hintergrund läuft?"), ein einziger Tipp, keine Einstellungen, keine Liste. Der Screen
 * beschrieb also einen Ablauf, den der Nutzer nie zu sehen bekommt, und der Knopf hiess
 * "Zu Einstellungen", obwohl er nirgendwohin führt.
 *
 * (Nur der Fallback in [MainScreen] — wenn der Dialog-Intent wirft — landet tatsächlich in den
 * Einstellungen. Ein Ausnahmefall, für den man den Regelfall nicht falsch beschriften sollte.)
 *
 * TONALITÄT: Der Kernpunkt steht genau EINMAL und konkret — bei einer Wecker-App ist der Einsatz
 * nicht "Background-Jobs werden gestoppt", sondern "der Wecker bleibt still". Wer mehr wissen
 * will, tippt auf "Warum ist das nötig?"; das ist gestaffelte Auskunft, keine dritte Wiederholung.
 */
@Composable
fun BatteryOnboardingScreen(
    onExplain: () -> Unit,
    onRequestExemption: () -> Unit,
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
            Icons.Default.BatteryChargingFull,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            "Damit der Wecker klingelt",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Der Kernpunkt - einmal, konkret, mit dem echten Einsatz.
        Text(
            "Android darf Apps im Hintergrund einfrieren. Trifft es CF Alarm, holt die App keine " +
                "neuen Schichten mehr ab — und der Wecker bleibt still.",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(32.dp))

        // Was gleich WIRKLICH passiert.
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
                    "Android fragt dich gleich, ob die App immer im Hintergrund laufen darf. " +
                        "Tippe auf „Zulassen“.",
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
                onClick = onRequestExemption,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Akku-Freigabe erteilen")
            }

            TextButton(
                onClick = onExplain,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Warum ist das nötig?")
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
