package com.github.f1rlefanz.cf_alarmfortimeoffice.shift

import com.github.f1rlefanz.cf_alarmfortimeoffice.model.CalendarEvent
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftConfig
import java.time.LocalDate

/**
 * Ein Kuerzel, das im Kalender des Nutzers vorkommt, aber von keinem Erkennungsmuster getroffen
 * wird - also ein Kandidat fuer eine Zuordnung.
 *
 * @param code der Termintitel, so wie er im Kalender steht (getrimmt, sonst unveraendert)
 * @param occurrences wie oft er im geladenen Zeitraum vorkommt
 * @param firstDate erstes Vorkommen - macht dem Nutzer greifbar, worum es geht
 */
data class ShiftCodeSuggestion(
    val code: String,
    val occurrences: Int,
    val firstDate: LocalDate
)

/**
 * Schlaegt Schicht-Kuerzel aus dem TATSAECHLICHEN Kalender des Nutzers vor.
 *
 * WARUM DAS DER RICHTIGE WEG IST (und bessere Vorgaben nicht):
 * Die Standardkonfiguration kann nur raten. Sie trug lange die Kuerzel EINER Station
 * ("IMCF"/"IMCS"/"IMCN"/"IMCZ"); fuer einen Kollegen auf einer anderen Station traf davon nichts,
 * er haette NIE einen Wecker bekommen und es erst nach dem Verschlafen gemerkt. Ein Versuch, die
 * Vorgaben "generischer" zu machen, hat am echten Dienstplan des Entwicklers die Erkennung von 4
 * auf 1 Schicht gesenkt - Raten skaliert nicht.
 * Was im Kalender steht, weiss die App dagegen genau. Sie muss es nur zeigen und zuordnen lassen.
 *
 * ES BLEIBT EIN VORSCHLAG. Die App ordnet NICHTS selbst zu: eine stille Automatik, die danebengreift,
 * stellt einen Wecker auf die falsche Uhrzeit - und das ist schlimmer als gar keiner, weil der
 * Nutzer sich darauf verlaesst. Diese Funktion liefert deshalb nur Kandidaten samt Haeufigkeit; die
 * Zuordnung zu einer Schichtdefinition macht der Mensch.
 */
object ShiftCodeSuggester {

    /**
     * Mehr als so viele Vorschlaege ueberfordern die Karte. Was darueber liegt, wird NICHT
     * verschwiegen, sondern als Anzahl gemeldet ([SuggestionResult.droppedCount]).
     */
    const val MAX_SUGGESTIONS = 8

    /**
     * Titel ab dieser Laenge sind keine Kuerzel mehr, sondern Freitext ("Zahnarzt Dr. Mueller").
     * Als Erkennungsmuster waeren sie nutzlos und wuerden die Liste zumuellen. Grosszuegig
     * gewaehlt, damit echte Bezeichnungen wie "Zwischendienst" oder "Nachtdienst lang" drin bleiben.
     */
    const val MAX_CODE_LENGTH = 24

    /**
     * @param suggestions die Kandidaten, haeufigste zuerst
     * @param droppedCount wie viele weitere Kandidaten es gibt, die nicht in die Liste passten -
     *        bewusst sichtbar, damit die Deckelung nicht wie Vollstaendigkeit aussieht
     */
    data class SuggestionResult(
        val suggestions: List<ShiftCodeSuggestion>,
        val droppedCount: Int
    ) {
        val isEmpty: Boolean get() = suggestions.isEmpty()
    }

    /**
     * @param events die geladenen Kalendertermine
     * @param config die aktuelle Schichtkonfiguration - Titel, die von IRGENDEINER Definition
     *        getroffen werden, sind keine Kandidaten (auch von einer ausgeschalteten: sie ist
     *        eine Zuordnung, nur eben eine wirkungslose - das gehoert in die Schichttypen-Liste)
     * @param maxSuggestions Deckelung, per Parameter nur fuer Tests
     */
    fun suggest(
        events: List<CalendarEvent>,
        config: ShiftConfig,
        maxSuggestions: Int = MAX_SUGGESTIONS
    ): SuggestionResult {
        // ALLE Definitionen zaehlen, auch ausgeschaltete - NICHT nur die aktivierten.
        //
        // WARUM: Diese Karte beantwortet genau eine Frage - "zu welchem Kuerzel in deinem Kalender
        // gibt es noch keine Zuordnung?". Eine ausgeschaltete Definition IST eine Zuordnung. Bis
        // v1.29.1 filterte diese Zeile auf `isEnabled`, und die Karte forderte den Nutzer deshalb
        // auf, ein Kuerzel zuzuordnen, das er laengst zugeordnet hatte - am 19.08.2026 am echten
        // Kalender aufgefallen ("AD1" als Abrufdienst angelegt, aber ausgeschaltet). Der
        // Kartentext behauptete dabei woertlich "Fuer sie gibt es noch kein Erkennungsmuster",
        // und das war schlicht falsch.
        //
        // Der Zustand einer Definition gehoert in die Schichttypen-Liste, nicht hierher: dort
        // steht bei jeder ausgeschalteten Definition, was das kostet. Diese Karte still zu halten
        // ist deshalb kein Verschweigen, sondern die richtige Arbeitsteilung - und ein Vorschlag,
        // der beim Antippen nur wiederholt, was schon existiert, ist eine Sackgasse.
        val zugeordneteDefinitionen = config.definitions

        val candidates = events
            .asSequence()
            .mapNotNull { event -> normalizeCode(event.title)?.let { it to event } }
            // Schon getroffene Titel sind kein Problem, das der Nutzer loesen muesste.
            .filterNot { (code, _) -> zugeordneteDefinitionen.any { it.matchesKeywords(code) } }
            .groupBy({ it.first }, { it.second })

        val ranked = candidates
            .map { (code, group) ->
                ShiftCodeSuggestion(
                    code = code,
                    occurrences = group.size,
                    firstDate = group.minOf { it.startTime.toLocalDate() }
                )
            }
            // Haeufigstes zuerst; bei Gleichstand alphabetisch, damit die Reihenfolge stabil ist
            // und nicht bei jedem Laden springt.
            .sortedWith(compareByDescending<ShiftCodeSuggestion> { it.occurrences }.thenBy { it.code })

        return SuggestionResult(
            suggestions = ranked.take(maxSuggestions),
            droppedCount = (ranked.size - maxSuggestions).coerceAtLeast(0)
        )
    }

    /**
     * Macht aus einem Termintitel einen Kuerzel-Kandidaten - oder null, wenn er keiner sein kann.
     *
     * Zwei Ausschlussgruende, beide technisch begruendet und nicht Geschmackssache:
     *  - Zu lang: Freitext ist als Erkennungsmuster nutzlos (siehe [MAX_CODE_LENGTH]).
     *  - Kein einziger Buchstabe und keine Ziffer: [com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftDefinition.matchesKeywords]
     *    arbeitet mit Wortgrenzen ueber Unicode-Buchstaben/Ziffern. Ein Titel wie "+" (steht real
     *    im Dienstplan des Entwicklers) koennte als Muster also NIE treffen - ihn vorzuschlagen
     *    waere ein Versprechen, das die Erkennung nicht halten kann.
     */
    internal fun normalizeCode(rawTitle: String): String? {
        val code = rawTitle.trim()
        if (code.isEmpty()) return null
        if (code.length > MAX_CODE_LENGTH) return null
        if (code.none { it.isLetterOrDigit() }) return null
        return code
    }
}
