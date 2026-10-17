package com.github.f1rlefanz.cf_alarmfortimeoffice.tagesleiste

import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.Blockposition
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimAnchor
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimRule
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimRuleUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimWindow
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimWindowResolver
import com.github.f1rlefanz.cf_alarmfortimeoffice.dnd.DndPrefs
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftKategorie
import com.github.f1rlefanz.cf_alarmfortimeoffice.tageslicht.HellAb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset

/**
 * Dimmen und "Nicht stoeren" auf der Tagesleiste (Umbau #194), gerechnet aus den HEUTIGEN Regeln. Alle
 * Minuten sind Minuten ab 18:00 am Vortag (0..2880), die Erwartungen stehen als LITERALE da; Orakel ist
 * `DimWindowResolver.buildRuleSpans` auf von Hand gebauten Slots.
 *
 * Der Mustertag ist ein Normaltag: Fruehdienst 06:00 bis 14:15, Wecker 04:30 (Minute 630).
 */
class TagesleisteDimmenDndTest {

    private val auswahl = DimRuleUseCase(mock())

    private fun vortag(stunde: Int, minute: Int = 0) = (stunde - 18) * 60 + minute
    private fun tag(stunde: Int, minute: Int = 0) = 360 + stunde * 60 + minute
    private fun folgetag(stunde: Int, minute: Int = 0) = 1800 + stunde * 60 + minute

    private val frueh = Dienstzeit(LocalTime.of(6, 0), 495, ganztaegig = false)
    private val nachtdienst = Dienstzeit(LocalTime.of(20, 45), 615, ganztaegig = false)
    private val ruf = Dienstzeit(LocalTime.of(7, 0), 1440, ganztaegig = false)

    /** "Nacht davor": von 22:00 am Vortag bis zum Wecker. */
    private val nachtDavor = DimWindow(
        startAnchor = DimAnchor.CLOCK, startClockMinutes = 22 * 60, endAnchor = DimAnchor.ALARM, endOffsetMinutes = 0
    )

    /** Die Nacht dieses Kalendertags: von 22:00 bis zum naechsten Wecker, spaetestens 07:00. */
    private val nachtBisWecker = DimWindow(
        startAnchor = DimAnchor.CLOCK, startClockMinutes = 22 * 60,
        endAnchor = DimAnchor.ALARM_SONST_CLOCK, endClockMinutes = 7 * 60
    )

    /** "Abend danach": ab 20:00 des Tages, bis zum naechsten Wecker, spaetestens 07:00. */
    private val abendDanach = nachtBisWecker.copy(startClockMinutes = 20 * 60)

    private fun regel(muster: String, vararg fenster: DimWindow, staerke: Int = 55, aktiv: Boolean = true) =
        DimRule(id = "r-$muster", name = muster, shiftPattern = muster, enabled = aktiv, windows = fenster.toList(), strength = staerke)

    private val keinDnd = DndPrefs.Toggles(followDimmerEnabled = false, duringShiftEnabled = false)

    private fun einstellungen(
        vararg regeln: DimRule,
        dimEnabled: Boolean = true,
        dnd: DndPrefs.Toggles = keinDnd,
        ausgenommen: Set<String> = emptySet(),
        hellUm: (HellAb) -> LocalTime? = { null }
    ) = DimDndEinstellungen(dimEnabled, regeln.toList(), dnd, ausgenommen, hellUm)

    private fun eingabe(
        kategorie: ShiftKategorie = ShiftKategorie.DIENST,
        name: String = "F",
        weckerAn: Boolean = true,
        weckzeit: LocalTime? = LocalTime.of(4, 30),
        dienstzeit: Dienstzeit? = frueh,
        blockposition: Blockposition = standardBlockposition(kategorie)
    ) = TagesleisteEingabe(name, kategorie, weckerAn, weckzeit, still = false, dienstzeit = dienstzeit, blockposition = blockposition)

    private fun rechne(e: TagesleisteEingabe, s: DimDndEinstellungen) = dimmenUndNichtStoeren(e, s, auswahl)

    private fun Tagesleiste.ereignisseVon(spur: Spur) = ereignisse.filter { it.spur == spur }.map { it.zeit to it.text }

    // ── Regelwahl ────────────────────────────────────────────────────────────────────────────

    @Test
    fun `Nacht davor endet beim Wecker und die Leiste legt den Wecker auf dieselbe Minute`() {
        val e = eingabe(blockposition = Blockposition.EINZELNER)
        val r = rechne(e, einstellungen(regel("F", nachtDavor)))

        assertEquals(listOf(DimBereich(vortag(22), tag(4, 30), 55, endetBeimWecker = true)), r.dimmen)
        // Die zwei Mustertage (hier und in Tagesleiste.kt) sind getrennte Konstanten - dieselbe Minute.
        assertEquals(tag(4, 30), weckpunktMin(ShiftKategorie.DIENST, true, LocalTime.of(4, 30), frueh))
        assertEquals(weckpunktMin(ShiftKategorie.DIENST, true, LocalTime.of(4, 30), frueh), r.dimmen.single().bisMin)
    }

    @Test
    fun `UNIVERSAL gilt nur ohne eigene Regel und wird nie dazugerechnet`() {
        val universal = regel("__UNIVERSAL__", nachtBisWecker, staerke = 40).copy(shiftPattern = DimRule.SHIFT_UNIVERSAL)

        val ohneEigene = rechne(eingabe(blockposition = Blockposition.EINZELNER), einstellungen(universal))
        assertEquals(listOf(DimBereich(tag(22), folgetag(7), 40, endetBeimWecker = false)), ohneEigene.dimmen)

        val mitEigener = rechne(eingabe(blockposition = Blockposition.EINZELNER), einstellungen(universal, regel("F", nachtDavor)))
        assertEquals(listOf(DimBereich(vortag(22), tag(4, 30), 55, endetBeimWecker = true)), mitEigener.dimmen)

        // Eine AUSGESCHALTETE eigene Regel zaehlt nicht: dann gilt wieder UNIVERSAL.
        val ausgeschaltet = rechne(
            eingabe(blockposition = Blockposition.EINZELNER),
            einstellungen(universal, regel("F", nachtDavor, aktiv = false))
        )
        assertEquals(ohneEigene.dimmen, ausgeschaltet.dimmen)
    }

    @Test
    fun `eine leere Fensterliste heisst kein Dimmen und kein Nicht stoeren, auch nicht ueber UNIVERSAL`() {
        val universal = regel("u", nachtBisWecker).copy(shiftPattern = DimRule.SHIFT_UNIVERSAL)
        val folgt = DndPrefs.Toggles(followDimmerEnabled = true, duringShiftEnabled = false)

        Blockposition.entries.forEach { position ->
            val r = rechne(eingabe(blockposition = position), einstellungen(universal, regel("F"), dnd = folgt))
            assertEquals("Dimmen bei $position", emptyList<DimBereich>(), r.dimmen)
            assertEquals("Nicht stoeren bei $position", emptyList<DndBereich>(), r.nichtStoeren)
        }
    }

    @Test
    fun `dim_enabled aus heisst keine Dimm-Spur und kein Nicht stoeren folgt dem Dimmer, den Kalendereintrag aber schon`() {
        val beide = DndPrefs.Toggles(followDimmerEnabled = true, duringShiftEnabled = true)
        val r = rechne(
            eingabe(blockposition = Blockposition.EINZELNER),
            einstellungen(regel("F", nachtDavor), dimEnabled = false, dnd = beide)
        )

        assertEquals(emptyList<DimBereich>(), r.dimmen)
        assertEquals(listOf(DndBereich(tag(6), tag(14, 15), DndQuelle.KALENDEREINTRAG)), r.nichtStoeren)
    }

    @Test
    fun `Krank und Ignorieren laufen wie ein freier Tag, ein erkannter Typ ist ein Schichttag`() {
        val universal = regel("u", nachtBisWecker, staerke = 20).copy(shiftPattern = DimRule.SHIFT_UNIVERSAL)
        val frei = regel("f", nachtBisWecker, staerke = 30).copy(shiftPattern = DimRule.SHIFT_FREE)
        val alle = arrayOf(universal, frei, regel("K", nachtDavor, staerke = 70), regel("Fr", nachtDavor, staerke = 60))

        // Krank: FREI-Regel, nicht die Regel, die zufaellig auf den Namen "K" zeigt.
        val krank = rechne(
            eingabe(ShiftKategorie.KRANK, "K", weckerAn = false, dienstzeit = null),
            einstellungen(*alle)
        )
        assertEquals(listOf(DimBereich(tag(22), folgetag(7), 30, endetBeimWecker = false)), krank.dimmen)

        // Ignorieren hat keinen Tag.
        assertEquals(DimUndNichtStoeren.LEER, rechne(eingabe(ShiftKategorie.IGNORIEREN, "TB", dienstzeit = null), einstellungen(*alle)))

        // Frei-Typ MIT eigener Regel: die Regel auf seinen Namen; Wecker aus, also kein "der Wecker klingelt".
        val freiMitRegel = rechne(
            eingabe(ShiftKategorie.FREI, "Fr", weckerAn = false, weckzeit = LocalTime.of(8, 0), dienstzeit = null),
            einstellungen(*alle)
        )
        assertEquals(listOf(DimBereich(vortag(22), tag(8), 60, endetBeimWecker = false)), freiMitRegel.dimmen)

        // Frei-Typ OHNE eigene Regel: UNIVERSAL (er ist ein Schichttag), nicht die FREI-Regel.
        val freiOhneRegel = rechne(
            eingabe(ShiftKategorie.FREI, "Fx", weckerAn = false, weckzeit = LocalTime.of(8, 0), dienstzeit = null),
            einstellungen(universal, frei)
        )
        assertEquals(listOf(DimBereich(tag(22), folgetag(7), 20, endetBeimWecker = false)), freiOhneRegel.dimmen)
    }

    // ── Blockposition, startNurAmWecktag, Nachlauf, Hell ab ──────────────────────────────────

    @Test
    fun `ein Fenster gilt nur an den Tagen seines Blocks, und die Wecker der Nachbarn beenden das Ende bis Wecker`() {
        val nurMitte = nachtDavor.copy(blockPositionen = setOf(Blockposition.MITTLERER))
        val nurEinzeln = abendDanach.copy(blockPositionen = setOf(Blockposition.EINZELNER))
        val nurBlock = einstellungen(regel("F", nurMitte, nurEinzeln))

        fun dim(position: Blockposition) = rechne(eingabe(blockposition = position), nurBlock).dimmen

        assertEquals(listOf(DimBereich(tag(20), folgetag(7), 55, false)), dim(Blockposition.EINZELNER))
        assertEquals(listOf(DimBereich(vortag(22), tag(4, 30), 55, true)), dim(Blockposition.MITTLERER))
        assertEquals(emptyList<DimBereich>(), dim(Blockposition.ERSTER))
        assertEquals(emptyList<DimBereich>(), dim(Blockposition.LETZTER))

        // Der Folgetag desselben Typs weckt um 04:30: das beendet "bis Wecker" dort, nicht erst um 07:00.
        val abend = einstellungen(regel("F", abendDanach))
        fun abendEnde(position: Blockposition) = rechne(eingabe(blockposition = position), abend).dimmen.single().bisMin
        assertEquals(folgetag(7), abendEnde(Blockposition.EINZELNER))
        assertEquals(folgetag(7), abendEnde(Blockposition.LETZTER))
        assertEquals(folgetag(4, 30), abendEnde(Blockposition.ERSTER))
        assertEquals(folgetag(4, 30), abendEnde(Blockposition.MITTLERER))
    }

    @Test
    fun `ein Fenster mit startNurAmWecktag entfaellt, wenn der Wecker davor liegt`() {
        val vorschlaf = DimWindow(
            startAnchor = DimAnchor.CLOCK, startClockMinutes = 16 * 60, endAnchor = DimAnchor.ALARM,
            endOffsetMinutes = 0, startNurAmWecktag = true
        )
        val s = einstellungen(regel("N", vorschlaf))
        fun dim(weckzeit: LocalTime) = rechne(
            eingabe(name = "N", weckzeit = weckzeit, dienstzeit = nachtdienst, blockposition = Blockposition.EINZELNER), s
        ).dimmen

        assertEquals(emptyList<DimBereich>(), dim(LocalTime.of(13, 30)))
        assertEquals(listOf(DimBereich(tag(16), tag(17, 30), 55, true)), dim(LocalTime.of(17, 30)))
    }

    @Test
    fun `Nach dem Dienst haengt am Ende der Dienstzeit, ohne Dienstzeit gibt es das Fenster nicht`() {
        val nachDienst = DimWindow(
            startAnchor = DimAnchor.SHIFT_END, startOffsetMinutes = 60, endAnchor = DimAnchor.CLOCK, endClockMinutes = 20 * 60
        )
        val s = einstellungen(regel("F", nachDienst))

        // Dienstende 14:15, eine Stunde danach bis 20:00.
        assertEquals(
            listOf(DimBereich(tag(15, 15), tag(20), 55, endetBeimWecker = false)),
            rechne(eingabe(blockposition = Blockposition.EINZELNER), s).dimmen
        )
        // Kein Termin im Kalender: das Ende ist unbekannt (0), und eine Dienstzeit wird nicht erfunden.
        assertEquals(emptyList<DimBereich>(), rechne(eingabe(dienstzeit = null, blockposition = Blockposition.EINZELNER), s).dimmen)
    }

    @Test
    fun `der Nachlauf verlaengert das Dimmen, nicht aber Nicht stoeren, und Hell ab kuerzt ihn nur mit Standort`() {
        val folgt = DndPrefs.Toggles(followDimmerEnabled = true, duringShiftEnabled = false)
        val mitNachlauf = nachtBisWecker.copy(weckzeitNachlaufMinuten = 30, nachlaufBisHell = HellAb.DAEMMERUNG)
        val mitte = eingabe(blockposition = Blockposition.MITTLERER)
        val ohneStandort = rechne(mitte, einstellungen(regel("F", mitNachlauf), dnd = folgt))

        // Der Wecker des Folgetags: 04:30. Dimmen bis 05:00 - und der Text behauptet keinen Wecker dort.
        assertEquals(listOf(DimBereich(tag(22), folgetag(5), 55, endetBeimWecker = false)), ohneStandort.dimmen)
        assertEquals(listOf(DndBereich(tag(22), folgetag(4, 30), DndQuelle.DIMM_FENSTER)), ohneStandort.nichtStoeren)

        val mitStandort = rechne(
            mitte,
            einstellungen(regel("F", mitNachlauf), dnd = folgt, hellUm = { if (it == HellAb.DAEMMERUNG) LocalTime.of(4, 50) else null })
        )
        assertEquals(listOf(DimBereich(tag(22), folgetag(4, 50), 55, endetBeimWecker = false)), mitStandort.dimmen)
        assertEquals(ohneStandort.nichtStoeren, mitStandort.nichtStoeren)
    }

    @Test
    fun `ohne Weckzeit und bei Wecker aus - kein Anker ohne Weckzeit, aber der Anker bleibt bei Wecker aus`() {
        val folgt = DndPrefs.Toggles(followDimmerEnabled = true, duringShiftEnabled = true)
        val s = einstellungen(regel("F", nachtDavor), dnd = folgt)

        val ohneWeckzeit = rechne(eingabe(weckzeit = null, blockposition = Blockposition.EINZELNER), s)
        assertEquals(emptyList<DimBereich>(), ohneWeckzeit.dimmen)
        assertEquals(listOf(DndBereich(tag(6), tag(14, 15), DndQuelle.KALENDEREINTRAG)), ohneWeckzeit.nichtStoeren)

        // Wecker aus: heute fuehrt die Schichtspanne die Weckzeit trotzdem (#181), das Fenster endet dort.
        val weckerAus = rechne(eingabe(weckerAn = false, blockposition = Blockposition.EINZELNER), s)
        assertEquals(listOf(DimBereich(vortag(22), tag(4, 30), 55, endetBeimWecker = false)), weckerAus.dimmen)
    }

    @Test
    fun `der Ereignistext nennt den Wecker nur, wenn er klingelt`() {
        val s = einstellungen(regel("F", nachtDavor))
        fun text(weckerAn: Boolean): String {
            val e = eingabe(weckerAn = weckerAn, blockposition = Blockposition.EINZELNER)
            return tagesleiste(e.copy(dimmen = rechne(e, s).dimmen)).ereignisseVon(Spur.DIMMEN).last().second
        }

        assertEquals("Dimmen endet (F) – der Wecker klingelt", text(weckerAn = true))
        assertEquals("Dimmen endet (F)", text(weckerAn = false))
    }

    // ── Nicht stoeren ────────────────────────────────────────────────────────────────────────

    @Test
    fun `Nicht stoeren waehrend des Eintrags ist die Spanne ohne ausgenommene Schichten, Krank hat keine`() {
        val waehrend = DndPrefs.Toggles(followDimmerEnabled = false, duringShiftEnabled = true)

        assertEquals(
            listOf(DndBereich(tag(6), tag(14, 15), DndQuelle.KALENDEREINTRAG)),
            rechne(eingabe(), einstellungen(dnd = waehrend)).nichtStoeren
        )
        assertEquals(emptyList<DndBereich>(), rechne(eingabe(), einstellungen(dnd = waehrend, ausgenommen = setOf("F"))).nichtStoeren)
        // Ganztaegig ohne Uhrzeit: der ganze Tag, wie der Eintrag-Balken der Leiste.
        assertEquals(
            listOf(DndBereich(tag(0), folgetag(0), DndQuelle.KALENDEREINTRAG)),
            rechne(eingabe(ShiftKategorie.URLAUB, "U", dienstzeit = null), einstellungen(dnd = waehrend)).nichtStoeren
        )
        assertEquals(
            emptyList<DndBereich>(),
            rechne(eingabe(ShiftKategorie.KRANK, "K", dienstzeit = null), einstellungen(dnd = waehrend)).nichtStoeren
        )
    }

    @Test
    fun `Nicht stoeren endet bei Rufbereitschaft um 05 Uhr, und die Leiste sagt es`() {
        val folgt = DndPrefs.Toggles(followDimmerEnabled = true, duringShiftEnabled = false)
        val universal = regel("u", nachtBisWecker).copy(shiftPattern = DimRule.SHIFT_UNIVERSAL)
        val e = eingabe(
            ShiftKategorie.RUFBEREITSCHAFT, "AD", weckzeit = LocalTime.of(6, 0), dienstzeit = ruf,
            blockposition = Blockposition.EINZELNER
        )

        val r = rechne(e, einstellungen(universal, dnd = folgt))
        // Der Adapter kappt NICHT selbst: das UNGEKAPPTE Ende (07:00) geht an die Leiste.
        assertEquals(listOf(DndBereich(tag(22), folgetag(7), DndQuelle.DIMM_FENSTER)), r.nichtStoeren)

        val leiste = tagesleiste(e.copy(dimmen = r.dimmen, nichtStoeren = r.nichtStoeren))
        assertEquals(listOf(Balken(Spur.NICHT_STOEREN, tag(22), folgetag(5))), leiste.balken.filter { it.spur == Spur.NICHT_STOEREN })
        assertEquals(
            listOf(
                "22:00" to "„Nicht stören“ an (Dimm-Fenster)",
                "Folgetag 05:00" to "„Nicht stören“ endet spätestens jetzt – erreichbar sein (Rufbereitschaft)"
            ),
            leiste.ereignisseVon(Spur.NICHT_STOEREN)
        )
    }

    // ── Gegen buildRuleSpans ─────────────────────────────────────────────────────────────────

    private fun ms(t: LocalDateTime) = t.toInstant(ZoneOffset.UTC).toEpochMilli()
    private val leisteNull = ms(LocalDateTime.of(2001, 1, 1, 18, 0))
    private fun minute(epoch: Long) = ((epoch - leisteNull) / 60_000).toInt()

    /** Orakel: von Hand gebaute Slots (Fruehdienst, Wecker 04:30) und der Kalendertag des Mustertags allein. */
    private fun orakel(regeln: List<DimRule>, position: Blockposition): List<Triple<Int, Int, Int>> {
        val wecker = LocalDateTime.of(2001, 1, 2, 4, 30)
        val ende = LocalDateTime.of(2001, 1, 2, 14, 15)
        fun slot(tage: Long) = DimWindowResolver.AlarmSlot(ms(wecker.plusDays(tage)), "F", ms(ende.plusDays(tage)))
        val tage = when (position) {
            Blockposition.EINZELNER -> listOf(0L)
            Blockposition.ERSTER -> listOf(0L, 1L)
            Blockposition.LETZTER -> listOf(-1L, 0L)
            Blockposition.MITTLERER -> listOf(-1L, 0L, 1L)
        }
        fun lauf(horizont: Int) = DimWindowResolver.buildRuleSpans(
            alarms = tage.map(::slot), horizonDays = horizont, today = LocalDate.of(2001, 1, 2), zone = ZoneOffset.UTC,
            ruleForShift = { auswahl.findRuleForShift(it, regeln) }, ruleForFreeDay = { auswahl.findRuleForFreeDay(regeln) },
            weckzeiten = tage.map { ms(wecker.plusDays(it)) }
        )
        val nurMustertag = lauf(1).toMutableList().also { rest -> lauf(0).forEach { rest.remove(it) } }
        return nurMustertag.map { Triple(minute(it.range.first), minute(it.range.last), it.strength) }.sortedBy { it.first }
    }

    @Test
    fun `das Ergebnis ist das von buildRuleSpans, fuer jede Blockposition und mehrere Regelsaetze`() {
        val universal = regel("u", nachtBisWecker, staerke = 40).copy(shiftPattern = DimRule.SHIFT_UNIVERSAL)
        val saetze = listOf(
            listOf(regel("F", nachtDavor, abendDanach.copy(weckzeitNachlaufMinuten = 45), staerke = 65)),
            listOf(universal),
            listOf(universal, regel("F", nachtDavor.copy(blockPositionen = setOf(Blockposition.MITTLERER, Blockposition.LETZTER)), abendDanach)),
            listOf(regel("F", nachtDavor, aktiv = false), universal)
        )

        saetze.forEachIndexed { i, regeln ->
            Blockposition.entries.forEach { position ->
                val erwartet = orakel(regeln, position)
                val ist = rechne(eingabe(blockposition = position), einstellungen(*regeln.toTypedArray())).dimmen
                    .map { Triple(it.vonMin, it.bisMin, it.prozent) }.sortedBy { it.first }
                assertEquals("Regelsatz $i, $position", erwartet, ist)
                assertTrue("Regelsatz $i, $position liefert etwas", erwartet.isNotEmpty())
            }
        }
    }

    @Test
    fun `ein Dienst kurz nach Mitternacht mit Wecker am Vortag datiert die Fenster nach dem Wecker`() {
        // Beginn 00:30, Wecker 23:30 am VORTAG (weckzeitpunkt, #192). Die Spanne datiert nach der Weckzeit,
        // also gilt der Kalendertag davor - und das Ende "beim Wecker" liegt genau auf dem Weckpunkt der Leiste.
        val n0 = Dienstzeit(LocalTime.of(0, 30), 450, ganztaegig = false)
        val fenster = DimWindow(startAnchor = DimAnchor.CLOCK, startClockMinutes = 21 * 60, endAnchor = DimAnchor.ALARM)
        val e = eingabe(name = "N0", weckzeit = LocalTime.of(23, 30), dienstzeit = n0, blockposition = Blockposition.EINZELNER)

        val r = rechne(e, einstellungen(regel("N0", fenster)))

        assertEquals(listOf(DimBereich(vortag(21), vortag(23, 30), 55, endetBeimWecker = true)), r.dimmen)
        assertEquals(vortag(23, 30), weckpunktMin(ShiftKategorie.DIENST, true, LocalTime.of(23, 30), n0))
    }
}
