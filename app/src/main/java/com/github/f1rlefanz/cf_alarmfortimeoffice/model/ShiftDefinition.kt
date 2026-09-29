package com.github.f1rlefanz.cf_alarmfortimeoffice.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable
import java.time.LocalTime
import java.util.Locale

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
    val isSilent: Boolean = false,
    // "Rufbereitschaft": die EINE Quelle fuer die stuendliche Kalender-Abfrage an solchen Tagen
    // (service/RufbereitschaftAbfrage) und den Cutoff von "Nicht stoeren" (dnd/DndOnCallCutoffResolver).
    // Hergang: Skill cfalarm-kalender-und-schichten, reference/kalender-datenfluss.md.
    // Unabhaengig von [isSilent]: eine Rufbereitschaft kann still sein (typisch) oder klingeln.
    val isOnCall: Boolean = false
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
     * Muster werden GETRIMMT, und leere Muster matchen NIE: ein leeres Muster bestuende nur noch
     * aus den beiden Wortgrenzen und traefe als LEERER Treffer ueberall dort, wo beide Seiten
     * kein Wortzeichen sind. Der [name] zaehlt als zusaetzliches Muster, aber nur ab
     * [ShiftConfig.MIN_FUZZY_KEYWORD_LENGTH] Zeichen - sonst kehrt die einbuchstabige Falle zurueck.
     * Hergang: Skill cfalarm-kalender-und-schichten, reference/schichterkennung.md.
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
     * Eigene Grenzpruefung ueber Unicode-Kategorien statt `\b` (ASCII-basiert, invertiert die
     * Semantik bei Umlauten) und statt `(?U)` (definiert `\w`/`\d`/`\s` im ganzen Ausdruck um).
     * Hergang: Skill cfalarm-kalender-und-schichten, reference/schichterkennung.md.
     */
    private fun matchesAsWholeWord(lowercaseTitle: String, pattern: String): Boolean {
        val needle = pattern.trim().lowercase()
        if (needle.isEmpty()) return false
        return WordBoundaryPatterns.forNeedle(needle).containsMatchIn(lowercaseTitle)
    }
}

/**
 * Zwischenspeicher fuer die kompilierten Wortgrenzen-Muster.
 *
 * WARUM (Pruefrunde 7): [ShiftDefinition.matchesAsWholeWord] baute den Ausdruck bei JEDEM Aufruf
 * neu (`"...".toRegex()` kompiliert ein frisches [java.util.regex.Pattern]). Aufgerufen wird die
 * Funktion in der verschachtelten Schleife von
 * [com.github.f1rlefanz.cf_alarmfortimeoffice.shift.ShiftRecognitionEngine] - einmal pro
 * Termin x Definition x Muster - und direkt danach ein zweites Mal fuer dieselben Muster aus
 * [com.github.f1rlefanz.cf_alarmfortimeoffice.shift.ShiftCodeSuggester]. Bei 14 Tagen Dienstplan
 * und einer Handvoll Definitionen sind das mehrere hundert Kompilate pro Durchlauf, und der
 * Durchlauf hing bis zur selben Pruefrunde am Hauptthread.
 *
 * Die Muster stammen ausschliesslich aus der Schicht-Konfiguration des Nutzers (Keywords und
 * Definitionsnamen), nie aus Termintiteln - der Schluesselraum ist also klein und begrenzt.
 * [MAX_ENTRIES] ist trotzdem da, damit eine absurd grosse Konfiguration den Speicher nicht
 * unbegrenzt fuellt; ueberschreitet der Vorrat die Grenze, wird er komplett verworfen und baut
 * sich neu auf (nur ein Kostenthema, nie ein Korrektheitsthema).
 *
 * NEBENLAEUFIGKEIT: [Regex] ist unveraenderlich und erzeugt pro Suche einen eigenen Matcher, ist
 * also zwischen Threads teilbar. Zwei Threads koennen dasselbe Muster gleichzeitig kompilieren -
 * das kostet einmalig doppelt und ergibt zwei gleichwertige Objekte, aber nie ein falsches
 * Ergebnis. Deshalb bewusst kein Lock im heissen Pfad.
 */
internal object WordBoundaryPatterns {

    private const val MAX_ENTRIES = 256

    private val cache = java.util.concurrent.ConcurrentHashMap<String, Regex>()

    /** Nur Diagnose bzw. Test: wie oft wirklich kompiliert wurde. */
    private val compilations = java.util.concurrent.atomic.AtomicInteger()

    val compilationCount: Int get() = compilations.get()

    fun forNeedle(needle: String): Regex {
        cache[needle]?.let { return it }
        if (cache.size >= MAX_ENTRIES) cache.clear()
        val compiled = "$WORD_START${Regex.escape(needle)}$WORD_END".toRegex()
        compilations.incrementAndGet()
        cache[needle] = compiled
        return compiled
    }

    /** Setzt Vorrat und Zaehler zurueck - ausschliesslich fuer Tests. */
    internal fun resetForTest() {
        cache.clear()
        compilations.set(0)
    }
}

/**
 * Links des Musters darf kein Buchstabe, keine Ziffer und kein `_` stehen (unicode-faehig).
 * Dateiebene, nicht im Companion: `@Serializable` - ein private companion machte serializer() privat.
 */
private const val WORD_START = "(?<![\\p{L}\\p{N}_])"

/** Dasselbe rechts des Musters. Siehe [WORD_START]. */
private const val WORD_END = "(?![\\p{L}\\p{N}_])"


