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
 * Nutzertext der Master-Pause an dieser Stelle. Als Konstante, damit ein Test genau ihn
 * festhalten kann - der Text IST die Zusicherung.
 *
 * Bewusst ohne Fachbegriff ("Master-Pause", "Sync"): der Nutzer hat einen Schalter umgelegt, der
 * "Hintergrunddienste pausieren" heisst, und sucht genau diesen Wortlaut wieder.
 */
internal const val ALARM_STATUS_PAUSIERT_TEXT: String =
    "Alles pausiert — es wird kein Wecker gestellt und keiner klingelt."

/**
 * Der AUSWEG gehoert dazu: die App laeuft aus diesem Zustand NIE von allein heraus (die
 * 6h-Wartung ist mit abgeschaltet). Nennt die Karten-/Schalterbeschriftung wortgleich, nie eine
 * Position.
 */
internal const val ALARM_STATUS_PAUSIERT_AUSWEG: String =
    "Zum Fortsetzen im Einstellungen-Tab den Schalter \"Hintergrunddienste pausieren\" ausschalten."

/** Die Zweige des Status-Texts - siehe [alarmStatusZustand]. */
internal enum class AlarmStatusZustand { PAUSIERT, AKTIVE_ALARME, LAEDT, KEINE_ALARME }

/**
 * PURE, TESTBAR: welcher Zweig des Alarm-Status gilt.
 *
 * DIE PAUSE STEHT VORNE, UND ZWAR AUCH VOR [AlarmUiState.hasActiveAlarms]. Die Master-Pause
 * loescht ALLE Alarme; ein noch angezeigter Bestand ist danach ein veralteter Zustand, kein
 * Versprechen. "3 aktive Alarme" waere dann die gefaehrlichste Anzeige, die eine Wecker-App haben
 * kann - eine Weckzeit, die nie gestellt wird. Aus demselben Grund liegt sie vor dem
 * Lade-Zustand: pausiert ist pausiert, egal ob gerade nachgeladen wird.
 */
internal fun alarmStatusZustand(
    masterPausePaused: Boolean,
    hasActiveAlarms: Boolean,
    isLoading: Boolean
): AlarmStatusZustand = when {
    masterPausePaused -> AlarmStatusZustand.PAUSIERT
    hasActiveAlarms -> AlarmStatusZustand.AKTIVE_ALARME
    isLoading -> AlarmStatusZustand.LAEDT
    else -> AlarmStatusZustand.KEINE_ALARME
}

/**
 * Icon + Titel + 4-Zweig-Status-Text (pausiert / aktive Alarme / lädt / keine Alarme), geteilt
 * zwischen Home- und Wecker-Tab. `trailingContent` haengt ein optionales drittes Element (z.B. Chevron)
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
 *
 * @param masterPausePaused sind ALLE Hintergrunddienste pausiert? Dann ist "Keine aktiven Alarme"
 *   nicht die ganze Wahrheit: der Zustand hat eine Ursache, und ohne sie schliesst der Nutzer,
 *   die Wecker entstuenden schon noch. Reine Anzeige - dieser Wert wird hier nur gelesen.
 */
@Composable
fun AlarmStatusHeader(
    alarmState: AlarmUiState,
    skipState: AlarmSkipUiState,
    modifier: Modifier = Modifier,
    masterPausePaused: Boolean,
    trailingContent: @Composable (() -> Unit)? = null
) {
    val zustand = alarmStatusZustand(
        masterPausePaused = masterPausePaused,
        hasActiveAlarms = alarmState.hasActiveAlarms,
        isLoading = alarmState.isLoading
    )

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
                // Vor allem anderen: pausiert ist weder gruen ("es klingelt") noch neutral-grau.
                zustand == AlarmStatusZustand.PAUSIERT -> MaterialTheme.colorScheme.warning
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

            when (zustand) {
                AlarmStatusZustand.PAUSIERT -> {
                    // "Keine aktiven Alarme" in Grau war hier bis v1.29.x der einzige Hinweis - ein
                    // Zustand ohne Ursache, aus dem der Nutzer den falschen Schluss zieht ("kommt
                    // schon noch"). Ursache UND Ausweg, sonst findet er die Pause nur zufaellig
                    // wieder.
                    Text(
                        ALARM_STATUS_PAUSIERT_TEXT,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.warning
                    )
                    Text(
                        ALARM_STATUS_PAUSIERT_AUSWEG,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                AlarmStatusZustand.AKTIVE_ALARME -> {
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
                }
                AlarmStatusZustand.LAEDT -> {
                    Text(
                        "Wird geladen …",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                AlarmStatusZustand.KEINE_ALARME -> {
                    Text(
                        "Keine aktiven Alarme",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        trailingContent?.invoke()
    }
}
