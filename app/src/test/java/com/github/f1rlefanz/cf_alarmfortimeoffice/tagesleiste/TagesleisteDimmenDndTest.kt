package com.github.f1rlefanz.cf_alarmfortimeoffice.tagesleiste

import android.content.Context
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.Blockposition
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimAnchor
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimCorrectionNotifier
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimOverlayPrefs
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimRule
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimScheduleUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimWindow
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimWindowResolver
import com.github.f1rlefanz.cf_alarmfortimeoffice.dnd.DndPrefs
import com.github.f1rlefanz.cf_alarmfortimeoffice.dnd.DndScheduleUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.freietage.keineFreienTage
import com.github.f1rlefanz.cf_alarmfortimeoffice.masterpause.MasterPausePrefs
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.AlarmInfo
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.OhneEintragEinstellungen
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftConfig
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftDefinition
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftKategorie
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.TypDimmFenster
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.TypDimmen
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.TypNichtStoeren
import com.github.f1rlefanz.cf_alarmfortimeoffice.repository.interfaces.IShiftConfigRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.shift.ShiftSpan
import com.github.f1rlefanz.cf_alarmfortimeoffice.shift.ShiftSpanStore
import com.github.f1rlefanz.cf_alarmfortimeoffice.tageslicht.HellAb
import com.github.f1rlefanz.cf_alarmfortimeoffice.tageslicht.TageslichtQuelle
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.IAlarmUseCase
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset
import java.util.TimeZone

/**
 * Dimmen und "Nicht stoeren" auf der Tagesleiste, gerechnet aus den Einstellungen AM TYP (Umbau #194,
 * seit #219). Alle Minuten sind Minuten ab 18:00 am Vortag (0..2880), die Erwartungen stehen als LITERALE
 * da; Orakel sind `DimWindowResolver.buildRuleSpans` auf von Hand gebauten Slots und - fuer "Leiste =
 * Laufzeit" - der ECHTE `DimScheduleUseCase` und `DndScheduleUseCase` auf demselben Typ-Bestand.
 *
 * Der Mustertag ist ein Normaltag: Fruehdienst 06:00 bis 14:15, Wecker 04:30 (Minute 630).
 */
class TagesleisteDimmenDndTest {

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

    private fun dimmen(vararg fenster: DimWindow, staerke: Int = 55, an: Boolean = true): TypDimmen =
        TypDimmen(an = an).mitFenster(fenster.map { TypDimmFenster(it, staerke) })

    private val solange = TypNichtStoeren(solangeGedimmt = true)
    private val waehrend = TypNichtStoeren(waehrendEintrag = true)

    private fun einstellungen(
        dimmen: TypDimmen = TypDimmen(),
        nichtStoeren: TypNichtStoeren = TypNichtStoeren(),
        freierTag: OhneEintragEinstellungen = OhneEintragEinstellungen(),
        hellUm: (HellAb) -> LocalTime? = { null }
    ) = DimDndEinstellungen(dimmen, nichtStoeren, freierTag, hellUm)

    private fun eingabe(
        kategorie: ShiftKategorie = ShiftKategorie.DIENST,
        name: String = "F",
        weckerAn: Boolean = true,
        weckzeit: LocalTime? = LocalTime.of(4, 30),
        dienstzeit: Dienstzeit? = frueh,
        blockposition: Blockposition = standardBlockposition(kategorie),
        rufCutoffMinuten: Int = DndPrefs.DEFAULT_ONCALL_CUTOFF_MIN
    ) = TagesleisteEingabe(
        name, kategorie, weckerAn, weckzeit, still = false, dienstzeit = dienstzeit,
        blockposition = blockposition, rufCutoffMinuten = rufCutoffMinuten
    )

    private fun rechne(e: TagesleisteEingabe, s: DimDndEinstellungen) = dimmenUndNichtStoeren(e, s)

    private fun Tagesleiste.ereignisseVon(spur: Spur) = ereignisse.filter { it.spur == spur }.map { it.zeit to it.text }

    // ── Der Typ bestimmt die Fenster ─────────────────────────────────────────────────────────

    @Test
    fun `Nacht davor endet beim Wecker und die Leiste legt den Wecker auf dieselbe Minute`() {
        val e = eingabe(blockposition = Blockposition.EINZELNER)
        val r = rechne(e, einstellungen(dimmen(nachtDavor)))

        assertEquals(listOf(DimBereich(vortag(22), tag(4, 30), 55, endetBeimWecker = true)), r.dimmen)
        // Die zwei Mustertage (hier und in Tagesleiste.kt) sind getrennte Konstanten - dieselbe Minute.
        assertEquals(tag(4, 30), weckpunktMin(ShiftKategorie.DIENST, true, LocalTime.of(4, 30), frueh))
        assertEquals(weckpunktMin(ShiftKategorie.DIENST, true, LocalTime.of(4, 30), frueh), r.dimmen.single().bisMin)
    }

    @Test
    fun `ein Entwurf mit geaendertem Fenster aendert die Spur - Staerke und Grenzen kommen vom uebergebenen Typ`() {
        val e = eingabe(blockposition = Blockposition.EINZELNER)

        val gespeichert = rechne(e, einstellungen(dimmen(nachtDavor, staerke = 55)))
        val entwurf = rechne(e, einstellungen(dimmen(nachtDavor.copy(startClockMinutes = 23 * 60), staerke = 70)))

        assertEquals(listOf(DimBereich(vortag(22), tag(4, 30), 55, endetBeimWecker = true)), gespeichert.dimmen)
        assertEquals(listOf(DimBereich(vortag(23), tag(4, 30), 70, endetBeimWecker = true)), entwurf.dimmen)

        // Und bis in die Leiste: der Balken beginnt eine Stunde spaeter.
        fun beginn(r: DimUndNichtStoeren) = tagesleiste(e.copy(dimmen = r.dimmen)).balken.first { it.spur == Spur.DIMMEN }.vonMin
        assertEquals(vortag(22), beginn(gespeichert))
        assertEquals(vortag(23), beginn(entwurf))
    }

    @Test
    fun `jedes Fenster traegt seine eigene Staerke`() {
        val s = einstellungen(
            TypDimmen().mitFenster(listOf(TypDimmFenster(nachtDavor, staerke = 30), TypDimmFenster(abendDanach, staerke = 80)))
        )
        val r = rechne(eingabe(blockposition = Blockposition.EINZELNER), s)

        assertEquals(setOf(vortag(22) to 30, tag(20) to 80), r.dimmen.map { it.vonMin to it.prozent }.toSet())
    }

    @Test
    fun `ein Typ ohne Fenster oder mit Dimmen aus dimmt nicht, und Nicht stoeren folgt dem Dimmer dann nicht`() {
        val gesperrtMitFenstern = dimmen(nachtDavor, an = false)
        val ohneFenster = TypDimmen()

        listOf(gesperrtMitFenstern, ohneFenster).forEachIndexed { i, d ->
            Blockposition.entries.forEach { position ->
                val r = rechne(eingabe(blockposition = position), einstellungen(d, nichtStoeren = solange))
                assertEquals("Dimmen, Fall $i bei $position", emptyList<DimBereich>(), r.dimmen)
                assertEquals("Nicht stoeren, Fall $i bei $position", emptyList<DndBereich>(), r.nichtStoeren)
            }
        }
        // „Waehrend des Kalendereintrags“ haengt nicht am Dimmer.
        val beide = TypNichtStoeren(solangeGedimmt = true, waehrendEintrag = true)
        assertEquals(
            listOf(DndBereich(tag(6), tag(14, 15), DndQuelle.KALENDEREINTRAG)),
            rechne(eingabe(blockposition = Blockposition.EINZELNER), einstellungen(gesperrtMitFenstern, beide)).nichtStoeren
        )
    }

    @Test
    fun `Krank und Ignorieren laufen wie ein freier Tag mit der festen Zeile, ein erkannter Typ ist ein Schichttag`() {
        val feste = OhneEintragEinstellungen(dimmen(nachtBisWecker, staerke = 30), nichtStoerenSolangeGedimmt = true)

        // Krank: die FESTE ZEILE dimmt, nicht die eigenen Fenster des Typs (die Laufzeit gibt einem
        // freigegebenen Tag die feste Zeile) - samt deren eigenem "Solange gedimmt wird".
        val krank = rechne(
            eingabe(ShiftKategorie.KRANK, "K", weckerAn = false, dienstzeit = null),
            einstellungen(dimmen(nachtDavor, staerke = 70), TypNichtStoeren(solangeGedimmt = false), freierTag = feste)
        )
        assertEquals(listOf(DimBereich(tag(22), folgetag(7), 30, endetBeimWecker = false)), krank.dimmen)
        assertEquals(listOf(DndBereich(tag(22), folgetag(7), DndQuelle.DIMM_FENSTER)), krank.nichtStoeren)

        // Die feste Zeile OHNE ihren Schalter: gedimmt wird, Nicht stoeren folgt nicht.
        val ohneSchalter = rechne(
            eingabe(ShiftKategorie.KRANK, "K", weckerAn = false, dienstzeit = null),
            einstellungen(freierTag = feste.copy(nichtStoerenSolangeGedimmt = false))
        )
        assertEquals(krank.dimmen, ohneSchalter.dimmen)
        assertEquals(emptyList<DndBereich>(), ohneSchalter.nichtStoeren)

        // Ignorieren ohne weiteren Eintrag: derselbe freie Tag - aber die Leiste eines Ignorieren-Typs bleibt leer.
        val ignorieren = eingabe(ShiftKategorie.IGNORIEREN, "TB", weckerAn = false, dienstzeit = null)
        val ignoriert = rechne(ignorieren, einstellungen(dimmen(nachtDavor, staerke = 80), freierTag = feste))
        assertEquals(krank.dimmen, ignoriert.dimmen)
        assertEquals(Tagesleiste.LEER, tagesleiste(ignorieren.copy(dimmen = ignoriert.dimmen)))

        // Frei-Typ MIT eigenem Fenster: das Fenster des Typs; Wecker aus, also kein "der Wecker klingelt".
        val freiMitFenster = rechne(
            eingabe(ShiftKategorie.FREI, "Fr", weckerAn = false, weckzeit = LocalTime.of(8, 0), dienstzeit = null),
            einstellungen(dimmen(nachtDavor, staerke = 60), freierTag = feste)
        )
        assertEquals(listOf(DimBereich(vortag(22), tag(8), 60, endetBeimWecker = false)), freiMitFenster.dimmen)

        // Frei-Typ OHNE Fenster: er ist ein Schichttag und unterdrueckt - die feste Zeile springt NICHT ein.
        val freiOhneFenster = rechne(
            eingabe(ShiftKategorie.FREI, "Fx", weckerAn = false, weckzeit = LocalTime.of(8, 0), dienstzeit = null),
            einstellungen(freierTag = feste)
        )
        assertEquals(emptyList<DimBereich>(), freiOhneFenster.dimmen)
    }

    @Test
    fun `die feste Zeile bei wie Typ nimmt die Einstellungen dieses Typs`() {
        val f = ShiftDefinition(
            id = "id-F", name = "F", keywords = listOf("F"), alarmTime = LocalTime.of(4, 30),
            dimmen = dimmen(nachtBisWecker, staerke = 45), nichtStoeren = TypNichtStoeren(solangeGedimmt = true)
        )
        val eigene = OhneEintragEinstellungen(dimmen(abendDanach, staerke = 10))
        val config = ShiftConfig(definitions = listOf(f), ohneKalendereintrag = eigene)

        assertEquals(eigene, freierTagAus(config))
        assertEquals(OhneEintragEinstellungen(f.dimmen, nichtStoerenSolangeGedimmt = true), freierTagAus(config.copy(ohneKalendereintragWie = "id-F")))
        // Ein Verweis ins Leere steht als "Eigene Einstellungen" und wirkt auch so.
        assertEquals(eigene, freierTagAus(config.copy(ohneKalendereintragWie = "weg")))
    }

    // ── Blockposition, startNurAmWecktag, Nachlauf, Hell ab ──────────────────────────────────

    @Test
    fun `ein Fenster gilt nur an den Tagen seines Blocks, und die Wecker der Nachbarn beenden das Ende bis Wecker`() {
        val nurMitte = nachtDavor.copy(blockPositionen = setOf(Blockposition.MITTLERER))
        val nurEinzeln = abendDanach.copy(blockPositionen = setOf(Blockposition.EINZELNER))
        val nurBlock = einstellungen(dimmen(nurMitte, nurEinzeln))

        fun dim(position: Blockposition) = rechne(eingabe(blockposition = position), nurBlock).dimmen

        assertEquals(listOf(DimBereich(tag(20), folgetag(7), 55, false)), dim(Blockposition.EINZELNER))
        assertEquals(listOf(DimBereich(vortag(22), tag(4, 30), 55, true)), dim(Blockposition.MITTLERER))
        assertEquals(emptyList<DimBereich>(), dim(Blockposition.ERSTER))
        assertEquals(emptyList<DimBereich>(), dim(Blockposition.LETZTER))

        // Der Folgetag desselben Typs weckt um 04:30: das beendet "bis Wecker" dort, nicht erst um 07:00.
        val abend = einstellungen(dimmen(abendDanach))
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
        val s = einstellungen(dimmen(vorschlaf))
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
        val s = einstellungen(dimmen(nachDienst))

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
        val mitNachlauf = nachtBisWecker.copy(weckzeitNachlaufMinuten = 30, nachlaufBisHell = HellAb.DAEMMERUNG)
        val mitte = eingabe(blockposition = Blockposition.MITTLERER)
        val ohneStandort = rechne(mitte, einstellungen(dimmen(mitNachlauf), solange))

        // Der Wecker des Folgetags: 04:30. Dimmen bis 05:00 - und der Text behauptet keinen Wecker dort.
        assertEquals(listOf(DimBereich(tag(22), folgetag(5), 55, endetBeimWecker = false)), ohneStandort.dimmen)
        assertEquals(listOf(DndBereich(tag(22), folgetag(4, 30), DndQuelle.DIMM_FENSTER)), ohneStandort.nichtStoeren)

        val mitStandort = rechne(
            mitte,
            einstellungen(dimmen(mitNachlauf), solange, hellUm = { if (it == HellAb.DAEMMERUNG) LocalTime.of(4, 50) else null })
        )
        assertEquals(listOf(DimBereich(tag(22), folgetag(4, 50), 55, endetBeimWecker = false)), mitStandort.dimmen)
        assertEquals(ohneStandort.nichtStoeren, mitStandort.nichtStoeren)
    }

    @Test
    fun `ohne Weckzeit und bei Wecker aus - kein Anker ohne Weckzeit, aber der Anker bleibt bei Wecker aus`() {
        val beide = TypNichtStoeren(solangeGedimmt = true, waehrendEintrag = true)
        val s = einstellungen(dimmen(nachtDavor), beide)

        val ohneWeckzeit = rechne(eingabe(weckzeit = null, blockposition = Blockposition.EINZELNER), s)
        assertEquals(emptyList<DimBereich>(), ohneWeckzeit.dimmen)
        assertEquals(listOf(DndBereich(tag(6), tag(14, 15), DndQuelle.KALENDEREINTRAG)), ohneWeckzeit.nichtStoeren)

        // Wecker aus: heute fuehrt die Schichtspanne die Weckzeit trotzdem (#181), das Fenster endet dort.
        val weckerAus = rechne(eingabe(weckerAn = false, blockposition = Blockposition.EINZELNER), s)
        assertEquals(listOf(DimBereich(vortag(22), tag(4, 30), 55, endetBeimWecker = false)), weckerAus.dimmen)
    }

    @Test
    fun `der Ereignistext nennt den Wecker nur, wenn er klingelt`() {
        val s = einstellungen(dimmen(nachtDavor))
        fun text(weckerAn: Boolean): String {
            val e = eingabe(weckerAn = weckerAn, blockposition = Blockposition.EINZELNER)
            return tagesleiste(e.copy(dimmen = rechne(e, s).dimmen)).ereignisseVon(Spur.DIMMEN).last().second
        }

        assertEquals("Dimmen endet (F) – der Wecker klingelt", text(weckerAn = true))
        assertEquals("Dimmen endet (F)", text(weckerAn = false))
    }

    // ── Nicht stoeren ────────────────────────────────────────────────────────────────────────

    @Test
    fun `Nicht stoeren waehrend des Eintrags ist die Spanne des Typs mit dem Schalter, Krank hat keine`() {
        assertEquals(
            listOf(DndBereich(tag(6), tag(14, 15), DndQuelle.KALENDEREINTRAG)),
            rechne(eingabe(), einstellungen(nichtStoeren = waehrend)).nichtStoeren
        )
        // Schalter aus: keine Spanne - die frueheren globalen Ausnahmen gibt es nicht mehr.
        assertEquals(emptyList<DndBereich>(), rechne(eingabe(), einstellungen(nichtStoeren = TypNichtStoeren())).nichtStoeren)
        // Ganztaegig ohne Uhrzeit: der ganze Tag, wie der Eintrag-Balken der Leiste.
        assertEquals(
            listOf(DndBereich(tag(0), folgetag(0), DndQuelle.KALENDEREINTRAG)),
            rechne(eingabe(ShiftKategorie.URLAUB, "U", dienstzeit = null), einstellungen(nichtStoeren = waehrend)).nichtStoeren
        )
        assertEquals(
            emptyList<DndBereich>(),
            rechne(eingabe(ShiftKategorie.KRANK, "K", dienstzeit = null), einstellungen(nichtStoeren = waehrend)).nichtStoeren
        )
    }

    @Test
    fun `Nicht stoeren endet bei Rufbereitschaft um 05 Uhr, und die Leiste sagt es`() {
        val e = eingabe(
            ShiftKategorie.RUFBEREITSCHAFT, "AD", weckzeit = LocalTime.of(6, 0), dienstzeit = ruf,
            blockposition = Blockposition.EINZELNER
        )

        val r = rechne(e, einstellungen(dimmen(nachtBisWecker), solange))
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

    @Test
    fun `der Ruf-Cutoff des Nachbartags im Block kappt Nicht stoeren auch, wenn die Leiste nur den eigenen kennt`() {
        // Beginn 07:00: der Ruf des VORTAGS hat seinen Cutoff um 05:00 am Mustertag. "Nacht davor" reicht
        // bis zum Wecker um 06:00 - heute endet Nicht stoeren dort um 05:00, nicht erst um 06:00.
        fun mitte(position: Blockposition, cutoffMinuten: Int = 5 * 60): List<Balken> {
            val e = eingabe(
                ShiftKategorie.RUFBEREITSCHAFT, "AD", weckerAn = false, weckzeit = LocalTime.of(6, 30), dienstzeit = ruf,
                blockposition = position, rufCutoffMinuten = cutoffMinuten
            )
            val r = rechne(e, einstellungen(dimmen(nachtDavor), TypNichtStoeren(true, false, cutoffMinuten)))
            return tagesleiste(e.copy(dimmen = r.dimmen, nichtStoeren = r.nichtStoeren)).balken.filter { it.spur == Spur.NICHT_STOEREN }
        }
        assertEquals(listOf(Balken(Spur.NICHT_STOEREN, vortag(22), tag(5))), mitte(Blockposition.MITTLERER))
        assertEquals(listOf(Balken(Spur.NICHT_STOEREN, vortag(22), tag(5))), mitte(Blockposition.LETZTER))
        // Ohne Vortag im Block gibt es dort keinen Cutoff.
        assertEquals(listOf(Balken(Spur.NICHT_STOEREN, vortag(22), tag(6, 30))), mitte(Blockposition.EINZELNER))
        assertEquals(listOf(Balken(Spur.NICHT_STOEREN, vortag(22), tag(6, 30))), mitte(Blockposition.ERSTER))

        // "Endet spaetestens um" gehoert dem TYP: 06:00 kappt in der Blockmitte um 06:00, nicht um 05:00.
        assertEquals(listOf(Balken(Spur.NICHT_STOEREN, vortag(22), tag(6))), mitte(Blockposition.MITTLERER, cutoffMinuten = 6 * 60))

        // Beginn 03:00: der eigene Cutoff liegt um 05:00 am Mustertag, der des FOLGETAGS um 05:00 danach.
        val frueherBeginn = Dienstzeit(LocalTime.of(3, 0), 1440, ganztaegig = false)
        val erster = eingabe(
            ShiftKategorie.RUFBEREITSCHAFT, "AD", weckzeit = LocalTime.of(6, 0), dienstzeit = frueherBeginn,
            blockposition = Blockposition.ERSTER
        )
        val r = rechne(erster, einstellungen(dimmen(nachtBisWecker), solange))
        assertEquals(listOf(DndBereich(tag(22), folgetag(5), DndQuelle.DIMM_FENSTER)), r.nichtStoeren)
    }

    // ── Gegen buildRuleSpans ─────────────────────────────────────────────────────────────────

    private fun ms(t: LocalDateTime) = t.toInstant(ZoneOffset.UTC).toEpochMilli()
    private val leisteNull = ms(LocalDateTime.of(2001, 1, 1, 18, 0))
    private fun minute(epoch: Long) = ((epoch - leisteNull) / 60_000).toInt()

    /** Orakel: von Hand gebaute Slots (Fruehdienst, Wecker 04:30) und der Kalendertag des Mustertags allein. */
    private fun orakel(regel: DimRule, position: Blockposition): List<Triple<Int, Int, Int>> {
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
            ruleForShift = { regel }, ruleForFreeDay = { null },
            weckzeiten = tage.map { ms(wecker.plusDays(it)) }
        )
        val nurMustertag = lauf(1).toMutableList().also { rest -> lauf(0).forEach { rest.remove(it) } }
        return nurMustertag.map { Triple(minute(it.range.first), minute(it.range.last), it.strength) }.sortedBy { it.first }
    }

    @Test
    fun `das Ergebnis ist das von buildRuleSpans, fuer jede Blockposition und mehrere Fenstersaetze`() {
        val saetze = listOf(
            listOf(nachtDavor, abendDanach.copy(weckzeitNachlaufMinuten = 45)),
            listOf(nachtBisWecker),
            listOf(nachtDavor.copy(blockPositionen = setOf(Blockposition.MITTLERER, Blockposition.LETZTER)), abendDanach)
        )

        saetze.forEachIndexed { i, fenster ->
            Blockposition.entries.forEach { position ->
                val regel = DimRule(
                    id = "r", name = "F", shiftPattern = "F", windows = fenster, strength = 65,
                    fensterStaerken = fenster.map { 65 }
                )
                val erwartet = orakel(regel, position)
                val ist = rechne(eingabe(blockposition = position), einstellungen(dimmen(*fenster.toTypedArray(), staerke = 65))).dimmen
                    .map { Triple(it.vonMin, it.bisMin, it.prozent) }.sortedBy { it.first }
                assertEquals("Fenstersatz $i, $position", erwartet, ist)
                assertTrue("Fenstersatz $i, $position liefert etwas", erwartet.isNotEmpty())
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

        val r = rechne(e, einstellungen(dimmen(fenster)))

        assertEquals(listOf(DimBereich(vortag(21), vortag(23, 30), 55, endetBeimWecker = true)), r.dimmen)
        assertEquals(vortag(23, 30), weckpunktMin(ShiftKategorie.DIENST, true, LocalTime.of(23, 30), n0))
    }

    // ── Leiste = Laufzeit: der ECHTE Dimmer und der ECHTE DND-Scheduler auf demselben Typ ─────

    private lateinit var alteZone: TimeZone

    @Before
    fun zoneFestlegen() {
        // Die Laufzeit rechnet in der Systemzone, die Leiste auf einem UTC-Mustertag: in UTC fallen beide
        // zusammen und eine Zeitumstellung in der Naehe von "heute" kann den Test nicht verschieben.
        alteZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    @After
    fun zoneZuruecksetzen() = TimeZone.setDefault(alteZone)

    private fun laufzeit(config: ShiftConfig, spannen: List<ShiftSpan>): Pair<DimScheduleUseCase, DndScheduleUseCase> {
        val repo = mock<IShiftConfigRepository>()
        val store = mock<ShiftSpanStore>()
        val prefs = mock<DimOverlayPrefs>()
        val alarme = mock<IAlarmUseCase>()
        val pause = mock<MasterPausePrefs>()
        runBlocking {
            whenever(repo.getCurrentShiftConfig()).thenReturn(Result.success(config))
            whenever(store.spansNow()).thenReturn(Result.success(spannen))
            whenever(prefs.warmthNow()).thenReturn(40)
            whenever(alarme.getAllAlarms()).thenReturn(Result.success(emptyList<AlarmInfo>()))
            whenever(pause.pausedNow()).thenReturn(false)
        }
        val dimmer = DimScheduleUseCase(
            mock<Context>(), alarme, store, keineFreienTage(), repo, prefs, mock<DimCorrectionNotifier>(), pause,
            TageslichtQuelle.OHNE
        )
        return dimmer to DndScheduleUseCase(mock<Context>(), store, keineFreienTage(), dimmer, mock<DndPrefs>(), mock<MasterPausePrefs>(), repo)
    }

    /** Die Leiste eines Typs als epoch-Bereiche (UTC), der Mustertag [mustertag]: Minute 0 = 18:00 am Vortag. */
    private fun epoche(mustertag: LocalDate, minute: Int): Long =
        mustertag.minusDays(1).atTime(18, 0).toInstant(ZoneOffset.UTC).toEpochMilli() + minute * 60_000L

    private fun typDefinition(
        name: String, kategorie: ShiftKategorie, dimmen: TypDimmen, nichtStoeren: TypNichtStoeren
    ) = ShiftDefinition(
        id = "id-$name", name = name, keywords = listOf(name), alarmTime = LocalTime.of(4, 30),
        dimmen = dimmen, nichtStoeren = nichtStoeren
    ).mitKategorie(kategorie)

    @Test
    fun `Leiste und Laufzeit liefern fuer denselben Typ dieselben Bereiche`() = runTest {
        val mustertag = LocalDate.now(ZoneOffset.UTC).plusDays(10)
        // Zwei Fenster mit verschiedener Staerke, beide Schalter an; ein zweiter Typ mit anderen Fenstern
        // und anderem Cutoff im selben Bestand - die Leiste von F darf von ihm nichts uebernehmen.
        val f = typDefinition(
            "F", ShiftKategorie.DIENST,
            TypDimmen().mitFenster(listOf(TypDimmFenster(nachtDavor, 30), TypDimmFenster(abendDanach, 80))),
            TypNichtStoeren(solangeGedimmt = true, waehrendEintrag = true)
        )
        val ad = typDefinition(
            "AD", ShiftKategorie.RUFBEREITSCHAFT, dimmen(nachtDavor, staerke = 40), TypNichtStoeren(true, true, 6 * 60)
        )
        val config = ShiftConfig(definitions = listOf(f, ad))

        fun span(name: String, tag: LocalDate, beginn: LocalTime, ende: LocalTime) = ShiftSpan(
            shiftName = name,
            startTime = tag.atTime(beginn).toInstant(ZoneOffset.UTC).toEpochMilli(),
            endTime = tag.atTime(ende).toInstant(ZoneOffset.UTC).toEpochMilli(),
            alarmTriggerTime = tag.atTime(4, 30).toInstant(ZoneOffset.UTC).toEpochMilli()
        )

        // F einzeln; AD als Block von drei Tagen (der Mustertag ist die Mitte, sein Cutoff-Vortag wirkt).
        val faelle = listOf(
            Triple(f, Blockposition.EINZELNER, listOf(span("F", mustertag, LocalTime.of(6, 0), LocalTime.of(14, 15)))),
            Triple(
                ad, Blockposition.MITTLERER,
                (-1L..1L).map { span("AD", mustertag.plusDays(it), LocalTime.of(7, 0), LocalTime.of(7, 0)).let { s -> s.copy(endTime = s.startTime + 24 * 3_600_000L) } }
            )
        )
        faelle.forEach { (typ, position, spannen) ->
            val (dimmer, dnd) = laufzeit(config, spannen)
            val e = eingabe(
                typ.kategorie, typ.name, blockposition = position,
                rufCutoffMinuten = typ.nichtStoeren.endetSpaetestensMinuten, dienstzeit = if (typ.name == "F") frueh else ruf
            )
            val leiste = rechne(e, einstellungen(typ.dimmen, typ.nichtStoeren))
            // Verglichen wird, was die Leiste ZEIGT (inklusive ihres eigenen Ruf-Cutoffs), mit dem, was
            // die Laufzeit schaltet - nicht die Rohausgabe des Adapters, die den eigenen Cutoff bewusst nicht kappt.
            val gezeigt = tagesleiste(e.copy(dimmen = leiste.dimmen, nichtStoeren = leiste.nichtStoeren)).balken

            val laufzeitDimmen = dimmer.previewTimeline().map { it.range.first to it.range.last }.toSet()
            val laufzeitDnd = dnd.fensterJetzt().map { it.range.first to it.range.last }.toSet()

            val dimmBalken = gezeigt.filter { it.spur == Spur.DIMMEN }
            val dndBalken = gezeigt.filter { it.spur == Spur.NICHT_STOEREN }
            assertTrue("${typ.name}: die Leiste zeigt Dimmen", dimmBalken.isNotEmpty())
            assertTrue("${typ.name}: die Leiste zeigt Nicht stoeren", dndBalken.isNotEmpty())
            dimmBalken.forEach {
                assertTrue(
                    "${typ.name} bei $position: Dimmen ${it.vonMin}..${it.bisMin} steht in der Laufzeit",
                    (epoche(mustertag, it.vonMin) to epoche(mustertag, it.bisMin)) in laufzeitDimmen
                )
            }
            dndBalken.forEach {
                assertTrue(
                    "${typ.name} bei $position: Nicht stoeren ${it.vonMin}..${it.bisMin} steht in der Laufzeit",
                    (epoche(mustertag, it.vonMin) to epoche(mustertag, it.bisMin)) in laufzeitDnd
                )
            }
            if (typ.name == "F") {
                // Ohne Block gibt es nichts Fremdes: Anzahl und Menge der Bereiche stimmen, nicht nur "enthalten".
                assertEquals(
                    dimmBalken.map { epoche(mustertag, it.vonMin) to epoche(mustertag, it.bisMin) }.toSet(),
                    laufzeitDimmen.filter { it.first >= epoche(mustertag, 0) }.toSet()
                )
            }
        }
    }
}
