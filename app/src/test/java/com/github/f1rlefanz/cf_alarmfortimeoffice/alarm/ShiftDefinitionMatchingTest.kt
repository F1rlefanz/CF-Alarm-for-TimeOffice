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
    }

    @Test
    fun `Nachtschicht und Zwischendienst landen nicht auf Spaetschicht`() {
        // Beide enthalten ein kleines "s" - fruehere Ursache derselben Fehlzuordnung.
        assertEquals("night_shift", config.findDefinitionFor("Nachtschicht")?.id)
        assertEquals("intermediate_shift", config.findDefinitionFor("Zwischendienst")?.id)
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
        // "Buerodienst" enthaelt ein "s" - frueher haette das Spaetschicht getroffen.
        // Jetzt lieber gar keine Regel als die falschen Lampen.
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
