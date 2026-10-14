package com.github.f1rlefanz.cf_alarmfortimeoffice.tageslicht

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlzTabelleTest {

    // Bewusst mit CRLF: die Windows-Arbeitskopie checkt Assets mit \r\n aus, Git und CI mit \n.
    private val de = PlzTabelle.parse(
        PlzLand.DE,
        "# Quelle: GeoNames (www.geonames.org), CC BY 4.0, Stand 2026-10-14\r\n" +
            "80\t48.14\t11.58\tMünchen\r\n" +
            "\r\n" +
            "99\t50.98\t11.03\tErfurt\r\n" +
            "803\t48.13\t11.57\tMünchen Altstadt\r\n" +
            "kaputt ohne tabulator\r\n" +
            "81\tkeine-zahl\t11.6\tUngültig\r\n"
    )

    @Test
    fun `parse liest Zeilen, ueberspringt Kopf, Leerzeilen und kaputte Zeilen, ohne CR im Namen`() {
        val r = de.suche(PlzLand.DE, "99")!!
        assertEquals(PlzRegion(PlzLand.DE, "99", 50.98, 11.03, "Erfurt"), r)
        assertNull(de.suche(PlzLand.DE, "81"))
    }

    @Test
    fun `eine Ziffer reicht nicht`() {
        assertNull(de.suche(PlzLand.DE, "8"))
    }

    @Test
    fun `zwei Ziffern treffen den Zweierpraefix`() {
        assertEquals("80", de.suche(PlzLand.DE, "80")!!.praefix)
    }

    @Test
    fun `drei Ziffern - genauerer Praefix, wenn vorhanden, sonst der kuerzere`() {
        assertEquals("803", de.suche(PlzLand.DE, "803")!!.praefix)
        assertEquals("80", de.suche(PlzLand.DE, "809")!!.praefix)
    }

    @Test
    fun `volle Postleitzahl nimmt den laengsten bekannten Praefix`() {
        assertEquals("803", de.suche(PlzLand.DE, "80331")!!.praefix)
        assertEquals("80", de.suche(PlzLand.DE, "80995")!!.praefix)
    }

    @Test
    fun `Leerzeichen am Rand stoeren nicht`() {
        assertEquals("803", de.suche(PlzLand.DE, " 80331 ")!!.praefix)
    }

    @Test
    fun `Nicht-Ziffern werden abgelehnt statt still entfernt`() {
        assertNull(de.suche(PlzLand.DE, "D-80331"))
        assertNull(de.suche(PlzLand.DE, "8o"))
    }

    @Test
    fun `leere Eingabe und unbekannter Praefix ergeben null`() {
        assertNull(de.suche(PlzLand.DE, ""))
        assertNull(de.suche(PlzLand.DE, "   "))
        assertNull(de.suche(PlzLand.DE, "00"))
    }

    @Test
    fun `fremdes Land findet nichts in einer DE-Tabelle`() {
        assertNull(de.suche(PlzLand.AT, "80"))
    }

    @Test
    fun `leere Tabelle findet nichts`() {
        assertNull(PlzTabelle.leer().suche(PlzLand.DE, "80"))
    }
}
