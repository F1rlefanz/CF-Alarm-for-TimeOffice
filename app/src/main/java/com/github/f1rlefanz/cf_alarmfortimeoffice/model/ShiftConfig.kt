package com.github.f1rlefanz.cf_alarmfortimeoffice.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable
import java.time.LocalTime

@Immutable
@Serializable
data class ShiftConfig(
    val autoAlarmEnabled: Boolean = true,
    val definitions: List<ShiftDefinition> = emptyList()
) {
    /**
     * Ordnet ein Schichtkuerzel aus dem Kalender GENAU EINER Definition zu (Vorschlags-Karte im
     * Schicht-Konfigurations-Screen). REIN und testbar, damit die drei Fallen unten festgehalten
     * werden koennen.
     *
     * Drei Dinge passieren zusammen, weil jedes einzeln fuer sich wirkungslos waere:
     *
     *  1. Das Kuerzel kommt als Erkennungsmuster an die Zieldefinition.
     *  2. Die Zieldefinition wird AKTIVIERT. `ShiftRecognitionEngine` beachtet nur aktivierte
     *     Definitionen - eine Zuordnung an eine ausgeschaltete Schicht waere ein stiller
     *     Nichts-Passiert-Klick. Genau der Fall tritt real auf: die Vorschlags-Karte bietet ein
     *     Kuerzel nur an, wenn es von keiner AKTIVIERTEN Definition getroffen wird - ein Kuerzel,
     *     das bei einer ausgeschalteten Schicht liegt, wird also vorgeschlagen.
     *  3. Das Kuerzel wird bei JEDER anderen Definition ENTFERNT. Sonst haette es zwei Besitzer,
     *     und `findDefinitionFor` nimmt den ERSTEN Treffer in Listenreihenfolge - eine stille,
     *     von der Sortierung abhaengige Entscheidung ueber den Wecker. Der Nutzer hat gerade
     *     gesagt, zu WELCHER Schicht das Kuerzel gehoert; das ist die Antwort, nicht die
     *     Listenreihenfolge.
     *
     * @return die neue Konfiguration, oder `null` wenn nichts zu tun war (leeres Kuerzel,
     *         unbekannte Ziel-ID, oder alles steht schon so).
     */
    fun withCodeAssignedTo(code: String, definitionId: String): ShiftConfig? {
        val normalized = code.trim()
        if (normalized.isEmpty()) return null
        if (definitions.none { it.id == definitionId }) return null

        val updated = definitions.map { definition ->
            if (definition.id == definitionId) {
                val keywords =
                    if (definition.keywords.any { it.equals(normalized, ignoreCase = true) }) {
                        definition.keywords
                    } else {
                        definition.keywords + normalized
                    }
                definition.copy(keywords = keywords, isEnabled = true)
            } else {
                val cleaned = definition.keywords.filterNot { it.equals(normalized, ignoreCase = true) }
                if (cleaned.size == definition.keywords.size) definition
                else definition.copy(keywords = cleaned)
            }
        }
        return if (updated == definitions) null else copy(definitions = updated)
    }

    /**
     * Die Definition, zu der [shiftName] gehoert - streng nach Genauigkeit gestaffelt.
     * Entscheidet, WELCHE Hue-Regeln ein Alarm ausfuehrt.
     *
     * [shiftName] ist der NAME der Definition - Stufe 1 trifft also immer; die Keyword-Stufen
     * sind nur fuer umbenannte Definitionen da.
     *
     * Wer hier wieder ein `contains` nach vorne zieht, baut den Fehler neu.
     * Hergang: Skill cfalarm-kalender-und-schichten, reference/schichterkennung.md.
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
         * Die einbuchstabigen Keywords "F"/"S"/"N" gehoeren bewusst in die Vorgaben, und jede
         * Definition hat neben dem Stationskuerzel ein generisches Muster. Das ersetzt KEINE
         * Konfiguration. `ShiftConfigDefaultsTest` haelt es fest.
         * Hergang: Skill cfalarm-kalender-und-schichten, reference/schichterkennung.md.
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
