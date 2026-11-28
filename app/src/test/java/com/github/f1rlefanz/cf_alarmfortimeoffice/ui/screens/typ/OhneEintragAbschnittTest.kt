package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.typ

import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimAnchor
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimWindow
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.OhneEintragEinstellungen
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftConfig
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftDefinition
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftKategorie
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.TypDimmFenster
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.TypDimmen
import com.github.f1rlefanz.cf_alarmfortimeoffice.tageslicht.HellAb
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

/**
 * Die Wahl auf der Seite „Ohne Kalendereintrag“ (Umbau #191, SPEC E5 und 3.3 Punkt 2, BILDSCHIRME B3o).
 * Es gibt keine Compose-Testumgebung: getestet wird die Rechnung hinter der Seite
 * ([OhneEintragAbschnittLogik]); das Schreiben selbst haelt `OhneEintragWahlTest` am `ShiftViewModel` fest,
 * das Zurueck `NavigationViewModelTest`, den Bildschirm die Geraetepruefung.
 *
 * Dazu der Weg des Blatts „Weitere Optionen“ an der festen Zeile (#238): was es bekommt und dass jede
 * Aenderung sofort in den eigenen Einstellungen ankommt.
 */
class OhneEintragAbschnittTest {

    private fun typ(id: String, kategorie: ShiftKategorie = ShiftKategorie.DIENST, weckerAn: Boolean = true) =
        ShiftDefinition(
            id = id,
            name = "Typ $id",
            keywords = listOf(id),
            alarmTime = LocalTime.of(5, 30),
            weckerAn = weckerAn
        ).mitKategorie(kategorie)

    private val dienst = typ("F")
    private val frei = typ("+", ShiftKategorie.FREI, weckerAn = false)
    private val ignorieren = typ("TB", ShiftKategorie.IGNORIEREN, weckerAn = false)
    private val krank = typ("K", ShiftKategorie.KRANK)

    private fun config(wie: String? = null) = ShiftConfig(
        definitions = listOf(dienst, frei, ignorieren, krank),
        ohneKalendereintragWie = wie
    )

    @Test
    fun `die Wahl zeigt Eigene Einstellungen zuerst, dann Wie-Zeilen ohne Ignorieren - gehakt ist die Konfiguration`() {
        val zeilen = OhneEintragAbschnittLogik.zeilen(config(wie = "+"))

        assertEquals(listOf(null, "F", "+", "K"), zeilen.map { it.typId })
        assertEquals(
            listOf("Eigene Einstellungen", "Wie Typ F (F)", "Wie Typ + (+)", "Wie Typ K (K)"),
            zeilen.map { it.titel }
        )
        assertEquals(listOf(false, false, true, false), zeilen.map { it.ausgewaehlt })
        assertEquals("Kein Wecker an diesen Tagen.", zeilen.first().beschreibung)
        assertEquals("Kategorie Frei · kein Wecker", zeilen.first { it.typId == "+" }.beschreibung)
    }

    @Test
    fun `ohne Wahl und bei einem Verweis ins Leere ist Eigene Einstellungen gehakt und es gibt keinen Oeffnen-Kasten`() {
        for (wie in listOf(null, "geloescht", "TB")) {
            val zeilen = OhneEintragAbschnittLogik.zeilen(config(wie = wie))
            assertEquals("nur Eigene Einstellungen gehakt bei wie=$wie", listOf(true, false, false, false), zeilen.map { it.ausgewaehlt })
            assertNull("kein Typ zum Oeffnen bei wie=$wie", OhneEintragAbschnittLogik.oeffnenZiel(config(wie = wie)))
        }
    }

    @Test
    fun `Oeffnen zeigt die Seite des gewaehlten Typs - nicht eines anderen und nicht der ersten Zeile`() {
        // Der Kasten gehoert zu dem Typ, bei dem die Wahl STEHT, nicht zum zuletzt getippten oder zum ersten.
        assertEquals("K", OhneEintragAbschnittLogik.oeffnenZiel(config(wie = "K"))?.id)
        assertEquals("F", OhneEintragAbschnittLogik.oeffnenZiel(config(wie = "F"))?.id)
    }

    @Test
    fun `ein Tipp schreibt genau einmal mit dem Typ der Zeile - Eigene Einstellungen mit null`() {
        val zeilen = OhneEintragAbschnittLogik.zeilen(config())

        for (zeile in zeilen) {
            val geschrieben = mutableListOf<String?>()
            OhneEintragAbschnittLogik.tippe(zeile) { geschrieben += it }
            assertEquals("ein einziger Schreibaufruf fuer ${zeile.titel}", listOf(zeile.typId), geschrieben)
        }
    }

    // ---- „Weitere Optionen“ an der festen Zeile (#238) ----------------------------------------

    /** Wie aus einer FREI-Regel: „22:00 bis Weckzeit, spaetestens 07:00“ - nur hier gibt es den Nachlauf. */
    private val bisWecker = TypDimmFenster(
        fenster = DimWindow(
            startAnchor = DimAnchor.CLOCK,
            startClockMinutes = 22 * 60,
            endAnchor = DimAnchor.ALARM_SONST_CLOCK,
            endClockMinutes = 7 * 60,
            startAmVorabend = true
        ),
        staerke = 40
    )

    /** Passt in kein „Wann“ (eine Stunde vor dem Wecker bis zum Wecker): bekommt den Anker-Editor. */
    private val ohneWann = TypDimmFenster(
        fenster = DimWindow(
            startAnchor = DimAnchor.ALARM,
            startOffsetMinutes = -60,
            endAnchor = DimAnchor.ALARM,
            endOffsetMinutes = 0
        ),
        staerke = 25
    )

    /** Beide Fenster, dazu eines, das diese Version nicht deuten kann, und eine eigene Farbwaerme. */
    private val eigene = OhneEintragEinstellungen(
        dimmen = Json.decodeFromString(
            TypDimmen.serializer(),
            """{"an":true,"fenster":[{"fenster":{"startAnchor":"SPAETER_ERFUNDEN"},"staerke":20}],"farbwaerme":12}"""
        ).mitFenster(listOf(bisWecker, ohneWann)),
        nichtStoerenSolangeGedimmt = true
    )

    /** Die feste Zeile hinter dem Blatt: geschrieben wird wie in `ShiftViewModel.aendereOhneEintrag`. */
    private inner class FesteZeile(var stand: OhneEintragEinstellungen) {
        var schreibaufrufe = 0
        private val aendere = OhneEintragAbschnittLogik.blattSchreibweg { aenderung ->
            schreibaufrufe++
            stand = aenderung(stand)
        }

        private fun werte() = OhneEintragAbschnittLogik.blattWerte(config().copy(ohneKalendereintrag = stand))!!

        fun waerme(wahl: Int?) {
            val gespeichert = werte().gespeicherteWaerme
            aendere { WeitereOptionenLogik.waermeWaehlen(it, wahl, gespeichert) }
        }

        fun fenster(index: Int, aenderung: (DimWindow) -> DimWindow) =
            aendere { WeitereOptionenLogik.fensterAendern(it, index, aenderung) }
    }

    @Test
    fun `Nachlauf, Hell ab, Anker und Farbwaerme aus dem Blatt landen sofort im Dimmen der festen Zeile`() {
        assertEquals(1, eigene.dimmen.unlesbareFenster)
        val zeile = FesteZeile(eigene)

        zeile.fenster(0) { WeitereOptionenLogik.nachlaufSetzen(it, 30) }
        assertEquals(30, zeile.stand.dimmen.fenster[0].fenster.weckzeitNachlaufMinuten)
        zeile.fenster(0) { it.copy(nachlaufBisHell = HellAb.SONNENAUFGANG) }
        zeile.fenster(1) { it.copy(endAnchor = DimAnchor.SHIFT_END) }
        zeile.waerme(55)

        assertEquals("jede Aenderung schreibt fuer sich, ohne Speichern", 4, zeile.schreibaufrufe)
        assertEquals(
            listOf(
                bisWecker.copy(
                    fenster = bisWecker.fenster.copy(weckzeitNachlaufMinuten = 30, nachlaufBisHell = HellAb.SONNENAUFGANG)
                ),
                ohneWann.copy(fenster = ohneWann.fenster.copy(endAnchor = DimAnchor.SHIFT_END))
            ),
            zeile.stand.dimmen.fenster
        )
        assertEquals(55, zeile.stand.dimmen.farbwaerme)
        assertEquals("das undeutbare Fenster bleibt gespeichert", 1, zeile.stand.dimmen.unlesbareFenster)
        assertTrue(zeile.stand.nichtStoerenSolangeGedimmt)
    }

    @Test
    fun `wie in den Einstellungen ergibt Waerme null und der fruehere Wert kommt wieder an`() {
        val zeile = FesteZeile(eigene)

        zeile.waerme(null)

        assertNull(zeile.stand.dimmen.farbwaerme)
        assertEquals(eigene.dimmen.fenster, zeile.stand.dimmen.fenster)
        assertEquals(1, zeile.stand.dimmen.unlesbareFenster)

        // Verglichen wird gegen den JETZT gespeicherten Wert, nicht gegen den beim Oeffnen.
        zeile.waerme(12)
        assertEquals(eigene, zeile.stand)
    }

    @Test
    fun `das Blatt bekommt die Werte der festen Zeile - bei wie Typ gibt es keines`() {
        val werte = OhneEintragAbschnittLogik.blattWerte(config().copy(ohneKalendereintrag = eigene))!!

        assertEquals(DimmenEntwurf(an = true, fenster = listOf(bisWecker, ohneWann)), werte.dimmen)
        assertFalse("an Tagen ohne Eintrag gibt es keinen eigenen Wecker", werte.weckerAn)
        assertFalse(werte.mitBlocktagen)
        assertEquals(12, werte.gespeicherteWaerme)

        assertNull(OhneEintragAbschnittLogik.blattWerte(config(wie = "F").copy(ohneKalendereintrag = eigene)))
        assertNotNull("ein Verweis ins Leere ist Eigene Einstellungen", OhneEintragAbschnittLogik.blattWerte(config(wie = "geloescht")))
    }
}
