package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.runtime.Composable
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.components.PermissionOnboardingScreen

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
    PermissionOnboardingScreen(
        icon = Icons.Default.HourglassEmpty,
        headline = "Noch ein Schritt für zuverlässige Wecker",
        body = "Android pausiert Apps, die eine Weile nicht geöffnet wurden — dabei gehen alle " +
            "bereits gestellten Wecker verloren, ohne jeden Hinweis. CF Alarm ist bewusst so " +
            "gebaut, dass du sie nicht täglich öffnen musst — genau das kann diesen " +
            "Mechanismus auslösen.",
        infoText = "Android öffnet jetzt eine Einstellungsseite (kein einzelner Bestätigungs-" +
            "Dialog wie eben bei der Akku-Freigabe). Schalte dort „App bei Nichtnutzung " +
            "pausieren“ aus und geh zurück in die App.",
        primaryLabel = "Einstellung öffnen",
        onPrimaryAction = onOpenSettings,
        onSkip = onSkip
    )
}
