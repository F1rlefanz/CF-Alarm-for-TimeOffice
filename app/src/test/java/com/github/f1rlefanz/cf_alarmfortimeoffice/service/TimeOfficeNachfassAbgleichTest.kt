package com.github.f1rlefanz.cf_alarmfortimeoffice.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Die Zeitrechnung der beiden Abgleiche nach "TimeOffice öffnen".
 *
 * DER VORFALL, gegen den sie gebaut sind (12.10.2026): TimeOffice geoeffnet 15:50:28, Android
 * laedt 15:51:06 hoch, bei Google 15:52 - CF-Alarm sah den neuen Dienst trotzdem erst Stunden
 * spaeter. Der erste Abgleich muss also NACH diesen gut zwei Minuten liegen, der zweite faengt ein
 * langsameres Hochladen ab.
 */
class TimeOfficeNachfassAbgleichTest {

    private val min = TimeUnit.MINUTES.toMillis(1)

    // 12.10.2026 15:50:28 (MESZ) - die Rechnung ist rein relativ.
    private val druck = 1_791_813_028_000L

    @Test
    fun `nach dem Druck kommen genau zwei Abgleiche - nach 3 und nach 10 Minuten`() {
        val termine = TimeOfficeNachfassAbgleich.termine(druck)

        assertEquals(listOf(druck + 3 * min, druck + 10 * min), termine.map { it.zeitpunkt })
    }

    @Test
    fun `der erste Abgleich liegt hinter dem gemessenen Hochladen, der zweite danach`() {
        // Am Befundtag lag der Dienst 1 min 51 s nach dem Oeffnen bei Google. Ein Abgleich davor
        // saehe ihn nicht - und waere vergeudet.
        val beiGoogle = druck + TimeUnit.SECONDS.toMillis(111)
        val (erster, zweiter) = TimeOfficeNachfassAbgleich.termine(druck)

        assertTrue(erster.zeitpunkt > beiGoogle)
        assertTrue(zweiter.zeitpunkt > erster.zeitpunkt)
    }

    @Test
    fun `jeder Abgleich hat seinen festen Slot - ein zweiter Druck ersetzt, statt zu stapeln`() {
        // FLAG_UPDATE_CURRENT auf einem festen Request-Code: AlarmManager ersetzt den Alarm mit
        // gleichem PendingIntent. Wechselte der Code mit dem Zeitpunkt, kaemen nach drei Druecken
        // sechs Wartungslaeufe.
        val ersterDruck = TimeOfficeNachfassAbgleich.termine(druck)
        val zweiterDruck = TimeOfficeNachfassAbgleich.termine(druck + 2 * min)

        assertEquals(ersterDruck.map { it.requestCode }, zweiterDruck.map { it.requestCode })
        assertNotEquals(ersterDruck[0].requestCode, ersterDruck[1].requestCode)
    }

    @Test
    fun `die Slots kollidieren mit keiner anderen Kette - kein zweiter Planer der 6h-Wartung`() {
        // Auf Code 0 ersetzte ein Nachfass-Abgleich den naechsten regulaeren Wartungslauf, auf
        // 8801/8802 den Nachhol-Alarm bzw. den Wachhund, auf 8803 die Rufbereitschafts-Abfrage.
        val belegt = setOf(
            WartungsKettenPlanung.REGULAER_REQUEST_CODE,
            WartungsKettenPlanung.NACHHOL_REQUEST_CODE,
            WartungsKettenPlanung.WACHHUND_REQUEST_CODE,
            RufbereitschaftAbfrage.REQUEST_CODE
        )
        val eigene = TimeOfficeNachfassAbgleich.termine(druck).map { it.requestCode }.toSet()

        assertEquals(setOf(8804, 8805), eigene)
        assertTrue("Slot doppelt belegt: ${eigene intersect belegt}", (eigene intersect belegt).isEmpty())
    }
}
