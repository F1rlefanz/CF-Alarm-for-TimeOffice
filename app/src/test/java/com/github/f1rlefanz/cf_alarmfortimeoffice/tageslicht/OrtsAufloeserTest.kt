package com.github.f1rlefanz.cf_alarmfortimeoffice.tageslicht

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId

/** Die Stufenkette "genaueste belegte Stufe gewinnt": Standort > PLZ > Zeitzone > kein Ort. */
class OrtsAufloeserTest {

    private val berlin = ZoneId.of("Europe/Berlin")
    private val tabelleDe = PlzTabelle(listOf(PlzRegion(PlzLand.DE, "80", 48.14, 11.58, "München")))
    private val tabellen: (PlzLand) -> PlzTabelle = { if (it == PlzLand.DE) tabelleDe else PlzTabelle.leer() }

    private val standort = GespeicherterStandort(breiteZehntel = 535, laengeZehntel = 100, uebernommenAm = 1L)

    @Test
    fun `Standort schlaegt PLZ und Zeitzone`() {
        val ort = OrtsAufloeser.loese(TageslichtEinstellungen(standort, PlzLand.DE, "80"), tabellen, berlin)!!
        assertEquals(OrtQuelle.STANDORT, ort.quelle)
        assertEquals(53.5, ort.breite, 1e-9)
        assertEquals(10.0, ort.laenge, 1e-9)
        assertNull(ort.bezeichnung)
    }

    @Test
    fun `PLZ schlaegt Zeitzone`() {
        val ort = OrtsAufloeser.loese(TageslichtEinstellungen(null, PlzLand.DE, "80331"), tabellen, berlin)!!
        assertEquals(Ort(48.14, 11.58, OrtQuelle.PLZ, "München"), ort)
    }

    @Test
    fun `unbekannte PLZ faellt auf die Zeitzone durch`() {
        val ort = OrtsAufloeser.loese(TageslichtEinstellungen(null, PlzLand.DE, "00"), tabellen, berlin)!!
        assertEquals(OrtQuelle.ZEITZONE, ort.quelle)
        assertEquals("Erfurt", ort.bezeichnung)
    }

    @Test
    fun `PLZ ohne Land und Land ohne PLZ fallen auf die Zeitzone durch`() {
        assertEquals(OrtQuelle.ZEITZONE, OrtsAufloeser.loese(TageslichtEinstellungen(null, null, "80"), tabellen, berlin)!!.quelle)
        assertEquals(OrtQuelle.ZEITZONE, OrtsAufloeser.loese(TageslichtEinstellungen(null, PlzLand.DE, ""), tabellen, berlin)!!.quelle)
    }

    @Test
    fun `nichts eingestellt - Zeitzone`() {
        assertEquals(OrtQuelle.ZEITZONE, OrtsAufloeser.loese(TageslichtEinstellungen.LEER, tabellen, berlin)!!.quelle)
    }

    @Test
    fun `unbekannte Zeitzone ohne weitere Stufe - kein Ort`() {
        assertNull(OrtsAufloeser.loese(TageslichtEinstellungen.LEER, tabellen, ZoneId.of("America/New_York")))
    }
}
