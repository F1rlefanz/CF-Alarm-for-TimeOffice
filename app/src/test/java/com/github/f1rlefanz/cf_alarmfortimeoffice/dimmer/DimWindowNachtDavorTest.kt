package com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer

import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimWindowResolver.AlarmSlot
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * #227 „Nacht davor“: [DimWindow.startAmVorabend] laesst ein Fenster mit CLOCK-Start und
 * CLOCK-/„Weckzeit, spaetestens“-Ende bei Von ≥ Bis am VORABEND des Tages D beginnen und an D
 * enden – egal, wann an D der Wecker klingelt.
 *
 * WARUM (#215): Ohne das Feld ist ein solches Fenster die Nacht NACH dem Kalendertag. Bei einem
 * Nachtdienst-Wecker um 19:30 und Von 16:00 dimmte „Nacht davor“ deshalb die ganze Dienstnacht.
 *
 * Die Lage wird ueber [DimWindowResolver.buildRuleSpans] geprueft – den Weg, den Dimmer und
 * „Nicht stoeren“ wirklich gehen – und in Minuten ab Mitternacht von D angegeben (negativ =
 * Vortag). Jede Zeile laeuft durch beide Pfade: Dimmer (`mitWeckzeitNachlauf = true`) und
 * „Nicht stoeren“ (`false`). Verglichen wird die GANZE Spannenliste (mit `today = D` rechnet die
 * Schleife auch den Vortag D−1 mit), damit ein Fenster an alter UND neuer Stelle auffiele.
 */
class DimWindowNachtDavorTest {

    private val zone = ZoneId.of("Europe/Berlin")

    /** Tag D: ein gewoehnlicher Dienstag, in den Naechten davor keine Zeitumstellung. */
    private val d = LocalDate.parse("2026-11-24")

    private fun t(tag: LocalDate, uhrzeit: String): Long =
        LocalDateTime.of(tag, LocalTime.parse(uhrzeit)).atZone(zone).toInstant().toEpochMilli()

    private fun t(tag: String, uhrzeit: String): Long = t(LocalDate.parse(tag), uhrzeit)

    private fun minute(hhmm: String): Int = LocalTime.parse(hhmm).toSecondOfDay() / 60

    private fun fenster(
        von: String,
        bis: String,
        ende: DimAnchor = DimAnchor.CLOCK,
        amVorabend: Boolean = true,
        nachlauf: Int = 0
    ) = DimWindow(
        startAnchor = DimAnchor.CLOCK,
        startClockMinutes = minute(von),
        endAnchor = ende,
        endClockMinutes = minute(bis),
        weckzeitNachlaufMinuten = nachlauf,
        startAmVorabend = amVorabend
    )

    private fun slot(tag: LocalDate, uhrzeit: String, schichtende: Long = 0L) =
        AlarmSlot(t(tag, uhrzeit), SCHICHT, schichtende)

    /** Alle Spannen fuer `today = tag` (Schleife: Vortag und Tag) als Epoch-Bereiche. */
    private fun spannen(
        w: DimWindow,
        slots: List<AlarmSlot> = emptyList(),
        freierTag: Boolean = false,
        mitNachlauf: Boolean = true,
        tag: LocalDate = d
    ): List<LongRange> {
        val regel = DimRule(id = "typ", name = SCHICHT, shiftPattern = SCHICHT, windows = listOf(w))
        return DimWindowResolver.buildRuleSpans(
            alarms = slots,
            horizonDays = 1,
            today = tag,
            zone = zone,
            ruleForShift = { if (it == SCHICHT) regel else null },
            ruleForFreeDay = { if (freierTag) regel else null },
            weckzeiten = slots.map { it.triggerTime },
            mitWeckzeitNachlauf = mitNachlauf
        ).map { it.range }
    }

    private fun inMinuten(spannen: List<LongRange>): List<LongRange> {
        val mitternacht = t(d, "00:00")
        return spannen.map { (it.first - mitternacht) / 60_000..(it.last - mitternacht) / 60_000 }
    }

    /** Dimmer und „Nicht stoeren“ muessen dieselbe Lage liefern (DND rechnet ueber dieselbe Funktion). */
    private fun assertLage(soll: List<LongRange>, w: DimWindow, slots: List<AlarmSlot> = emptyList(), freierTag: Boolean = false) {
        assertEquals("Dimmer", soll, inMinuten(spannen(w, slots, freierTag, mitNachlauf = true)))
        assertEquals("Nicht stoeren", soll, inMinuten(spannen(w, slots, freierTag, mitNachlauf = false)))
    }

    // ------------------------------------------------------------ die Tabelle aus #227

    @Test
    fun `Von 16 Bis 7 - Wecker 13 30 - die Nacht davor`() {
        assertLage(listOf(-480L..420L), fenster("16:00", "07:00"), listOf(slot(d, "13:30")))
    }

    @Test
    fun `Von 16 Bis 7 - Nachtdienst-Wecker 19 30 - die Nacht davor, nicht die Dienstnacht`() {
        // Der Fall aus #215: ohne das Feld laege das Fenster 16:00 an D bis 07:00 an D+1.
        val nachtdienst = slot(d, "19:30", schichtende = t(d.plusDays(1), "07:00"))
        assertLage(listOf(-480L..420L), fenster("16:00", "07:00"), listOf(nachtdienst))
    }

    @Test
    fun `Von 22 Bis 7 - Wecker 22 00 und 22 30 - die Nacht davor`() {
        assertLage(listOf(-120L..420L), fenster("22:00", "07:00"), listOf(slot(d, "22:00")))
        assertLage(listOf(-120L..420L), fenster("22:00", "07:00"), listOf(slot(d, "22:30")))
    }

    @Test
    fun `Von 22 Bis 7 - Tag ohne Eintrag - die Nacht davor, auch fuer den freien Vortag`() {
        // Auch D−1 ist frei: seine eigene Nacht davor (D−2 22:00 bis D−1 07:00) kommt mit.
        assertLage(listOf(-1560L..-1020L, -120L..420L), fenster("22:00", "07:00"), freierTag = true)
    }

    @Test
    fun `Von 22 - Ende Weckzeit spaetestens 7 - Wecker 05 30 - endet am Wecker`() {
        assertLage(
            listOf(-120L..330L),
            fenster("22:00", "07:00", ende = DimAnchor.ALARM_SONST_CLOCK),
            listOf(slot(d, "05:30"))
        )
    }

    @Test
    fun `Von 16 - Ende Weckzeit spaetestens 7 - Block mit Wecker 19 30 - endet am Wecker des Vortags`() {
        // Wie im Prototyp: die Nacht davor von D beginnt D−1 16:00 und endet am ersten Wecker
        // danach – dem des Vortags um 19:30. D−1 selbst findet zwischen D−2 16:00 und D−1 07:00
        // keinen Wecker und endet an der Uhrzeit.
        assertLage(
            listOf(-1920L..-1020L, -480L..-270L),
            fenster("16:00", "07:00", ende = DimAnchor.ALARM_SONST_CLOCK),
            listOf(slot(d.minusDays(1), "19:30"), slot(d, "19:30"))
        )
    }

    // ------------------------------------------------------------ Grenzen der Bedeutung

    @Test
    fun `Von gleich Bis zaehlt als Von groesser gleich Bis - 24 Stunden bis zum Tag D`() {
        assertLage(listOf(-1020L..420L), fenster("07:00", "07:00"), listOf(slot(d, "05:30")))
    }

    @Test
    fun `Bei Von kleiner Bis wirkt das Feld nicht`() {
        val slots = listOf(slot(d, "05:30"))
        for (ende in listOf(DimAnchor.CLOCK, DimAnchor.ALARM_SONST_CLOCK)) {
            val mit = fenster("01:00", "07:00", ende = ende, amVorabend = true)
            val ohne = fenster("01:00", "07:00", ende = ende, amVorabend = false)
            assertEquals("Schicht-Tag, Ende $ende", spannen(ohne, slots), spannen(mit, slots))
            assertEquals("freier Tag, Ende $ende", spannen(ohne, freierTag = true), spannen(mit, freierTag = true))
        }
        // Die Lage selbst: 01:00 bis 07:00 bzw. bis zum Wecker 05:30 an D.
        assertLage(listOf(60L..420L), fenster("01:00", "07:00"), slots)
    }

    @Test
    fun `Nachlauf und Nicht stoeren - der Dimmer bekommt den Nachlauf, Nicht stoeren nicht`() {
        val w = fenster("22:00", "07:00", ende = DimAnchor.ALARM_SONST_CLOCK, nachlauf = 30)
        val slots = listOf(slot(d, "05:30"))
        assertEquals(listOf(-120L..360L), inMinuten(spannen(w, slots, mitNachlauf = true)))
        assertEquals(listOf(-120L..330L), inMinuten(spannen(w, slots, mitNachlauf = false)))
    }

    // ------------------------------------------------------------ Sommerzeit

    @Test
    fun `Am Sommerzeit-Umstellungstag wird der Vorabend ueber Kalendertage gerollt, nicht ueber minus 24 h`() {
        // 29.03.2026 Vorspringen, 25.10.2026 Zurueckspringen (Europe/Berlin): die Nacht davor
        // beginnt um 22:00 WANDUHRZEIT des Vortags. „22:00 an D minus 24 h“ traefe 21:00 bzw. 23:00.
        val w = fenster("22:00", "07:00")
        assertEquals(
            listOf(t("2026-03-27", "22:00")..t("2026-03-28", "07:00"), t("2026-03-28", "22:00")..t("2026-03-29", "07:00")),
            spannen(w, freierTag = true, tag = LocalDate.parse("2026-03-29"))
        )
        assertEquals(
            listOf(t("2026-10-23", "22:00")..t("2026-10-24", "07:00"), t("2026-10-24", "22:00")..t("2026-10-25", "07:00")),
            spannen(w, freierTag = true, tag = LocalDate.parse("2026-10-25"))
        )
    }

    // ------------------------------------------------------------ Speicher

    @Test
    fun `Ein gespeichertes Fenster ohne das Feld liest sich als false, ein gesetztes bleibt erhalten`() {
        // Wie die Stores heute schreiben (encodeDefaults): alle bisherigen Schluessel, der neue fehlt.
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val alt = """{"startAnchor":"CLOCK","startClockMinutes":1320,"startOffsetMinutes":-120,""" +
            """"endAnchor":"ALARM_SONST_CLOCK","endClockMinutes":420,"endOffsetMinutes":0,""" +
            """"weckzeitNachlaufMinuten":0,"blockPositionen":["ERSTER","MITTLERER","LETZTER","EINZELNER"],""" +
            """"nachlaufBisHell":"IMMER","startNurAmWecktag":false}"""
        assertFalse(json.decodeFromString(DimWindow.serializer(), alt).startAmVorabend)

        val gesetzt = fenster("22:00", "07:00")
        val wieder = json.decodeFromString(DimWindow.serializer(), json.encodeToString(DimWindow.serializer(), gesetzt))
        assertTrue("das Feld muss gespeichert werden, nicht @Transient sein", wieder.startAmVorabend)
        assertEquals(gesetzt, wieder)
    }

    private companion object {
        const val SCHICHT = "Nachtdienst"
    }
}
