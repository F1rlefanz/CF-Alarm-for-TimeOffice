package com.github.f1rlefanz.cf_alarmfortimeoffice.tageslicht

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * #144: Passt der gespeicherte Standort zur Zeitzone? Sommer UND Winter, weil die Pruefung bewusst
 * mit dem Standard-Offset rechnet - ein Rechnen mit dem aktuellen Offset fiele genau im Sommer auf.
 */
class StandortZeitzoneTest {

    private val berlin = ZoneId.of("Europe/Berlin")
    private val sommer = ZonedDateTime.of(2026, 7, 1, 12, 0, 0, 0, berlin).toInstant()
    private val winter = ZonedDateTime.of(2026, 1, 15, 12, 0, 0, 0, berlin).toInstant()

    @Test
    fun `Muenchen und Berlin passen zu Europe Berlin, Sommer wie Winter`() {
        for (jetzt in listOf(sommer, winter)) {
            assertTrue(StandortZeitzone.passt(11.6, berlin, jetzt))
            assertTrue(StandortZeitzone.passt(13.4, berlin, jetzt))
        }
        // Muenchen: 60 - 4 * 11,6 = 13,6 min - unabhaengig von der Sommerzeit
        assertEquals(14, StandortZeitzone.abweichungMinuten(11.6, berlin, sommer))
        assertEquals(14, StandortZeitzone.abweichungMinuten(11.6, berlin, winter))
    }

    @Test
    fun `Kalifornien bei Europe Berlin passt nicht - der Fall aus dem Issue`() {
        assertFalse(StandortZeitzone.passt(-122.4, berlin, sommer))
        assertFalse(StandortZeitzone.passt(-122.4, berlin, winter))
        // 60 + 489,6 = 549,6 min: die Sonne steht dort gut 9 h spaeter im Sueden
        assertEquals(550, StandortZeitzone.abweichungMinuten(-122.4, berlin, winter))
    }

    @Test
    fun `spanische Westkueste passt im Sommer zur eigenen Zone`() {
        // Mit dem aktuellen Offset (+2) waeren es 155 min und der Hinweis kaeme zu Unrecht
        val madrid = ZoneId.of("Europe/Madrid")
        assertEquals(95, StandortZeitzone.abweichungMinuten(-8.7, madrid, sommer))
        assertTrue(StandortZeitzone.passt(-8.7, madrid, sommer))
    }

    @Test
    fun `Schwelle ist inklusiv bei genau zwei Stunden`() {
        // UTC: Standard-Offset 0, 4 min je Grad -> 30° = 120 min, 30,25° = 121 min
        val utc = ZoneId.of("UTC")
        assertEquals(-120, StandortZeitzone.abweichungMinuten(30.0, utc, winter))
        assertTrue(StandortZeitzone.passt(30.0, utc, winter))
        assertFalse(StandortZeitzone.passt(30.25, utc, winter))
        assertTrue(StandortZeitzone.passt(-30.0, utc, winter))
        assertFalse(StandortZeitzone.passt(-30.25, utc, winter))
    }

    @Test
    fun `Abweichung wird ueber die Datumsgrenze normiert`() {
        // Auckland (174,8° O) unter Pacific/Auckland (+12): 720 - 699,2 = 20,8 min, nicht -1419
        val auckland = ZoneId.of("Pacific/Auckland")
        assertEquals(21, StandortZeitzone.abweichungMinuten(174.8, auckland, winter))
        // Samoa-Laenge (-171,8°) unter Auckland: 720 + 687,2 = 1407 -> -33 min (Nachbar ueber 180°)
        assertEquals(-33, StandortZeitzone.abweichungMinuten(-171.8, auckland, winter))
    }
}
