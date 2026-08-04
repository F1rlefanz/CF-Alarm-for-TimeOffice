package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SyncProblem
import androidx.compose.runtime.Composable
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.components.PermissionOnboardingScreen

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
    PermissionOnboardingScreen(
        icon = Icons.Default.SyncProblem,
        headline = "Auch TimeOffice braucht eine Ausnahme",
        body = "Dein Dienstplan kommt über TimeOffice in deinen Kalender, aus dem CFAlarm die " +
            "Alarme baut. Pausiert Android TimeOffice im Hintergrund, bleibt dieser Sync stehen " +
            "— auch wenn CFAlarm selbst einwandfrei läuft.",
        infoText = "Android öffnet jetzt TimeOffices eigene App-Info-Seite. Schalte dort " +
            "„Akkunutzung“ auf „Uneingeschränkt“ und „App bei Nichtnutzung pausieren“ aus, " +
            "dann geh zurück in CFAlarm.",
        primaryLabel = "TimeOffice-Einstellungen öffnen",
        onPrimaryAction = onOpenSettings,
        onSkip = onSkip
    )
}
