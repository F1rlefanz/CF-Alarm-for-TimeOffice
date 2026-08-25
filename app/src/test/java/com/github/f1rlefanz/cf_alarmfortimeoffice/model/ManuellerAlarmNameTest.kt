package com.github.f1rlefanz.cf_alarmfortimeoffice.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Der Schichtname eines VON HAND angelegten Weckers traegt ein Anzeige-Anhaengsel
 * ("Fruehschicht (Manuell)"). Wer damit eine Schichtdefinition SUCHT, findet nichts.
 *
 * Der Fehler, den das verhindert (am Emulator gemessen, 27.08.2026): Ein manueller
 * Fruehschicht-Wecker klingelte normal, aber die Hue-Regel derselben Schicht lief nicht. Im Log
 * stand nur `No shift definition found for: Fruehschicht (Manuell) (skipping Hue rules)` - das
 * liest sich wie "keine Regel konfiguriert", war aber ein fehlgeschlagener Namensabgleich.
 * Sichtbar wurde es erst, weil ein echter Weckvorgang gefahren wurde; kein Unit-Test und kein
 * Blick in die Oberflaeche haette es gezeigt.
 */
class ManuellerAlarmNameTest {

    private val definitionen = listOf(
        ShiftDefinition(
            id = "def_frueh",
            name = "Frühschicht",
            keywords = listOf("F"),
            alarmTime = java.time.LocalTime.of(5, 30)
        ),
        ShiftDefinition(
            id = "def_spaet",
            name = "Spätschicht",
            keywords = listOf("S"),
            alarmTime = java.time.LocalTime.of(12, 30)
        )
    )

    private val config = ShiftConfig(definitions = definitionen)

    @Test
    fun `der manuelle Wecker findet seine Schichtdefinition wieder`() {
        val angezeigt = "Frühschicht$MANUELLER_ALARM_SUFFIX"

        // So sah es aus, als der Fehler auftrat:
        assertEquals(null, config.findDefinitionFor(angezeigt))

        // ... und so soll zugeordnet werden:
        val gefunden = config.findDefinitionFor(reinerSchichtname(angezeigt))
        assertEquals("Frühschicht", gefunden?.name)
    }

    @Test
    fun `ein Wecker aus dem Kalender bleibt unveraendert`() {
        assertEquals("Spätschicht", reinerSchichtname("Spätschicht"))
        assertEquals("Spätschicht", config.findDefinitionFor(reinerSchichtname("Spätschicht"))?.name)
    }

    @Test
    fun `das Anhaengsel wird nur am ENDE entfernt`() {
        // Eine Schicht, die selbst so heisst, darf nicht verstuemmelt werden.
        assertEquals("(Manuell) Sonderdienst", reinerSchichtname("(Manuell) Sonderdienst"))
    }
}
