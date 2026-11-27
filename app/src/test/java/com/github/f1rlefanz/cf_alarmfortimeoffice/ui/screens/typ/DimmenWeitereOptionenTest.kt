package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.typ

import com.github.f1rlefanz.cf_alarmfortimeoffice.di.state.CalendarStateHolder
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.Blockposition
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimAnchor
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimOverlayPrefs
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimRuleRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimRuleUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimWindow
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimWindowResolver
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.HellZeitpunkt
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.TageslichtEnde
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.ZeitkettenArmierer
import com.github.f1rlefanz.cf_alarmfortimeoffice.dnd.DndPrefs
import com.github.f1rlefanz.cf_alarmfortimeoffice.error.ErrorHandler
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.HueRuleUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.AlarmInfo
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftConfig
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftDefinition
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftKategorie
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.TypDimmFenster
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.TypDimmen
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.repository.interfaces.IHueConfigRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.shift.ShiftSpanStore
import com.github.f1rlefanz.cf_alarmfortimeoffice.tageslicht.HellAb
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.IAlarmUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.IShiftUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.BeispielGrund
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.ShiftViewModel
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.nachlaufBeispiel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.stub
import org.mockito.kotlin.whenever
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * „Weitere Optionen“ der Karte „Bildschirm dimmen“ (Umbau Etappe 3, #224), ohne Compose: was das Blatt aendert,
 * landet im Entwurf des Typs und wird erst mit „Speichern“ geschrieben. Dazu die Beispielzeile zum
 * Tageslicht-Nachlauf, umgezogen aus `DimmerRulesViewModelTageslichtTest`.
 */
class DimmenWeitereOptionenTest {

    /** „Nacht davor 22:00 bis Wecker, spaetestens 07:00“ - darstellbar, endet am Wecker. */
    private val nachtDavor = TypDimmFenster(
        DimWindow(
            startAnchor = DimAnchor.CLOCK, startClockMinutes = 22 * 60,
            endAnchor = DimAnchor.ALARM_SONST_CLOCK, endClockMinutes = 7 * 60, startAmVorabend = true
        ),
        staerke = 55
    )

    /** Das Vorschlaf-Fenster der Nachtdienst-Vorlage: CLOCK bis AM Wecker - die Typ-Seite hat dafuer keine Form. */
    private val ohneForm = TypDimmFenster(
        DimWindow(
            startAnchor = DimAnchor.CLOCK, startClockMinutes = 16 * 60,
            endAnchor = DimAnchor.ALARM, endOffsetMinutes = 15,
            blockPositionen = setOf(Blockposition.ERSTER), startNurAmWecktag = true
        ),
        staerke = 70
    )

    private fun typ(dimmen: TypDimmen, kategorie: ShiftKategorie = ShiftKategorie.DIENST) = ShiftDefinition(
        id = "id-f", name = "F", keywords = listOf("F"), alarmTime = LocalTime.of(4, 30), dimmen = dimmen
    ).mitKategorie(kategorie)

    private fun dimmen(vararg fenster: TypDimmFenster, waerme: Int? = null) =
        TypDimmen(an = true, farbwaerme = waerme).mitFenster(fenster.toList())

    /** Speichert den Entwurf ueber denselben Weg wie der Knopf der Typ-Seite und gibt den gespeicherten Typ zurueck. */
    private fun speichere(ausgang: ShiftDefinition, entwurf: TypEntwurf): ShiftDefinition {
        var geschrieben: ShiftConfig? = null
        val ergebnis = TypSeiteSpeicherung { geschrieben = it }
            .speichere(ShiftConfig(definitions = listOf(ausgang)), ausgang.id, entwurf)
        assertEquals(SpeicherErgebnis.GESCHRIEBEN, ergebnis)
        return geschrieben!!.definitions.single()
    }

    private fun TypEntwurf.mitDimmen(aenderung: (DimmenEntwurf) -> DimmenEntwurf) = copy(dimmen = aenderung(dimmen!!))

    // ── Abnahme: Nachlauf, „Hell ab“ und Anker-Aenderung landen im Entwurf des Typs ───────────

    @Test
    fun `Nachlauf, Hell ab und Anker-Aenderung landen im Entwurf und werden mit dem Typ gespeichert`() {
        val ausgang = typ(dimmen(nachtDavor, ohneForm))
        val start = TypEntwurf.aus(ausgang)

        val entwurf = start
            .mitDimmen { d ->
                WeitereOptionenLogik.fensterAendern(d, 0) {
                    WeitereOptionenLogik.nachlaufSetzen(it, 45).copy(nachlaufBisHell = HellAb.SONNENAUFGANG)
                }
            }
            .mitDimmen { d ->
                WeitereOptionenLogik.fensterAendern(d, 1) {
                    WeitereOptionenLogik.startAnkerWaehlen(it, DimAnchor.SHIFT_END).copy(startOffsetMinutes = 30)
                }
            }

        assertTrue("„Speichern“ wird frei", TypSeiteEntwurf.hatAenderungen(start, entwurf))
        val gespeichert = speichere(ausgang, entwurf).dimmen.fenster
        assertEquals(
            nachtDavor.fenster.copy(weckzeitNachlaufMinuten = 45, nachlaufBisHell = HellAb.SONNENAUFGANG),
            gespeichert[0].fenster
        )
        assertEquals(
            "nur der Anker und sein Versatz aendern sich, Blocktage und Kennung bleiben",
            ohneForm.fenster.copy(startAnchor = DimAnchor.SHIFT_END, startOffsetMinutes = 30),
            gespeichert[1].fenster
        )
        assertEquals(listOf(55, 70), gespeichert.map { it.staerke })
    }

    @Test
    fun `eine Uhrzeit am Start schreibt den festen Anker mit - wie im Regel-Editor`() {
        val eingeschleust = DimWindow(startAnchor = DimAnchor.ALARM_SONST_CLOCK, endAnchor = DimAnchor.ALARM)

        val neu = WeitereOptionenLogik.startUhrzeit(eingeschleust, 15 * 60)

        assertEquals(DimAnchor.CLOCK, neu.startAnchor)
        assertEquals(15 * 60, neu.startClockMinutes)
    }

    @Test
    fun `der Nachlauf bleibt in den Grenzen des Resolvers`() {
        assertEquals(0, WeitereOptionenLogik.nachlaufSetzen(nachtDavor.fenster, -5).weckzeitNachlaufMinuten)
        assertEquals(
            DimWindowResolver.NACHLAUF_MAX_MINUTEN,
            WeitereOptionenLogik.nachlaufSetzen(nachtDavor.fenster, 999).weckzeitNachlaufMinuten
        )
        assertEquals("" to null, WeitereOptionenLogik.minutenEingabe(""))
        assertEquals("45" to 45, WeitereOptionenLogik.minutenEingabe("45"))
        assertEquals("120" to 120, WeitereOptionenLogik.minutenEingabe("500"))
        assertNull(WeitereOptionenLogik.minutenEingabe("4a"))
        assertNull(WeitereOptionenLogik.minutenEingabe("-5"))
        assertNull(WeitereOptionenLogik.minutenEingabe("1000"))
    }

    // ── Abnahme: Farbwaerme ───────────────────────────────────────────────────────────────

    @Test
    fun `wie in den Einstellungen ergibt Waerme null`() {
        val ausgang = typ(dimmen(nachtDavor, waerme = 80))
        val start = TypEntwurf.aus(ausgang)

        val entwurf = start.mitDimmen { WeitereOptionenLogik.waermeWaehlen(it, eigene = null, gespeichert = 80) }

        assertTrue(TypSeiteEntwurf.hatAenderungen(start, entwurf))
        assertNull(speichere(ausgang, entwurf).dimmen.farbwaerme)
    }

    @Test
    fun `ein eigener Wert wird gespeichert und in die Grenzen geklemmt`() {
        val ausgang = typ(dimmen(nachtDavor))
        val start = TypEntwurf.aus(ausgang)

        val eigener = start.mitDimmen { WeitereOptionenLogik.waermeWaehlen(it, eigene = 60, gespeichert = null) }
        val zuGross = start.mitDimmen { WeitereOptionenLogik.waermeWaehlen(it, eigene = 500, gespeichert = null) }

        assertEquals(60, speichere(ausgang, eigener).dimmen.farbwaerme)
        assertEquals(DimOverlayPrefs.WARMTH_MAX, speichere(ausgang, zuGross).dimmen.farbwaerme)
    }

    @Test
    fun `die gespeicherte Wahl erneut zu waehlen ist keine Aenderung`() {
        for (gespeichert in listOf(null, 80)) {
            val start = TypEntwurf.aus(typ(dimmen(nachtDavor, waerme = gespeichert)))
            val umweg = start
                .mitDimmen { WeitereOptionenLogik.waermeWaehlen(it, eigene = 25, gespeichert = gespeichert) }
                .mitDimmen { WeitereOptionenLogik.waermeWaehlen(it, eigene = gespeichert, gespeichert = gespeichert) }
            assertEquals("gespeichert=$gespeichert", start, umweg)
        }
    }

    @Test
    fun `das Blatt zeigt die gespeicherte Waerme, bis es sie selbst aendert`() {
        val unberuehrt = DimmenEntwurf(an = true, fenster = emptyList())
        assertEquals(80, WeitereOptionenLogik.waerme(unberuehrt, gespeichert = 80))
        assertNull(WeitereOptionenLogik.waerme(unberuehrt, gespeichert = null))
        assertEquals(25, WeitereOptionenLogik.waerme(unberuehrt.copy(farbwaerme = FarbwaermeWahl(25)), gespeichert = 80))
        assertNull(WeitereOptionenLogik.waerme(unberuehrt.copy(farbwaerme = FarbwaermeWahl(null)), gespeichert = 80))
        // Ein Import-Wert ausserhalb des Reglers wird nur angezeigt geklemmt, nicht umgeschrieben.
        assertEquals(DimOverlayPrefs.WARMTH_MAX, WeitereOptionenLogik.waerme(unberuehrt, gespeichert = 400))
    }

    @Test
    fun `die Wahl uebersteht alsSpeicherListe - auch wie in den Einstellungen - und ein altes Bundle bleibt unberuehrt`() {
        val ausgang = TypEntwurf.aus(typ(dimmen(nachtDavor, waerme = 80)))
        for (wahl in listOf(null, FarbwaermeWahl(null), FarbwaermeWahl(25))) {
            val entwurf = ausgang.mitDimmen { it.copy(farbwaerme = wahl) }
            assertEquals(entwurf, TypEntwurf.ausSpeicherListe(entwurf.alsSpeicherListe()))
        }

        // Ein Bundle von vor #224 kennt den Schluessel nicht.
        val alt = ausgang.alsSpeicherListe().toMutableList()
        alt[7] = """{"dimmen":{"an":false,"fenster":[]}}"""
        assertNull(TypEntwurf.ausSpeicherListe(alt)!!.dimmen!!.farbwaerme)
    }

    @Test
    fun `die Wahl bleibt, wenn sich das gespeicherte Dimmen unter der offenen Seite aendert`() {
        val ausgang = TypEntwurf.aus(typ(dimmen(nachtDavor)))
        val entwurf = ausgang.mitDimmen { it.copy(farbwaerme = FarbwaermeWahl(25)) }

        val (neuerAusgang, neuerEntwurf) =
            TypSeiteEntwurf.nachgezogen(ausgang, entwurf, dimmen(nachtDavor, waerme = 80))

        assertEquals(FarbwaermeWahl(25), neuerEntwurf.dimmen!!.farbwaerme)
        assertNotEquals(neuerAusgang, neuerEntwurf)
        assertEquals(25, speichere(typ(dimmen(nachtDavor, waerme = 80)), neuerEntwurf).dimmen.farbwaerme)
    }

    // ── Welches Fenster was zeigt ─────────────────────────────────────────────────────────

    @Test
    fun `ein Fenster ohne Form behaelt den Anker-Editor, auch wenn es durch eine Wahl darstellbar wird`() {
        val d = DimmenEntwurf(an = true, fenster = listOf(nachtDavor, ohneForm))
        val beimOeffnen = WeitereOptionenLogik.ohneForm(d)
        assertEquals(setOf(1), beimOeffnen)

        val darstellbar = WeitereOptionenLogik.fensterAendern(d, 1) { it.copy(endAnchor = DimAnchor.CLOCK) }

        assertEquals(emptySet<Int>(), WeitereOptionenLogik.ohneForm(darstellbar))
        assertTrue(WeitereOptionenLogik.zeigtAnkerEditor(darstellbar.fenster[1], 1, beimOeffnen))
        assertEquals(false, WeitereOptionenLogik.zeigtAnkerEditor(darstellbar.fenster[0], 0, beimOeffnen))
        // Und eines, das erst danach keine Form mehr hat (anderer Schreiber), bekommt ihn auch.
        assertTrue(WeitereOptionenLogik.zeigtAnkerEditor(ohneForm, 0, emptySet()))
    }

    @Test
    fun `der Nachlauf wird nur angeboten, wo das Fenster an der Weckzeit endet`() {
        fun fenster(start: DimAnchor, ende: DimAnchor) = TypDimmFenster(DimWindow(startAnchor = start, endAnchor = ende), 50)
        val amWecker = fenster(DimAnchor.CLOCK, DimAnchor.ALARM_SONST_CLOCK)
        val feste = fenster(DimAnchor.CLOCK, DimAnchor.CLOCK)
        val nachDienst = fenster(DimAnchor.SHIFT_END, DimAnchor.CLOCK)

        assertEquals(NachlaufLage.WIRKT, WeitereOptionenLogik.nachlaufLage(amWecker, weckerAn = true))
        assertEquals(NachlaufLage.WIRKT_NUR_MIT_FREMDEM_WECKER, WeitereOptionenLogik.nachlaufLage(amWecker, weckerAn = false))
        assertEquals(NachlaufLage.BEIM_WECKER_AUS, WeitereOptionenLogik.nachlaufLage(feste, weckerAn = true))
        // Ohne den Schalter auf der Karte darf kein Satz auf ihn verweisen.
        assertEquals(NachlaufLage.KEIN_NACHLAUF, WeitereOptionenLogik.nachlaufLage(feste, weckerAn = false))
        assertEquals(NachlaufLage.KEIN_NACHLAUF, WeitereOptionenLogik.nachlaufLage(nachDienst, weckerAn = true))
    }

    // ── Beispielzeile zum Tageslicht (umgezogen aus DimmerRulesViewModelTageslichtTest) ─────

    private val zone = ZoneId.of("Europe/Berlin")
    private val tag = LocalDate.parse("2026-10-15")
    private val jetzt = t(LocalDate.parse("2026-10-14"), "20:00")

    private fun t(datum: LocalDate, uhrzeit: String): Long =
        LocalDateTime.parse("${datum}T$uhrzeit").atZone(zone).toInstant().toEpochMilli()

    private fun nachlaufFenster(hellAb: HellAb = HellAb.SONNENAUFGANG, nachlauf: Int = 60, schranke: Int = 8 * 60) = DimWindow(
        startAnchor = DimAnchor.CLOCK,
        startClockMinutes = 22 * 60,
        endAnchor = DimAnchor.ALARM_SONST_CLOCK,
        endClockMinutes = schranke,
        weckzeitNachlaufMinuten = nachlauf,
        nachlaufBisHell = hellAb
    )

    private val hell0731: HellZeitpunkt = { d, wahl -> if (wahl == HellAb.IMMER) null else t(d, "07:31") }

    private fun beispiel(w: DimWindow, wecker: List<Long>, hell: HellZeitpunkt = hell0731) =
        nachlaufBeispiel(w, hell, wecker, jetzt, zone)

    @Test
    fun `voller Nachlauf - Sonnenaufgang ist erst danach`() {
        val b = beispiel(nachlaufFenster(), listOf(t(tag, "05:30")))!!
        assertEquals(t(tag, "05:30"), b.weckzeit)
        assertEquals(t(tag, "06:30"), b.ende)
        assertNull(b.grund)
        assertEquals(t(tag, "07:31"), b.hellZeitpunkt)
        assertEquals(BeispielGrund.NACHLAUF_VOLL, b.grundArt)
        assertEquals(60, b.nachlaufMin)
    }

    @Test
    fun `Sonnenaufgang im Nachlauf - gedimmt bis Sonnenaufgang`() {
        val b = beispiel(nachlaufFenster(), listOf(t(tag, "07:00")))!!
        assertEquals(t(tag, "07:31"), b.ende)
        assertEquals(TageslichtEnde(HellAb.SONNENAUFGANG, t(tag, "07:31"), vorWeckzeit = false), b.grund)
        assertEquals(BeispielGrund.HELL, b.grundArt)
    }

    @Test
    fun `schon hell beim Wecken - Ende an der Weckzeit`() {
        val b = beispiel(nachlaufFenster(), listOf(t(tag, "07:40")))!!
        assertEquals(t(tag, "07:40"), b.ende)
        assertEquals(BeispielGrund.WECKZEIT, b.grundArt)
    }

    @Test
    fun `kappt die Uhrzeit-Schranke den Nachlauf, behauptet die Zeile keine vollen Minuten`() {
        val b = beispiel(nachlaufFenster(schranke = 7 * 60 + 45), listOf(t(tag, "07:20")), { d, _ -> t(d, "07:50") })!!
        assertEquals(t(tag, "07:45"), b.ende)
        assertNull(b.grundArt)
    }

    @Test
    fun `der naechste Wecker, den das Fenster faengt - verstrichene und spaete zaehlen nicht`() {
        val vorbei = t(LocalDate.parse("2026-10-14"), "05:30") // vor jetzt
        val abends = t(LocalDate.parse("2026-10-14"), "21:00") // nach der Schranke, vor dem Start
        val morgen = t(tag, "06:00")
        val b = beispiel(nachlaufFenster(), listOf(abends, vorbei, morgen))!!
        assertEquals(morgen, b.weckzeit)
    }

    @Test
    fun `kein Beispiel ohne passenden Wecker, ohne Nachlauf oder bei IMMER`() {
        assertNull(beispiel(nachlaufFenster(), emptyList()))
        assertNull(beispiel(nachlaufFenster(), listOf(t(tag, "12:30")))) // nach der Schranke
        assertNull(beispiel(nachlaufFenster(nachlauf = 0), listOf(t(tag, "06:00"))))
        assertNull(beispiel(nachlaufFenster(hellAb = HellAb.IMMER), listOf(t(tag, "06:00"))))
    }

    @Test
    fun `ohne Ereignis an dem Tag gilt der volle Nachlauf, ohne Begruendung mit einer Uhrzeit`() {
        val b = beispiel(nachlaufFenster(), listOf(t(tag, "07:00")), { _, _ -> null })!!
        assertEquals(t(tag, "08:00"), b.ende)
        assertNull(b.hellZeitpunkt)
        assertNull(b.grundArt)
    }

    // ── Was das Blatt aus dem Bestand liest ───────────────────────────────────────────────

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `das ViewModel liefert die Vorgabe-Waerme und nur aktive Weckzeiten`() {
        val dispatcher = UnconfinedTestDispatcher()
        Dispatchers.setMain(dispatcher)
        try {
            runTest(dispatcher) {
                fun alarm(id: Int, trigger: Long, aktiv: Boolean) = AlarmInfo(
                    id = id, shiftId = "s", shiftName = "F", triggerTime = trigger, formattedTime = "", isActive = aktiv
                )
                val alarmUseCase = mock<IAlarmUseCase>()
                alarmUseCase.stub {
                    on { getAllAlarms() } doReturn
                        Result.success(listOf(alarm(1, 5_000L, aktiv = true), alarm(2, 4_000L, aktiv = false)))
                }
                val shiftUseCase = mock<IShiftUseCase>()
                whenever(shiftUseCase.shiftConfig).thenReturn(flowOf(ShiftConfig(definitions = emptyList())))
                shiftUseCase.stub {
                    on { getCurrentShiftConfig() } doReturn Result.success(ShiftConfig(definitions = emptyList()))
                    on { recognizeShiftsInEvents(any()) } doReturn Result.success(emptyList())
                }
                val overlay = mock<DimOverlayPrefs>()
                whenever(overlay.warmth).thenReturn(flowOf(65))
                val vm = ShiftViewModel(
                    shiftUseCase = shiftUseCase,
                    alarmUseCase = alarmUseCase,
                    calendarStateHolder = CalendarStateHolder(),
                    errorHandler = mock<ErrorHandler>(),
                    dimRuleUseCase = dagger.Lazy { DimRuleUseCase(mock<DimRuleRepository>()) },
                    hueRuleUseCase = dagger.Lazy { mock<HueRuleUseCase>() },
                    armierer = mock<ZeitkettenArmierer>(),
                    dndPrefs = dagger.Lazy { mock<DndPrefs>() },
                    shiftSpanStore = dagger.Lazy { mock<ShiftSpanStore>() },
                    dimOverlayPrefs = dagger.Lazy { overlay },
                    hueConfigRepository = dagger.Lazy { mock<IHueConfigRepository>() }
                )

                val stand = vm.weitereOptionenStand().first()

                assertEquals(65, stand.vorgabeWaerme)
                assertEquals(listOf(5_000L), stand.weckzeiten)
            }
        } finally {
            Dispatchers.resetMain()
        }
    }
}
