package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.typ

import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftConfig
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftDefinition
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftKategorie
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalTime

/**
 * Die Wahl auf der Seite „Ohne Kalendereintrag“ (Umbau #191, SPEC E5 und 3.3 Punkt 2, BILDSCHIRME B3o).
 * Es gibt keine Compose-Testumgebung: getestet wird die Rechnung hinter der Seite
 * ([OhneEintragAbschnittLogik]); das Schreiben selbst haelt `OhneEintragWahlTest` am `ShiftViewModel` fest,
 * das Zurueck `NavigationViewModelTest`, den Bildschirm die Geraetepruefung.
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
}
