package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.tabs

import com.github.f1rlefanz.cf_alarmfortimeoffice.service.TimeOfficeNachfassAbgleich
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Der Satz unter "TimeOffice öffnen" verspricht zwei Abgleiche - und darf das nur, wenn
 * `TimeOfficeNachfassAbgleich.plane()` sie auch so stellt, dass sie laufen (Review 1.48.0).
 */
class TimeOfficeNachfassSatzTest {

    @Test
    fun `im Normalfall nennt der Satz den Knopf und die Minuten, mit denen geplant wird`() {
        val satz = timeOfficeNachfassSatz(pausiert = false, exakt = true)

        assertEquals(
            "Nach „TimeOffice öffnen“ gleicht CF-Alarm nach 3 und nach 10 Minuten von selbst ab.",
            satz
        )
        assertTrue(satz.contains("${TimeUnit.MILLISECONDS.toMinutes(TimeOfficeNachfassAbgleich.ERSTER_ABSTAND_MS)} und"))
        assertTrue(satz.contains("${TimeUnit.MILLISECONDS.toMinutes(TimeOfficeNachfassAbgleich.ZWEITER_ABSTAND_MS)} Minuten"))
    }

    @Test
    fun `der Satz nennt den Knopf beim Namen, nicht ueber seine Lage`() {
        // Bis 1.48.0-Review: "Danach gleicht CF-Alarm …" stand unter der ganzen Knopfreihe - bei
        // 320 dp direkt unter "TimeOffice-Einstellungen öffnen", das nichts plant.
        listOf(true, false).forEach { pausiert ->
            listOf(true, false).forEach { exakt ->
                val satz = timeOfficeNachfassSatz(pausiert, exakt)
                assertTrue(satz, satz.contains("„$TIMEOFFICE_OEFFNEN“"))
                assertFalse(satz, satz.startsWith("Danach"))
            }
        }
    }

    @Test
    fun `waehrend der Pause verspricht der Satz keinen Abgleich`() {
        val satz = timeOfficeNachfassSatz(pausiert = true, exakt = true)
        assertTrue(satz, satz.startsWith("Solange alles pausiert ist"))
        assertTrue(satz, satz.contains("nicht von selbst ab"))
    }

    @Test
    fun `ohne exakte Alarme verspricht der Satz keinen Abgleich und nennt die Karte`() {
        // Android 12 mit entzogenem "Alarme & Erinnerungen": plane() stellt ungenau, und ein
        // ungenauer Alarm darf den Wartungslauf nicht im Vordergrund starten.
        val satz = timeOfficeNachfassSatz(pausiert = false, exakt = false)
        assertTrue(satz, satz.contains("„Alarme & Erinnerungen“"))
        assertTrue(satz, satz.contains("nicht zuverlässig"))
        assertFalse(satz, satz.contains("Minuten"))
    }

    @Test
    fun `die Pause hat Vorrang - sie ist der umfassendere Grund`() {
        assertEquals(
            timeOfficeNachfassSatz(pausiert = true, exakt = true),
            timeOfficeNachfassSatz(pausiert = true, exakt = false)
        )
    }
}
