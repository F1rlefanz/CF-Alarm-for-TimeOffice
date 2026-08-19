package com.github.f1rlefanz.cf_alarmfortimeoffice.shift

import com.github.f1rlefanz.cf_alarmfortimeoffice.model.CalendarEvent
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftConfig
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftDefinition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/**
 * Eine AUSGESCHALTETE Schichtdefinition ist eine Zuordnung - die Vorschlagskarte darf ihr
 * Kuerzel nicht weiterhin als "noch nicht zugeordnet" anbieten.
 *
 * HERGANG (19.08.2026, am echten Kalender aufgefallen): Der Nutzer legte "AD1" als Abrufdienst
 * an und schaltete die Definition aus, weil er dafuer keinen Wecker will. Die Karte listete
 * "AD1" unveraendert weiter, mit dem Text "Fuer sie gibt es noch kein Erkennungsmuster" - und
 * das war falsch, das Muster existierte. Ursache war ein `filter { it.isEnabled }` im
 * Suggester: die Karte stellte damit eine andere Frage ("welches Kuerzel erzeugt einen Wecker?")
 * als die, die sie behauptete ("welches Kuerzel ist noch nicht zugeordnet?").
 */
class AusgeschalteteDefinitionIstZugeordnetTest {

    private fun event(titel: String, tag: Int) = CalendarEvent(
        id = "e$tag",
        title = titel,
        startTime = LocalDateTime.of(2026, 8, tag, 12, 0),
        endTime = LocalDateTime.of(2026, 8, tag, 20, 0),
        calendarId = "cal"
    )

    private fun definition(id: String, keyword: String, aktiviert: Boolean) = ShiftDefinition(
        id = id,
        name = keyword,
        keywords = listOf(keyword),
        alarmTime = java.time.LocalTime.of(6, 0),
        isEnabled = aktiviert
    )

    @Test
    fun `ausgeschaltete Definition unterdrueckt den Vorschlag`() {
        val config = ShiftConfig(definitions = listOf(definition("ad", "AD1", aktiviert = false)))

        val ergebnis = ShiftCodeSuggester.suggest(listOf(event("AD1", 20), event("AD1", 21)), config)

        assertTrue(
            "AD1 ist einer (ausgeschalteten) Definition zugeordnet und darf nicht mehr als " +
                "unzugeordnetes Kuerzel vorgeschlagen werden - sonst fordert die Karte zu einer " +
                "Zuordnung auf, die es laengst gibt.",
            ergebnis.suggestions.none { it.code == "AD1" }
        )
    }

    @Test
    fun `wirklich unzugeordnete Kuerzel kommen weiterhin durch`() {
        val config = ShiftConfig(definitions = listOf(definition("ad", "AD1", aktiviert = false)))

        val ergebnis = ShiftCodeSuggester.suggest(listOf(event("AD1", 20), event("XYZ", 21)), config)

        assertEquals(
            "Die Karte muss ihre eigentliche Aufgabe behalten - der Fix darf sie nicht stumm machen.",
            listOf("XYZ"),
            ergebnis.suggestions.map { it.code }
        )
    }

    @Test
    fun `aktivierte Definition unterdrueckt den Vorschlag unveraendert`() {
        val config = ShiftConfig(definitions = listOf(definition("ad", "AD1", aktiviert = true)))

        val ergebnis = ShiftCodeSuggester.suggest(listOf(event("AD1", 20)), config)

        assertTrue(ergebnis.isEmpty)
    }
}
