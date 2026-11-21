package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.typ

import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.Blockposition
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimAnchor
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimRule
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimWindow
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimWindowResolver
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.TypDimmFenster
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.TypDimmen
import com.github.f1rlefanz.cf_alarmfortimeoffice.tageslicht.HellAb
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.typ.DimFensterAbbildung.deute
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.typ.DimFensterAbbildung.zuFenster
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.DimmerRulesViewModel.Companion.baueVorlagenRegel
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.DimmerRulesViewModel.SchnellstartVorlage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Das Dimm-Fenster in der Sprache der Typ-Seite (Umbau #215, SPEC E6/E9/E14).
 *
 * ORAKEL ist `DimWindowResolver.buildRuleSpans`: die Lage-Tests lassen das aus der Form gebaute Fenster
 * auf einem Mustertag laufen und vergleichen mit LITERALEN Minuten. Alle Minuten stehen relativ zu
 * Mitternacht des Mustertags (negativ = Vortag): -240 = 20:00 am Vortag, 1320 = 22:00, 1860 = 07:00 am Folgetag.
 *
 * Mustertage (Wecker / Dienstende): [FRUEH] 04:30 / 14:15 · [SPAET] 10:00 / 22:00 · [NACHT] 19:30 /
 * 07:00 am Folgetag · [FREI] ganztaegig, 07:30 / 00:00 am Folgetag · [RUF] 06:00 / 07:00 am Folgetag ·
 * [OHNE] gar kein Kalendereintrag.
 */
class DimmFensterFormTest {

    private val zone = ZoneOffset.UTC
    private val mustertag: LocalDate = LocalDate.of(2026, 10, 20)

    private class Tag(val wecker: Int?, val dienstende: Int?)

    private val frueh = Tag(wecker = 270, dienstende = 855)
    private val spaet = Tag(wecker = 600, dienstende = 1320)
    private val nacht = Tag(wecker = 1170, dienstende = 1860)
    private val frei = Tag(wecker = 450, dienstende = 1440)
    private val ruf = Tag(wecker = 360, dienstende = 1860)
    private val ohne = Tag(wecker = null, dienstende = null)
    private val alleTage = listOf(frueh, spaet, nacht, frei, ruf, ohne)

    private fun epoch(minutenAbMitternacht: Int): Long =
        mustertag.atStartOfDay().plusMinutes(minutenAbMitternacht.toLong()).toInstant(zone).toEpochMilli()

    private fun minuten(epoch: Long): Long = (epoch - epoch(0)) / 60_000L

    /**
     * Die Spannen DES MUSTERTAGS, so wie der Betrieb sie rechnet - mit `DimWindowResolver.buildRuleSpans`.
     * Der Rueckblick-Tag der Schleife steht in beiden Laeufen und faellt in der Differenz heraus (derselbe
     * Griff wie `tagesleiste/TagesleisteDimmenDnd.kt`). [folgeWecker] ist die Weckzeit des Folgetags in der Zeitleiste.
     */
    private fun lage(w: DimWindow, tag: Tag, folgeWecker: Int? = null): List<LongRange> {
        val slots = tag.wecker?.let { listOf(DimWindowResolver.AlarmSlot(epoch(it), "X", epoch(tag.dienstende!!))) }.orEmpty()
        val weckzeiten = slots.map { it.triggerTime } + listOfNotNull(folgeWecker?.let(::epoch))
        val regel = DimRule(name = "r", shiftPattern = "X", windows = listOf(w))
        fun lauf(horizont: Int) = DimWindowResolver.buildRuleSpans(
            alarms = slots, horizonDays = horizont, today = mustertag, zone = zone,
            ruleForShift = { regel }, ruleForFreeDay = { regel }, weckzeiten = weckzeiten
        )
        val eigene = lauf(1).toMutableList()
        lauf(0).forEach { eigene.remove(it) }
        return eigene.map { minuten(it.range.first)..minuten(it.range.last) }
    }

    private fun fenster(form: DimFensterForm) = zuFenster(form)

    private fun form(wann: DimWann, von: Int?, bis: Int, beimWecker: Boolean = false, prozent: Int = 55) =
        DimFensterForm(wann, von, bis, prozent, beimWecker)

    private fun uhr(h: Int, m: Int = 0) = h * 60 + m

    // ------------------------------------------------------------------------------------------
    // Lage: liegt das Fenster dort, wo der Text es verspricht?
    // ------------------------------------------------------------------------------------------

    @Test
    fun abendDanach_liegtAmAbendDesTagesUndEndetAmFolgetag_anJedemMustertag() {
        // "Ab dem Abend dieses Tages, Bis am Folgetag, wenn früher als Von" - auch ohne jeden Kalendereintrag.
        val w = fenster(form(DimWann.ABEND_DANACH, von = uhr(22), bis = uhr(7)))
        for (tag in alleTage) assertEquals(listOf(1320L..1860L), lage(w, tag))
        // Bis NACH Von bleibt am selben Tag.
        val amTag = fenster(form(DimWann.ABEND_DANACH, von = uhr(8), bis = uhr(12)))
        for (tag in alleTage) assertEquals(listOf(480L..720L), lage(amTag, tag))
    }

    @Test
    fun abendDanach_beimWeckerEndetAmWeckerSpaetestensBis() {
        val w = fenster(form(DimWann.ABEND_DANACH, von = uhr(22), bis = uhr(7), beimWecker = true))
        // Wecker am Folgetag 05:30 -> Ende dort; 08:00 liegt hinter Bis -> Ende 07:00 (spaetestens).
        for (tag in alleTage) {
            assertEquals(listOf(1320L..1770L), lage(w, tag, folgeWecker = 24 * 60 + uhr(5, 30)))
            assertEquals(listOf(1320L..1860L), lage(w, tag, folgeWecker = 24 * 60 + uhr(8)))
            // Ohne bekannte Weckzeit: fail-safe hell an der Uhrzeit.
            assertEquals(listOf(1320L..1860L), lage(w, tag))
        }
    }

    @Test
    fun nachtDavor_beginntAmVorabend_undHaeltSpaetestensBis() {
        // "Vom Vorabend bis zum Aufstehen an diesem Tag": Von 20:00, Bis 07:00.
        val hart = fenster(form(DimWann.NACHT_DAVOR, von = uhr(20), bis = uhr(7)))
        for (tag in listOf(frueh, spaet, nacht, frei, ruf)) assertEquals(listOf(-240L..420L), lage(hart, tag))

        val beimWecker = fenster(form(DimWann.NACHT_DAVOR, von = uhr(20), bis = uhr(7), beimWecker = true))
        assertEquals("Wecker 04:30 beendet es", listOf(-240L..270L), lage(beimWecker, frueh))
        assertEquals("Wecker 06:00 beendet es", listOf(-240L..360L), lage(beimWecker, ruf))
        // "spaetestens Bis": Wecker 10:00 / 07:30 / 19:30 liegen dahinter, das Ende bleibt 07:00.
        assertEquals(listOf(-240L..420L), lage(beimWecker, spaet))
        assertEquals(listOf(-240L..420L), lage(beimWecker, frei))
        assertEquals(listOf(-240L..420L), lage(beimWecker, nacht))
    }

    @Test
    fun nachtDavor_brauchtEinenKalendereintragMitWeckzeit() {
        // Ohne Eintrag gibt es kein "Aufstehen an diesem Tag": kein Fenster, statt eines geratenen.
        for (beimWecker in listOf(false, true)) {
            assertEquals(emptyList<LongRange>(), lage(fenster(form(DimWann.NACHT_DAVOR, uhr(20), uhr(7), beimWecker)), ohne))
        }
    }

    @Test
    fun nachtDavor_vonVorBis_bleibtAmTagSelbst_unddasIstE14() {
        // Der Vorschlaf der Nachtdienst-Vorlage: Von 16:00, Bis 19:30 (E14, #151).
        val beimWecker = fenster(form(DimWann.NACHT_DAVOR, von = uhr(16), bis = uhr(19, 30), beimWecker = true))
        val hart = fenster(form(DimWann.NACHT_DAVOR, von = uhr(16), bis = uhr(19, 30)))
        // Wecker 19:30: am Tag selbst 16:00 bis 19:30.
        assertEquals(listOf(960L..1170L), lage(beimWecker, nacht))
        assertEquals(listOf(960L..1170L), lage(hart, nacht))
        // Wecker 17:00 beendet es frueher.
        assertEquals(listOf(960L..1020L), lage(beimWecker, Tag(wecker = uhr(17), dienstende = 1860)))
        // E14: Wecker 13:30 und Von 16:00 - KEIN Fenster ab 16:00 am Vortag. (Frueher: 21,5 h am Stueck.)
        val wecker1330 = Tag(wecker = uhr(13, 30), dienstende = 1320)
        for (w in listOf(beimWecker, hart)) assertEquals(emptyList<LongRange>(), lage(w, wecker1330))
        // Auch der Fruehwecker 04:30 faengt an diesem Tag nichts.
        assertEquals(emptyList<LongRange>(), lage(beimWecker, frueh))
    }

    @Test
    fun e14_keineWahlErzeugtBeiWecker1330UndVon1600EinFensterAbVortag() {
        val wecker1330 = Tag(wecker = uhr(13, 30), dienstende = 1320)
        for (wann in DimWann.entries) {
            for (beimWecker in listOf(false, true)) {
                val f = form(wann, von = uhr(16), bis = uhr(19, 30), beimWecker = beimWecker).normalisiert()
                val spannen = lage(fenster(f), wecker1330)
                assertTrue("$wann beimWecker=$beimWecker: $spannen", spannen.none { it.first < 0 })
            }
        }
        // Wer ausdruecklich "Vom Vorabend 16:00 bis 07:00" waehlt (Von NACH Bis), bekommt genau das: sein Text sagt es.
        assertEquals(listOf(-480L..420L), lage(fenster(form(DimWann.NACHT_DAVOR, uhr(16), uhr(7))), wecker1330))
    }

    @Test
    fun nachDemDienst_beginntAmDienstende_undEndetAnDerUhrzeit() {
        val w = fenster(form(DimWann.NACH_DEM_DIENST, von = null, bis = uhr(14)))
        // Bis VOR dem Dienstende rollt auf den Folgetag (Tagschlaf nach der Nacht: 07:00 bis 14:00).
        assertEquals(listOf(1860L..2280L), lage(w, nacht))
        assertEquals(listOf(1860L..2280L), lage(w, ruf))
        assertEquals(listOf(855L..2280L), lage(w, frueh))
        assertEquals(listOf(1320L..2280L), lage(w, spaet))
        assertEquals(listOf(1440L..2280L), lage(w, frei))
        // Bis hinter dem Dienstende: am selben Tag.
        assertEquals(listOf(855L..960L), lage(fenster(form(DimWann.NACH_DEM_DIENST, null, uhr(16))), frueh))
        // Ohne Eintrag gibt es kein Dienstende.
        assertEquals(emptyList<LongRange>(), lage(w, ohne))
    }

    // ------------------------------------------------------------------------------------------
    // Totalitaet und Rundreisen
    // ------------------------------------------------------------------------------------------

    private val anker = DimAnchor.entries

    /** Jede Anker-Kombination x Kennung x Versatz x (Von vor/nach Bis): 128 Fenster. */
    private fun alleFenster(): List<DimWindow> = buildList {
        for (start in anker) for (ende in anker) for (nur in listOf(false, true)) for (versatz in listOf(0, 60)) {
            for ((von, bis) in listOf(uhr(20) to uhr(7), uhr(16) to uhr(19, 30))) {
                add(
                    DimWindow(
                        startAnchor = start, startClockMinutes = von, startOffsetMinutes = versatz,
                        endAnchor = ende, endClockMinutes = bis, endOffsetMinutes = versatz,
                        startNurAmWecktag = nur
                    )
                )
            }
        }
    }

    @Test
    fun abbildung_istTotal_jedesFensterIstFormOderAusdruecklichNichtDarstellbar() {
        val ergebnisse = alleFenster().map { deute(TypDimmFenster(it)) }
        assertEquals(128, ergebnisse.size)
        assertEquals("Form", 28, ergebnisse.count { it is DimFensterAnsicht.Darstellbar })
        fun grund(g: NichtDarstellbarGrund) = ergebnisse.count { it is DimFensterAnsicht.NichtDarstellbar && it.grund == g }
        // ASC-Start mit falscher Kennung (2 Enden x 2 Kennungs-Fehlstellungen x 2 Versaetze).
        assertEquals(8, grund(NichtDarstellbarGrund.KENNUNG))
        // Nach dem Dienst mit Versatz 60 (2 Kennungen x 2 Von/Bis).
        assertEquals(4, grund(NichtDarstellbarGrund.VERSATZ))
        assertEquals(88, grund(NichtDarstellbarGrund.ANKER))
    }

    @Test
    fun fensterFormFenster_istIdentisch_oderNichtDarstellbar() {
        for (w in alleFenster()) {
            val f = TypDimmFenster(w, staerke = 70)
            val ansicht = deute(f)
            if (ansicht is DimFensterAnsicht.Darstellbar) {
                assertEquals("Fenster -> Form -> Fenster: $w", w, zuFenster(ansicht.form, basis = w))
                assertEquals(f, DimFensterAbbildung.mitForm(f, ansicht.form))
                assertEquals(70, ansicht.form.prozent)
            }
        }
    }

    @Test
    fun formFensterForm_istIdentisch_auchUeberEinFremdesBasisFenster() {
        // Ein Basisfenster mit ganz anderen Ankern und Kennungen: die Form darf von ihnen nichts mitnehmen.
        val schmutzig = DimWindow(
            startAnchor = DimAnchor.ALARM, startOffsetMinutes = -90, endAnchor = DimAnchor.SHIFT_END,
            endOffsetMinutes = 45, startNurAmWecktag = true
        )
        val vonWerte = listOf(0, uhr(7), uhr(16), uhr(20), 1439)
        val bisWerte = listOf(0, uhr(7), uhr(19, 30), 1439)
        var geprueft = 0
        for (basis in listOf(DimWindow(), schmutzig)) for (wann in DimWann.entries) {
            for (von in vonWerte) for (bis in bisWerte) for (beimWecker in listOf(false, true)) {
                val form = form(wann, von, bis, beimWecker, prozent = 40).normalisiert()
                val ansicht = deute(TypDimmFenster(zuFenster(form, basis), staerke = form.prozent))
                assertEquals("Form -> Fenster -> Form: $form", DimFensterAnsicht.Darstellbar(form), ansicht)
                geprueft++
            }
        }
        assertEquals(2 * 3 * 5 * 4 * 2, geprueft)
    }

    @Test
    fun felderAusserhalbDerForm_bleibenBeimBearbeitenErhalten() {
        // Die Nachtruhe der Vorlage, mit allem, was die Form nicht zeigt.
        val original = DimWindow(
            startAnchor = DimAnchor.CLOCK, startClockMinutes = uhr(22),
            startOffsetMinutes = -30, endAnchor = DimAnchor.ALARM_SONST_CLOCK, endClockMinutes = uhr(7),
            endOffsetMinutes = 15, weckzeitNachlaufMinuten = 20, nachlaufBisHell = HellAb.DAEMMERUNG,
            blockPositionen = setOf(Blockposition.ERSTER, Blockposition.MITTLERER)
        )
        val f = TypDimmFenster(original, staerke = 60)
        val form = (deute(f) as DimFensterAnsicht.Darstellbar).form
        fun unberuehrt(neu: DimWindow) {
            assertEquals(20, neu.weckzeitNachlaufMinuten)
            assertEquals(HellAb.DAEMMERUNG, neu.nachlaufBisHell)
            assertEquals(setOf(Blockposition.ERSTER, Blockposition.MITTLERER), neu.blockPositionen)
            assertEquals(15, neu.endOffsetMinutes)
        }

        // Uhrzeit, Prozent und Wecker-Ende aendern.
        val bearbeitet = DimFensterAbbildung.mitForm(f, form.copy(bisMinuten = uhr(8), prozent = 30, beimWecker = false))
        unberuehrt(bearbeitet.fenster)
        assertEquals(30, bearbeitet.staerke)
        assertEquals(DimAnchor.CLOCK, bearbeitet.fenster.endAnchor)
        assertEquals(-30, bearbeitet.fenster.startOffsetMinutes)

        // Das "Wann" wechseln: der Start-Versatz des alten Fensters (-30) darf NICHT zum Versatz
        // vom Dienstende werden; alles andere bleibt.
        val nachDienst = DimFensterAbbildung.mitForm(f, form.copy(wann = DimWann.NACH_DEM_DIENST).normalisiert())
        unberuehrt(nachDienst.fenster)
        assertEquals(DimAnchor.SHIFT_END, nachDienst.fenster.startAnchor)
        assertEquals(0, nachDienst.fenster.startOffsetMinutes)
    }

    // ------------------------------------------------------------------------------------------
    // Die drei Fensterarten der Vorlage
    // ------------------------------------------------------------------------------------------

    @Test
    fun dieDreiFensterartenDerVorlage_sindFormOderAusdruecklichNichtDarstellbar() {
        // (1) UNIVERSAL: CLOCK 22:00 -> ALARM_SONST_CLOCK 07:00, dazu Nachlauf 20 (nicht Teil der Form).
        val nachtruhe = baueVorlagenRegel(SchnellstartVorlage.NACHT_DIMMEN, "Nacht", null)!!.windows.single()
            .copy(weckzeitNachlaufMinuten = 20)
        val ansicht1 = deute(TypDimmFenster(nachtruhe, staerke = 55)) as DimFensterAnsicht.Darstellbar
        assertEquals(form(DimWann.ABEND_DANACH, uhr(22), uhr(7), beimWecker = true), ansicht1.form)
        assertEquals("Nachlauf bleibt beim Schreiben", nachtruhe, zuFenster(ansicht1.form, nachtruhe))

        val nd = baueVorlagenRegel(SchnellstartVorlage.NACHTDIENST_RHYTHMUS, "ND", "ND")!!.windows
        // (2) Nachtschicht: SHIFT_END -> CLOCK (Schlaf nach dem Dienst; zwei Fenster, je nach Blockposition).
        val schlaf = nd[0]
        val ansicht2 = deute(TypDimmFenster(schlaf)) as DimFensterAnsicht.Darstellbar
        assertEquals(form(DimWann.NACH_DEM_DIENST, null, uhr(14)), ansicht2.form)
        assertEquals(schlaf, zuFenster(ansicht2.form, schlaf))
        assertEquals(form(DimWann.NACH_DEM_DIENST, null, uhr(12)), (deute(TypDimmFenster(nd[1])) as DimFensterAnsicht.Darstellbar).form)

        // (3) Nachtschicht: CLOCK 16:00 -> ALARM (Vorschlaf bis zur Weckzeit). Das Ende AM Wecker ohne
        // Uhrzeit-Grenze hat keine Bis-Angabe; ein erfundenes Bis machte "spaetestens 06:00" daraus.
        val vorschlaf = nd[2]
        assertEquals(DimAnchor.ALARM, vorschlaf.endAnchor)
        assertEquals(DimFensterAnsicht.NichtDarstellbar(NichtDarstellbarGrund.ANKER, prozent = 55), deute(TypDimmFenster(vorschlaf)))
    }

    // ------------------------------------------------------------------------------------------
    // Texte (wie im Prototyp)
    // ------------------------------------------------------------------------------------------

    private fun tf(form: DimFensterForm) = TypDimmFenster(zuFenster(form), staerke = form.prozent)

    @Test
    fun wannTexte_sindDieDesPrototyps() {
        assertEquals("Nacht davor", DimWann.NACHT_DAVOR.label)
        assertEquals("Abend danach", DimWann.ABEND_DANACH.label)
        assertEquals("Nach dem Dienst", DimWann.NACH_DEM_DIENST.label)
        assertEquals("Vom Vorabend bis zum Aufstehen an diesem Tag", DimWann.NACHT_DAVOR.erklaerung)
        assertEquals("Ab dem Abend dieses Tages", DimWann.ABEND_DANACH.erklaerung)
        assertEquals("Ab Dienstende laut Kalender – z. B. Tagschlaf", DimWann.NACH_DEM_DIENST.erklaerung)
        assertEquals("am Tag selbst", DimWann.NACHT_DAVOR.bisHinweis)
        assertEquals("am Folgetag, wenn früher als „Von“", DimWann.ABEND_DANACH.bisHinweis)
        assertEquals("nach Dienstende", DimWann.NACH_DEM_DIENST.bisHinweis)
        // "Von" ist beim Dienstende keine Uhrzeit.
        assertFalse(DimWann.NACH_DEM_DIENST.hatVon)
        assertTrue(DimWann.NACHT_DAVOR.hatVon && DimWann.ABEND_DANACH.hatVon)
    }

    @Test
    fun beimWeckerEnden_gibtEsNurBeiTypenMitWecker_undNieNachDemDienst() {
        assertTrue(DimFensterAbbildung.zeigeBeimWecker(DimWann.NACHT_DAVOR, weckerAn = true))
        assertTrue(DimFensterAbbildung.zeigeBeimWecker(DimWann.ABEND_DANACH, weckerAn = true))
        assertFalse(DimFensterAbbildung.zeigeBeimWecker(DimWann.NACH_DEM_DIENST, weckerAn = true))
        assertFalse(DimFensterAbbildung.zeigeBeimWecker(DimWann.ABEND_DANACH, weckerAn = false))
    }

    @Test
    fun fensterTitel_folgenDemPrototyp() {
        val davor = tf(form(DimWann.NACHT_DAVOR, uhr(20), uhr(7), beimWecker = true, prozent = 55))
        assertEquals("Fenster 1: 20:00 bis Wecker, spätestens 07:00 · 55 %", DimFensterAbbildung.titel(1, davor, weckerAn = true))
        // Ohne Wecker steht das Wecker-Ende nicht im Text (Prototyp: `f.beimWecker && wecker`).
        assertEquals("Fenster 1: 20:00 bis 07:00 · 55 %", DimFensterAbbildung.titel(1, davor, weckerAn = false))
        val danach = tf(form(DimWann.ABEND_DANACH, uhr(22), uhr(7), prozent = 40))
        assertEquals("Fenster 2: Abends 22:00 bis 07:00 · 40 %", DimFensterAbbildung.titel(2, danach, weckerAn = true))
        val nachher = tf(form(DimWann.NACH_DEM_DIENST, null, uhr(14), prozent = 70))
        assertEquals("Fenster 3: Nach dem Dienst bis 14:00 · 70 %", DimFensterAbbildung.titel(3, nachher, weckerAn = true))
        // Ein Fenster, das die Seite nicht ausdrueckt, hat trotzdem einen Titel.
        val fremd = TypDimmFenster(DimWindow(startAnchor = DimAnchor.ALARM, endAnchor = DimAnchor.ALARM), staerke = 25)
        assertEquals("Fenster 4: Eigene Zeiten · 25 %", DimFensterAbbildung.titel(4, fremd, weckerAn = true))
    }

    @Test
    fun dimText_undKurzform_wieImPrototyp() {
        fun dimmen(vararg f: TypDimmFenster, an: Boolean = true) = TypDimmen(an = an).mitFenster(f.toList())

        // Ohne Fenster: "Nicht dimmen", und die Listenzeile sagt nichts zum Dimmen.
        assertEquals("Nicht dimmen", DimFensterAbbildung.dimText(dimmen(), weckerAn = true))
        assertNull(DimFensterAbbildung.kurzform(dimmen(), weckerAn = true))

        val frueh = tf(form(DimWann.NACHT_DAVOR, uhr(20), uhr(7), beimWecker = true, prozent = 55))
        assertEquals("20:00 bis Wecker, spätestens 07:00 · 55 %", DimFensterAbbildung.dimText(dimmen(frueh), weckerAn = true))
        // E9: "dimmt 20:00–Wecker"
        assertEquals("dimmt 20:00–Wecker", DimFensterAbbildung.kurzform(dimmen(frueh), weckerAn = true))
        assertEquals("dimmt 20:00–07:00", DimFensterAbbildung.kurzform(dimmen(frueh), weckerAn = false))

        val nachher = tf(form(DimWann.NACH_DEM_DIENST, null, uhr(14), prozent = 70))
        assertEquals("dimmt nach Dienst", DimFensterAbbildung.kurzform(dimmen(nachher), weckerAn = true))
        val vorschlaf = tf(form(DimWann.NACHT_DAVOR, uhr(16), uhr(19, 30), beimWecker = true, prozent = 60))
        assertEquals(
            "Nach dem Dienst bis 14:00 · 70 % + 16:00 bis Wecker, spätestens 19:30 · 60 %",
            DimFensterAbbildung.dimText(dimmen(nachher, vorschlaf), weckerAn = true)
        )
        assertEquals("3 Dimm-Fenster", DimFensterAbbildung.kurzform(dimmen(nachher, nachher, vorschlaf), weckerAn = true))

        // Ein einzelnes Fenster ausserhalb der Form: ehrliche Kurzform statt einer erfundenen Uhrzeit.
        val fremd = TypDimmFenster(DimWindow(startAnchor = DimAnchor.ALARM, endAnchor = DimAnchor.ALARM), staerke = 25)
        assertEquals("dimmt (eigene Zeiten)", DimFensterAbbildung.kurzform(dimmen(fremd), weckerAn = true))
    }

    @Test
    fun ausgeschaltetesDimmen_sagtDasselbeWieKeinFenster_dieFensterBleibenGespeichert() {
        val frueh = tf(form(DimWann.NACHT_DAVOR, uhr(20), uhr(7), beimWecker = true))
        val aus = TypDimmen(an = false).mitFenster(listOf(frueh))
        assertEquals("Nicht dimmen", DimFensterAbbildung.dimText(aus, weckerAn = true))
        assertNull(DimFensterAbbildung.kurzform(aus, weckerAn = true))
        assertEquals("gespeichert bleibt es", listOf(frueh), aus.fenster)
    }

    @Test
    fun uhrzeit_faltetWerteAusserhalbDesTagesStattZuWerfen() {
        assertEquals("00:00", DimFensterAbbildung.uhrzeit(0))
        assertEquals("23:59", DimFensterAbbildung.uhrzeit(1439))
        assertEquals("00:30", DimFensterAbbildung.uhrzeit(1470))
        assertEquals("23:00", DimFensterAbbildung.uhrzeit(-60))
    }
}
