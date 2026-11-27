package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.typ

import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.Blockposition
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimAnchor
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimOverlayPrefs
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimWindow
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.OhneEintragEinstellungen
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftConfig
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftDefinition
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftKategorie
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.TypDimmFenster
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.TypDimmen
import com.github.f1rlefanz.cf_alarmfortimeoffice.shift.ShiftSpan
import com.github.f1rlefanz.cf_alarmfortimeoffice.tagesleiste.LichtQuelle
import com.github.f1rlefanz.cf_alarmfortimeoffice.tagesleiste.Quelle
import com.github.f1rlefanz.cf_alarmfortimeoffice.tagesleiste.Spur
import com.github.f1rlefanz.cf_alarmfortimeoffice.tagesleiste.TagesleisteQuellen
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset

/**
 * Der Abschnitt „Bildschirm dimmen“ der Typ-Seite (Umbau Etappe 3, #221), ohne Compose: der Entwurf
 * traegt die Fenster ueber Speichern, Prozesstod und die Tagesleiste; was die Seite nicht anfasst
 * (nicht darstellbare und undeutbare Fenster, Farbwaerme), bleibt wortgleich.
 */
class TypEntwurfDimmenTest {

    /** „Nacht davor 22:00 bis Wecker, spaetestens 07:00“ - darstellbar. */
    private val nachtDavor = TypDimmFenster(
        DimWindow(
            startAnchor = DimAnchor.CLOCK, startClockMinutes = 22 * 60,
            endAnchor = DimAnchor.ALARM_SONST_CLOCK, endClockMinutes = 7 * 60, startAmVorabend = true
        ),
        staerke = 55
    )

    /** Das Vorschlaf-Fenster der Nachtdienst-Vorlage: CLOCK bis AM Wecker - die Seite hat dafuer keine Form. */
    private val ohneForm = TypDimmFenster(
        DimWindow(
            startAnchor = DimAnchor.CLOCK, startClockMinutes = 16 * 60,
            endAnchor = DimAnchor.ALARM, endOffsetMinutes = 15,
            blockPositionen = setOf(Blockposition.ERSTER), weckzeitNachlaufMinuten = 20, startNurAmWecktag = true
        ),
        staerke = 70
    )

    /** Ein gespeicherter Stand, den diese Version nicht deuten kann (Anker aus einer spaeteren Version). */
    private val undeutbar: TypDimmen = Json { ignoreUnknownKeys = true }.decodeFromString(
        TypDimmen.serializer(),
        """{"an":true,"fenster":[{"fenster":{"startAnchor":"AB_UEBERMORGEN"},"staerke":33}],"farbwaerme":12}"""
    )

    private fun typ(dimmen: TypDimmen, kategorie: ShiftKategorie = ShiftKategorie.DIENST) = ShiftDefinition(
        id = "id-f", name = "F", keywords = listOf("F"), alarmTime = LocalTime.of(4, 30), dimmen = dimmen
    ).mitKategorie(kategorie)

    private fun mitFenstern(vararg fenster: TypDimmFenster, an: Boolean = true, basis: TypDimmen = TypDimmen()) =
        basis.copy(an = an).mitFenster(fenster.toList())

    // ── Die drei Abnahme-Faelle des Issues ───────────────────────────────────────────────────

    @Test
    fun `der Entwurf mit Fenstern uebersteht alsSpeicherListe und ausSpeicherListe`() {
        val ausgang = TypEntwurf.aus(typ(mitFenstern(nachtDavor, ohneForm)))
        // Eine ungespeicherte Aenderung an den Fenstern muss den Prozesstod ueberleben, nicht nur der Stand beim Oeffnen.
        val entwurf = ausgang.copy(
            dimmen = ausgang.dimmen!!.copy(fenster = listOf(nachtDavor.copy(staerke = 20), ohneForm, nachtDavor))
        )

        val zurueck = TypEntwurf.ausSpeicherListe(entwurf.alsSpeicherListe())

        assertEquals(entwurf, zurueck)
        assertEquals(listOf(20, 70, 55), zurueck!!.dimmen!!.fenster.map { it.staerke })
        assertEquals(ohneForm, zurueck.dimmen!!.fenster[1])
    }

    @Test
    fun `an gleich false bleibt erhalten - im Bundle und beim Speichern`() {
        val aus = typ(mitFenstern(nachtDavor, an = false))
        val entwurf = TypEntwurf.aus(aus)
        assertFalse(entwurf.dimmen!!.an)
        assertFalse(TypEntwurf.ausSpeicherListe(entwurf.alsSpeicherListe())!!.dimmen!!.an)

        // Eine Aenderung an etwas anderem schaltet das Dimmen nicht wieder ein.
        val gespeichert = entwurf.copy(weckzeitText = "05:00").alsDefinition(aus)!!
        assertFalse(gespeichert.dimmen.an)
        assertEquals(listOf(nachtDavor), gespeichert.dimmen.fenster)

        // Und ausschalten im Entwurf kommt an, die Fenster bleiben.
        val an = typ(mitFenstern(nachtDavor))
        val ausgeschaltet = TypEntwurf.aus(an).let { it.copy(dimmen = it.dimmen!!.copy(an = false)) }.alsDefinition(an)!!
        assertFalse(ausgeschaltet.dimmen.an)
        assertEquals(listOf(nachtDavor), ausgeschaltet.dimmen.fenster)
    }

    @Test
    fun `ein nicht darstellbares Fenster bleibt beim Aendern der Weckzeit unveraendert`() {
        assertTrue(
            "Voraussetzung des Falls",
            DimFensterAbbildung.deute(ohneForm) is DimFensterAnsicht.NichtDarstellbar
        )
        val dimmen = mitFenstern(ohneForm, nachtDavor, basis = undeutbar)
        val vorher = typ(dimmen)

        val gespeichert = TypEntwurf.aus(vorher).copy(weckzeitText = "11:15").alsDefinition(vorher)!!

        assertEquals(LocalTime.of(11, 15), gespeichert.alarmTime)
        // Das GANZE Dimmen ist gleich - auch das undeutbare Fenster und die Farbwaerme, die die Seite nicht zeigt.
        assertEquals(dimmen, gespeichert.dimmen)
        assertEquals(1, gespeichert.dimmen.unlesbareFenster)
        assertEquals(12, gespeichert.dimmen.farbwaerme)
    }

    @Test
    fun `eine Aenderung an einem anderen Fenster laesst das nicht darstellbare und das undeutbare stehen`() {
        val vorher = typ(mitFenstern(ohneForm, nachtDavor, basis = undeutbar))
        val entwurf = TypEntwurf.aus(vorher)
        val geaendert = entwurf.copy(
            dimmen = DimmenAbschnittLogik.fensterAendern(entwurf.dimmen!!, 1) { it.copy(staerke = 25) }
        )

        val gespeichert = geaendert.alsDefinition(vorher)!!.dimmen

        assertEquals(listOf(ohneForm, nachtDavor.copy(staerke = 25)), gespeichert.fenster)
        assertEquals(1, gespeichert.unlesbareFenster)
        assertEquals(12, gespeichert.farbwaerme)
    }

    @Test
    fun `der Dimm-Teil geht auf den gerade gespeicherten Typ - was die Seite nicht zeigt bleibt aktuell`() {
        val beimOeffnen = typ(mitFenstern(nachtDavor))
        val entwurf = TypEntwurf.aus(beimOeffnen).let { it.copy(dimmen = it.dimmen!!.copy(an = false)) }
        // Inzwischen hat ein anderer Weg die Farbwaerme gesetzt.
        val jetzt = beimOeffnen.copy(dimmen = beimOeffnen.dimmen.copy(farbwaerme = 80))

        val gespeichert = entwurf.alsDefinition(jetzt)!!.dimmen

        assertFalse(gespeichert.an)
        assertEquals(80, gespeichert.farbwaerme)
    }

    @Test
    fun `ein gespeicherter Entwurf ohne Dimm-Teil gilt als unberuehrt, nie als keine Fenster`() {
        val vorher = typ(mitFenstern(nachtDavor, ohneForm))
        val alt = TypEntwurf.aus(vorher).alsSpeicherListe().take(7)

        val gelesen = TypEntwurf.ausSpeicherListe(alt)!!

        assertNull(gelesen.dimmen)
        assertEquals(vorher.dimmen, gelesen.alsDefinition(vorher)!!.dimmen)
    }

    @Test
    fun `ein unlesbarer Dimm-Teil verwirft den ganzen Entwurf statt Fenster zu verlieren`() {
        val liste = TypEntwurf.aus(typ(mitFenstern(nachtDavor))).alsSpeicherListe()
        assertEquals(8, liste.size)
        assertNull(TypEntwurf.ausSpeicherListe(liste.take(7) + "{kaputt"))
    }

    @Test
    fun `eine Aenderung an den Fenstern gilt als geaendert, zurueckgestellt nicht mehr`() {
        val ausgang = TypEntwurf.aus(typ(mitFenstern(nachtDavor)))
        val aus = ausgang.copy(dimmen = ausgang.dimmen!!.copy(an = false))
        assertTrue(TypSeiteEntwurf.hatAenderungen(ausgang, aus))
        assertFalse(TypSeiteEntwurf.hatAenderungen(ausgang, aus.copy(dimmen = aus.dimmen!!.copy(an = true))))
    }

    @Test
    fun `Speichern der Typ-Seite schreibt die Fenster des Entwurfs in die Konfiguration`() {
        val vorher = typ(TypDimmen())
        val config = ShiftConfig(definitions = listOf(vorher))
        val entwurf = TypEntwurf.aus(vorher).let {
            it.copy(dimmen = DimmenAbschnittLogik.fensterDazu(it.dimmen!!, weckerAn = true))
        }
        val geschrieben = mutableListOf<ShiftConfig>()

        TypSeiteSpeicherung { geschrieben += it }.speichere(config, vorher.id, entwurf)

        assertEquals(1, geschrieben.single().definitions.single().dimmen.fenster.size)
    }

    @Test
    fun `auch die Neuanlage nimmt die Fenster mit`() {
        val vorlage = ShiftEditFormular.leererTyp { "neu" }
        val entwurf = TypEntwurf.aus(vorlage).let {
            it.copy(name = "Neu", muster = listOf("N"), dimmen = DimmenAbschnittLogik.fensterDazu(it.dimmen!!, true))
        }
        assertEquals(1, entwurf.alsDefinition(vorlage)!!.dimmen.fenster.size)
    }

    // ── „+ Weiteres Fenster“, Entfernen, „Wann“ ─────────────────────────────────────────────

    @Test
    fun `ein neues Fenster ist Nacht davor 22 bis 7 Uhr mit 40 Prozent - mit Wecker endet es beim Wecker`() {
        fun form(weckerAn: Boolean): DimFensterForm {
            val neu = DimmenAbschnittLogik.neuesFenster(weckerAn)
            return (DimFensterAbbildung.deute(neu) as DimFensterAnsicht.Darstellbar).form
        }
        assertEquals(DimFensterForm(DimWann.NACHT_DAVOR, 22 * 60, 7 * 60, 40, beimWecker = true), form(true))
        assertEquals(DimFensterForm(DimWann.NACHT_DAVOR, 22 * 60, 7 * 60, 40, beimWecker = false), form(false))
        assertEquals(Blockposition.ALLE, DimmenAbschnittLogik.neuesFenster(true).fenster.blockPositionen)
    }

    @Test
    fun `+ haengt an, Entfernen nimmt genau das Fenster an der Stelle`() {
        val start = DimmenEntwurf(an = true, fenster = listOf(nachtDavor, ohneForm))
        val dazu = DimmenAbschnittLogik.fensterDazu(start, weckerAn = true)
        assertEquals(listOf(nachtDavor, ohneForm, DimmenAbschnittLogik.neuesFenster(true)), dazu.fenster)
        assertEquals(listOf(nachtDavor, DimmenAbschnittLogik.neuesFenster(true)), DimmenAbschnittLogik.fensterEntfernen(dazu, 1).fenster)
    }

    @Test
    fun `Wann wechseln behaelt Von und Bis - auch ueber Nach dem Dienst hinweg`() {
        val abend = DimmenAbschnittLogik.wannWaehlen(nachtDavor.copy(fenster = nachtDavor.fenster.copy(startClockMinutes = 21 * 60)), DimWann.ABEND_DANACH)
        val dienst = DimmenAbschnittLogik.wannWaehlen(abend, DimWann.NACH_DEM_DIENST)
        val zurueck = DimmenAbschnittLogik.wannWaehlen(dienst, DimWann.NACHT_DAVOR)

        fun form(f: TypDimmFenster) = (DimFensterAbbildung.deute(f) as DimFensterAnsicht.Darstellbar).form
        assertEquals(DimWann.ABEND_DANACH, form(abend).wann)
        assertNull(form(dienst).vonMinuten)
        assertEquals(21 * 60, form(zurueck).vonMinuten)
        assertEquals(7 * 60, form(zurueck).bisMinuten)
        assertEquals(55, form(zurueck).prozent)
    }

    @Test
    fun `ein nicht darstellbares Fenster laesst sich nicht ueber die Form umschreiben`() {
        assertEquals(ohneForm, DimmenAbschnittLogik.wannWaehlen(ohneForm, DimWann.ABEND_DANACH))
        assertEquals(ohneForm, DimmenAbschnittLogik.formAendern(ohneForm) { it.copy(prozent = 1) })
    }

    // ── Blocktage ────────────────────────────────────────────────────────────────────────────

    @Test
    fun `Blocktage - von Alle waehlt ein Tipp genau diesen Tag, das letzte Haekchen bleibt`() {
        val l = DimmenAbschnittLogik
        val nurErster = l.blockTageWaehlen(Blockposition.ALLE, Blockposition.ERSTER)
        assertEquals(setOf(Blockposition.ERSTER), nurErster)
        assertEquals("das letzte Haekchen bleibt", nurErster, l.blockTageWaehlen(nurErster, Blockposition.ERSTER))
        val zwei = l.blockTageWaehlen(nurErster, Blockposition.LETZTER)
        assertEquals(setOf(Blockposition.ERSTER, Blockposition.LETZTER), zwei)
        assertEquals(setOf(Blockposition.LETZTER), l.blockTageWaehlen(zwei, Blockposition.ERSTER))
        assertEquals(Blockposition.ALLE, l.blockTageWaehlen(zwei, null))
    }

    @Test
    fun `Blocktage - alle vier einzeln gewaehlt ist Alle, und dann leuchtet kein einzelner Chip`() {
        var wahl: Set<Blockposition> = setOf(Blockposition.ERSTER)
        listOf(Blockposition.MITTLERER, Blockposition.LETZTER, Blockposition.EINZELNER).forEach {
            wahl = DimmenAbschnittLogik.blockTageWaehlen(wahl, it)
        }
        assertEquals(Blockposition.ALLE, wahl)
        assertTrue(DimmenAbschnittLogik.chipAn(wahl, null))
        Blockposition.entries.forEach { assertFalse(DimmenAbschnittLogik.chipAn(wahl, it)) }
        assertTrue(DimmenAbschnittLogik.chipAn(setOf(Blockposition.LETZTER), Blockposition.LETZTER))
        assertFalse(DimmenAbschnittLogik.chipAn(setOf(Blockposition.LETZTER), null))
    }

    @Test
    fun `die Chips nennen die Woerter des Prototyps in seiner Reihenfolge`() {
        assertEquals(
            listOf("Alle", "Erster", "Mittlere", "Letzter", "Einzeln"),
            DimmenAbschnittLogik.CHIPS.map { DimmenTexte.blockTag(it) }
        )
    }

    // ── Sichtbarkeit ────────────────────────────────────────────────────────────────────────

    @Test
    fun `der Abschnitt fehlt bei Krank und Ignorieren, Blocktage nur bei Dienst und Rufbereitschaft`() {
        assertEquals(
            ShiftKategorie.entries.toSet() - setOf(ShiftKategorie.KRANK, ShiftKategorie.IGNORIEREN),
            ShiftKategorie.entries.filter { DimmenAbschnittLogik.zeigtAbschnitt(it) }.toSet()
        )
        assertEquals(
            setOf(ShiftKategorie.DIENST, ShiftKategorie.RUFBEREITSCHAFT),
            ShiftKategorie.entries.filter { DimmenAbschnittLogik.zeigtBlocktage(it) }.toSet()
        )
    }

    @Test
    fun `der Hinweis zu Tagen ohne Eintrag steht nur bei den zwei Formen, die es dort nicht gibt`() {
        fun hinweis(form: DimFensterForm, ohneEintrag: Boolean) =
            DimmenAbschnittLogik.ohneEintragHinweis(form, ohneEintrag)
        val nachDienst = DimFensterForm(DimWann.NACH_DEM_DIENST, null, 12 * 60, 40, false)
        val vorschlaf = DimFensterForm(DimWann.NACHT_DAVOR, 16 * 60, 19 * 60, 40, false)
        val ueberNacht = DimFensterForm(DimWann.NACHT_DAVOR, 22 * 60, 7 * 60, 40, false)
        val abend = DimFensterForm(DimWann.ABEND_DANACH, 10 * 60, 12 * 60, 40, false)

        assertEquals(DimmenTexte.NICHT_OHNE_EINTRAG, hinweis(nachDienst, true))
        assertEquals(DimmenTexte.NICHT_OHNE_EINTRAG, hinweis(vorschlaf, true))
        assertNull(hinweis(ueberNacht, true))
        assertNull(hinweis(abend, true))
        assertNull("gilt der Typ nicht fuer Tage ohne Eintrag, gibt es nichts zu sagen", hinweis(nachDienst, false))
    }

    @Test
    fun `die Erklaerung von Nacht davor sagt bei Von vor Bis, dass das Fenster am Tag selbst liegt`() {
        val l = DimmenAbschnittLogik
        val ueberNacht = DimFensterForm(DimWann.NACHT_DAVOR, 22 * 60, 7 * 60, 40, false)
        val vorschlaf = DimFensterForm(DimWann.NACHT_DAVOR, 16 * 60, 19 * 60 + 30, 40, false)
        assertEquals(DimWann.NACHT_DAVOR.erklaerung, l.erklaerung(ueberNacht))
        assertEquals(DimmenTexte.NACHT_DAVOR_AM_TAG_SELBST, l.erklaerung(vorschlaf))
        assertEquals(DimWann.ABEND_DANACH.erklaerung, l.erklaerung(DimFensterForm(DimWann.ABEND_DANACH, 20 * 60, 7 * 60, 40, false)))
    }

    // ── „Mit %“ ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun `Mit Prozent nimmt ganze Zahlen bis zur Grenze des heutigen Reglers`() {
        val l = DimmenAbschnittLogik
        assertEquals(DimOverlayPrefs.STRENGTH_MAX, 85)
        assertEquals("40" to 40, l.prozentEingabe("40"))
        assertEquals("0" to 0, l.prozentEingabe("0"))
        assertEquals("85" to 85, l.prozentEingabe("100"))
        assertEquals("7" to 7, l.prozentEingabe("07"))
        assertEquals("" to null, l.prozentEingabe(""))
        assertNull(l.prozentEingabe("4,5"))
        assertNull(l.prozentEingabe("-3"))
        assertNull(l.prozentEingabe("1000"))
    }

    // ── Die Tagesleiste rechnet mit dem Entwurf ──────────────────────────────────────────────

    private val zone = ZoneOffset.UTC
    private val jetzt = LocalDateTime.of(2026, 11, 9, 12, 0).toInstant(zone).toEpochMilli()

    private fun quellen(config: ShiftConfig) = TagesleisteQuellen(
        typen = Quelle.Bekannt(config),
        licht = Quelle.Bekannt(LichtQuelle(konfiguriert = false, regeln = emptyList(), vorlaufMinuten = { _, _, _, _ -> null })),
        spannen = Quelle.Bekannt(
            listOf(
                ShiftSpan(
                    shiftName = "F",
                    startTime = LocalDateTime.of(2026, 11, 10, 6, 0).toInstant(zone).toEpochMilli(),
                    endTime = LocalDateTime.of(2026, 11, 10, 14, 15).toInstant(zone).toEpochMilli(),
                    alarmTriggerTime = LocalDateTime.of(2026, 11, 10, 4, 30).toInstant(zone).toEpochMilli()
                )
            )
        )
    )

    private fun dimmBalken(entwurf: TypEntwurf, gespeichert: String?, config: ShiftConfig) =
        TagesleisteKarteLogik.rechne(entwurf, gespeichert, quellen(config), Blockposition.EINZELNER, jetzt, zone)
            .leiste.balken.filter { it.spur == Spur.DIMMEN }.map { it.vonMin to it.bisMin }

    @Test
    fun `die Leiste zeigt das Fenster des Entwurfs, bevor gespeichert ist`() {
        val gespeichert = typ(TypDimmen())
        val config = ShiftConfig(definitions = listOf(gespeichert))
        val ohne = TypEntwurf.aus(gespeichert)
        assertEquals(emptyList<Pair<Int, Int>>(), dimmBalken(ohne, "F", config))

        val mit = ohne.copy(dimmen = DimmenAbschnittLogik.fensterDazu(ohne.dimmen!!, weckerAn = true))
        // 22:00 am Vortag (Minute 240) bis zum Wecker 04:30 (Minute 630).
        assertEquals(listOf(240 to 630), dimmBalken(mit, "F", config))

        val spaeter = mit.copy(
            dimmen = DimmenAbschnittLogik.fensterAendern(mit.dimmen!!, 0) { f ->
                DimmenAbschnittLogik.formAendern(f) { it.copy(vonMinuten = 23 * 60) }
            }
        )
        assertEquals(listOf(300 to 630), dimmBalken(spaeter, "F", config))
    }

    @Test
    fun `im Entwurf ausgeschaltet verschwindet der Balken, obwohl die Fenster noch gespeichert sind`() {
        val gespeichert = typ(mitFenstern(nachtDavor))
        val config = ShiftConfig(definitions = listOf(gespeichert))
        val entwurf = TypEntwurf.aus(gespeichert)
        assertEquals(listOf(240 to 630), dimmBalken(entwurf, "F", config))
        assertEquals(emptyList<Pair<Int, Int>>(), dimmBalken(entwurf.copy(dimmen = entwurf.dimmen!!.copy(an = false)), "F", config))
    }

    @Test
    fun `bei der Neuanlage zeigt die Leiste die Fenster des Entwurfs`() {
        val vorlage = ShiftEditFormular.leererTyp { "neu" }
        val entwurf = TypEntwurf.aus(vorlage).let {
            it.copy(name = "Neu", weckzeitText = "04:30", dimmen = DimmenAbschnittLogik.fensterDazu(it.dimmen!!, true))
        }
        assertEquals(listOf(240 to 630), dimmBalken(entwurf, null, ShiftConfig(definitions = emptyList())))
    }

    @Test
    fun `ist die feste Zeile wie dieser Typ, rechnet auch sie mit dem Entwurf`() {
        val gespeichert = typ(TypDimmen())
        val config = ShiftConfig(
            definitions = listOf(gespeichert),
            ohneKalendereintrag = OhneEintragEinstellungen(),
            ohneKalendereintragWie = gespeichert.id
        )
        val neu = DimmenAbschnittLogik.fensterDazu(TypEntwurf.aus(gespeichert).dimmen!!, true)

        val einstellungen = TagesleisteKarteLogik.einstellungenAus(config, "F", neu)

        assertEquals(neu.fenster, einstellungen.dimmen.fenster)
        assertEquals(neu.fenster, einstellungen.freierTag.dimmen.fenster)
        assertNotEquals(gespeichert.dimmen, einstellungen.dimmen)
        assertTrue(DimmenAbschnittLogik.giltOhneEintrag(config, gespeichert.id))
        assertFalse(DimmenAbschnittLogik.giltOhneEintrag(config.copy(ohneKalendereintragWie = null), gespeichert.id))
    }
}
