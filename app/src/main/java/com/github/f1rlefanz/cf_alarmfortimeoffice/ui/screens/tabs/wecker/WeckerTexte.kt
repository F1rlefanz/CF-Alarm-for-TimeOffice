package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.tabs.wecker

import com.github.f1rlefanz.cf_alarmfortimeoffice.alarm.AlarmPrefs
import com.github.f1rlefanz.cf_alarmfortimeoffice.alarm.ProbeFehler
import com.github.f1rlefanz.cf_alarmfortimeoffice.alarm.ProbeZustand
import com.github.f1rlefanz.cf_alarmfortimeoffice.alarm.SchlummerEingabe
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftDefinition
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.reinerSchichtname
import com.github.f1rlefanz.cf_alarmfortimeoffice.shift.LetzterSchichtStand
import com.github.f1rlefanz.cf_alarmfortimeoffice.shift.ShiftSpan
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.components.dienstzeitText
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.components.kurzesDatum
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.AlarmSkipUiState
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.Kaertchen
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.SchichtFolgen
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.SchichtWirkung
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.SkipLage
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.TagesSchicht
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.WeckerEintrag
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.WeckerTag
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/*
 * Die Texte und reinen Entscheidungen des Wecker-Tabs (Issue #139) - ohne Compose, damit jede
 * Aussage als JVM-Test pruefbar ist. Die Composables daneben setzen nur noch zusammen.
 */

/** Geschuetztes Leerzeichen: haelt "06:00–14:12" und "5 Min" bei schmaler Anzeige zusammen. */
private const val NBSP = ' '

private val UHRZEIT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.GERMAN)

// ---- Zustandshinweis oben ---------------------------------------------------------------

/** Ein Zustand, der die App dauerhaft nicht wecken laesst - oben im Tab, mit Handlung. */
internal enum class WeckerZustand { MASTER_PAUSE, AUTOMATIK_AUS }

/**
 * PURE, TESTBAR: hoechstens EIN Zustandshinweis, und die Master-Pause hat Vorrang (Skill
 * cfalarm-ui-und-navigation: "es steht höchstens EIN Zusatz-Hinweis gleichzeitig"). Beide
 * Zustaende koennen zugleich gelten - die Pause ruehrt `autoAlarmEnabled` bewusst nicht an.
 *
 * @param autoAlarmEnabled `null` = Konfiguration noch nicht geladen. Dann KEIN Hinweis: ein
 *   "Automatik aus" waehrend des Kaltstarts waere eine Behauptung ohne Grundlage.
 */
internal fun weckerZustand(masterPausePaused: Boolean, autoAlarmEnabled: Boolean?): WeckerZustand? = when {
    masterPausePaused -> WeckerZustand.MASTER_PAUSE
    autoAlarmEnabled == false -> WeckerZustand.AUTOMATIK_AUS
    else -> null
}

internal data class ZustandsHinweisText(val titel: String, val text: String, val handlung: String)

internal fun zustandsHinweisText(zustand: WeckerZustand): ZustandsHinweisText = when (zustand) {
    WeckerZustand.MASTER_PAUSE -> ZustandsHinweisText(
        titel = "Alles ist pausiert.",
        text = "Es wird kein Wecker gestellt, und keiner klingelt.",
        handlung = "Pause beenden"
    )
    // Die Automatik raeumt in diesem Zustand ALLE Wecker, auch eigene, und das Anlegen eines
    // eigenen wird verweigert (AlarmViewModel.createManualAlarm) - deshalb "auch kein eigener".
    WeckerZustand.AUTOMATIK_AUS -> ZustandsHinweisText(
        titel = "Automatische Alarme sind aus.",
        text = "Solange sie aus sind, wird kein Wecker gestellt – auch kein eigener.",
        handlung = "Einschalten"
    )
}

/**
 * PURE, TESTBAR: der Leerzustand der Liste sagt, WARUM sie leer ist. Bei Pause und Automatik-aus
 * steht der Grund schon im Hinweis darueber; der Satz hier verbindet ihn nur mit der leeren Liste.
 *
 * @param vorausschauTage `CalendarUiState.vorausschauTage` - nie eine feste Zahl (Skill
 *   cfalarm-ui-und-navigation).
 */
internal fun leerzustandText(zustand: WeckerZustand?, vorausschauTage: Int): String = when (zustand) {
    WeckerZustand.MASTER_PAUSE -> "Während der Pause ist kein Wecker gestellt."
    WeckerZustand.AUTOMATIK_AUS -> "Solange die Automatik aus ist, ist kein Wecker gestellt."
    null -> "Zurzeit ist kein Wecker gestellt. Die App stellt Wecker für die Dienste, die sie in " +
        "den nächsten $vorausschauTage Tagen im Kalender erkennt."
}

// ---- Tagesliste ------------------------------------------------------------------------

/** Die Skip-Lage, wie die Tagesliste sie braucht - aus dem Zustand des ViewModels. */
internal fun AlarmSkipUiState.alsSkipLage(): SkipLage = SkipLage(
    aktiv = isNextAlarmSkipped,
    alarmId = skippedAlarmId,
    weckzeitpunkt = skippedAlarmTriggerTime
)

/**
 * PURE, TESTBAR: Schichtname -> Wirkung, aus denselben [SchichtFolgen] wie die Statuszeile im
 * `ShiftConfigScreen` (`ShiftViewModel.schichtFolgen`, geschluesselt nach `definition.id`).
 * Deaktivierte Definitionen fehlen dort bewusst - und damit auch hier: sie stellen keinen Wecker.
 */
internal fun weckerWirkungen(
    definitionen: List<ShiftDefinition>,
    folgenJeId: Map<String, SchichtFolgen>
): Map<String, SchichtWirkung> =
    definitionen
        .filter { it.isEnabled }
        .mapNotNull { definition ->
            folgenJeId[definition.id]?.let { definition.name to SchichtWirkung(definition, it) }
        }
        .toMap()

/**
 * PURE, TESTBAR: welche Schichtspannen die Tagesliste liest. Waehrend der Master-Pause und bei
 * ausgeschalteter Automatik KEINE: dann ist kein Wecker gestellt (der Hinweis oben sagt es), und
 * der Spannen-Bestand wird in diesen Lagen auch nicht mehr geschrieben - ein Tag „Dienst ohne
 * Wecker“ aus einem alten Stand widerspraeche dem Hinweis. `null` (nicht lesbar, noch nicht
 * gelesen) heisst ebenfalls keine: die Liste faellt dann auf die Wecker allein zurueck.
 */
internal fun spannenFuerTagesliste(zustand: WeckerZustand?, stand: LetzterSchichtStand?): List<ShiftSpan> =
    if (zustand != null) emptyList() else stand?.spans.orEmpty()

/** "Mi 14.10." - dieselbe Datumsform wie in der Dienstzeit („ganztägig bis So 18.10.“). */
internal fun tagUeberschrift(datum: LocalDate): String = kurzesDatum(datum)

/** "heute"/"morgen" neben dem Datum - ein Wecker-Tag liest sich so ohne Kalender im Kopf. */
internal fun relativerTag(datum: LocalDate, heute: LocalDate): String? = when (datum) {
    heute -> "heute"
    heute.plusDays(1) -> "morgen"
    else -> null
}

/**
 * "F · 06:00–14:12", mehrere Schichten mit Komma. Der Name ohne "(Manuell)": dass ein Wecker von
 * Hand gestellt ist, sagt dessen Zeile. Unbekannte Dienstzeit (Feld 0) -> nur der Name. Die
 * Dienstzeit kommt aus derselben Formatierung wie in der Uebersicht ([dienstzeitText]):
 * „Abrufdienst · ganztägig“ statt „00:00–00:00“.
 */
internal fun schichtenText(schichten: List<TagesSchicht>, zone: ZoneId): String =
    schichten.joinToString(", ") { schicht ->
        val name = reinerSchichtname(schicht.name)
        dienstzeitText(schicht.beginn, schicht.ende, zone)?.let { "$name$NBSP·$NBSP$it" } ?: name
    }

internal fun uhrzeit(zeit: LocalTime): String = zeit.format(UHRZEIT)

/**
 * PURE, TESTBAR: was ein Wecker tut, in einem Satzstueck. Ein Dienstplan-Wecker an einem
 * freigegebenen Tag klingelt nicht (Gate und Backstop) - er darf nicht als "Weckt" erscheinen.
 *
 * @param mehrereSchichten nennt den Dienst, wenn der Tag mehrere hat - sonst steht er schon oben.
 */
internal fun weckerArt(eintrag: WeckerEintrag.Wecker, mehrereSchichten: Boolean): String {
    val art = when {
        eintrag.entfaelltDurchFreigabe -> "Entfällt, der Dienst fällt aus"
        eintrag.still -> "Still: kein Ton"
        else -> "Weckt"
    }
    val teile = buildList {
        add(art)
        if (eintrag.manuell) add("von dir gestellt")
        if (mehrereSchichten && eintrag.alarm.shiftName.isNotBlank()) add(reinerSchichtname(eintrag.alarm.shiftName))
    }
    return teile.joinToString(" · ")
}

internal const val UEBERSPRUNGEN_TEXT: String = "Weckt diesmal nicht"

/** Die Zeile im Bottom-Sheet: was dieser Wecker ist - Herkunft ausgeschrieben. */
internal fun weckerBeschreibung(eintrag: WeckerEintrag): String = when (eintrag) {
    is WeckerEintrag.Uebersprungen -> "$UEBERSPRUNGEN_TEXT. „Doch wecken“ stellt ihn wieder."
    is WeckerEintrag.Wecker -> when {
        eintrag.entfaelltDurchFreigabe -> "Klingelt nicht: der Dienst fällt aus."
        eintrag.still -> "Still: kein Ton, nur Zeitanker für den Dienst."
        eintrag.manuell -> "Weckt. Von dir gestellt."
        else -> "Weckt. Aus dem Dienstplan."
    }
}

internal fun kaertchenText(kaertchen: Kaertchen): String = when (kaertchen) {
    Kaertchen.NICHT_STOEREN -> "Nicht stören"
    Kaertchen.DIMMEN -> "Dimmen"
    Kaertchen.LICHT -> "Licht"
    Kaertchen.RUFBEREITSCHAFT -> "stündliche Kalender-Abfrage"
}

/** Die Beschriftung der Rufbereitschaft im Bottom-Sheet - dieselbe Wirkung wie das Kaertchen. */
internal const val RUFBEREITSCHAFT_BESCHREIBUNG: String =
    "Rufbereitschaft: die App fragt stündlich den Kalender ab."

/**
 * Was „Dienst fällt aus“ bewirkt - steht direkt am Knopf (Skill: ein Hinweis zu einem Knopf steht
 * unter DIESEM Knopf). Gekuerzte Fassung des frueheren Freigabe-Hinweises.
 *
 * JEDE Aussage ist am Code belegt (Stand 1.48.0, Hergang cfalarm-wecker-und-boot/reference/
 * tag-freigeben.md):
 *  - "kein Wecker aus dem Dienstplan, auch nicht nach spaeteren Aenderungen": Gate in
 *    `AlarmUseCase.syncAlarms()` und Backstop in `scheduleSystemAlarm()` ueber
 *    `AlarmUseCase.istTagFreigegeben` - die Freigabe haengt am Datum, nicht am Termin.
 *  - "„Nicht stören“ und Dimmen richten sich nicht mehr nach dem Dienst, der Tag zaehlt als
 *    freier Tag": `FreieTageStore.filtereSpannen` nimmt die Spannen des Tages heraus. Bewusst
 *    NICHT "kein Dimmen": eine Regel fuer freie Tage wirkt an diesem Tag weiter.
 *  - "stuendliche Rufdienst-Abfrage entfaellt": `RufbereitschaftAbfrage.reschedule()` filtert
 *    ueber dieselbe Funktion, `TagFreigabeUseCase` plant sie beim Freigeben neu.
 *  - "eigene Wecker bleiben": `istTagFreigegeben` und `TagFreigabeUseCase.gehoertZuTag` nehmen
 *    manuelle Wecker aus.
 *  - "der Termin im Kalender bleibt": die App schreibt nie in den Kalender.
 * Wer eine dieser Stellen aendert, prueft diesen Text mit.
 */
internal const val DIENST_FAELLT_AUS_ERKLAERUNG: String =
    "Für diesen Tag wird kein Wecker aus dem Dienstplan gestellt, auch nicht nach späteren " +
        "Änderungen im Dienstplan. „Nicht stören“ und Dimmen richten sich nicht mehr nach dem " +
        "Dienst – der Tag zählt als freier Tag. Die stündliche Rufdienst-Abfrage entfällt. " +
        "Deine eigenen Wecker bleiben, der Termin im Kalender auch."

/** Die Kurzform auf der Karte eines freigegebenen Tages. */
internal const val DIENST_FAELLT_AUS_KURZ: String =
    "Kein Wecker aus dem Dienstplan, der Tag zählt als freier Tag."

/** Am freigegebenen Tag im Bottom-Sheet: was „Zurücknehmen“ tut. */
internal const val ZURUECKNEHMEN_ERKLAERUNG: String =
    "„Zurücknehmen“ stellt die Wecker dieses Tages wieder aus dem Dienstplan, und „Nicht stören“ " +
        "und Dimmen richten sich wieder nach dem Dienst."

/**
 * Der Satz unter der Liste (Variante A): „Nicht wecken“ steht nur am naechsten klingelnden
 * Wecker, weil es genau einen Skip-Platz gibt und `skipNextAlarm` immer den naechsten trifft.
 */
internal const val NICHT_WECKEN_NUR_NAECHSTER: String =
    "„Nicht wecken“ gibt es nur für den nächsten Wecker. Fällt ein späterer Dienst aus, tippe auf " +
        "seinen Tag und wähle „Dienst fällt aus“."

/**
 * Statt [NICHT_WECKEN_NUR_NAECHSTER], solange ein Wecker ausgelassen ist: dann traegt der folgende
 * Wecker zwar „nächster“, aber KEINEN Knopf - es gibt genau einen Skip-Platz. Der Satz darf ihn
 * dann nicht versprechen.
 */
internal const val NUR_EIN_WECKER_AUSLASSEN: String =
    "Es lässt sich immer nur ein Wecker auslassen – „Doch wecken“ nimmt das zurück. Fällt ein " +
        "späterer Dienst aus, tippe auf seinen Tag und wähle „Dienst fällt aus“."

/**
 * PURE, TESTBAR: der Satz unter der Liste - nur in dem Zustand, den er beschreibt.
 *
 * - Eine Zeile „weckt diesmal nicht“ steht da: [NUR_EIN_WECKER_AUSLASSEN].
 * - Eine Zeile bietet „Nicht wecken“ an: [NICHT_WECKEN_NUR_NAECHSTER].
 * - Sonst (nur stille Eintraege, nur freigegebene Tage, Skip ohne bekannten Zeitpunkt): keiner -
 *   jeder der beiden verspraeche einen Knopf, den es in der Liste nicht gibt.
 */
internal fun nichtWeckenHinweis(tage: List<WeckerTag>): String? {
    val eintraege = tage.flatMap { it.eintraege }
    return when {
        eintraege.any { it is WeckerEintrag.Uebersprungen } -> NUR_EIN_WECKER_AUSLASSEN
        eintraege.any { it is WeckerEintrag.Wecker && it.nichtWeckenMoeglich } -> NICHT_WECKEN_NUR_NAECHSTER
        else -> null
    }
}

/** Der Textknopf unter der Liste - dieselbe Geste, derselbe Name wie am Tag. */
internal const val ANDERER_TAG_KNOPF: String = "Dienst fällt an einem anderen Tag aus …"

// ---- Klang ------------------------------------------------------------------------------

/** "1 bis 30 Minuten" - aus den Grenzen in [AlarmPrefs], nie als eigene Zahl. */
internal val SCHLUMMER_BEREICH: String =
    "${AlarmPrefs.MIN_SNOOZE_MINUTES} bis ${AlarmPrefs.MAX_SNOOZE_MINUTES} Minuten"

/** Kurzer Hinweis, wenn eine Eingabe korrigiert wurde. Nennt den Wert, der jetzt gilt. */
internal fun schlummerHinweisText(hinweis: SchlummerEingabe.Hinweis, wert: Int): String = when (hinweis) {
    SchlummerEingabe.Hinweis.LEER -> "Leer – es bleibt bei $wert${NBSP}Min."
    SchlummerEingabe.Hinweis.KEINE_ZAHL -> "Keine Zahl – es bleibt bei $wert${NBSP}Min."
    SchlummerEingabe.Hinweis.ZU_KLEIN -> "Mindestens ${AlarmPrefs.MIN_SNOOZE_MINUTES} Minute – jetzt $wert${NBSP}Min."
    SchlummerEingabe.Hinweis.ZU_GROSS -> "Höchstens ${AlarmPrefs.MAX_SNOOZE_MINUTES} Minuten – jetzt $wert${NBSP}Min."
}

/** Was „Probehören“ tut - verspricht nur, was die Probe wirklich hält (Stopp bei ON_STOP). */
internal const val PROBE_BESCHREIBUNG: String =
    "Spielt deinen Weckton so, wie er morgens anläuft, mit deiner Alarm-Lautstärke. Endet von " +
        "selbst und sobald du die App verlässt."

/** "Läuft · 0:12 von 0:30 · 58 %" - der Pegel relativ zur Alarm-Lautstaerke des Geraets. */
internal fun probeLaeuftText(zustand: ProbeZustand.Laeuft): String {
    val prozent = (zustand.pegel.coerceIn(0f, 1f) * 100).roundToInt()
    return "Läuft · ${dauer(zustand.vergangenMs)} von ${dauer(zustand.gesamtMs)} · $prozent$NBSP%"
}

private fun dauer(ms: Long): String {
    val sekunden = (ms.coerceAtLeast(0L) / 1000L).toInt()
    return "%d:%02d".format(Locale.GERMAN, sekunden / 60, sekunden % 60)
}

/** Warum eine Probe nicht lief oder endete - [ProbeFehler] traegt bewusst keinen Text. */
internal fun probeFehlerText(fehler: ProbeFehler): String = when (fehler) {
    ProbeFehler.WECKER_LAEUFT ->
        "Ein Wecker klingelt gerade. Er hat Vorrang – Probehören ist aus."
    ProbeFehler.FOKUS_VERWEIGERT ->
        "Android lässt Probehören gerade nicht zu, zum Beispiel während eines Telefonats. " +
            "Ein Wecker klingelt trotzdem."
    ProbeFehler.UNTERBROCHEN ->
        "Ein anderer Ton hat übernommen – Probehören wurde beendet."
    ProbeFehler.KEIN_WECKTON ->
        "Es ließ sich kein Weckton öffnen – der Wecker hätte dasselbe Problem. Bitte in den " +
            "Android-Einstellungen einen Weckerton auswählen."
    ProbeFehler.WIEDERGABE_GESCHEITERT ->
        "Der Weckton ließ sich nicht abspielen. Bitte noch einmal versuchen."
}
