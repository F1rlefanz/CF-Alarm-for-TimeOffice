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

        fun getDefaultConfig(): ShiftConfig = ShiftConfig(
            autoAlarmEnabled = true,
            definitions = listOf(
                ShiftDefinition(
                    id = "early_shift",
                    name = "Frühschicht",
                    keywords = listOf("F", "IMCF"),
                    alarmTime = LocalTime.of(5, 30),
                    isEnabled = true
                ),
                ShiftDefinition(
                    id = "late_shift", 
                    name = "Spätschicht",
                    keywords = listOf("S", "IMCS"),
                    alarmTime = LocalTime.of(12, 30),
                    isEnabled = true
                ),
                ShiftDefinition(
                    id = "night_shift",
                    name = "Nachtschicht",
                    keywords = listOf("N", "IMCN"),
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
                    keywords = listOf("IMCZ"),
                    alarmTime = LocalTime.of(7, 0),
                    isEnabled = true
                )
            )
        )
    }
}
