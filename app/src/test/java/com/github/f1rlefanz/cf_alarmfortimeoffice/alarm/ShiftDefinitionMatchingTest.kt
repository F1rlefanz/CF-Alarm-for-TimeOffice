package com.github.f1rlefanz.cf_alarmfortimeoffice.alarm

import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftConfig
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftDefinition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalTime

/**
 * Sichert [ShiftConfig.findDefinitionFor] ab - die Zuordnung "Schichtname des Alarms ->
 * Schichtdefinition", die entscheidet, WELCHE Hue-Regeln ein Alarm ausfuehrt.
 *
 * HINTERGRUND: Diese Zuordnung war kaputt und hat es niemandem gesagt. Der frueherere
 * `find { name == x || keywords.any { shiftName.contains(it) } }` im AlarmReceiver nahm den
 * ersten Treffer in Listenreihenfolge - und "Spaetschicht" traegt das Keyword "S", das in fast
 * jedem anderen Schichtnamen steckt. Am Emulator gegen die echte Standardkonfiguration
 * reproduziert (16.07.2026): Ein Alarm fuer "S2" landete auf der Definition "Spaetschicht",
 * die S2-Regel feuerte nie.
 *
 * Die Testfaelle laufen gegen [ShiftConfig.getDefaultConfig] - also genau das, was ein Nutzer
 * ohne eigene Anpassung bekommt.
 */
class ShiftDefinitionMatchingTest {

    private val config = ShiftConfig.getDefaultConfig()

    /**
     * Die Standardkonfiguration, wie sie VOR der Ausweitung des Testkreises aussah - mit den
     * einbuchstabigen Keywords "F"/"S"/"N".
     *
     * Warum die hier weiterlebt, obwohl [ShiftConfig.getDefaultConfig] sie nicht mehr liefert:
     * eine bereits gespeicherte `ShiftConfig` wird NICHT migriert. Bestandsnutzer fahren also
     * genau diese Keywords weiter, und die Staffelung in `findDefinitionFor` muss die
     * "S"-Kollision fuer sie unveraendert abfangen.
     */
    private val legacyConfig = ShiftConfig(
        definitions = listOf(
            ShiftDefinition("early_shift", "Frühschicht", listOf("F", "IMCF"), LocalTime.of(5, 30)),
            ShiftDefinition("late_shift", "Spätschicht", listOf("S", "IMCS"), LocalTime.of(12, 30)),
            ShiftDefinition("night_shift", "Nachtschicht", listOf("N", "IMCN"), LocalTime.of(20, 0)),
            ShiftDefinition("s2_shift", "S2", listOf("S2"), LocalTime.of(14, 30)),
            ShiftDefinition("intermediate_shift", "Zwischendienst", listOf("IMCZ"), LocalTime.of(7, 0))
        )
    )

    @Test
    fun `Bestandskonfiguration mit einbuchstabigen Keywords bleibt korrekt zugeordnet`() {
        // Der eigentliche historische Bug, jetzt gegen die gespeicherte Alt-Konfiguration:
        // "S2", "Nachtschicht" und "Zwischendienst" enthalten alle ein "s" und landeten frueher
        // samt und sonders auf "Spaetschicht".
        legacyConfig.definitions.forEach { expected ->
            assertEquals(
                "Schicht '${expected.name}' muss ihre eigene Definition treffen",
                expected.id,
                legacyConfig.findDefinitionFor(expected.name)?.id
            )
        }
        assertNull(legacyConfig.findDefinitionFor("Buerodienst"))
    }

    @Test
    fun `jede Standard-Schicht findet ihre eigene Definition`() {
        // Der eigentliche Regressionstest: vorher landeten S2, Nachtschicht und
        // Zwischendienst allesamt auf "Spaetschicht", weil ihre Namen ein "s" enthalten.
        config.definitions.forEach { expected ->
            assertEquals(
                "Schicht '${expected.name}' muss ihre eigene Definition treffen",
                expected.id,
                config.findDefinitionFor(expected.name)?.id
            )
        }
    }

    @Test
    fun `S2 landet nicht auf Spaetschicht`() {
        // Der konkrete Fall vom Emulator-Test: "S2" enthaelt das Keyword "S" der Spaetschicht,
        // und Spaetschicht steht in der Liste VOR S2.
        assertEquals("s2_shift", config.findDefinitionFor("S2")?.id)
        assertEquals("s2_shift", legacyConfig.findDefinitionFor("S2")?.id)
    }

    @Test
    fun `Nachtschicht und Zwischendienst landen nicht auf Spaetschicht`() {
        // Beide enthalten ein kleines "s" - fruehere Ursache derselben Fehlzuordnung.
        assertEquals("night_shift", config.findDefinitionFor("Nachtschicht")?.id)
        assertEquals("intermediate_shift", config.findDefinitionFor("Zwischendienst")?.id)
        assertEquals("night_shift", legacyConfig.findDefinitionFor("Nachtschicht")?.id)
        assertEquals("intermediate_shift", legacyConfig.findDefinitionFor("Zwischendienst")?.id)
    }

    @Test
    fun `exakter Name schlaegt Keyword-Treffer einer frueheren Definition`() {
        // Reihenfolge darf keine Rolle spielen: auch wenn eine unscharf passende Definition
        // vorne steht, gewinnt der exakte Name.
        val ordered = ShiftConfig(
            definitions = listOf(
                ShiftDefinition("late", "Spätschicht", listOf("S", "IMCS"), LocalTime.of(12, 30)),
                ShiftDefinition("s2", "S2", listOf("S2"), LocalTime.of(14, 30))
            )
        )
        assertEquals("s2", ordered.findDefinitionFor("S2")?.id)
    }

    @Test
    fun `Gross-Kleinschreibung spielt keine Rolle`() {
        assertEquals("s2_shift", config.findDefinitionFor("s2")?.id)
        assertEquals("night_shift", config.findDefinitionFor("NACHTSCHICHT")?.id)
    }

    @Test
    fun `exaktes Keyword trifft, wenn kein Name passt`() {
        // Notnagel-Stufe 2: der Alarm traegt noch ein Keyword statt des Namens.
        assertEquals("early_shift", config.findDefinitionFor("IMCF")?.id)
    }

    @Test
    fun `einbuchstabige Keywords matchen nicht mehr unscharf`() {
        // Gegen eine Konfiguration mit einbuchstabigem Keyword geprueft (die Standardkonfiguration
        // hat seit der Ausweitung des Testkreises keine solchen Keywords mehr - die Staffelung in
        // findDefinitionFor muss die Falle aber weiterhin abfangen, weil ein Nutzer sich selbst
        // ein einbuchstabiges Muster anlegen darf).
        val mitEinzelbuchstabe = ShiftConfig(
            definitions = listOf(
                ShiftDefinition("late", "Spätschicht", listOf("S", "IMCS"), LocalTime.of(12, 30))
            )
        )
        // "Buerodienst" enthaelt ein "s" - frueher haette das Spaetschicht getroffen.
        // Jetzt lieber gar keine Regel als die falschen Lampen.
        assertNull(mitEinzelbuchstabe.findDefinitionFor("Buerodienst"))
        assertNull(config.findDefinitionFor("Buerodienst"))
    }

    @Test
    fun `laengere Keywords duerfen weiterhin unscharf treffen`() {
        // Stufe 3 bleibt fuer den Fall "Definition wurde umbenannt" erhalten - aber nur mit
        // Keywords, die lang genug sind, um etwas zu bedeuten.
        assertEquals("early_shift", config.findDefinitionFor("IMCF Zusatzdienst")?.id)
    }

    @Test
    fun `leere Konfiguration liefert null statt zu werfen`() {
        assertNull(ShiftConfig(definitions = emptyList()).findDefinitionFor("S2"))
    }
}
