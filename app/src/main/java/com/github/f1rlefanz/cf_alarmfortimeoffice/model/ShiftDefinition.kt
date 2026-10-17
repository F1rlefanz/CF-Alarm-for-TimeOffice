package com.github.f1rlefanz.cf_alarmfortimeoffice.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.SerialName
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
    val isOnCall: Boolean = false,
    // #142 "Krankmeldung": ein Termin dieses Typs ist KEINE Schicht, er gibt den ganzen Kalendertag
    // frei (FreieTageStore, Kalender-Schluessel). Default false -> alte JSONs lesen sich unveraendert,
    // kein Formatbump. Eine AELTERE App liest das Feld nicht (ignoreUnknownKeys) und haelt den Typ fuer
    // eine normale Schicht - bewusst akzeptiert, dokumentiert im Kalender-Skill.
    // alarmTime bleibt formal Pflicht (Serialisierung), hat bei isSickDay aber keine Bedeutung.
    val isSickDay: Boolean = false,
    // Umbau E1: der gespeicherte [ShiftKategorie.schluessel]. PRIVAT, damit niemand ihn liest:
    // `copy(isSickDay = ...)` aus dem heutigen Editor laesst ihn stehen, wirksam ist allein
    // [kategorie]. `null` = Bestand vor dem Umbau, die Kategorie wird aus den Flags abgeleitet
    // (beim Lesen, kein Schreibschritt). Text statt Enum: siehe [ShiftKategorie].
    @SerialName("kategorie")
    private val kategorieGespeichert: String? = null,
    // Umbau E2 „Wecker an/aus“ (Vorgabe an). NICHT [isEnabled] - das heisst weiter „Typ wird
    // erkannt“. Wirkt erst mit dem Paket „Wecker aus am Typ“; eine aeltere App liest das Feld nicht.
    val weckerAn: Boolean = true
) {
    /**
     * Die wirksame Kategorie (Umbau E1) - immer deckungsgleich mit den Flags, weil sie aus ihnen
     * gelesen wird: [isSickDay] == (KRANK), [isOnCall] == (RUFBEREITSCHAFT). Einzige Ausnahme ist
     * der Altbestand mit BEIDEN Flags: er gilt als KRANK, sein `isOnCall` bleibt unangetastet
     * stehen und wirkt nirgends (siehe [ShiftKategorie.ableiten]). Aendern nur ueber [mitKategorie].
     */
    val kategorie: ShiftKategorie
        get() = ShiftKategorie.ableiten(isSickDay, isOnCall, ShiftKategorie.vonSchluessel(kategorieGespeichert))

    /**
     * Derselbe Typ in [neu]: setzt die Kategorie UND beide Flags, damit alter Code und heutige
     * Leser dasselbe sehen. Alles andere bleibt (E2: ein Wechsel der Kategorie behaelt die
     * Einstellungen). Erzeugt nie einen Typ mit beiden Flags.
     */
    fun mitKategorie(neu: ShiftKategorie): ShiftDefinition = copy(
        kategorieGespeichert = neu.schluessel,
        isSickDay = neu == ShiftKategorie.KRANK,
        isOnCall = neu == ShiftKategorie.RUFBEREITSCHAFT
    )


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
     *
     * Ein Muster aus REINEN ZEICHEN ("+" fuer frei, kein Buchstabe, keine Ziffer) trifft nur den
     * GANZEN getrimmten Titel (#150) - siehe [matchesAsWholeWord].
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
     *
     * AUSNAHME Zeichenmuster (#150): Hat [pattern] keinen Buchstaben und keine Ziffer, finden die
     * Grenzen keinen Halt - sie pruefen nur, dass neben dem Muster kein Wortzeichen (Buchstabe,
     * Ziffer, `_`) steht, und "+"
     * traf so auch "C++" und "x + y". Ein solches Muster muss deshalb dem ganzen Titel entsprechen.
     */
    private fun matchesAsWholeWord(lowercaseTitle: String, pattern: String): Boolean {
        val needle = pattern.trim().lowercase()
        if (needle.isEmpty()) return false
        if (istZeichenmuster(needle)) return lowercaseTitle.trim() == needle
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

/**
 * Dieselben Zeichenklassen wie [WORD_START]/[WORD_END]: ein Muster ohne einen einzigen Treffer
 * hierauf ist ein Zeichenmuster und trifft nur den ganzen Titel (#150).
 */
private val BUCHSTABE_ODER_ZIFFER = Regex("[\\p{L}\\p{N}]")

/**
 * Hat [muster] keinen einzigen Buchstaben und keine Ziffer ("+", "---")? Dann trifft es nur den
 * ganzen Titel (#150). Die Warnung im Schicht-Editor fragt dasselbe, damit sie nie etwas anderes
 * behauptet, als die Erkennung tut.
 */
internal fun istZeichenmuster(muster: String): Boolean = !BUCHSTABE_ODER_ZIFFER.containsMatchIn(muster)

/**
 * #142/#146: Ist [titel] eine Krankmeldung? Die EINE Regel - nur aktive Krank-Typen, Keyword-Treffer.
 * `ShiftRecognitionEngine` (Krank hat Vorrang vor jeder Schicht) und die Karten im Home-Tab fragen
 * beide hier, damit die Oberflaeche nicht anders zaehlt als der Abgleich.
 */
fun List<ShiftDefinition>.istKrankmeldung(titel: String): Boolean =
    any { it.isEnabled && it.isSickDay && it.matchesKeywords(titel) }
