package com.github.f1rlefanz.cf_alarmfortimeoffice.ui

import com.github.f1rlefanz.cf_alarmfortimeoffice.model.AlarmInfo
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.tabs.wecker.DIENST_FAELLT_AUS_ERKLAERUNG
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.tabs.wecker.DIENST_FAELLT_AUS_KURZ
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.tabs.wecker.ZURUECKNEHMEN_ERKLAERUNG
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.tabs.wecker.tagUeberschrift
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.SkipLage
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.baueWeckerTage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * Anzeige-Zusicherungen von „Dienst fällt aus“ (bis 1.49 "Tag freigeben") in der Tagesliste.
 *
 * Der wichtigste Test hier ist der Rueckweg: „Dienst fällt aus“ LOESCHT die Wecker des Tages. War
 * es der einzige, gibt es danach keinen Wecker mehr - und der Tag duerfte trotzdem nicht aus der
 * Liste verschwinden, sonst ist „Zurücknehmen“ weg und der Zustand nicht mehr umkehrbar. Genau
 * diese Falle hat das Ueberspringen bis v1.26.2 gehabt; bis 1.49 hielt sie
 * `freigabeAbschnittSichtbar` fest, seit #139 die Tagesliste selbst.
 */
class TagFreigabeAnzeigeTest {

    private val zone: ZoneId = ZoneId.of("Europe/Berlin")
    private val heute: LocalDate = LocalDate.of(2026, 8, 24)
    private val jetzt: Long = heute.atTime(6, 0).atZone(zone).toInstant().toEpochMilli()

    private fun tage(alarme: List<AlarmInfo>, freieTage: List<LocalDate>) =
        baueWeckerTage(alarme, emptyList(), freieTage, SkipLage.KEIN, emptyList(), jetzt, zone, emptyMap())

    @Test
    fun `Der freigegebene Tag bleibt in der Liste, wenn danach kein Wecker mehr uebrig ist`() {
        val morgen = heute.plusDays(1)
        val liste = tage(alarme = emptyList(), freieTage = listOf(morgen))

        assertEquals(1, liste.size)
        assertEquals(morgen, liste.single().datum)
        assertTrue("Nur ein freigegebener Tag bietet „Zurücknehmen“ an", liste.single().freigegeben)
    }

    @Test
    fun `Ohne Wecker und ohne Freigabe gibt es nichts zu zeigen`() {
        assertTrue(tage(alarme = emptyList(), freieTage = emptyList()).isEmpty())
    }

    @Test
    fun `Die Erklaerung am Knopf nennt, was wegfaellt und was bleibt`() {
        // Der Text IST die Zusicherung: er muss sagen, was wegfaellt - sonst greift der Nutzer
        // wieder zur falschen Geste, wie am 24.08.2026 („Nicht stören“ ging zum Schichtbeginn an).
        assertTrue(DIENST_FAELLT_AUS_ERKLAERUNG.contains("Nicht stören"))
        assertTrue(DIENST_FAELLT_AUS_ERKLAERUNG.contains("Dimmen"))
        assertTrue(DIENST_FAELLT_AUS_ERKLAERUNG.contains("Kalender"))
        assertTrue(DIENST_FAELLT_AUS_ERKLAERUNG.contains("eigenen Wecker bleiben"))
    }

    @Test
    fun `Die Texte sprechen die neuen Namen, nicht die alten`() {
        listOf(DIENST_FAELLT_AUS_ERKLAERUNG, DIENST_FAELLT_AUS_KURZ, ZURUECKNEHMEN_ERKLAERUNG).forEach { text ->
            assertFalse(text, text.contains("freigeb", ignoreCase = true))
            assertFalse(text, text.contains("Überspring", ignoreCase = true))
        }
        assertTrue(ZURUECKNEHMEN_ERKLAERUNG.contains("„Zurücknehmen“"))
    }

    @Test
    fun `Ein Tag wird mit Wochentag und Datum angezeigt`() {
        // Ohne Wochentag ist ein Datum in einer Wecker-App schwer zu pruefen.
        val text = tagUeberschrift(heute)
        assertEquals("Mo 24.08.", text)
    }
}
