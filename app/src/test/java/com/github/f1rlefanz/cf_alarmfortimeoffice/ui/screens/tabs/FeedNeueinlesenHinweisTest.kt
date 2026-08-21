package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.tabs

import com.github.f1rlefanz.cf_alarmfortimeoffice.alarm.FeedNeueinlesenStand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Der Text der stillen Statuszeile ist die eigentliche Zusicherung dieses Features - er ist alles,
 * was der Nutzer von dem Vorgang je zu sehen bekommt. Deshalb ist er eine reine Funktion und wird
 * hier ohne Geraet festgehalten (dasselbe Muster wie `noShiftExplanation`).
 */
class FeedNeueinlesenHinweisTest {

    private val zone: ZoneId = ZoneId.of("Europe/Berlin")

    private fun am(tag: Int, monat: Int = 8, stunde: Int = 11): Long =
        ZonedDateTime.of(2026, monat, tag, stunde, 0, 0, 0, zone).toInstant().toEpochMilli()

    @Test
    fun `elf Wecker - Datum kurz, Anzahl konkret, Folge benannt`() {
        val text = feedNeueinlesenHinweis(FeedNeueinlesenStand(am(22), 11), zone)

        assertNotNull(text)
        assertEquals(
            "Dienstplan-Kalender zuletzt neu eingelesen: 22.08., 11 Wecker wiedererkannt und " +
                "neu zugeordnet. Am Dienstplan hat sich dadurch nichts geändert.",
            text
        )
    }

    /**
     * Ein falscher Plural ("1 Wecker wurden") untergraebt genau die Ruhe, die die Zeile stiften
     * soll - der Nutzer stolpert, und aus einer Auskunft wird ein Stirnrunzeln.
     */
    @Test
    fun `ein einzelner Wecker steht im Singular`() {
        val text = feedNeueinlesenHinweis(FeedNeueinlesenStand(am(3, monat = 9), 1), zone)!!

        assertTrue("Erwartet '1 Wecker', bekommen: $text", text.contains("1 Wecker "))
        assertTrue("Kein Plural-s an 'Wecker' und keine 'Weckern'", !text.contains("Weckern"))
        assertTrue("Das Datum steht kurz mit Tag und Monat", text.contains("03.09.,"))
    }

    /**
     * Der Nutzer ist Schichtarbeiter, nicht Kalender-Entwickler. Was im Log "neue Event-ID aus
     * einem neu eingelesenen Feed" heisst, muss hier in seiner Sprache stehen.
     */
    @Test
    fun `der Text enthaelt keinen Fachbegriff`() {
        val text = feedNeueinlesenHinweis(FeedNeueinlesenStand(am(22), 11), zone)!!

        listOf("Kennung", "Feed", "Sync", "Event", "Hash", "Abonnement", "ICS", "Kalender-ID")
            .forEach { fachwort ->
                assertTrue(
                    "'$fachwort' ist ein Fachbegriff und darf in der Statuszeile nicht vorkommen: $text",
                    !text.contains(fachwort, ignoreCase = true)
                )
            }
        assertTrue("Auch kein blankes 'ID': $text", !text.contains("ID"))
    }

    /**
     * Die Folge MUSS dastehen. Ohne sie liest sich "11 Wecker neu zugeordnet" wie eine Aenderung
     * am Dienstplan - genau die Fehldeutung, die die App schon einmal ausgeloest hat.
     */
    @Test
    fun `der Text sagt ausdruecklich, dass sich am Dienstplan nichts geaendert hat`() {
        val text = feedNeueinlesenHinweis(FeedNeueinlesenStand(am(22), 11), zone)!!

        assertTrue(text.contains("nichts geändert"))
    }

    @Test
    fun `ohne gespeicherten Stand gibt es keine Zeile`() {
        assertNull("Was nie vorkam, wird auch nicht behauptet", feedNeueinlesenHinweis(null, zone))
    }

    /**
     * Backstop gegen einen unsinnigen Stand (der Store laesst so etwas gar nicht erst entstehen):
     * eine Zeile mit "0 Wecker" oder einem Datum aus dem Jahr 1970 waere schlechter als keine.
     */
    @Test
    fun `unsinniger Stand ergibt keine Zeile`() {
        assertNull(feedNeueinlesenHinweis(FeedNeueinlesenStand(am(22), 0), zone))
        assertNull(feedNeueinlesenHinweis(FeedNeueinlesenStand(0L, 5), zone))
    }
}
