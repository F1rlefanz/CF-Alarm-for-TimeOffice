package com.github.f1rlefanz.cf_alarmfortimeoffice.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable
import java.time.LocalTime
import java.util.Locale

/**
 * IMMUTABLE Shift Definition Model
 * 
 * PERFORMANCE OPTIMIZATIONS:
 * ✅ @Immutable annotation für Compose-Performance
 * ✅ Unveränderliche Keywords-Liste für bessere Cache-Performance
 */
@Immutable
@Serializable
data class ShiftDefinition(
    val id: String,
    val name: String,
    val keywords: List<String>,
    @Serializable(with = LocalTimeSerializer::class)
    val alarmTime: LocalTime,
    val isEnabled: Boolean = true,
    // "Stille Schicht" (z.B. Rufbereitschaft AD1): alarmTime bleibt Pflicht-Anker fuer
    // DND/Dimmer/Hue, aber Ton/Vibration/Vollbild-Wecker werden beim Feuern unterdrueckt.
    // Bewusst KEIN Ersatz fuer eine optionale alarmTime - siehe CLAUDE.md "Stille Schicht".
    val isSilent: Boolean = false
) {
    /**
     * Get alarm time as formatted string for display
     */
    fun getAlarmTimeFormatted(): String {
        return String.format(Locale.US, "%02d:%02d", alarmTime.hour, alarmTime.minute)
    }
    
    /**
     * Get alarm local time for scheduling
     */
    fun getAlarmLocalTime(): LocalTime = alarmTime
    
    /**
     * Trifft dieser Schichttyp auf den Titel eines Kalendertermins zu?
     *
     * Gematcht wird auf WORTGRENZEN ([matchesAsWholeWord]) - "F" trifft "Meeting F", aber nicht
     * "Fortbildung" oder "12F". Das ist Absicht und getestet; nicht auf `contains` umbauen.
     *
     * ZWEI DINGE, DIE HIER LEICHT ZURUECKGEDREHT WERDEN:
     *
     * 1. Muster werden GETRIMMT, und leere Muster matchen NIE. Der [ShiftEditDialog] trimmte
     *    beim Speichern nur den Namen, nicht die Muster - ein per Autovervollstaendigung
     *    angehaengtes Leerzeichen wurde als " IMCF" gespeichert und ergab den Regex
     *    `\b\Q imcf\E\b`: das fuehrende `\b` verlangt ein Wortzeichen VOR dem Leerzeichen, ein
     *    Titel "IMCF" traf also nicht mehr. Die UI zeigte dabei genau das Muster, das der Nutzer
     *    erwartete. Der Trim hier repariert zusaetzlich schon gespeicherte Altwerte.
     *    Die Blank-Pruefung ist kein Luxus: ein leeres Muster ergaebe einen Ausdruck, der nur noch
     *    aus den beiden Wortgrenzen besteht, und der trifft als LEERER Treffer ueberall dort, wo
     *    beide Seiten kein Wortzeichen sind (Titelanfang vor einem Leerzeichen, zwei Leerzeichen
     *    hintereinander, Satzzeichen). Ein leeres Muster wuerde also Termine erkennen, ohne
     *    irgendetwas zu bedeuten.
     *
     * 2. Der [name] zaehlt als zusaetzliches Muster - aber nur ab [ShiftConfig.MIN_FUZZY_KEYWORD_LENGTH]
     *    Zeichen. Vorher matchte AUSSCHLIESSLICH [keywords]: ein Termin, der wortwoertlich
     *    "Zwischendienst" hiess, wurde von der Definition "Zwischendienst" NICHT erkannt, solange
     *    deren Muster nur das Stationskuerzel enthielt. Fuer einen Nutzer ist das nicht
     *    erklaerbar - er sieht den Namen und haelt ihn fuer das Erkennungsmerkmal. Die
     *    Laengengrenze ist Pflicht: ein Definitionsname "F" wuerde sonst genau die einbuchstabige
     *    Falle zurueckholen, gegen die [ShiftConfig.MIN_FUZZY_KEYWORD_LENGTH] existiert.
     */
    fun matchesKeywords(eventTitle: String): Boolean {
        val title = eventTitle.lowercase()
        if (title.isBlank()) return false

        if (keywords.any { matchesAsWholeWord(title, it) }) return true

        val trimmedName = name.trim()
        return trimmedName.length >= ShiftConfig.MIN_FUZZY_KEYWORD_LENGTH &&
            matchesAsWholeWord(title, trimmedName)
    }

    /**
     * Trifft [pattern] in [lowercaseTitle] als EIGENSTAENDIGES Wort?
     *
     * WARUM EIGENE GRENZPRUEFUNG STATT `\b` (Fix v1.22.2): Javas `\b` ist ohne
     * `UNICODE_CHARACTER_CLASS` ASCII-basiert - Wortzeichen sind nur `[a-zA-Z0-9_]`. Umlaute und
     * `ß` zaehlen also als NICHT-Wortzeichen, und das dreht die Semantik fuer jedes Muster um, das
     * mit einem Umlaut beginnt oder endet - genau ins Gegenteil des Gewollten. Selbst nachgemessen
     * (javac/java, UTF-8, 10.08.2026):
     *
     *  - `\büd\b` trifft "üd" NICHT und "station üd" NICHT (vor dem "ü" steht ein
     *    Nicht-Wortzeichen bzw. der Zeilenanfang - dort verlangt `\b` aber ein Wortzeichen),
     *  - `\büd\b` trifft dafuer "xüd" - also ausgerechnet MITTEN IM WORT.
     *
     * Echte deutsche Schichtbezeichnungen sind davon betroffen ("Übergabedienst", "Ärztlicher
     * Dienst", Kuerzel wie "ÜD"), und seit der [name] als zusaetzliches Muster zaehlt, sagt der
     * Dialogtext dem Nutzer ausdruecklich zu, dass sein Schichtname erkannt wird.
     *
     * Gewaehlt sind Lookarounds ueber Unicode-Kategorien statt `Pattern.UNICODE_CHARACTER_CLASS`
     * bzw. dem Inline-Flag `(?U)`: `(?U)` wuerde `\b` unicode-faehig machen, aber gleichzeitig
     * `\w`/`\d`/`\s` im GESAMTEN Ausdruck umdefinieren - hier harmlos, weil das Muster durch
     * [Regex.escape] literal ist, aber eine Fernwirkung, die bei der naechsten Erweiterung des
     * Ausdrucks stillschweigend mitkommt. Die Lookarounds sagen genau das, was gemeint ist:
     * links und rechts des Musters darf kein Buchstabe, keine Ziffer und kein `_` stehen.
     *
     * Die bestehende Zusicherung bleibt unveraendert erhalten und ist in [ShiftDefinitionTest]
     * festgehalten: ein einzelner Buchstabe trifft weiterhin NICHT innerhalb eines Wortes ("F"
     * nicht in "Fortbildung", "S" nicht in "S2"), und "Früh" trifft weiterhin nicht in
     * "Frühschicht".
     */
    private fun matchesAsWholeWord(lowercaseTitle: String, pattern: String): Boolean {
        val needle = pattern.trim().lowercase()
        if (needle.isEmpty()) return false
        return "$WORD_START${Regex.escape(needle)}$WORD_END".toRegex()
            .containsMatchIn(lowercaseTitle)
    }
}

/**
 * Links des Musters darf kein Buchstabe, keine Ziffer und kein `_` stehen (unicode-faehig).
 *
 * Bewusst auf DATEIEBENE und nicht in einem `companion object` von [ShiftDefinition]: die Klasse
 * ist `@Serializable`, und kotlinx.serialization legt `serializer()` in ihren Companion. Ein
 * `private companion object` hier macht damit auch `ShiftDefinition.serializer()` privat - der
 * bestehende `ShiftConfigSerializationTest` bricht sofort. (Genau so passiert, 10.08.2026.)
 */
private const val WORD_START = "(?<![\\p{L}\\p{N}_])"

/** Dasselbe rechts des Musters. Siehe [WORD_START]. */
private const val WORD_END = "(?![\\p{L}\\p{N}_])"


