package com.github.f1rlefanz.cf_alarmfortimeoffice.hue

import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.BridgeSchedule
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.BridgeTimer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * Tests fuer die Zeitmuster und Namen der bridge-seitigen Auto-Aus-Zeitplaene.
 *
 * Diese Zeichenketten gehen wortwoertlich an die Bridge. Ist das Muster falsch, lehnt die
 * Bridge den Zeitplan ab (Fehler 7) - und da das Auto-Aus seit v1.11.0 ausschliesslich ueber
 * die Bridge laeuft, bliebe das Licht an. Deshalb hier abgesichert und nicht "offensichtlich".
 *
 * Die Formate sind am 15.07.2026 gegen die echte Bridge verifiziert (BSB002, apiversion 1.78.0).
 */
class BridgeTimerTest {

    @Test
    fun `timerPattern formatiert Minuten als PT-Muster`() {
        assertEquals("PT00:30:00", BridgeTimer.timerPattern(30))
        assertEquals("PT00:01:00", BridgeTimer.timerPattern(1))
        assertEquals("PT01:00:00", BridgeTimer.timerPattern(60))
        assertEquals("PT01:30:00", BridgeTimer.timerPattern(90))
    }

    @Test
    fun `timerPattern nutzt immer zweistellige Felder`() {
        // "PT0:5:00" wuerde die Bridge nicht parsen.
        assertEquals("PT00:05:00", BridgeTimer.timerPattern(5))
        assertEquals("PT02:05:00", BridgeTimer.timerPattern(125))
    }

    @Test
    fun `timerPattern klemmt auf die Timer-Obergrenze der Bridge`() {
        // Ein Timer kann hoechstens PT23:59:59 laufen.
        assertEquals("PT23:59:00", BridgeTimer.timerPattern(BridgeTimer.MAX_TIMER_MINUTES))
        assertEquals("PT23:59:00", BridgeTimer.timerPattern(99_999))
    }

    @Test
    fun `timerPattern klemmt nicht-positive Werte auf eine Minute`() {
        // "PT00:00:00" waere ein Timer, der sofort feuert - das Licht ginge im selben
        // Moment wieder aus, in dem es angegangen ist.
        assertEquals("PT00:01:00", BridgeTimer.timerPattern(0))
        assertEquals("PT00:01:00", BridgeTimer.timerPattern(-5))
    }

    @Test
    fun `timerPattern bleibt unter fremdem Systemgebiet stabil`() {
        // String.format ohne Locale.ROOT erzeugt z.B. unter arabischem Gebiet oestlich-arabische
        // Ziffern - die Bridge parst sie nicht.
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("ar-EG"))
            assertEquals("PT00:30:00", BridgeTimer.timerPattern(30))
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun `resourcePath unterscheidet Gruppe und Lampe`() {
        assertEquals("/groups/2/action", BridgeTimer.resourcePath("2", isGroup = true))
        assertEquals("/lights/7/state", BridgeTimer.resourcePath("7", isGroup = false))
    }

    @Test
    fun `scheduleName bleibt im 32-Zeichen-Limit der Bridge`() {
        val name = BridgeTimer.scheduleName("11", isGroup = true)
        assertEquals("CFAlarm Auto-Off G11", name)
        assertTrue(name.length <= 32)

        val absurd = BridgeTimer.scheduleName("x".repeat(200), isGroup = false)
        assertTrue("Name muss geklemmt werden, sonst lehnt die Bridge ab", absurd.length <= 32)
    }

    @Test
    fun `scheduleDescription bleibt im 64-Zeichen-Limit der Bridge`() {
        val description = BridgeTimer.scheduleDescription("Fruehschicht", 30)
        assertEquals("Auto-Off Fruehschicht +30min", description)
        assertTrue(description.length <= 64)

        val absurd = BridgeTimer.scheduleDescription("S".repeat(200), 30)
        assertTrue(absurd.length <= 64)
    }

    @Test
    fun `isOwnSchedule erkennt nur eigene Zeitplaene`() {
        assertTrue(isOwn(BridgeTimer.scheduleName("2", isGroup = true)))

        // Auf der Bridge liegen real auch fremde Zeitplaene - die duerfen wir niemals loeschen.
        assertFalse(isOwn("Hue dimmer switch 1"))
        assertFalse(isOwn("Wake up"))
        assertFalse(isOwn(null))
    }

    private fun isOwn(name: String?): Boolean =
        BridgeTimer.isOwnSchedule(BridgeSchedule(name = name))
}
