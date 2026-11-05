package com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel

import com.github.f1rlefanz.cf_alarmfortimeoffice.di.state.CalendarStateHolder
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimOverlayPrefs
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimRule
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimRuleRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimRuleUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimWindow
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.ZeitkettenArmierer
import com.github.f1rlefanz.cf_alarmfortimeoffice.dnd.DndPrefs
import com.github.f1rlefanz.cf_alarmfortimeoffice.error.ErrorHandler
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.HueSchedule
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.HueScheduleRule
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.repository.interfaces.HueConfiguration
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.repository.interfaces.IHueConfigRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.HueRuleUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.CalendarEvent
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftConfig
import com.github.f1rlefanz.cf_alarmfortimeoffice.shift.ShiftSpan
import com.github.f1rlefanz.cf_alarmfortimeoffice.shift.ShiftSpanStore
import com.github.f1rlefanz.cf_alarmfortimeoffice.tagesleiste.Quelle
import com.github.f1rlefanz.cf_alarmfortimeoffice.tagesleiste.Spur
import com.github.f1rlefanz.cf_alarmfortimeoffice.tagesleiste.SpurStand
import com.github.f1rlefanz.cf_alarmfortimeoffice.tagesleiste.TagesleisteQuellen
import com.github.f1rlefanz.cf_alarmfortimeoffice.tagesleiste.alsQuelle
import com.github.f1rlefanz.cf_alarmfortimeoffice.tagesleiste.fehlerAlsUnbekannt
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.IAlarmUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.IShiftUseCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.stub
import org.mockito.kotlin.verifyBlocking
import org.mockito.kotlin.whenever
import java.io.IOException
import java.time.LocalDateTime

/**
 * Die Quellen der Tagesleiste im `ShiftViewModel` (Umbau #196, SPEC 3.3 Punkt 3).
 *
 * Der Kern: ein LESEFEHLER einer Quelle wird [Quelle.Unbekannt], nie eine leere Liste - "keine
 * Dimm-Regel" hiesse "kein Dimmen", "keine Hue-Regel" hiesse "kein Licht" (Pflichtregel "Kein Fehler
 * darf als leeres Erfolgsergebnis durchrutschen"). Und er trifft nur die Spuren, die an der Quelle
 * haengen.
 */
@OptIn(ExperimentalCoroutinesApi::class) // Dispatchers.setMain/resetMain, advanceUntilIdle, runCurrent
class TagesleisteQuellenTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // --- Bausteine -------------------------------------------------------------------------

    private val dimRegel = DimRule(
        id = "r1", name = "Regel r1", shiftPattern = DimRule.SHIFT_UNIVERSAL, enabled = true,
        windows = listOf(DimWindow())
    )
    private val hueRegel: HueSchedule =
        HueScheduleRule(id = "h1", name = "Hue h1", shiftPattern = "AD1", enabled = true, timeRanges = emptyList())
    private val spanne = ShiftSpan("AD1", startTime = 1_000L, endTime = 2_000L, alarmTriggerTime = 500L)

    private val toggles = DndPrefs.Toggles(followDimmerEnabled = true, duringShiftEnabled = true)

    /** Alles, was ein Lauf einstellen kann; der Rest sind gesunde Vorgaben. */
    private class Welt(
        var dimRegeln: Flow<List<DimRule>>,
        var dndToggles: DndPrefs.Toggles,
        var hueRegeln: Result<List<HueSchedule>>,
        var spannen: Result<List<ShiftSpan>>
    ) {
        var angefasst = 0
        var hueGraphGebaut = 0
        val holder = CalendarStateHolder()
        lateinit var vm: ShiftViewModel
        lateinit var spanStore: ShiftSpanStore
        lateinit var hueUseCase: HueRuleUseCase
    }

    private fun welt(
        dimRegeln: Flow<List<DimRule>> = flowOf(listOf(dimRegel)),
        dndToggles: DndPrefs.Toggles = toggles,
        hueRegeln: Result<List<HueSchedule>> = Result.success(listOf(hueRegel)),
        spannen: Result<List<ShiftSpan>> = Result.success(listOf(spanne))
    ): Welt {
        val w = Welt(dimRegeln, dndToggles, hueRegeln, spannen)
        val config = ShiftConfig(definitions = emptyList())
        val shiftUseCase = mock<IShiftUseCase>()
        whenever(shiftUseCase.shiftConfig).thenReturn(flowOf(config))
        shiftUseCase.stub {
            on { getCurrentShiftConfig() } doReturn Result.success(config)
            on { recognizeShiftsInEvents(any()) } doReturn Result.success(emptyList())
        }
        val dimRepo = mock<DimRuleRepository>()
        whenever(dimRepo.rules).thenReturn(dimRegeln)
        val dimUseCase = DimRuleUseCase(dimRepo)
        val overlay = mock<DimOverlayPrefs>()
        whenever(overlay.toggles).thenReturn(flowOf(DimOverlayPrefs.Toggles(dimEnabled = true)))
        val dnd = mock<DndPrefs>()
        whenever(dnd.toggles).thenReturn(flowOf(dndToggles))
        whenever(dnd.shiftExcludedShifts).thenReturn(flowOf(setOf("Ruf")))
        whenever(dnd.onCallCutoffMinutes).thenReturn(flowOf(6 * 60))
        val hue = mock<IHueConfigRepository>()
        whenever(hue.getConfiguration()).thenReturn(
            flowOf(HueConfiguration(bridgeIp = "1.2.3.4", username = "u", isConfigured = true))
        )
        hue.stub { on { getScheduleRules() } doReturn hueRegeln }
        w.spanStore = mock<ShiftSpanStore>()
        w.spanStore.stub { onBlocking { spansNow() } doReturn spannen }
        w.hueUseCase = mock<HueRuleUseCase>()

        w.vm = ShiftViewModel(
            shiftUseCase = shiftUseCase,
            alarmUseCase = mock<IAlarmUseCase>(),
            calendarStateHolder = w.holder,
            errorHandler = mock<ErrorHandler>(),
            dimRuleUseCase = dagger.Lazy { w.angefasst++; dimUseCase },
            hueRuleUseCase = dagger.Lazy { w.hueGraphGebaut++; w.hueUseCase },
            armierer = mock<ZeitkettenArmierer>(),
            dndPrefs = dagger.Lazy { w.angefasst++; dnd },
            shiftSpanStore = dagger.Lazy { w.spanStore },
            dimOverlayPrefs = dagger.Lazy { w.angefasst++; overlay },
            hueConfigRepository = dagger.Lazy { w.angefasst++; hue }
        )
        return w
    }

    /** Abonniert die Quellen (sie sind `WhileSubscribed`) und arbeitet das Lesen ab. */
    private fun TestScope.lese(w: Welt): TagesleisteQuellen {
        backgroundScope.launch { w.vm.tagesleisteQuellen.collect { } }
        runCurrent()
        advanceUntilIdle()
        val stand = w.vm.tagesleisteQuellen.value
        assertNotNull("Nach dem Lesen muss ein Stand vorliegen", stand)
        return stand!!
    }

    private fun TagesleisteQuellen.staende(): Map<Spur, SpurStand> = Spur.entries.associateWith { stand(it) }

    private fun alleBekannt() = Spur.entries.associateWith { SpurStand.BEKANNT }

    // --- Der Kern: Lesefehler wird sichtbar, nicht leer -------------------------------------

    @Test
    fun `Lesefehler bei den Hue-Regeln macht NUR die Licht-Spur unbekannt - nicht leer`() = runTest(dispatcher) {
        val q = lese(welt(hueRegeln = Result.failure(IOException("hue_rules nicht lesbar"))))

        assertEquals("Hue-Regeln unlesbar heisst Licht unbekannt, nicht 'keine Regel'", Quelle.Unbekannt, q.licht)
        assertEquals(
            alleBekannt() + (Spur.LICHT to SpurStand.UNBEKANNT),
            q.staende()
        )
        // Die uebrigen Quellen sind unberuehrt - ein Fehler schaltet nicht alle Spuren ab.
        assertTrue(q.dimmer is Quelle.Bekannt)
        assertTrue(q.nichtStoeren is Quelle.Bekannt)
        assertTrue(q.spannen is Quelle.Bekannt)
    }

    @Test
    fun `Lesefehler bei den Dimm-Regeln macht Dimmen unbekannt - und Nicht stoeren nur, solange es dem Dimmer folgt`() =
        runTest(dispatcher) {
            val kaputt = flow<List<DimRule>> { throw IOException("dim_rules nicht lesbar") }

            val folgtDemDimmer = lese(welt(dimRegeln = kaputt, dndToggles = toggles))
            assertEquals("Dimm-Regeln unlesbar heisst Dimmen unbekannt, nicht 'kein Dimmen'", Quelle.Unbekannt, folgtDemDimmer.dimmer)
            assertEquals(
                alleBekannt() + (Spur.DIMMEN to SpurStand.UNBEKANNT) + (Spur.NICHT_STOEREN to SpurStand.UNBEKANNT),
                folgtDemDimmer.staende()
            )
        }

    @Test
    fun `Dimm-Lesefehler laesst Nicht stoeren bekannt, wenn es nicht dem Dimmer folgt`() = runTest(dispatcher) {
        val kaputt = flow<List<DimRule>> { throw IOException("dim_rules nicht lesbar") }
        val nurDienstzeit = DndPrefs.Toggles(followDimmerEnabled = false, duringShiftEnabled = true)

        val q = lese(welt(dimRegeln = kaputt, dndToggles = nurDienstzeit))

        assertEquals(alleBekannt() + (Spur.DIMMEN to SpurStand.UNBEKANNT), q.staende())
        assertTrue("Hue und Spannen sind vom Dimm-Fehler unberuehrt", q.licht is Quelle.Bekannt && q.spannen is Quelle.Bekannt)
    }

    @Test
    fun `Lesefehler bei den Spannen macht jede Spur unbekannt - ihre Dienstzeit legt den Weckpunkt fest`() = runTest(dispatcher) {
        val q = lese(welt(spannen = Result.failure(IOException("shift_spans nicht lesbar"))))

        assertEquals(Quelle.Unbekannt, q.spannen)
        assertEquals(Spur.entries.associateWith { SpurStand.UNBEKANNT }, q.staende())
        // Die anderen Quellen wurden trotzdem gelesen und sind bekannt.
        assertTrue(q.dimmer is Quelle.Bekannt && q.nichtStoeren is Quelle.Bekannt && q.licht is Quelle.Bekannt)
    }

    @Test
    fun `gesunde Quellen kommen mit ihren Werten an, auch der Ruf-Cutoff`() = runTest(dispatcher) {
        val w = welt()
        val q = lese(w)

        assertEquals(alleBekannt(), q.staende())
        val dimmer = (q.dimmer as Quelle.Bekannt).wert
        assertEquals(true, dimmer.an)
        assertEquals(listOf(dimRegel), dimmer.regeln)
        val dnd = (q.nichtStoeren as Quelle.Bekannt).wert
        assertEquals(toggles, dnd.toggles)
        assertEquals(setOf("Ruf"), dnd.ausgenommeneSchichten)
        assertEquals(6 * 60, dnd.rufCutoffMinuten)
        val licht = (q.licht as Quelle.Bekannt).wert
        assertEquals(true, licht.konfiguriert)
        assertEquals(listOf(hueRegel), licht.regeln)
        assertEquals(listOf(spanne), (q.spannen as Quelle.Bekannt).wert)
        assertEquals("Das Abo baut den Hue-Graphen nicht", 0, w.hueGraphGebaut)
    }

    @Test
    fun `die Vorlauf-Rechnung der Licht-Quelle greift erst beim Aufruf auf den Hue-Use-Case zu`() = runTest(dispatcher) {
        val w = welt()
        val licht = (lese(w).licht as Quelle.Bekannt).wert
        assertEquals(0, w.hueGraphGebaut)
        whenever(w.hueUseCase.getPreAlarmSunriseLeadMinutes(any(), any(), anyOrNullLong(), anyOrNullStand())).thenReturn(7)

        assertEquals(7, licht.vorlaufMinuten(listOf(hueRegel), "AD1", null, null))
        assertEquals(1, w.hueGraphGebaut)
    }

    private fun anyOrNullLong(): Long? = org.mockito.kotlin.anyOrNull()
    private fun anyOrNullStand(): com.github.f1rlefanz.cf_alarmfortimeoffice.tageslicht.TageslichtStand? =
        org.mockito.kotlin.anyOrNull()

    // --- Wann gelesen wird -----------------------------------------------------------------

    @Test
    fun `ohne Abo wird nichts gelesen, und vor dem ersten Lesen gibt es keinen Stand (null, nicht unbekannt)`() =
        runTest(dispatcher) {
            val w = welt()
            advanceUntilIdle()

            assertEquals("Ohne Abo darf keine Quelle angefasst werden (nicht beim App-Start)", 0, w.angefasst)
            // Der Span-Store selbst wird schon von `aktualisiereLetztenSchichtStand()` angefasst (andere
            // Methode, `letzterStand()`); die Tagesleiste liest `spansNow()` erst mit dem Abo.
            verifyBlocking(w.spanStore, never()) { spansNow() }
            assertNull("null heisst noch nicht gelesen - Unbekannt waere ein Lesefehler", w.vm.tagesleisteQuellen.value)

            lese(w)
            assertTrue("Mit dem Abo werden die Quellen gelesen", w.angefasst > 0)
            verifyBlocking(w.spanStore) { spansNow() }
        }

    @Test
    fun `eine neue Eventliste liest die Spannen neu`() = runTest(dispatcher) {
        val w = welt()
        val q1 = lese(w)
        assertEquals(listOf(spanne), (q1.spannen as Quelle.Bekannt).wert)

        val neu = ShiftSpan("AD1", startTime = 5_000L, endTime = 6_000L, alarmTriggerTime = 4_500L)
        w.spanStore.stub { onBlocking { spansNow() } doReturn Result.success(listOf(neu)) }
        w.holder.updateEvents(
            listOf(
                CalendarEvent(
                    id = "e1", title = "AD1",
                    startTime = LocalDateTime.of(2026, 8, 20, 6, 0), endTime = LocalDateTime.of(2026, 8, 20, 14, 0),
                    calendarId = "cal"
                )
            ),
            complete = true
        )
        runCurrent()
        advanceUntilIdle()

        assertEquals(listOf(neu), ((w.vm.tagesleisteQuellen.value)!!.spannen as Quelle.Bekannt).wert)
    }

    // --- Die Abhaengigkeiten der Spuren (rein) ----------------------------------------------

    private val bekanntDimmer = Quelle.Bekannt(
        com.github.f1rlefanz.cf_alarmfortimeoffice.tagesleiste.DimmerQuelle(true, emptyList(), DimRuleUseCase(mock<DimRuleRepository>()))
    )
    private fun dnd(folgt: Boolean) = Quelle.Bekannt(
        com.github.f1rlefanz.cf_alarmfortimeoffice.tagesleiste.NichtStoerenQuelle(
            DndPrefs.Toggles(followDimmerEnabled = folgt, duringShiftEnabled = true), emptySet(), 300
        )
    )
    private val bekanntLicht = Quelle.Bekannt(
        com.github.f1rlefanz.cf_alarmfortimeoffice.tagesleiste.LichtQuelle(true, emptyList()) { _, _, _, _ -> null }
    )
    private val bekannteSpannen = Quelle.Bekannt(emptyList<ShiftSpan>())

    @Test
    fun `jede Spur ist genau von ihren Quellen abhaengig`() {
        fun quellen(
            dimmer: Quelle<com.github.f1rlefanz.cf_alarmfortimeoffice.tagesleiste.DimmerQuelle> = bekanntDimmer,
            nichtStoeren: Quelle<com.github.f1rlefanz.cf_alarmfortimeoffice.tagesleiste.NichtStoerenQuelle> = dnd(folgt = true),
            licht: Quelle<com.github.f1rlefanz.cf_alarmfortimeoffice.tagesleiste.LichtQuelle> = bekanntLicht,
            spannen: Quelle<List<ShiftSpan>> = bekannteSpannen
        ) = TagesleisteQuellen(dimmer, nichtStoeren, licht, spannen)

        val unbekannt = SpurStand.UNBEKANNT

        assertEquals(alleBekannt(), quellen().staende())
        assertEquals(alleBekannt() + (Spur.DIMMEN to unbekannt) + (Spur.NICHT_STOEREN to unbekannt), quellen(dimmer = Quelle.Unbekannt).staende())
        assertEquals(alleBekannt() + (Spur.DIMMEN to unbekannt), quellen(dimmer = Quelle.Unbekannt, nichtStoeren = dnd(folgt = false)).staende())
        assertEquals(alleBekannt() + (Spur.NICHT_STOEREN to unbekannt), quellen(nichtStoeren = Quelle.Unbekannt).staende())
        assertEquals(alleBekannt() + (Spur.LICHT to unbekannt), quellen(licht = Quelle.Unbekannt).staende())
        assertEquals(Spur.entries.associateWith { unbekannt }, quellen(spannen = Quelle.Unbekannt).staende())
    }

    // --- Fangstellen ------------------------------------------------------------------------

    @Test
    fun `eine CancellationException ist kein Lesefehler und wird nicht zu unbekannt`() = runTest(dispatcher) {
        try {
            flow<Quelle<Int>> { throw CancellationException("abgebrochen") }.fehlerAlsUnbekannt("t").toList()
            fail("Der Flow-Fang darf eine CancellationException nicht schlucken")
        } catch (erwartet: CancellationException) {
            // richtig
        }
        try {
            Result.failure<Int>(CancellationException("abgebrochen")).alsQuelle("t") { it }
            fail("Der Result-Fang darf eine CancellationException nicht schlucken")
        } catch (erwartet: CancellationException) {
            // richtig
        }
        val geworfen = flow<Quelle<Int>> { throw IOException("kaputt") }.fehlerAlsUnbekannt("t").toList()
        assertSame(Quelle.Unbekannt, geworfen.single())
    }
}
