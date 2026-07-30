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
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.SyncProblem
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
 * Bittet einmalig darum, TimeOffice (de.pradtke.timeoffice) von Androids Hintergrund-
 * Einschraenkungen auszunehmen — die App, die den Dienstplan-Kalender liefert, aus dem CFAlarm
 * seine Alarme baut.
 *
 * ANDERS ALS [BatteryOnboardingScreen]/[UnusedAppRestrictionsOnboardingScreen]: hier gibt es
 * keinen eigenen System-Dialog fuer die Akku-Ausnahme eines FREMDEN Packages (nicht dokumentiert,
 * ob Android das erlaubt) und keine geprueft-funktionierende Ein-Klick-Loesung fuer "Nicht
 * verwendete Apps" bei einer anderen App. Ein einziger Knopf fuehrt daher direkt auf TimeOffices
 * eigene App-Info-Seite, wo beide Schalter nebeneinander liegen — der Weg, der am 30.07.2026 live
 * erfolgreich manuell begangen wurde.
 *
 * WARUM DAS UEBERHAUPT NOETIG IST: TimeOffice schreibt den Dienstplan (inkl. Krankschreibungen)
 * lokal in einen Google-Kalender. Bleibt TimeOffice ungenutzt liegen (was fuer eine App, die man
 * nicht taeglich oeffnen muss, der Normalfall ist), pausiert Android sie oder drosselt sie per
 * Akku-Optimierung — der Sync bleibt dann tagelang stehen, ohne dass CFAlarm selbst etwas davon
 * merkt.
 */
@Composable
fun TimeOfficeHealthOnboardingScreen(
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
            Icons.Default.SyncProblem,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            "Auch TimeOffice braucht eine Ausnahme",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            "Dein Dienstplan kommt über TimeOffice in deinen Kalender, aus dem CFAlarm die Alarme " +
                "baut. Pausiert Android TimeOffice im Hintergrund, bleibt dieser Sync stehen — " +
                "auch wenn CFAlarm selbst einwandfrei läuft.",
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
                    "Android öffnet jetzt TimeOffices eigene App-Info-Seite. Schalte dort " +
                        "„Akkunutzung“ auf „Uneingeschränkt“ und „App bei Nichtnutzung " +
                        "pausieren“ aus, dann geh zurück in CFAlarm.",
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
                Text("TimeOffice-Einstellungen öffnen")
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
