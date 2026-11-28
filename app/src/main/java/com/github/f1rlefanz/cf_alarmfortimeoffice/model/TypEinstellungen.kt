package com.github.f1rlefanz.cf_alarmfortimeoffice.model

import androidx.compose.runtime.Immutable
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimWindow
import com.github.f1rlefanz.cf_alarmfortimeoffice.dnd.DndPrefs
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.HueScheduleRule
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/*
 * Umbau Etappe 3 (#213, SPEC E6/E7/Abschnitt 4): Dimmen und „Nicht stoeren“ im gespeicherten
 * Modell des Schichttyps ([ShiftDefinition.dimmen], [ShiftDefinition.nichtStoeren]) und der festen
 * Zeile „Ohne Kalendereintrag“ ([ShiftConfig.ohneKalendereintrag]). Noch liest sie niemand - die
 * Fenster-Quelle des Dimmers bleibt bis zur Migration die Regelliste (`DimRuleRepository`).
 * Etappe 4 (#246, SPEC E8) ergaenzt das Licht ([ShiftDefinition.licht], [TypLicht]); auch das liest
 * noch niemand, die Hue-Regeln bleiben bis zur Licht-Migration die Quelle.
 *
 * Jedes Feld hat einen Default: ein Bestand von vor dem Umbau liest sich damit unveraendert, und
 * eine aeltere App ueberliest die Schluessel (`ignoreUnknownKeys`), Test: TypEinstellungenModellTest.
 */

/**
 * Ein Dimm-Fenster am Typ: das [DimWindow] UNVERAENDERT (der Resolver liest weiter genau dieses
 * Format, Festlegung des Betreuers in #213) plus die Staerke („Mit %“), die heute an der ganzen
 * Regel haengt (`DimRule.strength`).
 */
@Immutable
@Serializable
data class TypDimmFenster(
    val fenster: DimWindow = DimWindow(),
    val staerke: Int = STANDARD_STAERKE
) {
    companion object {
        /** Wie der Default von `DimRule.strength`: ein Fenster ohne gespeicherte Staerke dimmt wie bisher. */
        const val STANDARD_STAERKE = 55
    }
}

/**
 * „Bildschirm dimmen“ eines Typs (E6).
 *
 * @property an „Dimmen an diesem Tag“. Default an; noetig, weil migrierte Fenster nach SPEC 4
 *   „Ausgeschaltet bleibt ausgeschaltet“ AUS ankommen koennen, ohne verloren zu gehen.
 * @property farbwaerme die Farbwaerme des Typs; `null` = Vorgabe aus den Einstellungen (`dim_warmth`).
 *
 * WARUM DIE FENSTER ROH GESPEICHERT SIND ([fensterGespeichert], gedeutet in [fenster]):
 * [DimWindow] traegt drei kotlinx-Enums (`DimAnchor`, `Blockposition`, `HellAb`), und
 * `ShiftConfigRepository.json` hat kein `coerceInputValues`. Ein unbekannter Wert - eine spaetere
 * Version fuehrt einen neuen Anker ein und wird per Revert zurueckgenommen (ABLAUF 7) - machte sonst
 * die GANZE Schicht-Konfiguration `Broken`, und mit ihr den Wecker-Abgleich (Skill
 * cfalarm-persistenz-und-auth, #166: Aufzaehlungen als Text). Hier bleibt ein undeutbares Fenster
 * wortgleich gespeichert, dimmt aber nicht („im Zweifel NICHT verdunkeln“), und JEDES Zurueckschreiben
 * - auch [mitFenster] - nimmt es mit: eine stille Degradierung wird nie zur Schreibwahrheit.
 * Ein verworfenes Fenster waere beim naechsten Speichern verloren gewesen.
 */
@Immutable
@Serializable
data class TypDimmen(
    val an: Boolean = true,
    @SerialName("fenster")
    private val fensterGespeichert: List<JsonElement> = emptyList(),
    val farbwaerme: Int? = null
) {
    /** Die deutbaren Fenster in gespeicherter Reihenfolge; ein undeutbares fehlt hier (siehe Klasse). */
    @Transient
    val fenster: List<TypDimmFenster> = fensterGespeichert.mapNotNull(::deuteFenster)

    /** Wie viele gespeicherte Fenster diese Version nicht deuten kann - fuer die Diagnose des Lesers. */
    val unlesbareFenster: Int get() = fensterGespeichert.size - fenster.size

    /**
     * Dieselben Einstellungen mit den Fenstern [neu]. Undeutbare gespeicherte Fenster bleiben
     * angehaengt erhalten (siehe Klasse) - sie gehoeren einer anderen Version, nicht diesem Editor.
     */
    fun mitFenster(neu: List<TypDimmFenster>): TypDimmen = copy(
        fensterGespeichert = neu.map { TYP_JSON.encodeToJsonElement(TypDimmFenster.serializer(), it) } +
            fensterGespeichert.filter { deuteFenster(it) == null }
    )
}

/**
 * „Nicht stoeren“ eines Typs (E7): zwei Schalter, beide Default aus.
 *
 * Das Ende („Endet spaetestens um“) wirkt nur bei Rufbereitschaft und ist im LESEPFAD geklemmt:
 * Import und Android-Backup umgehen jede Eingabepruefung, und ein Wert ausserhalb des Tages wirft
 * spaetestens in `LocalTime.ofSecondOfDay` (dieselbe Klemme wie `DndPrefs.onCallCutoffMinutes`).
 */
@Immutable
@Serializable
data class TypNichtStoeren(
    /** „Solange gedimmt wird“ (heute global: `dnd_follow_dimmer_enabled`). */
    val solangeGedimmt: Boolean = false,
    /** „Waehrend des Kalendereintrags“ (heute global: `dnd_during_shift_enabled`). */
    val waehrendEintrag: Boolean = false,
    @SerialName("endetSpaetestensMinuten")
    private val endetSpaetestensGespeichert: Int = DndPrefs.DEFAULT_ONCALL_CUTOFF_MIN
) {
    /** „Endet spaetestens um“ in Minuten seit Mitternacht, geklemmt auf 0..1439. */
    val endetSpaetestensMinuten: Int
        get() = endetSpaetestensGespeichert.coerceIn(0, LETZTE_MINUTE_DES_TAGES)
}

/**
 * Die eigenen Einstellungen der festen Zeile „Ohne Kalendereintrag“ (gelten nur, solange sie nicht
 * „wie <Typ>“ ist, [ShiftConfig.ohneKalendereintragWie]). Nur Dimmen und „Solange gedimmt wird“ -
 * „Waehrend des Kalendereintrags“ gibt es hier nicht, an diesen Tagen steht nichts im Kalender
 * (Prototyp: der Schalter ist dort gesperrt).
 */
@Immutable
@Serializable
data class OhneEintragEinstellungen(
    val dimmen: TypDimmen = TypDimmen(),
    val nichtStoerenSolangeGedimmt: Boolean = false
)

/**
 * „Licht (Hue)“ eines Typs (E8, #246): die Lichtregeln, die mit dem Wecker dieses Typs starten.
 *
 * DAS REGELFORMAT IST [HueScheduleRule] (Aktionen, Szene, Sonnenaufgang, `hellAb`, `ausBeiHell`) -
 * es gibt keine zweite Regel-Klasse (Festlegung des Betreuers in #246). Zwei Felder der Regel haben
 * am Typ KEINE Bedeutung und werden hier wortgleich gespeichert, aber nie gelesen:
 * - `shiftPattern`: welcher Typ gemeint ist, sagt der Ort der Regel. Wer sie ausfuehrt, setzt den
 *   Typnamen ein. Deshalb braucht das Feld auch keinen Eintrag im Umbenennungs-Nachzug.
 * - `id` hat den Uhrzeit-Default `HueScheduleRule.generateId()`: eine gespeicherte Regel OHNE `id`
 *   (Fremdbestand) bekommt bei jedem Lesen eine neue. [mitRegeln] schreibt die `id` immer mit
 *   (`encodeDefaults`), danach ist sie fest. Bewusst hingenommen, keine undeutbare Regel.
 *
 * WARUM ROH GESPEICHERT ([regelnGespeichert], gedeutet in [regeln]) - dasselbe Muster wie
 * [TypDimmen]: die Regel traegt kotlinx-Enums (`TargetType`, `ActionType`, `HellAb`), und
 * `ShiftConfigRepository.json` hat kein `coerceInputValues`. Ein unbekannter Wert (spaetere Version,
 * dann Revert; `ZONE`/`ROOM` gab es bis #19) machte sonst die GANZE Schicht-Konfiguration `Broken`
 * und mit ihr den Wecker-Abgleich. Hier bleibt eine undeutbare Regel wortgleich gespeichert und
 * laeuft nur nicht; JEDES Zurueckschreiben - auch [mitRegeln] - nimmt sie mit. Leser nehmen [regeln],
 * Schreiber [mitRegeln], nie `copy(regelnGespeichert = ...)`.
 */
@Immutable
@Serializable
data class TypLicht(
    @SerialName("regeln")
    private val regelnGespeichert: List<JsonElement> = emptyList()
) {
    /** Die deutbaren Regeln in gespeicherter Reihenfolge; eine undeutbare fehlt hier (siehe Klasse). */
    @Transient
    val regeln: List<HueScheduleRule> = regelnGespeichert.mapNotNull(::deuteRegel)

    /** Wie viele gespeicherte Regeln diese Version nicht deuten kann - fuer die Diagnose des Lesers. */
    val unlesbareRegeln: Int get() = regelnGespeichert.size - regeln.size

    /**
     * Dasselbe Licht mit den Regeln [neu]. Undeutbare gespeicherte Regeln bleiben angehaengt erhalten
     * (siehe Klasse) - sie gehoeren einer anderen Version, nicht diesem Editor.
     */
    fun mitRegeln(neu: List<HueScheduleRule>): TypLicht = copy(
        regelnGespeichert = neu.map { TYP_JSON.encodeToJsonElement(HueScheduleRule.serializer(), it) } +
            regelnGespeichert.filter { deuteRegel(it) == null }
    )
}

private const val LETZTE_MINUTE_DES_TAGES = 24 * 60 - 1

/**
 * Json-Flags wie `ShiftConfigRepository.json`; Enums bleiben STRENG, das Fangen machen [deuteFenster]
 * und [deuteRegel].
 */
private val TYP_JSON = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

/** `null`, wenn diese Version das Fenster nicht lesen kann (unbekannter Enum-Wert, falscher Typ). */
private fun deuteFenster(roh: JsonElement): TypDimmFenster? = try {
    TYP_JSON.decodeFromJsonElement(TypDimmFenster.serializer(), roh)
} catch (_: IllegalArgumentException) {
    // SerializationException ist eine IllegalArgumentException.
    null
}

/** `null`, wenn diese Version die Lichtregel nicht lesen kann (unbekannter Enum-Wert, fehlendes Feld). */
private fun deuteRegel(roh: JsonElement): HueScheduleRule? = try {
    TYP_JSON.decodeFromJsonElement(HueScheduleRule.serializer(), roh)
} catch (_: IllegalArgumentException) {
    // SerializationException ist eine IllegalArgumentException.
    null
}
