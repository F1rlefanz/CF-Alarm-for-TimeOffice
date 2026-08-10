package com.github.f1rlefanz.cf_alarmfortimeoffice.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable
import java.time.LocalTime

/**
 * IMMUTABLE Shift Configuration Model
 * 
 * ARCHITECTURE (Briefing 4.0):
 * ✅ @Immutable annotation for Compose performance
 * ✅ Immutable lists for better performance
 * ✅ Removed: daysAhead (fixed 14d in maintenance)
 * ✅ Removed: syncIntervalHours (fixed 6h via Exact Alarm)
 */
@Immutable
@Serializable
data class ShiftConfig(
    val autoAlarmEnabled: Boolean = true,
    val definitions: List<ShiftDefinition> = emptyList()
) {
    /**
     * Die Definition, zu der [shiftName] gehoert - streng nach Genauigkeit gestaffelt.
     * Entscheidet, WELCHE Hue-Regeln ein Alarm ausfuehrt.
     *
     * WARUM GESTAFFELT UND NICHT EIN find{} MIT ODER-KETTE:
     * Genau das stand im AlarmReceiver, und es hat die Hue-Regeln fuer fast jede Schicht
     * stillgelegt. `find` nimmt den ERSTEN Treffer in Listenreihenfolge, und die Bedingung
     * enthielt ein `shiftName.contains(keyword)`. Die Standard-Schichten tragen einbuchstabige
     * Keywords ("F", "S", "N"), und "Spaetschicht" (Keyword "S") steht VOR "S2":
     *   - "S2"             enthaelt "S"  -> Spaetschicht  (statt S2)
     *   - "Nachtschicht"   enthaelt "s"  -> Spaetschicht  (statt Nachtschicht)
     *   - "Zwischendienst" enthaelt "s"  -> Spaetschicht  (statt Zwischendienst)
     * Nur "Fruehschicht" kam korrekt an, weil es zufaellig als erstes in der Liste steht.
     * Folge: eine Regel fuer S2 feuerte nie, und eine Regel fuer Spaetschicht feuerte bei JEDER
     * dieser Schichten - die falschen Lampen zur falschen Zeit. Am Emulator gegen die echte
     * Standardkonfiguration reproduziert (16.07.2026).
     *
     * [shiftName] ist der NAME der Definition (die App setzt ihn beim Anlegen des Alarms aus
     * [ShiftDefinition.name]) - Stufe 1 trifft also immer. Die Keyword-Stufen sind Notnaegel
     * fuer den Fall, dass eine Definition nach dem Setzen des Alarms umbenannt wurde.
     *
     * Wer hier wieder ein `contains` nach vorne zieht, baut den Fehler neu.
     *
     * @return die Definition, oder null wenn keine passt (dann lieber keine Regel als die
     *         falschen Lampen).
     */
    fun findDefinitionFor(shiftName: String): ShiftDefinition? {
        if (definitions.isEmpty()) return null

        // 1. Exakter Name - der Normalfall.
        definitions.firstOrNull { it.name.equals(shiftName, ignoreCase = true) }?.let { return it }

        // 2. Exaktes Keyword.
        definitions.firstOrNull { def ->
            def.keywords.any { it.equals(shiftName, ignoreCase = true) }
        }?.let { return it }

        // 3. Teiltreffer - nur wenn oben nichts passte, und nur mit Keywords, die lang genug
        //    sind, um etwas zu bedeuten. Ein einzelner Buchstabe passt auf zu vieles.
        return definitions.firstOrNull { def ->
            def.keywords.any { keyword ->
                keyword.length >= MIN_FUZZY_KEYWORD_LENGTH &&
                    shiftName.contains(keyword, ignoreCase = true)
            }
        }
    }

    companion object {
        /**
         * Ab dieser Laenge darf ein Keyword ueberhaupt noch unscharf (per `contains`) auf einen
         * Schichtnamen passen. Die Standard-Keywords "F"/"S"/"N" liegen bewusst darunter: ein
         * einzelner Buchstabe steckt in fast jedem Schichtnamen ("Nacht**s**chicht") und hat so
         * die falsche Definition gewaehlt. Siehe [findDefinitionFor].
         */
        const val MIN_FUZZY_KEYWORD_LENGTH = 2

        /**
         * Die Konfiguration, die ein Nutzer OHNE eigene Anpassung bekommt.
         *
         * WARUM DIE EINBUCHSTABIGEN KEYWORDS "F"/"S"/"N" HIER STEHEN - UND WARUM SIE EINMAL
         * ENTFERNT WAREN:
         * Ein Aufraeumschritt hatte sie mit der Begruendung entfernt, ein privater Termin
         * "Kino mit F" koenne einen Wecker um 05:30 erzeugen, und dabei auf
         * [MIN_FUZZY_KEYWORD_LENGTH] verwiesen. Das war ein Verwechslungsfehler zwischen zwei
         * verschiedenen Funktionen:
         *  - [findDefinitionFor] ordnet einem BESTEHENDEN Alarm eine Definition zu und vergleicht
         *    unscharf per `contains` OHNE Wortgrenzen. Dort ist ein einzelner Buchstabe wirklich
         *    gefaehrlich ("S" steckt in "Nacht**s**chicht") - deshalb gilt dort
         *    [MIN_FUZZY_KEYWORD_LENGTH] = 2, und das bleibt so.
         *  - [ShiftDefinition.matchesKeywords] erkennt Schichten in KALENDERTITELN und arbeitet
         *    mit Wortgrenzen. "F" trifft dort nur ein alleinstehendes F, nicht "Fruehschicht" und
         *    nicht "Fortbildung".
         * Am Emulator gegen den echten Dienstplan-Feed nachgewiesen (10.08.2026): ohne die
         * Buchstaben sank die Erkennung von 4 auf 1 Schicht. Die real vorkommenden Titel sind
         * kurze Codes ("F", "IMCF", "AD1", "FBE", "+"); der Eintrag "F" traf kein Muster mehr -
         * fuer eine echte Fruehschicht gab es keinen Wecker.
         * Abwaegung, bewusst so entschieden: eine nicht erkannte Schicht heisst KEIN WECKER. Fuer
         * einen ueberzaehligen Wecker gibt es "Naechsten Alarm ueberspringen", fuer einen
         * verschlafenen gibt es nichts. Das Restrisiko bleibt eng und beherrschbar: die Erkennung
         * liest nur Kalender, die der Nutzer selbst ausgewaehlt hat, und das Keyword ist
         * entfernbar. `ShiftConfigDefaultsTest` haelt beide Seiten fest.
         *
         * WARUM JEDE DEFINITION EIN GENERISCHES MUSTER NEBEN DEM STATIONSKUERZEL HAT:
         * "IMCF"/"IMCS"/"IMCN"/"IMCZ" sind die Kuerzel EINER Station. Fuer einen Kollegen auf
         * einer anderen Station traf der Zwischendienst mit seinem einzigen Muster "IMCZ"
         * garantiert nie - er haette dafuer NIE einen Wecker bekommen und es erst nach dem
         * Verschlafen gemerkt. Deshalb hat jede Definition zusaetzlich ein generisches,
         * mehrbuchstabiges Muster; der Definitionsname selbst zaehlt seit demselben Fix
         * ebenfalls als Muster (siehe [ShiftDefinition.matchesKeywords]), sodass ein Termin, der
         * wortwoertlich "Frühschicht"/"Zwischendienst" heisst, auch ohne eigenes Keyword trifft.
         *
         * Das ersetzt KEINE Konfiguration: wessen Station anders codiert, muss seine Kuerzel
         * eintragen. Darauf weist der Schicht-Konfigurationsscreen sichtbar hin.
         *
         * Der eigentliche Weg dahin sind aber nicht besser geratene Vorgaben, sondern die
         * Kuerzel, die im Kalender des Nutzers TATSAECHLICH vorkommen: die App kann die geladenen
         * Termintitel auswerten, die wiederkehrenden Kuerzel nach Haeufigkeit vorlegen und
         * zuordnen lassen. Solange das fehlt, sind diese Vorgaben ein Kompromiss und kein
         * Ersatz.
         */
        fun getDefaultConfig(): ShiftConfig = ShiftConfig(
            autoAlarmEnabled = true,
            definitions = listOf(
                ShiftDefinition(
                    id = "early_shift",
                    name = "Frühschicht",
                    keywords = listOf("F", "IMCF", "Frühdienst"),
                    alarmTime = LocalTime.of(5, 30),
                    isEnabled = true
                ),
                ShiftDefinition(
                    id = "late_shift",
                    name = "Spätschicht",
                    keywords = listOf("S", "IMCS", "Spätdienst"),
                    alarmTime = LocalTime.of(12, 30),
                    isEnabled = true
                ),
                ShiftDefinition(
                    id = "night_shift",
                    name = "Nachtschicht",
                    keywords = listOf("N", "IMCN", "Nachtdienst"),
                    alarmTime = LocalTime.of(20, 0),
                    isEnabled = true
                ),
                ShiftDefinition(
                    id = "s2_shift",
                    name = "S2",
                    keywords = listOf("S2"),
                    alarmTime = LocalTime.of(14, 30),
                    isEnabled = true
                ),
                ShiftDefinition(
                    id = "intermediate_shift",
                    name = "Zwischendienst",
                    keywords = listOf("IMCZ", "ZD"),
                    alarmTime = LocalTime.of(7, 0),
                    isEnabled = true
                )
            )
        )
    }
}
