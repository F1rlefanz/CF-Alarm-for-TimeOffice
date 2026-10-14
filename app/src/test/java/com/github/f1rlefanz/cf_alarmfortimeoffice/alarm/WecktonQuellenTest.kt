package com.github.f1rlefanz.cf_alarmfortimeoffice.alarm

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Die Auswahlregel der Weckton-Quellen. Wecker und Probehoeren teilen sie - ein Fehler hier
 * traefe beide zugleich, deshalb unter Test.
 */
class WecktonQuellenTest {

    @Test
    fun `Reihenfolge Wecker vor Benachrichtigung vor Klingelton bleibt erhalten`() {
        assertEquals(
            listOf("alarm", "benachrichtigung", "klingelton"),
            WecktonQuellen.auswahl("alarm", "benachrichtigung", "klingelton")
        )
    }

    @Test
    fun `fehlende Vorgaben fallen weg statt die Liste abzubrechen`() {
        // Kein Wecker-Ton eingestellt darf nicht "gar kein Ton" heissen - die naechste Quelle zaehlt.
        assertEquals(listOf("klingelton"), WecktonQuellen.auswahl(null, null, "klingelton"))
        assertEquals(emptyList<String>(), WecktonQuellen.auswahl<String>(null, null, null))
    }

    @Test
    fun `dieselbe Quelle wird nur einmal versucht`() {
        assertEquals(
            listOf("ton", "klingelton"),
            WecktonQuellen.auswahl("ton", "ton", "klingelton")
        )
    }
}
