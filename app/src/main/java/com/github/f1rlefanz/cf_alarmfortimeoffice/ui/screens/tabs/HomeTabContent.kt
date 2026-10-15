package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.tabs

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Work
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.github.f1rlefanz.cf_alarmfortimeoffice.alarm.KalenderVorausschauPrefs
import com.github.f1rlefanz.cf_alarmfortimeoffice.freietage.Ausfall
import com.github.f1rlefanz.cf_alarmfortimeoffice.freietage.AusfallQuelle
import com.github.f1rlefanz.cf_alarmfortimeoffice.freietage.laufenderAusfall
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftDefinition
import com.github.f1rlefanz.cf_alarmfortimeoffice.shift.LetzterSchichtStand
import com.github.f1rlefanz.cf_alarmfortimeoffice.shift.ShiftSpan
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.components.NaechsterWeckerKarte
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.components.kurzesDatum
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.components.rememberNaechsterWecker
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.components.schichtTerminText
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.tabs.wecker.KRANK_KALENDER_ZURUECK
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.theme.warning
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.business.DateTimeFormats
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.theme.SpacingConstants
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.AlarmSkipUiState
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.AlarmUiState
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.CalendarUiState
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.ShiftUiState
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.TagFreigabeUiState
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.sichtbarerFreigabeHinweis
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * PURE, TESTBAR: Warum die Karte "Naechste Schicht" gerade keine Schicht anzeigen kann.
 *
 * Der else-Zweig zeigte fuer mindestens sechs voellig verschiedene Zustaende denselben,
 * ursachenlosen Satz "Keine Schicht erkannt" - ein Nutzer konnte daraus nicht ablesen, ob der
 * Kalender, die Anmeldung oder seine Erkennungsmuster das Problem sind. Alle unterscheidenden
 * Felder liegen im Composable ohnehin schon als Parameter vor.
 */
internal enum class NoShiftReason {
    NO_CALENDAR_SELECTED,
    AUTHORIZATION_LOST,
    KALENDER_NICHT_ERREICHBAR,
    CALENDAR_UNAVAILABLE,
    LOAD_ERROR,
    NO_EVENTS,
    SHIFT_CONFIG_NOT_LOADED,
    NO_SHIFT_TYPES,
    NO_PATTERN_MATCH,
    /**
     * #142: ALLE Termine sind Krankmeldungen. Die Engine laesst Krank-Treffer bewusst aus den
     * Schichten heraus - ohne diesen Fall hiesse es "kein Erkennungsmuster passt (im Kalender
     * steht: K)", und der Rat, "K" als Muster einzutragen, braechte die Wecker an Krank-Tagen
     * zurueck.
     */
    NUR_KRANKMELDUNG,
    ONLY_PAST_SHIFTS
}

/**
 * #142: Ist [titel] eine Krankmeldung? Dieselbe Frage und derselbe Vorrang wie in der Engine
 * (`ShiftRecognitionEngine`: Krank vor Schicht, nur aktive Typen), damit die Karte nicht anders
 * zaehlt als der Sync.
 */
internal fun istKrankTermin(titel: String, definitionen: List<ShiftDefinition>): Boolean =
    definitionen.any { it.isEnabled && it.isSickDay && it.matchesKeywords(titel) }

/**
 * PURE, TESTBAR: Leitet den Grund aus den vorhandenen UI-Zustaenden ab.
 *
 * Reihenfolge = von der grundlegendsten Ursache zur speziellsten: Ohne Kalender ist alles andere
 * belanglos, ohne Autorisierung koennen keine Termine kommen, usw. Der letzte Fall ist bewusst
 * kein "unbekannt": Sind Schichten erkannt, aber keine kuenftige dabei, liegen sie in der
 * Vergangenheit ([ShiftUiState.upcomingShift] filtert auf `startTime.isAfter(now)`).
 */
internal fun noShiftReason(
    hasSelectedCalendars: Boolean,
    calendarAuthorizationValid: Boolean,
    kalenderNichtErreichbar: Boolean,
    unavailableCalendarCount: Int,
    errorMessage: String?,
    eventCount: Int,
    shiftConfigLoaded: Boolean,
    enabledShiftTypeCount: Int,
    recognizedShiftCount: Int,
    krankTerminCount: Int = 0
): NoShiftReason = when {
    !hasSelectedCalendars -> NoShiftReason.NO_CALENDAR_SELECTED
    !calendarAuthorizationValid -> NoShiftReason.AUTHORIZATION_LOST
    // VOR Fehlermeldung und leerer Terminliste: offline ist die Liste zwangslaeufig leer, und
    // sobald die Snackbar den Fehler geraeumt hat, hiesse es sonst "im gewaehlten Kalender steht
    // nichts" - eine Behauptung ueber den Dienstplan, die niemand pruefen konnte.
    kalenderNichtErreichbar -> NoShiftReason.KALENDER_NICHT_ERREICHBAR
    // NACH der Autorisierung, VOR allem Weiteren: ist ein Kalender nicht abrufbar, halten die
    // Vollstaendigkeits-Sperren jeden Alarm-Sync an. Jede Ursache darunter (keine Termine, kein
    // Muster) waere dann eine Folge davon, keine eigene Erklaerung - und wuerde den Nutzer an der
    // falschen Stelle suchen lassen.
    unavailableCalendarCount > 0 -> NoShiftReason.CALENDAR_UNAVAILABLE
    !errorMessage.isNullOrBlank() -> NoShiftReason.LOAD_ERROR
    eventCount == 0 -> NoShiftReason.NO_EVENTS
    !shiftConfigLoaded -> NoShiftReason.SHIFT_CONFIG_NOT_LOADED
    enabledShiftTypeCount == 0 -> NoShiftReason.NO_SHIFT_TYPES
    // #142: nur wenn WIRKLICH jeder Termin eine Krankmeldung ist - steht daneben ein
    // unerkannter Termin, bleibt die Frage nach dem Muster die richtige.
    recognizedShiftCount == 0 && krankTerminCount > 0 && krankTerminCount >= eventCount ->
        NoShiftReason.NUR_KRANKMELDUNG
    recognizedShiftCount == 0 -> NoShiftReason.NO_PATTERN_MATCH
    else -> NoShiftReason.ONLY_PAST_SHIFTS
}

/**
 * Der Zusatz fuer die Master-Pause. Bewusst kurz und ohne den Weg zurueck: den nennt die
 * Karte "Nächster Wecker" unmittelbar darunter (`ALARM_STATUS_PAUSIERT_AUSWEG`) - derselbe Zustand
 * soll nicht zweimal ausfuehrlich auf demselben Bildschirm stehen. "Darunter" haelt, obwohl die
 * Uebersicht die Karten seit #141 nach Zeit ordnet: pausiert nennt die Wecker-Karte keinen
 * Zeitpunkt, und dann bleibt die Schicht oben ([uebersichtReihenfolge]).
 */
internal const val NO_SHIFT_HINWEIS_PAUSIERT: String =
    "Hinweis: Alles ist pausiert — es wird kein Wecker gestellt."

/**
 * PURE, TESTBAR: Darf ein Text "die gestellten Wecker bleiben" sagen?
 *
 * Nur, wenn welche gestellt sind. Bei Master-Pause raeumt `syncAlarms()` alle Wecker, bei
 * ausgeschalteter Automatik ebenso - der Satz stand trotzdem fest in drei Offline-Texten
 * (Karte "Naechste Schicht", Karte "Kalender-Events", System-Status "Kalender") und widersprach
 * auf derselben Karte dem Pausenhinweis (am Emulator gesehen, 01.10.2026). EINE Stelle, damit
 * ein vierter Text nicht wieder fest verdrahtet wird.
 */
internal fun gestellteWeckerBleiben(masterPausePaused: Boolean, autoAlarmEnabled: Boolean): Boolean =
    !masterPausePaused && autoAlarmEnabled

/**
 * Karte "Kalender-Events", wenn ALLE ausgewaehlten Kalender fehlen (geloescht, nicht mehr
 * freigegeben). Bis v1.43.5 hiess das "Kalender-Autorisierung verloren" samt "Kalender-Zugriff
 * erneuern" - ein Knopf, der an einem geloeschten Kalender nichts aendert. Der Ausweg ("Aus
 * Auswahl entfernen", Rueckfrage) steht im System-Status; hier nur WAS, FOLGE, WOHIN. Einen NAMEN
 * nennt die Karte dort nur, solange Google den Kalender noch fuehrt - einen geloeschten kennt sie
 * nur als Anzahl, und genau das ist hier der Normalfall.
 * Als Konstanten, damit ein Test die Nennung von Ort und Folge festhalten kann.
 */
internal const val KALENDER_NICHT_GEFUNDEN_TITEL: String = "Kalender nicht gefunden"
internal const val KALENDER_NICHT_GEFUNDEN_TEXT: String =
    "Google findet keinen ausgewählten Kalender mehr – gelöscht oder nicht mehr freigegeben? " +
        "Solange entstehen keine neuen Wecker, die gestellten bleiben. Näheres im System-Status " +
        "unter \"Kalender\"."

/**
 * Wo die Schichttypen eingerichtet werden - EINE Stelle fuer beide Saetze der Karte "Nächste
 * Schicht", damit ein kuenftiger Umzug des Knopfs nicht einen davon vergisst (genau das waere bei
 * #139 sonst passiert: zwei Texte, ein Ort).
 */
internal const val SCHICHTTYPEN_ORT: String = "in den Einstellungen unter „Schichttypen verwalten“"

/**
 * PURE, TESTBAR: Formuliert Grund + Handlungsschritt.
 *
 * Bei [NoShiftReason.NO_PATTERN_MATCH] werden die tatsaechlich geladenen Termintitel genannt - das
 * ist der Fall, in dem der Nutzer seine Stichwoerter mit dem vergleichen muss, was im Kalender
 * wirklich steht (der gemeldete Konfigurationsfall eines Kollegen auf einer anderen Station).
 *
 * Der Hinweis auf ausgeschaltete Automatik-Alarme ist bewusst ein ZUSATZ und kein eigener Grund:
 * Die Schichterkennung laeuft unabhaengig von `autoAlarmEnabled` weiter (ShiftViewModel
 * .observeCalendarEvents), der Schalter erklaert also nie, warum keine Schicht erkannt wurde.
 * Genauso die Master-Pause: auch sie haelt die Erkennung nicht an, sondern nur alles, was danach
 * kaeme.
 *
 * ES STEHT IMMER HOECHSTENS EIN ZUSATZ DA, und die Master-Pause hat Vorrang: sie ist der
 * umfassendere Zustand (sie schaltet zusaetzlich Dimmer, "Nicht stoeren", Hue und die 6h-Wartung
 * ab), und zwei Hinweise nebeneinander liessen offen, welcher der wirksame ist.
 *
 * [vorausschauTage] ist die eingestellte Kalender-Vorausschau (#51) - der Text nennt das Fenster,
 * das wirklich gelesen wurde, nicht ein fest verdrahtetes "14".
 */
internal fun noShiftExplanation(
    reason: NoShiftReason,
    errorMessage: String? = null,
    sampleEventTitles: List<String> = emptyList(),
    autoAlarmEnabled: Boolean = true,
    masterPausePaused: Boolean = false,
    vorausschauTage: Int = KalenderVorausschauPrefs.STANDARD_TAGE
): String {
    val core = when (reason) {
        NoShiftReason.NO_CALENDAR_SELECTED ->
            // "Kalender wählen" ist der Knopf, den der Status-Tab in genau diesem Zustand anbietet
            // (StatusTabContent, Karte "Kalender") - dorthin zeigen, nicht auf einen erfundenen Weg.
            "Noch kein Kalender ausgewählt — im System-Status unter \"Kalender\" den Dienstplan-Kalender wählen."
        NoShiftReason.AUTHORIZATION_LOST ->
            // KEINE Positionsangabe ("in der Karte darunter"): direkt unter dieser Karte liegt die
            // Karte "Nächster Wecker", die stattdessen in den Wecker-Tab springt - der Erneuern-Knopf
            // sitzt erst eine Karte weiter (ohne Schicht kein Zeitpunkt, die Reihenfolge bleibt
            // also fest, siehe uebersichtReihenfolge). Wie bei den anderen Gruenden die Beschriftung nennen,
            // die im Screen wirklich steht (Karte "Kalender-Events", Knopf darin).
            "Kalender-Zugriff abgelaufen — in der Karte \"Kalender-Events\" auf " +
                "\"Kalender-Zugriff erneuern\" tippen."
        NoShiftReason.KALENDER_NICHT_ERREICHBAR ->
            // Kein Wort von Zugriff oder Anmeldung - genau diese Fehldiagnose stand hier bis
            // v1.43.4 (Flugmodus = "Kalender-Zugriff abgelaufen"). Knopf und Karte wortgleich.
            // "die gestellten Wecker bleiben" nur, wenn es welche gibt - sonst folgt unten der
            // Pausen- bzw. Automatik-Zusatz, und der Satz stuende im Widerspruch dazu.
            (if (gestellteWeckerBleiben(masterPausePaused, autoAlarmEnabled)) {
                "Google Kalender gerade nicht erreichbar — die gestellten Wecker bleiben. "
            } else {
                "Google Kalender gerade nicht erreichbar. "
            }) + "Mit Netz in der Karte \"Kalender-Events\" auf \"Mit Google Kalender abgleichen\" tippen."
        NoShiftReason.CALENDAR_UNAVAILABLE ->
            // Verweist auf die Karte mit dem Entfernen-Knopf (und dem Namen, solange Google den
            // Kalender noch fuehrt; sonst nur die Anzahl) - hier stehen die IDs nicht zur
            // Verfuegung, und ein halber Hinweis waere schlechter als der Weg zur Auskunft.
            "Mindestens ein ausgewählter Kalender ist nicht abrufbar — solange werden keine neuen " +
                "Wecker angelegt. Näheres im System-Status unter \"Kalender\"."
        NoShiftReason.LOAD_ERROR ->
            "Termine konnten nicht geladen werden: ${errorMessage?.takeIf { it.isNotBlank() } ?: "unbekannter Fehler"}"
        NoShiftReason.NO_EVENTS ->
            "Keine Termine in den nächsten $vorausschauTage Tagen — im gewählten Kalender steht nichts."
        NoShiftReason.SHIFT_CONFIG_NOT_LOADED ->
            // NICHT nur "wird geladen": derselbe Zustand entsteht, wenn der Read DAUERHAFT
            // gescheitert ist (vorhandene, aber nicht dekodierbare Konfiguration - das Repository
            // liefert dann ein Result.failure und die Rohdaten liegen als `shift_config_broken`).
            // Diese Karte wurde ausdruecklich gebaut, um den WARUM-Zustand ehrlich zu benennen; ein
            // behaupteter laufender Ladevorgang, der nie endet, ist das Gegenteil davon.
            // Der System-Status zeigt zu diesem Zustand selbst NICHTS (Review 30.09.2026) - deshalb
            // der Weg zu den Logs, Karte und Knopf wortgleich (StatusTabContent, DebugInfoCard).
            "Schichttypen sind (noch) nicht lesbar. Bleibt das so, schick bitte die Logs: im " +
                "System-Status unter \"Debug-Informationen\" auf \"Logs an Entwickler senden\" " +
                "tippen. Die Konfiguration liegt gesichert vor und wird NICHT überschrieben."
        NoShiftReason.NO_SHIFT_TYPES ->
            // Seit #139 liegt "Schichttypen verwalten" in den Einstellungen (neben "Kalender
            // auswählen"), nicht mehr im Wecker-Bereich - der Satz nennt den Ort, an dem der
            // Knopf WIRKLICH steht, wortgleich mit ShiftSelectorDialog.
            "Keine aktiven Schichttypen — lege sie $SCHICHTTYPEN_ORT an."
        NoShiftReason.NO_PATTERN_MATCH -> buildString {
            append("Termine gefunden, aber kein Erkennungsmuster passt")
            if (sampleEventTitles.isNotEmpty()) {
                append(" (im Kalender steht: ")
                append(sampleEventTitles.joinToString(", "))
                append(")")
            }
            append(". Erkennungsmuster $SCHICHTTYPEN_ORT prüfen.")
        }
        NoShiftReason.NUR_KRANKMELDUNG ->
            // Kein Rat zu Erkennungsmustern: "K" IST erkannt - als Krankmeldung.
            "Im Kalender stehen in den nächsten $vorausschauTage Tagen nur Krankmeldungen – " +
                "kein Dienst, kein Wecker aus dem Dienstplan."
        NoShiftReason.ONLY_PAST_SHIFTS ->
            "Alle erkannten Schichten liegen bereits in der Vergangenheit."
    }
    return when {
        masterPausePaused -> "$core\n$NO_SHIFT_HINWEIS_PAUSIERT"
        !autoAlarmEnabled -> "$core\nHinweis: Automatische Alarme sind derzeit ausgeschaltet."
        else -> core
    }
}

/**
 * PURE, TESTBAR: Welche Schicht die Karte zeigt, wenn der Kalender gerade nicht erreichbar ist.
 *
 * WARUM (Entscheidung des Eigentuemers, 01.10.2026): offline hiess die Karte "Keine Schicht
 * erkannt", obwohl der Dienstplan bekannt war - die Terminliste ist ohne Verbindung zwangslaeufig
 * leer. Gezeigt wird deshalb der letzte bekannte Stand, aber NUR in genau diesem Zustand
 * ([NoShiftReason.KALENDER_NICHT_ERREICHBAR]): bei jedem anderen Grund ist der alte Stand keine
 * Antwort (abgewaehlter Kalender, verlorener Zugriff, geaenderte Muster).
 *
 * NUR ANZEIGE: die Quelle ist [LetzterSchichtStand] (Name + Uhrzeit, keine Termininhalte - siehe
 * dort). Kein Wecker entsteht oder verschwindet daraus.
 */
internal fun offlineAngezeigteSchicht(
    reason: NoShiftReason,
    letzterStand: LetzterSchichtStand?,
    now: Long
): ShiftSpan? =
    if (reason == NoShiftReason.KALENDER_NICHT_ERREICHBAR) letzterStand?.naechsteNach(now) else null

/** In welcher Reihenfolge die Uebersicht "Nächste Schicht" und "Nächster Wecker" zeigt (#141). */
internal enum class UebersichtReihenfolge { SCHICHT_ZUERST, WECKER_ZUERST }

/**
 * PURE, TESTBAR: Was zuerst kommt, steht oben (#141, Eigentuemer 15.10.2026).
 *
 * WARUM NICHT EINFACH TAUSCHEN: meist klingelt der Wecker vor seiner Schicht (05:30 vor 06:00),
 * und er gehoerte nach oben. Ein stiller Abrufdienst ab Fr 00:00 mit dem naechsten Wecker erst
 * Sa 05:30 stuende bei festem Tausch aber unter dem spaeteren Termin.
 *
 * - Verglichen wird der Schichtbeginn (ganztaegig: 00:00) mit dem Zeitpunkt, den die Wecker-Karte
 *   NENNT (auch ein schwebender Snooze) - nie eine eigene Auswahl.
 * - Gleichstand: der Wecker steht oben.
 * - Fehlt einer der Zeitpunkte (Pause, kein klingelnder Wecker, keine Schicht erkannt, Laden),
 *   bleibt die bisherige Reihenfolge: Schicht oben. DARAUF STUETZEN SICH ZWEI TEXTE der
 *   Schicht-Karte ([NO_SHIFT_HINWEIS_PAUSIERT] und der Grund [NoShiftReason.AUTHORIZATION_LOST]):
 *   in diesen Zustaenden hat mindestens eine Karte keinen Zeitpunkt, die Wecker-Karte steht also
 *   sicher darunter.
 */
internal fun uebersichtReihenfolge(schichtBeginn: Long?, weckerZeitpunkt: Long?): UebersichtReihenfolge =
    if (schichtBeginn != null && weckerZeitpunkt != null && weckerZeitpunkt <= schichtBeginn) {
        UebersichtReihenfolge.WECKER_ZUERST
    } else {
        UebersichtReihenfolge.SCHICHT_ZUERST
    }

/**
 * PURE, TESTBAR: Der Beginn der Schicht, die die Karte "Nächste Schicht" gerade zeigt - dieselbe
 * Zweigfolge wie die Karte (erkannte Schicht, sonst Laden, sonst der Offline-Stand). Zeigt die
 * Karte keine Schicht, gibt es keinen Zeitpunkt.
 */
internal fun angezeigterSchichtBeginn(naechsteSchicht: Long?, laedt: Boolean, offlineSchicht: Long?): Long? =
    naechsteSchicht ?: if (laedt) null else offlineSchicht

/**
 * PURE, TESTBAR: Der Hinweis unter einer offline angezeigten Schicht. Er sagt, dass der Stand
 * ALT ist, und WORAUF sich der Zeitpunkt bezieht: die Schichtliste, nicht einen Kalender-Abgleich
 * (warum, steht an [LetzterSchichtStand.stand]). Ohne bekannten Zeitpunkt wird keiner erfunden.
 *
 * Der Satz ueber die Wecker haengt am Zustand - "die gestellten Wecker bleiben" waere bei
 * Master-Pause oder ausgeschalteter Automatik falsch, dort ist keiner gestellt (Review 01.10.2026).
 * Wie bei [noShiftExplanation] hoechstens EIN Zusatz, die Master-Pause hat Vorrang.
 */
internal fun offlineStandHinweis(
    stand: Long?,
    masterPausePaused: Boolean,
    autoAlarmEnabled: Boolean,
    zone: ZoneId = ZoneId.systemDefault()
): String {
    val wann = stand?.let {
        DateTimeFormatter.ofPattern(DateTimeFormats.STANDARD_DATETIME)
            .format(Instant.ofEpochMilli(it).atZone(zone))
    }
    val bezug = if (wann != null) "Aus der Schichtliste vom $wann" else "Aus einer früheren Schichtliste"
    val kern = "$bezug — Google Kalender ist gerade nicht erreichbar, spätere Änderungen fehlen hier."
    return when {
        masterPausePaused -> "$kern\n$NO_SHIFT_HINWEIS_PAUSIERT"
        !autoAlarmEnabled -> "$kern\nHinweis: Automatische Alarme sind derzeit ausgeschaltet."
        else -> "$kern Die gestellten Wecker bleiben."
    }
}

/**
 * PURE, TESTBAR: Die Zeile "Abgeglichen um HH:MM" in der Karte "Kalender-Events".
 *
 * Heute nur die Uhrzeit, an einem frueheren Tag mit Datum - eine blosse Uhrzeit von gestern
 * saehe aus wie ein Abgleich von heute, und genau darauf kommt es beim Nachsehen an: steht der
 * neue Dienst schon drin? Quelle ist [CalendarUiState.letzterAbgleich] (nur VOLLSTAENDIGE
 * Abgleiche, siehe dort); fehlt er, zeigt die Karte keine Zeile statt einer erfundenen.
 *
 * Der Satz sagt bewusst nicht, WER abgeglichen hat (Start, Rueckkehr, Knopf): das weiss der
 * Zeitpunkt nicht.
 */
internal fun abgleichZeitText(
    zeitpunkt: Long,
    zone: ZoneId = ZoneId.systemDefault(),
    heute: LocalDate = LocalDate.now(zone)
): String {
    val wann = Instant.ofEpochMilli(zeitpunkt).atZone(zone)
    val uhrzeit = DateTimeFormatter.ofPattern(DateTimeFormats.TIME_ONLY).format(wann)
    return if (wann.toLocalDate() == heute) {
        "Abgeglichen um $uhrzeit"
    } else {
        "Abgeglichen am ${DateTimeFormatter.ofPattern(DateTimeFormats.DATE_SHORT).format(wann)}. um $uhrzeit"
    }
}

// ---- Laufender Ausfall (#142) -------------------------------------------------------------

/**
 * #142: die zweite Zeile der Ausfall-Karte - Wortlaut der Spec. "Nachts gilt die Regel fuer freie
 * Tage": `FreieTageStore.filtereSpannen` nimmt die Spannen freier Tage aus Dimmer und "Nicht
 * stören". Ohne eigene FREI-Regel wirkt dort die allgemeine Regel oder keine - bewusst so
 * akzeptiert (im Skill notiert).
 */
internal const val AUSFALL_TEXT: String =
    "Kein Wecker aus dem Dienstplan, eigene Wecker bleiben. Nachts gilt die Regel für freie Tage."

/**
 * #142: direkt unter "Zurücknehmen", wenn der Block auch Kalender-Tage enthaelt - die nimmt der
 * Knopf nicht zurueck (den Kalender-Schluessel schreibt allein `syncAlarms()`).
 */
internal const val AUSFALL_GEMISCHT_HINWEIS: String =
    "„Zurücknehmen“ nimmt nur deine eigene Freigabe zurück. Tage, die laut Kalender krankgemeldet " +
        "sind, bleiben frei."

/**
 * PURE, TESTBAR: die Ueberschrift der Ausfall-Karte. [Ausfall.von] ist immer heute oder morgen
 * (`laufenderAusfall`). Ist eine eigene Freigabe beteiligt, heisst der Block "Dienst fällt aus";
 * "krankgemeldet" nur, wenn der Kalender die einzige Quelle ist.
 */
internal fun ausfallTitel(ausfall: Ausfall, heute: LocalDate): String {
    val morgen = heute.plusDays(1)
    val bis = kurzesDatum(ausfall.bis)
    val einTag = ausfall.von == ausfall.bis
    return if (ausfall.quelle == AusfallQuelle.KALENDER) {
        when {
            einTag && ausfall.von == heute -> "Heute krankgemeldet (laut Kalender)"
            einTag && ausfall.von == morgen -> "Morgen krankgemeldet (laut Kalender)"
            ausfall.von == heute -> "Krankgemeldet bis $bis (laut Kalender)"
            else -> "Ab morgen krankgemeldet, bis $bis (laut Kalender)"
        }
    } else {
        when {
            einTag && ausfall.von == heute -> "Dienst fällt heute aus"
            einTag && ausfall.von == morgen -> "Dienst fällt morgen aus"
            ausfall.von == heute -> "Dienst fällt aus bis $bis"
            else -> "Dienst fällt ab morgen aus, bis $bis"
        }
    }
}

/** PURE, TESTBAR: bietet die Karte "Zurücknehmen" an? Nur fuer eigene Freigaben im Block. */
internal fun ausfallZuruecknehmbar(ausfall: Ausfall): Boolean = ausfall.handTage.isNotEmpty()

/**
 * PURE, TESTBAR: der Satz unter dem Knopf bzw. an seiner Stelle. Reiner Kalender-Block: kein Knopf,
 * stattdessen, wie der Wecker zurueckkommt; gemischter Block: was der Knopf NICHT zuruecknimmt.
 * Reine Hand-Freigabe: nichts.
 */
internal fun ausfallHinweis(ausfall: Ausfall): String? = when (ausfall.quelle) {
    AusfallQuelle.HAND -> null
    AusfallQuelle.KALENDER -> KRANK_KALENDER_ZURUECK
    AusfallQuelle.GEMISCHT -> AUSFALL_GEMISCHT_HINWEIS
}

/**
 * #142: der laufende Ausfall, ganz oben in der Uebersicht. Neutral (Rand, keine Warnfarbe): er ist
 * zeitlich begrenzt und kein Fehler - der sichtbare Unterschied zur Master-Pause, die alles anhaelt.
 */
@Composable
private fun AusfallKarte(
    ausfall: Ausfall,
    heute: LocalDate,
    gesperrt: Boolean,
    onZuruecknehmen: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(SpacingConstants.PADDING_CARD),
            verticalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_SMALL)
        ) {
            Text(
                ausfallTitel(ausfall, heute),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(AUSFALL_TEXT, style = MaterialTheme.typography.bodyMedium)
            if (ausfallZuruecknehmbar(ausfall)) {
                OutlinedButton(onClick = onZuruecknehmen, enabled = !gesperrt) { Text("Zurücknehmen") }
            }
            ausfallHinweis(ausfall)?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * Die Karte "Nächste Schicht". Eigene Funktion, seit die Uebersicht sie vor ODER nach "Nächster
 * Wecker" stellt (#141, [uebersichtReihenfolge]). [reason] und [offlineSchicht] rechnet der
 * Aufrufer, weil er daraus auch den Zeitpunkt fuer die Reihenfolge nimmt
 * ([angezeigterSchichtBeginn]) - eine Rechnung, damit Karte und Reihenfolge dieselbe Schicht meinen.
 */
@Composable
private fun NaechsteSchichtKarte(
    calendarState: CalendarUiState,
    shiftState: ShiftUiState,
    masterPausePaused: Boolean,
    reason: NoShiftReason,
    offlineSchicht: ShiftSpan?
) {
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
                .padding(SpacingConstants.PADDING_CARD),
            horizontalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_LARGE),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Work,
                // dekorativ: Text daneben sagt es bereits ("Nächste Schicht")
                contentDescription = null,
                modifier = Modifier.size(SpacingConstants.ICON_SIZE_EXTRA_LARGE),
                tint = MaterialTheme.colorScheme.primary
            )
            
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Nächste Schicht",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                
                if (shiftState.upcomingShift != null) {
                    Text(
                        shiftState.upcomingShift.shiftType.displayName,
                        style = MaterialTheme.typography.bodyLarge
                    )
                    // Datum und Dienstzeit - dieselbe Formatierung wie im Wecker-Tab
                    // („ganztägig“ statt einer Uhrzeit 00:00, die es nicht gibt).
                    val zone = ZoneId.systemDefault()
                    Text(
                        schichtTerminText(
                            beginn = shiftState.upcomingShift.startTime.atZone(zone).toInstant().toEpochMilli(),
                            ende = shiftState.upcomingShift.endTime.atZone(zone).toInstant().toEpochMilli(),
                            zone = zone
                        ),
                        style = MaterialTheme.typography.bodyMedium
                    )
                } else if (calendarState.isLoading || shiftState.isLoading) {
                    // Beim (ersten) Oeffnen synchronisiert die App den Kalender neu; bis die
                    // Events da sind, ist noch keine Schicht erkannt. Das ist ein LADEzustand,
                    // kein Fehler - neutraler Hinweis, solange geladen wird.
                    Text(
                        "Wird geladen …",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    // Nicht nur "Keine Schicht erkannt", sondern WARUM: der Satz allein galt fuer
                    // sechs verschiedene Ursachen und liess den Nutzer ohne Anhaltspunkt zurueck.
                    val shiftConfig = shiftState.currentShiftConfig
                    val ladeFehler = calendarState.error ?: shiftState.error
                    if (offlineSchicht != null) {
                        // Offline: der letzte bekannte Stand statt "Keine Schicht erkannt" -
                        // als alt gekennzeichnet, siehe offlineAngezeigteSchicht().
                        Text(
                            offlineSchicht.shiftName,
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Text(
                            schichtTerminText(offlineSchicht.startTime, offlineSchicht.endTime, ZoneId.systemDefault()),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            offlineStandHinweis(
                                stand = shiftState.letzterSchichtStand?.stand,
                                masterPausePaused = masterPausePaused,
                                autoAlarmEnabled = shiftConfig?.autoAlarmEnabled != false
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Text(
                            "Keine Schicht erkannt",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            noShiftExplanation(
                                reason = reason,
                                errorMessage = ladeFehler,
                                // Nur eine kleine Kostprobe: die Karte soll erklaeren, nicht den
                                // Kalender abbilden (dafuer gibt es "Antippen fuer Details").
                                // #142: ohne Krankmeldungen - "(im Kalender steht: K)" legte
                                // nahe, "K" als Schicht-Muster einzutragen.
                                sampleEventTitles = calendarState.events
                                    .map { it.title }
                                    .filterNot { istKrankTermin(it, shiftConfig?.definitions.orEmpty()) }
                                    .distinct()
                                    .take(3),
                                autoAlarmEnabled = shiftConfig?.autoAlarmEnabled != false,
                                masterPausePaused = masterPausePaused,
                                vorausschauTage = calendarState.vorausschauTage
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun HomeTabContent(
    calendarState: CalendarUiState,
    shiftState: ShiftUiState,
    alarmState: AlarmUiState,
    skipState: AlarmSkipUiState,
    // #142: bewusst OHNE Default (Anzeigepfad) - vergessen hiesse still "keine Ausfall-Karte".
    tagFreigabeState: TagFreigabeUiState,
    masterPausePaused: Boolean,
    onJetztAbgleichen: () -> Unit,
    onNavigateToWecker: () -> Unit,
    onShowEventList: () -> Unit,
    onReauthorize: () -> Unit,
    /** #142: nimmt die eigenen Freigaben des laufenden Ausfalls zurueck. */
    onAusfallZuruecknehmen: (Set<LocalDate>) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(SpacingConstants.PADDING_SCREEN_HORIZONTAL)
            // Zusaetzlicher Freiraum unten, damit der Manueller-Alarm-FAB nicht ueber der
            // letzten Karte schwebt (der FAB liegt ausserhalb des Scaffold-innerPadding); 88 dp
            // decken seine 56 dp plus Rand.
            .padding(bottom = 88.dp),
        verticalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_LARGE)
    ) {
        val zone = ZoneId.systemDefault()
        val reason = noShiftReason(
            hasSelectedCalendars = calendarState.selectedCalendarIds.isNotEmpty(),
            calendarAuthorizationValid = calendarState.calendarAuthorizationValid,
            kalenderNichtErreichbar = calendarState.kalenderNichtErreichbar,
            unavailableCalendarCount = calendarState.unavailableCalendarIds.size,
            errorMessage = calendarState.error ?: shiftState.error,
            eventCount = calendarState.events.size,
            shiftConfigLoaded = shiftState.currentShiftConfig != null,
            // #142: ein Krankmeldungs-Typ ist kein Schichttyp, fuer den ein Wecker entsteht.
            enabledShiftTypeCount = shiftState.currentShiftConfig?.definitions
                ?.count { it.isEnabled && !it.isSickDay } ?: 0,
            recognizedShiftCount = shiftState.recognizedShifts.size,
            krankTerminCount = calendarState.events.count { event ->
                istKrankTermin(event.title, shiftState.currentShiftConfig?.definitions.orEmpty())
            }
        )
        val offlineSchicht = offlineAngezeigteSchicht(
            reason = reason,
            letzterStand = shiftState.letzterSchichtStand,
            now = System.currentTimeMillis()
        )
        // Statt "N aktive Alarme" die eine Frage: wann klingelt es als Naechstes (#139, P4).
        val naechsterWecker = rememberNaechsterWecker(
            alarmState = alarmState,
            skipState = skipState,
            masterPausePaused = masterPausePaused,
            autoAlarmEnabled = shiftState.currentShiftConfig?.autoAlarmEnabled != false
        )
        val reihenfolge = uebersichtReihenfolge(
            schichtBeginn = angezeigterSchichtBeginn(
                naechsteSchicht = shiftState.upcomingShift?.startTime?.atZone(zone)?.toInstant()?.toEpochMilli(),
                laedt = calendarState.isLoading || shiftState.isLoading,
                offlineSchicht = offlineSchicht?.startTime
            ),
            weckerZeitpunkt = naechsterWecker.zeitpunkt
        )
        val schichtKarte: @Composable () -> Unit = {
            NaechsteSchichtKarte(calendarState, shiftState, masterPausePaused, reason, offlineSchicht)
        }
        val weckerKarte: @Composable () -> Unit = {
            NaechsterWeckerKarte(stand = naechsterWecker, onAlleWecker = onNavigateToWecker)
        }
        // #142: ganz OBEN, VOR den beiden #141-Karten - deren Texte "die Karte darunter" beziehen
        // sich aufeinander und bleiben so wahr. Bei Master-Pause/Automatik aus entfaellt der
        // Hinweis: dort bleiben keine eigenen Wecker (gestellteWeckerBleiben), und die Pause hat
        // ihre eigene Anzeige.
        val heute = LocalDate.now(zone)
        val ausfall = if (gestellteWeckerBleiben(
                masterPausePaused,
                shiftState.currentShiftConfig?.autoAlarmEnabled != false
            )
        ) {
            laufenderAusfall(tagFreigabeState.quellen.hand, tagFreigabeState.quellen.kalender, heute)
        } else {
            null
        }
        ausfall?.let {
            AusfallKarte(
                ausfall = it,
                heute = heute,
                // Dieselbe Wiedereintrittssperre wie im Wecker-Tab (skipVorgangLaeuft).
                gesperrt = tagFreigabeState.isLoading || skipState.isLoading,
                onZuruecknehmen = { onAusfallZuruecknehmen(it.handTage) }
            )
        }
        // Der Ausgang eines Zuruecknehmens von HIER muss auch hier stehen - sonst saehe man einen
        // Fehlschlag nur, wenn man zufaellig in den Wecker-Tab wechselt. #142: nur FEHLER - eine
        // Erfolgsmeldung sagt die Ausfall-Karte selbst, und nur, solange sie gilt.
        sichtbarerFreigabeHinweis(
            tagFreigabeState.hinweis,
            tagFreigabeState.hinweisErfolgBis,
            heute,
            aufUebersicht = true
        )?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.warning)
        }

        // Ohne Animation (#141): die Karten tauschen den Platz nur bei neuen Daten oder beim
        // Zurueckkehren in die App, nicht waehrend man hinsieht.
        when (reihenfolge) {
            UebersichtReihenfolge.SCHICHT_ZUERST -> { schichtKarte(); weckerKarte() }
            UebersichtReihenfolge.WECKER_ZUERST -> { weckerKarte(); schichtKarte() }
        }

        val zugriffVerloren =
            !calendarState.calendarAuthorizationValid && calendarState.selectedCalendarIds.isNotEmpty()
        Card(
            modifier = Modifier.fillMaxWidth(),
            onClick = onShowEventList,
            colors = CardDefaults.cardColors(
                containerColor = if (zugriffVerloren) {
                    MaterialTheme.colorScheme.errorContainer
                } else {
                    CardDefaults.cardColors().containerColor
                }
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(SpacingConstants.PADDING_CARD),
                verticalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_SMALL)
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_LARGE),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.CalendarMonth,
                        // dekorativ: Ueberschrift daneben sagt es bereits ("Kalender-Events"); den
                        // Fehlerzustand nennt der Text darunter ausdruecklich
                        // ("⚠️ Kalender-Autorisierung verloren"), nicht dieses Icon
                        contentDescription = null,
                        modifier = Modifier.size(SpacingConstants.ICON_SIZE_LARGE),
                        tint = if (zugriffVerloren) {
                            MaterialTheme.colorScheme.onErrorContainer
                        } else {
                            MaterialTheme.colorScheme.primary
                        }
                    )
                    Text(
                        "Kalender-Events",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                
                HorizontalDivider()
                
                if (zugriffVerloren) {
                    Text(
                        "⚠️ Kalender-Autorisierung verloren",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Text(
                        "Der Zugriff auf deinen Google Kalender ist abgelaufen. Autorisiere ihn erneut, damit weiterhin Schichtalarme erstellt werden.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Button(
                        onClick = onReauthorize,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError
                        )
                    ) {
                        Text("Kalender-Zugriff erneuern")
                    }
                } else if (calendarState.kalenderNichtErreichbar &&
                    calendarState.selectedCalendarIds.isNotEmpty()
                ) {
                    // Eigener Zustand statt "Autorisierung verloren" (bis v1.43.4), siehe
                    // CalendarUiState.kalenderNichtErreichbar. Bewusst OHNE Fehlerfarbe: kaputt
                    // ist nichts, die Wecker stehen - es fehlt nur die Verbindung. Der Ausweg ist
                    // derselbe Abgleich wie sonst, mit demselben Namen. Die Auswahl-Pruefung wie
                    // bei zugriffVerloren: nach einer Abwahl steht der Merker noch, die Wecker
                    // sind dann aber geraeumt - "sie bleiben" waere gelogen.
                    Text(
                        "Google Kalender nicht erreichbar",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "Die Termine konnten gerade nicht abgerufen werden – meist fehlt die " +
                            "Internetverbindung." +
                            if (gestellteWeckerBleiben(
                                    masterPausePaused,
                                    shiftState.currentShiftConfig?.autoAlarmEnabled != false
                                )
                            ) " Die gestellten Wecker bleiben bestehen." else "",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    KalenderAbgleichKnopf(enabled = !calendarState.isLoading, onClick = onJetztAbgleichen)
                } else if (calendarState.alleKalenderFehlen &&
                    calendarState.selectedCalendarIds.isNotEmpty()
                ) {
                    // Alle ausgewaehlten Kalender fehlen - ausdruecklicher Merker, NICHT aus
                    // "nicht abrufbar + keine Termine" abgeleitet (das traefe auch einen Teilerfolg
                    // mit leerem Rest-Kalender). Siehe KALENDER_NICHT_GEFUNDEN_TEXT.
                    Text(
                        KALENDER_NICHT_GEFUNDEN_TITEL,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        KALENDER_NICHT_GEFUNDEN_TEXT,
                        style = MaterialTheme.typography.bodyMedium
                    )
                } else if (calendarState.events.isNotEmpty()) {
                    Text("${calendarState.events.size} Events in den nächsten ${calendarState.vorausschauTage} Tagen")
                    Text(
                        "${shiftState.recognizedShifts.size} Schichten erkannt",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    
                    val today = LocalDate.now()
                    val tomorrow = today.plusDays(1)
                    
                    val todayShifts = shiftState.recognizedShifts.filter { 
                        it.startTime.toLocalDate() == today 
                    }
                    val tomorrowShifts = shiftState.recognizedShifts.filter { 
                        it.startTime.toLocalDate() == tomorrow 
                    }
                    
                    if (todayShifts.isNotEmpty() || tomorrowShifts.isNotEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            if (todayShifts.isNotEmpty()) {
                                Text(
                                    "Heute: ${todayShifts.joinToString(", ") { it.shiftType.displayName }}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            if (tomorrowShifts.isNotEmpty()) {
                                Text(
                                    "Morgen: ${tomorrowShifts.joinToString(", ") { it.shiftType.displayName }}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                    
                    // Die Karte listet selbst keine Events - also keine Anzeigemenge behaupten,
                    // nur auf Nachladbares hinweisen (ui-texte-und-layout.md).
                    if (calendarState.hasMoreEvents) {
                        Text(
                            "Es gibt weitere Termine — in der Terminliste nachladbar",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                    
                    Text(
                        "Antippen für Details →",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )

                    // Der Abgleich steht an der Karte, deren Inhalt er erneuert, und traegt Worte
                    // statt eines Symbols in der Kopfzeile (ui-texte-und-layout.md).
                    //
                    // Der Text nennt ausdruecklich den Google Kalender: die App gleicht mit IHM ab,
                    // nicht mit TimeOffice. Sie ist nur die Schnittstelle - was TimeOffice noch
                    // nicht in den Kalender geschrieben hat, kann auch hier nicht auftauchen.
                    // Ohne diesen Satz vermutet man den Fehler in der App statt im Dienstplan.
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = SpacingConstants.SPACING_SMALL)
                    )
                    // Wie alt der Stand ist, steht direkt ueber dem Knopf, der ihn erneuert. Bleibt
                    // waehrend eines Neuladens stehen (die alte Anzeige bleibt ja auch) und springt
                    // erst mit dem fertigen, vollstaendigen Abgleich weiter.
                    calendarState.letzterAbgleich?.let { zeitpunkt ->
                        Text(
                            abgleichZeitText(zeitpunkt),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    KalenderAbgleichKnopf(enabled = !calendarState.isLoading, onClick = onJetztAbgleichen)
                    // Der letzte Satz ist der Befund vom 12.10.2026: TimeOffice schrieb einen
                    // gemeldeten Dienst erst sechseinhalb Stunden spaeter in den Kalender - beim
                    // Oeffnen. Ohne ihn sucht man den Fehler hier statt dort.
                    Text(
                        "Holt die Termine aus deinem Google Kalender und stellt die Wecker " +
                            "danach neu. CF-Alarm liest nur den Kalender — was TimeOffice dort " +
                            "noch nicht eingetragen hat, kann auch hier nicht auftauchen. " +
                            "TimeOffice trägt eine Änderung erst ein, wenn du TimeOffice öffnest.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text(
                        "Keine Events geladen",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    // Auch ein LEERER Kalender kann vollstaendig abgeglichen sein - dann sagt die
                    // Zeile, dass nachgesehen wurde. Vor dem ersten Abgleich und nach der Abwahl
                    // ist der Zeitpunkt null, dann steht hier nichts.
                    calendarState.letzterAbgleich?.let { zeitpunkt ->
                        Text(
                            abgleichZeitText(zeitpunkt),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        if (calendarState.isLoading || shiftState.isLoading || alarmState.isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = SpacingConstants.SPACING_LARGE),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        }
    }
}

/**
 * Der Abgleich mit dem Google Kalender - EIN Vorgang, EIN Name (ui-texte-und-layout.md). Steht in
 * der Karte "Kalender-Events" an zwei Stellen: im Normalfall unter der Zusammenfassung und im
 * Zustand "nicht erreichbar" als Ausweg. Deshalb hier einmal, damit Beschriftung und Aussehen
 * nicht auseinanderlaufen.
 */
@Composable
private fun KalenderAbgleichKnopf(enabled: Boolean, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth()
    ) {
        Icon(
            Icons.Default.Refresh,
            // dekorativ: die Beschriftung daneben sagt es vollstaendig
            contentDescription = null,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(SpacingConstants.SPACING_SMALL))
        Text("Mit Google Kalender abgleichen")
    }
}
