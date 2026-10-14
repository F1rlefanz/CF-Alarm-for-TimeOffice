package com.github.f1rlefanz.cf_alarmfortimeoffice.alarm

import com.github.f1rlefanz.cf_alarmfortimeoffice.alarm.SchlummerEingabe.Hinweis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SchlummerEingabeTest {

    @Test
    fun `Grenzen sind 1 bis 30 mit Default 5`() {
        assertEquals(1, AlarmPrefs.MIN_SNOOZE_MINUTES)
        assertEquals(30, AlarmPrefs.MAX_SNOOZE_MINUTES)
        assertEquals(5, AlarmPrefs.DEFAULT_SNOOZE_MINUTES)
    }

    @Test
    fun `gueltige Werte inklusive Grenzen bleiben unveraendert ohne Hinweis`() {
        for (n in listOf(1, 5, 15, 29, 30)) {
            val r = SchlummerEingabe.pruefen(n.toString())
            assertEquals(n, r.wert)
            assertNull(r.hinweis)
        }
    }

    @Test
    fun `Leerzeichen und fuehrende Nullen werden toleriert`() {
        assertEquals(7, SchlummerEingabe.pruefen("  7 ").wert)
        assertNull(SchlummerEingabe.pruefen("007").hinweis)
    }

    @Test
    fun `leer faellt auf den bisherigen Wert zurueck`() {
        val r = SchlummerEingabe.pruefen("", fallback = 12)
        assertEquals(12, r.wert)
        assertEquals(Hinweis.LEER, r.hinweis)
        assertEquals(Hinweis.LEER, SchlummerEingabe.pruefen("   ").hinweis)
    }

    @Test
    fun `ungueltiger Fallback wird selbst geklemmt`() {
        assertEquals(30, SchlummerEingabe.pruefen("", fallback = 120).wert)
        assertEquals(1, SchlummerEingabe.pruefen("", fallback = -3).wert)
    }

    @Test
    fun `nicht numerisch ergibt Hinweis und Fallback`() {
        for (t in listOf("abc", "5a", "-5", "+5", "1,5", "2.5", "1 0")) {
            val r = SchlummerEingabe.pruefen(t, fallback = 10)
            assertEquals(t, 10, r.wert)
            assertEquals(t, Hinweis.KEINE_ZAHL, r.hinweis)
        }
    }

    @Test
    fun `null wird auf das Minimum gehoben`() {
        val r = SchlummerEingabe.pruefen("0")
        assertEquals(1, r.wert)
        assertEquals(Hinweis.ZU_KLEIN, r.hinweis)
    }

    @Test
    fun `ueber 30 wird auf das Maximum gesetzt`() {
        val r = SchlummerEingabe.pruefen("31")
        assertEquals(30, r.wert)
        assertEquals(Hinweis.ZU_GROSS, r.hinweis)
        assertEquals(30, SchlummerEingabe.pruefen("120").wert)
    }

    @Test
    fun `absurd lange Ziffernfolge ist zu gross statt keine Zahl`() {
        val r = SchlummerEingabe.pruefen("99999999999999999999")
        assertEquals(30, r.wert)
        assertEquals(Hinweis.ZU_GROSS, r.hinweis)
    }
}
