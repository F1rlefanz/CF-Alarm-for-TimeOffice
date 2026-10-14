package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessAlarm
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
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.AlarmInfo
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.reinerSchichtname
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.theme.warning
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.AlarmSkipUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.business.DateTimeFormats
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.theme.SpacingConstants
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.AlarmSkipUiState
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.AlarmUiState
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

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
    "Zum Fortsetzen in den Einstellungen den Schalter \"Hintergrunddienste pausieren\" ausschalten."

/** Die Grundzweige der Karte - siehe [alarmStatusZustand]. */
internal enum class AlarmStatusZustand { PAUSIERT, AKTIVE_ALARME, LAEDT, KEINE_ALARME }

/**
 * PURE, TESTBAR: welcher Grundzweig der Karte "Nächster Wecker" gilt.
 *
 * DIE PAUSE STEHT VORNE, UND ZWAR AUCH VOR [AlarmUiState.hasActiveAlarms]. Die Master-Pause
 * loescht ALLE Alarme; ein noch angezeigter Bestand ist danach ein veralteter Zustand, kein
 * Versprechen. Eine Weckzeit, die nie gestellt wird, waere die gefaehrlichste Anzeige, die eine
 * Wecker-App haben kann. Aus demselben Grund liegt sie vor dem Lade-Zustand: pausiert ist
 * pausiert, egal ob gerade nachgeladen wird.
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
 * Die Alarm-Karte der Uebersicht (#139, Spec P4). Sie beantwortet zuerst EINE Frage: wann klingelt
 * es als Naechstes, und wofuer? Bis 1.49 stand hier "N aktive Alarme" - eine Zaehlung, aus der
 * niemand ablesen konnte, ob morgen um 05:30 oder um 14:30 geweckt wird.
 *
 * Alles, was die Karte zeigt, kommt aus VORHANDENEN Feldern von [AlarmUiState]: der naechste
 * klingelnde Wecker ist [AlarmUiState.nextRingingAlarmTriggerTime] - dieselbe Auswahl wie das Ziel
 * von "Nicht wecken" (`AlarmSkipUseCase.naechsterKlingelnderWecker` in `berechneWeckerAnzeige`,
 * dort schon ohne den uebersprungenen Eintrag). Hier wird NICHT neu ausgewaehlt: eine zweite
 * Auswahl auf dem rohen Bestand koennte einen anderen Wecker nennen als die Wecker-Liste.
 */
internal sealed interface NaechsterWeckerAnzeige {
    /** Master-Pause. Vorrang vor allem anderen, siehe [alarmStatusZustand]. */
    data object Pausiert : NaechsterWeckerAnzeige

    data object Laedt : NaechsterWeckerAnzeige

    /**
     * Kein Wecker, der klingelt. [stilleEintraege] > 0 heisst: es STEHT etwas im Bestand, aber nur
     * stille Zeitanker - das darf nicht aussehen wie "nichts gestellt" (gekennzeichnet statt
     * versteckt, siehe `AlarmUiState.nextAlarmIsSilent`).
     */
    data class KeinWecker(val stilleEintraege: Int) : NaechsterWeckerAnzeige

    data class Naechster(
        /** "Morgen, Mi 14.10." - siehe [weckerTagText]. */
        val tag: String,
        /** "05:30" */
        val uhrzeit: String,
        /** Schicht, Dienstzeit, Herkunft - siehe [schichtZeile]; `null`, wenn nichts bekannt ist. */
        val schicht: String?
    ) : NaechsterWeckerAnzeige
}

/**
 * PURE, TESTBAR: Was die Karte "Nächster Wecker" zeigt.
 *
 * Die Zweigreihenfolge ist die von [alarmStatusZustand] (Pause vor Bestand vor Laden) - sie ist
 * dort begruendet und getestet, hier wird sie nicht nachgebaut: ein noch angezeigter Bestand ist
 * waehrend der Pause ein veralteter Zustand, kein Versprechen.
 *
 * STILLE EINTRAEGE ZAEHLEN NICHT als naechster Wecker: ein Rufdienst-Zeitanker um 05:00 klingelt
 * nicht, "Nächster Wecker 05:00" waere eine Zusage, die die App nicht haelt.
 *
 * @param jetzt nur fuer die Zaehlung der noch anstehenden stillen Eintraege.
 */
internal fun naechsterWeckerAnzeige(
    masterPausePaused: Boolean,
    activeAlarms: List<AlarmInfo>,
    hasActiveAlarms: Boolean,
    isLoading: Boolean,
    naechsterKlingelnderZeitpunkt: Long?,
    jetzt: Long,
    zone: ZoneId,
    heute: LocalDate = LocalDate.now(zone)
): NaechsterWeckerAnzeige = when (alarmStatusZustand(masterPausePaused, hasActiveAlarms, isLoading)) {
    AlarmStatusZustand.PAUSIERT -> NaechsterWeckerAnzeige.Pausiert
    AlarmStatusZustand.LAEDT -> NaechsterWeckerAnzeige.Laedt
    AlarmStatusZustand.KEINE_ALARME -> NaechsterWeckerAnzeige.KeinWecker(stilleEintraege = 0)
    AlarmStatusZustand.AKTIVE_ALARME -> if (naechsterKlingelnderZeitpunkt == null) {
        NaechsterWeckerAnzeige.KeinWecker(
            stilleEintraege = activeAlarms.count { it.isSilent && it.triggerTime > jetzt }
        )
    } else {
        // Der Eintrag zum Zeitpunkt - nur fuer die Schicht. Fehlt er (Bestand und Anzeige aus
        // verschiedenen Emissionen), steht trotzdem die Weckzeit da, nur ohne Schicht: die Zeit
        // ist die Zusage, die Schicht nur Erlaeuterung.
        val alarm = activeAlarms.firstOrNull {
            it.triggerTime == naechsterKlingelnderZeitpunkt && !it.isSilent
        }
        val wann = Instant.ofEpochMilli(naechsterKlingelnderZeitpunkt).atZone(zone)
        NaechsterWeckerAnzeige.Naechster(
            tag = weckerTagText(wann.toLocalDate(), heute),
            uhrzeit = DateTimeFormatter.ofPattern(DateTimeFormats.TIME_ONLY).format(wann),
            schicht = alarm?.let { schichtZeile(it, zone) }
        )
    }
}

/** Wochentage fest verdrahtet: `Locale.GERMAN` liefert je nach JDK/CLDR "Mi." mit Punkt. */
private val WOCHENTAGE = listOf("Mo", "Di", "Mi", "Do", "Fr", "Sa", "So")

/**
 * Geschuetztes Leerzeichen. Innerhalb eines Teils ("Mi 14.10.", "von dir gestellt") darf die Zeile
 * bei 320 dp und Schrift 1,3 nicht umbrechen - nur HINTER einem Trenner.
 */
private const val NBSP = ' '

/** Trenner zwischen den Teilen einer Zeile: Umbruch nur danach. */
internal const val TRENNER: String = "$NBSP· "

/**
 * PURE, TESTBAR: "Heute, Mi 14.10." / "Morgen, Do 15.10." / "Fr 16.10.".
 *
 * "Heute"/"Morgen" zuerst, weil das die Frage ist, die man morgens beim Blick auf die Karte hat;
 * das Datum steht trotzdem dabei, damit die Zeile auch kurz nach Mitternacht eindeutig bleibt.
 */
internal fun weckerTagText(datum: LocalDate, heute: LocalDate): String {
    val kurz = "${WOCHENTAGE[datum.dayOfWeek.value - 1]}$NBSP" +
        DateTimeFormatter.ofPattern(DateTimeFormats.DATE_SHORT).format(datum) + "."
    return when (datum) {
        heute -> "Heute, $kurz"
        heute.plusDays(1) -> "Morgen, $kurz"
        else -> kurz
    }
}

/**
 * PURE, TESTBAR: "Frühdienst · 06:00–14:12" bzw. "Frühdienst · von dir gestellt".
 *
 * Der Name ohne "(Manuell)" ([reinerSchichtname]) - dass der Wecker von Hand gestellt ist, sagt
 * der eigene Teil "von dir gestellt" (dieselbe Kennzeichnung wie in der Wecker-Liste,
 * [AlarmSkipUseCase.isManualAlarm]). Die Dienstzeit nur, wenn sie bekannt ist (0 = unbekannt, bei
 * manuellen Weckern der Normalfall) - eine erfundene Zeit waere schlechter als keine.
 */
internal fun schichtZeile(alarm: AlarmInfo, zone: ZoneId): String? {
    val uhr = DateTimeFormatter.ofPattern(DateTimeFormats.TIME_ONLY)
    fun zeit(millis: Long) = uhr.format(Instant.ofEpochMilli(millis).atZone(zone))
    val dienstzeit = when {
        alarm.shiftStartTime != 0L && alarm.shiftEndTime != 0L ->
            "${zeit(alarm.shiftStartTime)}–${zeit(alarm.shiftEndTime)}"
        alarm.shiftStartTime != 0L -> "ab$NBSP${zeit(alarm.shiftStartTime)}"
        else -> null
    }
    val teile = listOfNotNull(
        reinerSchichtname(alarm.shiftName).trim().takeIf { it.isNotEmpty() },
        dienstzeit,
        "von${NBSP}dir${NBSP}gestellt".takeIf { AlarmSkipUseCase.isManualAlarm(alarm) }
    )
    return teile.takeIf { it.isNotEmpty() }?.joinToString(TRENNER)
}

/** Text fuer [NaechsterWeckerAnzeige.KeinWecker]. */
internal fun keinWeckerText(stilleEintraege: Int): String = when (stilleEintraege) {
    0 -> "Kein Wecker gestellt."
    1 -> "Kein Wecker, der klingelt – nur 1 stiller Eintrag (kein Ton, nur Zeitanker)."
    else -> "Kein Wecker, der klingelt – nur $stilleEintraege stille Einträge (kein Ton, nur Zeitanker)."
}

internal const val AUTOMATIK_AUS_ZUSATZ: String =
    "Automatische Alarme sind aus – aus dem Kalender wird kein Wecker gestellt."

/**
 * Nennt die Beschriftungen WORTGLEICH (Textknopf dieser Karte, Knopf in der Wecker-Liste) - der
 * uebersprungene Wecker ist im Bestand geloescht, die Karte zeigt deshalb schon den FOLGENDEN.
 * Ohne den Satz saehe der Nutzer nur eine "falsche" Weckzeit.
 */
internal const val NICHT_WECKEN_AKTIV_ZUSATZ: String =
    "Ein Wecker ist auf „Nicht wecken“ gesetzt. Zurücknehmen unter „Alle gestellten Wecker“ mit " +
        "„Doch wecken“."

/** Beschriftung des Textknopfs - dieselbe, auf die [NICHT_WECKEN_AKTIV_ZUSATZ] verweist. */
internal const val ALLE_GESTELLTEN_WECKER: String = "Alle gestellten Wecker"

/**
 * PURE, TESTBAR: Der HOECHSTENS EINE Zusatz unter der Karte.
 *
 * Die Pause bringt ihren Hinweis samt Ausweg im eigenen Zweig mit - daneben stuende sonst ein
 * zweiter, der offenlaesst, welcher wirkt. Danach "Automatik aus" vor dem Skip: der umfassendere
 * Zustand zuerst (dieselbe Regel wie `noShiftExplanation`).
 */
internal fun naechsterWeckerZusatz(
    masterPausePaused: Boolean,
    autoAlarmEnabled: Boolean,
    nichtWeckenAktiv: Boolean
): String? = when {
    masterPausePaused -> null
    !autoAlarmEnabled -> AUTOMATIK_AUS_ZUSATZ
    nichtWeckenAktiv -> NICHT_WECKEN_AKTIV_ZUSATZ
    else -> null
}

/**
 * Die Karte selbst. Die GANZE Karte springt in den Wecker-Tab; "Alle gestellten Wecker →" ist die
 * Beschriftung dieses einen Ziels, kein zweiter Knopf (zwei Ziele fuer eine Handlung waeren eins
 * zu viel). Bewusst KEIN "Nicht wecken" hier: eine Zeile, die das Ziel eines Knopfs nennt, muss
 * beim Tippen nachgerechnet werden - das macht die Wecker-Liste, nicht die Uebersicht.
 *
 * @param masterPausePaused ohne Default (sicherheitsrelevanter Anzeigeparameter, ui-Skill).
 * @param autoAlarmEnabled ohne Default, aus demselben Grund.
 */
@Composable
fun NaechsterWeckerKarte(
    alarmState: AlarmUiState,
    skipState: AlarmSkipUiState,
    masterPausePaused: Boolean,
    autoAlarmEnabled: Boolean,
    onAlleWecker: () -> Unit,
    modifier: Modifier = Modifier
) {
    val zone = ZoneId.systemDefault()
    val anzeige = naechsterWeckerAnzeige(
        masterPausePaused = masterPausePaused,
        activeAlarms = alarmState.activeAlarms,
        hasActiveAlarms = alarmState.hasActiveAlarms,
        isLoading = alarmState.isLoading,
        naechsterKlingelnderZeitpunkt = alarmState.nextRingingAlarmTriggerTime,
        jetzt = System.currentTimeMillis(),
        zone = zone
    )
    val zusatz = naechsterWeckerZusatz(
        masterPausePaused = masterPausePaused,
        autoAlarmEnabled = autoAlarmEnabled,
        nichtWeckenAktiv = skipState.isNextAlarmSkipped
    )

    Card(
        modifier = modifier.fillMaxWidth(),
        onClick = onAlleWecker,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(SpacingConstants.PADDING_CARD),
            horizontalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_LARGE),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                Icons.Default.AccessAlarm,
                // dekorativ: jeder Zustand steht daneben in Worten
                contentDescription = null,
                modifier = Modifier.size(SpacingConstants.ICON_SIZE_EXTRA_LARGE),
                tint = when (anzeige) {
                    is NaechsterWeckerAnzeige.Pausiert -> MaterialTheme.colorScheme.warning
                    is NaechsterWeckerAnzeige.Naechster -> MaterialTheme.colorScheme.primary
                    is NaechsterWeckerAnzeige.KeinWecker ->
                        if (anzeige.stilleEintraege > 0) MaterialTheme.colorScheme.warning
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    is NaechsterWeckerAnzeige.Laedt -> MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    "Nächster Wecker",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                when (anzeige) {
                    is NaechsterWeckerAnzeige.Pausiert -> {
                        // Ursache UND Ausweg - die App laeuft aus der Pause nie von allein heraus.
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
                    is NaechsterWeckerAnzeige.Laedt -> Text(
                        "Wird geladen …",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    is NaechsterWeckerAnzeige.KeinWecker -> Text(
                        keinWeckerText(anzeige.stilleEintraege),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (anzeige.stilleEintraege > 0) MaterialTheme.colorScheme.warning
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    is NaechsterWeckerAnzeige.Naechster -> {
                        Text(
                            anzeige.tag,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            anzeige.uhrzeit,
                            style = MaterialTheme.typography.displaySmall,
                            fontWeight = FontWeight.Bold
                        )
                        anzeige.schicht?.let {
                            Text(it, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
                zusatz?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.warning
                    )
                }
                Text(
                    "$ALLE_GESTELLTEN_WECKER →",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = SpacingConstants.SPACING_EXTRA_SMALL)
                )
            }
        }
    }
}
