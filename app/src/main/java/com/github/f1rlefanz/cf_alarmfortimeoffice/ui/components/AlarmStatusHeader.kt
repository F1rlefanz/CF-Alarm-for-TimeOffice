package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessAlarm
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.theme.success
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.theme.warning
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.theme.SpacingConstants
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.AlarmSkipUiState
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.AlarmUiState

/**
 * Icon + Titel + 3-Zweig-Status-Text (aktive Alarme / lädt / keine Alarme), geteilt zwischen
 * Home- und Wecker-Tab. `trailingContent` haengt ein optionales drittes Element (z.B. Chevron)
 * an dieselbe Row an - die aufrufende Karte bestimmt Padding/Klickbarkeit selbst.
 *
 * STILLE SCHICHTEN WERDEN AUSGEWIESEN, NICHT VERSTECKT. Diese Karte behauptet den naechsten
 * Wecker - und behauptete ihn bis v1.29.2 auch dann, wenn der fruehste Eintrag eine stille
 * Schicht (Rufbereitschaft) war, an der die App per Konstruktion stumm bleibt: kein Ton, keine
 * Vibration, kein Weckbildschirm. Genau davor warnt der Kommentar in `ShiftConfigScreen` in
 * Grossbuchstaben ("EINE ANGEZEIGTE WECKZEIT, DIE NIE GESTELLT WIRD, IST DIE GEFAEHRLICHSTE
 * ANZEIGE, DIE EINE WECKER-APP HABEN KANN") - dort hat `isSilent` deshalb ein eigenes Icon,
 * hier fehlte jedes Zeichen. Warum gekennzeichnet und nicht gefiltert wird, steht bei
 * `AlarmUiState.nextAlarmIsSilent`; der zusaetzlich genannte naechste KLINGELNDE Wecker sorgt
 * dafuer, dass der stille Eintrag den echten nicht mehr verdeckt.
 */
@Composable
fun AlarmStatusHeader(
    alarmState: AlarmUiState,
    skipState: AlarmSkipUiState,
    modifier: Modifier = Modifier,
    trailingContent: @Composable (() -> Unit)? = null
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_LARGE),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Default.AccessAlarm,
            // Der Skip-Zustand steht NUR in der Faerbung - kein Text daneben erwaehnt ihn
            // ("N aktive Alarme"/"Keine aktiven Alarme" sagen dazu nichts). Deshalb braucht
            // genau dieser Zweig eine Beschreibung. Die uebrigen Faerbungen sind dekorativ:
            // der Text daneben sagt es bereits - auch die stille Schicht, die deshalb zwar
            // die Gruenfaerbung verliert, aber keine eigene Beschreibung braucht.
            contentDescription = if (skipState.isNextAlarmSkipped) {
                "Nächster Alarm wird übersprungen"
            } else {
                null
            },
            modifier = Modifier.size(SpacingConstants.ICON_SIZE_EXTRA_LARGE),
            tint = when {
                skipState.isNextAlarmSkipped -> MaterialTheme.colorScheme.warning
                // Gruen heisst "es klingelt". Ist der naechste Eintrag stumm, waere das eine
                // Zusage, die die App nicht haelt.
                alarmState.nextAlarmIsSilent -> MaterialTheme.colorScheme.warning
                alarmState.hasActiveAlarms -> MaterialTheme.colorScheme.success
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
        )

        Column(modifier = Modifier.weight(1f)) {
            Text(
                "Alarm-Status",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            if (alarmState.hasActiveAlarms) {
                Text(
                    if (alarmState.silentAlarmCount > 0) {
                        "${alarmState.activeAlarms.size} aktive Alarme, " +
                            "davon ${alarmState.silentAlarmCount} stumm"
                    } else {
                        "${alarmState.activeAlarms.size} aktive Alarme"
                    }
                )
                alarmState.nextAlarmTime?.let { zeit ->
                    if (alarmState.nextAlarmIsSilent) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_SMALL),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.NotificationsOff,
                                // dekorativ: der Text daneben sagt dasselbe in Worten
                                contentDescription = null,
                                modifier = Modifier.size(SpacingConstants.ICON_SIZE_SMALL),
                                tint = MaterialTheme.colorScheme.warning
                            )
                            Text(
                                "Nächster Eintrag: $zeit — stille Schicht: kein Ton, " +
                                    "keine Vibration, kein Weckbildschirm",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.warning
                            )
                        }
                        Text(
                            alarmState.nextRingingAlarmTime
                                ?.let { "Nächster klingelnder Wecker: $it" }
                                ?: "Danach klingelt kein weiterer Wecker.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    } else {
                        Text("Nächster Alarm: $zeit")
                    }
                }
            } else if (alarmState.isLoading) {
                Text(
                    "Wird geladen …",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(
                    "Keine aktiven Alarme",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        trailingContent?.invoke()
    }
}
