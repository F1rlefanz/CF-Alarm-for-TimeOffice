package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens

import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftDefinition
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftKategorie
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.TypDimmFenster
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.TypDimmen
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.typ.DimFensterAbbildung.zuFenster
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.typ.DimFensterForm
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.typ.DimWann
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.SchichtFolgen
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.ermittleSchichtFolgen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalTime

/**
 * Umbau Etappe 3 (#220, SPEC E9 / 3.1): jede Zeile der Schichttypen-Liste nennt die Dimm-Kurzform
 * ihres Typs. Die Fenster-Texte selbst gehoeren [com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.typ.DimFensterAbbildung]
 * (Test dort); hier steht, WANN die Liste etwas sagt und was.
 */
class TypListeKurzformTest {

    private fun typ(
        name: String = "Frühdienst",
        kategorie: ShiftKategorie = ShiftKategorie.DIENST,
        weckerAn: Boolean = true,
        enabled: Boolean = true,
        dimmen: TypDimmen = TypDimmen()
    ): ShiftDefinition = ShiftDefinition(
        id = name,
        name = name,
        keywords = listOf(name),
        alarmTime = LocalTime.of(5, 30),
        isEnabled = enabled,
        weckerAn = weckerAn,
        dimmen = dimmen
    ).mitKategorie(kategorie)

    private fun fenster(wann: DimWann, von: Int?, bis: Int, beimWecker: Boolean = false): TypDimmFenster {
        val form = DimFensterForm(wann = wann, vonMinuten = von, bisMinuten = bis, prozent = 55, beimWecker = beimWecker)
        return TypDimmFenster(zuFenster(form), staerke = form.prozent)
    }

    private val abendsBisWecker = fenster(DimWann.NACHT_DAVOR, von = 20 * 60, bis = 7 * 60, beimWecker = true)
    private val nachDemDienst = fenster(DimWann.NACH_DEM_DIENST, von = null, bis = 14 * 60)

    private fun dimmen(vararg f: TypDimmFenster, an: Boolean = true) = TypDimmen(an = an).mitFenster(f.toList())

    /** Die Statuszeilen-Folgen, wie das ViewModel sie je Typ liest: keine Hue-Regel, Bridge da. */
    private fun folgen(definition: ShiftDefinition): SchichtFolgen =
        ermittleSchichtFolgen(definition, Result.success(emptyList()), hueKonfiguriert = true)

    /** Der Text der Zeile, wie ihn die Liste anzeigt (ohne die geschuetzten Leerzeichen der Umbruchsteuerung). */
    private fun zeile(definition: ShiftDefinition, gelesen: Boolean = true): String? =
        listenKurzform(definition, folgen(definition).takeIf { gelesen })?.replace(Char(0x00A0), ' ')

    /**
     * Der ganze Weg einer Zeile: Definition -> `listenKurzform` -> Text. Faengt, was ein Test nur ueber
     * `kurzform()` oder `dimmKurzform()` nicht sieht - die Zeile aus Lauf 1 zeigte die Dimm-Angabe
     * ZWEIMAL („dimmt …“ darueber, „Dimmen: 1 Fenster“ darunter), und beide Teile waren fuer sich gruen.
     */
    @Test
    fun `Die Zeile der Liste nennt das Dimmen genau einmal und an der Stelle von Dimmen`() {
        assertEquals(
            "Fenster",
            "dimmt 20:00–Wecker · Licht: keine · Nicht stören: aus",
            zeile(typ(dimmen = dimmen(abendsBisWecker)))
        )
        assertEquals("ohne Fenster", "Licht: keine · Nicht stören: aus", zeile(typ(dimmen = dimmen())))
        assertEquals(
            "Dimmen aus", "Dimmen aus · Licht: keine · Nicht stören: aus",
            zeile(typ(dimmen = dimmen(abendsBisWecker, an = false)))
        )
        assertEquals(
            "kein Wecker", "kein Wecker · dimmt 20:00–07:00 · Nicht stören: aus",
            zeile(typ(weckerAn = false, dimmen = dimmen(abendsBisWecker)))
        )
        assertNull("Krank", zeile(typ(kategorie = ShiftKategorie.KRANK, dimmen = dimmen(abendsBisWecker))))
        listOf(
            typ(dimmen = dimmen(abendsBisWecker)), typ(dimmen = dimmen()),
            typ(dimmen = dimmen(abendsBisWecker, an = false)), typ(weckerAn = false, dimmen = dimmen(abendsBisWecker))
        ).forEach { assertFalse(zeile(it), zeile(it)!!.contains("Dimmen:")) }
    }

    @Test
    fun `Ohne gelesene Folgen steht nur, was die Definition allein sagt`() {
        assertEquals("dimmt 20:00–Wecker", zeile(typ(dimmen = dimmen(abendsBisWecker)), gelesen = false))
        assertNull(zeile(typ(dimmen = dimmen()), gelesen = false))
    }

    @Test
    fun `Ein Typ mit Fenster zeigt dessen Kurzform`() {
        assertEquals("dimmt 20:00–Wecker", dimmKurzform(typ(dimmen = dimmen(abendsBisWecker))))
        assertEquals("dimmt nach Dienst", dimmKurzform(typ(dimmen = dimmen(nachDemDienst))))
        assertEquals("2 Dimm-Fenster", dimmKurzform(typ(dimmen = dimmen(abendsBisWecker, nachDemDienst))))
    }

    @Test
    fun `Das Ende am Wecker steht nur bei einem Typ mit Wecker, sonst die Uhrzeit`() {
        val ohneWecker = typ(weckerAn = false, dimmen = dimmen(abendsBisWecker))
        assertEquals("dimmt 20:00–07:00", dimmKurzform(ohneWecker))
    }

    @Test
    fun `Jede Kategorie ausser Krank und Ignorieren zeigt ihre Kurzform`() {
        val dimmt = dimmen(abendsBisWecker)
        listOf(
            ShiftKategorie.DIENST, ShiftKategorie.RUFBEREITSCHAFT, ShiftKategorie.FREI,
            ShiftKategorie.URLAUB, ShiftKategorie.FORTBILDUNG
        ).forEach { kategorie ->
            assertEquals("$kategorie", "dimmt 20:00–Wecker", dimmKurzform(typ(kategorie = kategorie, dimmen = dimmt)))
        }
    }

    @Test
    fun `Ein Typ ohne Fenster sagt nichts zum Dimmen - die Liste ist kein Ort fuer Nicht dimmen`() {
        assertNull(dimmKurzform(typ(dimmen = dimmen())))
    }

    @Test
    fun `Dimmen aus heisst Dimmen aus, auch wenn Fenster gespeichert sind`() {
        assertEquals("Dimmen aus", dimmKurzform(typ(dimmen = dimmen(abendsBisWecker, an = false))))
        assertEquals("Dimmen aus", dimmKurzform(typ(dimmen = dimmen(an = false))))
    }

    @Test
    fun `Krank und Ignorieren sagen nichts, auch mit Fenstern und auch bei Dimmen aus`() {
        listOf(ShiftKategorie.KRANK, ShiftKategorie.IGNORIEREN).forEach { kategorie ->
            assertNull("$kategorie mit Fenster", dimmKurzform(typ(kategorie = kategorie, dimmen = dimmen(abendsBisWecker))))
            assertNull("$kategorie mit Dimmen aus", dimmKurzform(typ(kategorie = kategorie, dimmen = dimmen(abendsBisWecker, an = false))))
        }
    }

    @Test
    fun `Ein ausgeschalteter Typ sagt nichts - er wird nicht erkannt, seine Fenster wirken nie`() {
        assertNull(dimmKurzform(typ(enabled = false, dimmen = dimmen(abendsBisWecker))))
        assertNull(dimmKurzform(typ(enabled = false, dimmen = dimmen(abendsBisWecker, an = false))))
    }
}
