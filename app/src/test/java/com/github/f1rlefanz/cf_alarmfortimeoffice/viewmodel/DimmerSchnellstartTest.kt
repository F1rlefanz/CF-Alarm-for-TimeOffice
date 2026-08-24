package com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel

import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimAnchor
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimOverlayPrefs
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimRule
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimRuleUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimScheduleUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.dnd.DndScheduleUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftConfig
import com.github.f1rlefanz.cf_alarmfortimeoffice.shift.ShiftSpanStore
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.IShiftUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.DimmerRulesViewModel.Companion.baueVorlagenRegel
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.DimmerRulesViewModel.SchnellstartVorlage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * Der Schnellstart legt eine SICHTBARE Regel an - er ist keine zweite, unsichtbare Fenster-Quelle.
 *
 * HERGANG: Bis zum Ein-Modell-Umbau gab es neben den Regeln zwei eingebaute Quellen (Wellness und
 * Nacht-Standard). Beide wirkten, ohne in der Regelliste zu stehen; wer wissen wollte, warum um
 * 07:00 noch gedimmt wird, fand dort keine Regel dazu. Der Komfort, den sie boten, ist echter
 * Bedarf - die Unsichtbarkeit war der Fehler. Deshalb erzeugt jede Vorlage hier eine gewoehnliche
 * [DimRule] ueber denselben Speicherweg wie der Regel-Editor.
 *
 * Diese Tests halten drei Dinge fest, deren Bruch die Vorlagen still wertlos machen wuerde: die
 * Fenster-ANKER (eine falsche Ankerwahl dimmt zur falschen Zeit oder gar nicht), den SPEICHERWEG
 * ueber `saveRule` samt Armierung BEIDER Zeitketten, und dass der Nutzer die entstandene Regel
 * anschliessend wirklich zu sehen bekommt.
 */
@OptIn(ExperimentalCoroutinesApi::class) // Dispatchers.setMain/resetMain, advanceUntilIdle
class DimmerSchnellstartTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private class Fixture(
        val vm: DimmerRulesViewModel,
        val ruleUseCase: DimRuleUseCase,
        val dimSchedule: DimScheduleUseCase,
        val dndSchedule: DndScheduleUseCase
    )

    private fun buildFixture(): Fixture {
        val prefs = mock<DimOverlayPrefs>()
        val shiftUseCase = mock<IShiftUseCase>()
        whenever(shiftUseCase.shiftConfig).thenReturn(flowOf(ShiftConfig()))

        val ruleUseCase = mock<DimRuleUseCase>()
        // Der Fluss bleibt bewusst leer: er bildet den echten DataStore nach, der die frisch
        // geschriebene Regel erst spaeter emittiert. Genau diese Luecke muss `ruleById`
        // ueberbruecken - sonst oeffnet der Editor leer.
        whenever(ruleUseCase.rules).thenReturn(flowOf(emptyList()))

        val dimSchedule = mock<DimScheduleUseCase>()
        val dndSchedule = mock<DndScheduleUseCase>()

        return Fixture(
            vm = DimmerRulesViewModel(
                ruleUseCase, shiftUseCase, dimSchedule, { dndSchedule }, prefs, mock<ShiftSpanStore>()
            ),
            ruleUseCase = ruleUseCase,
            dimSchedule = dimSchedule,
            dndSchedule = dndSchedule
        )
    }

    // --- Die Fenster-Anker der drei Vorlagen ---------------------------------------------------

    /**
     * Das ist der komplette abgeloeste Nacht-Standard als EIN Fenster: Der CLOCK-Start macht es
     * schicht-unabhaengig (gilt fuer jede Kalendernacht), der Ende-Anker nimmt das Minimum aus
     * Weckzeit und 07:00. Waere das Ende [DimAnchor.ALARM], dimmte die Regel vor einem Spaetdienst
     * bis mittags - der gemeldete Fehler vom 23.08.2026. Waere es [DimAnchor.CLOCK], ueberdimmte
     * sie jeden frueheren Wecker.
     */
    @Test
    fun `Nacht-Dimmen ist eine Universal-Regel mit genau einem Fenster 22 Uhr bis Weckzeit-sonst-7`() {
        val regel = baueVorlagenRegel(SchnellstartVorlage.NACHT_DIMMEN, "Nacht-Dimmen", null)

        assertNotNull(regel)
        assertEquals(DimRule.SHIFT_UNIVERSAL, regel!!.shiftPattern)
        assertTrue(regel.enabled)
        assertEquals(1, regel.windows.size)
        val fenster = regel.windows.single()
        assertEquals(DimAnchor.CLOCK, fenster.startAnchor)
        assertEquals(22 * 60, fenster.startClockMinutes)
        assertEquals(DimAnchor.ALARM_SONST_CLOCK, fenster.endAnchor)
        assertEquals(7 * 60, fenster.endClockMinutes)
    }

    /**
     * ZWEI Fenster an EINEM Kalendertag - die ausdrueckliche Anforderung fuer den Nachtdienst:
     * Vormittagsschlaf ab Schichtende bis 14:00, Nickerchen ab 15:00 bis zur Weckzeit des
     * naechsten Dienstes. Ein Zusammenziehen auf ein Fenster waere kein Detail, sondern ein
     * anderes Verhalten: die Stunde dazwischen ist bewusst hell.
     */
    @Test
    fun `Nachtdienst-Rhythmus legt zwei Fenster auf die gewaehlte Schicht`() {
        val regel = baueVorlagenRegel(
            SchnellstartVorlage.NACHTDIENST_RHYTHMUS,
            "Nachtdienst-Rhythmus: ND",
            "ND"
        )

        assertNotNull(regel)
        assertEquals("ND", regel!!.shiftPattern)
        assertEquals(2, regel.windows.size)

        val vormittagsschlaf = regel.windows[0]
        assertEquals(DimAnchor.SHIFT_END, vormittagsschlaf.startAnchor)
        assertEquals(0, vormittagsschlaf.startOffsetMinutes)
        assertEquals(DimAnchor.CLOCK, vormittagsschlaf.endAnchor)
        assertEquals(14 * 60, vormittagsschlaf.endClockMinutes)

        val nickerchen = regel.windows[1]
        assertEquals(DimAnchor.CLOCK, nickerchen.startAnchor)
        assertEquals(15 * 60, nickerchen.startClockMinutes)
        assertEquals(DimAnchor.ALARM, nickerchen.endAnchor)
        assertEquals(0, nickerchen.endOffsetMinutes)
    }

    /**
     * Die LEERE Fensterliste ist bedeutungstragend ("an diesen Tagen NICHT dimmen") und
     * unterscheidet sich von "keine Regel": nur weil die Regel EXISTIERT, verdraengt sie an ihren
     * Tagen die UNIVERSAL-Nachtregel. Wer sie beim Anlegen wegoptimiert, bekommt das Gegenteil.
     */
    @Test
    fun `Schicht ausnehmen legt eine Regel MIT Schichtmuster und OHNE Fenster an`() {
        val regel = baueVorlagenRegel(SchnellstartVorlage.SCHICHT_AUSNEHMEN, "Ohne Dimmen: ND", "ND")

        assertNotNull(regel)
        assertEquals("ND", regel!!.shiftPattern)
        assertTrue(regel.enabled)
        assertTrue(regel.windows.isEmpty())
    }

    /**
     * Ohne Schichtnamen entsteht NICHTS. Eine Regel auf leerem Muster traefe keine Schicht, wuerde
     * vom Umbenennungs-Nachzug nicht erfasst und stuende trotzdem als aktiv in der Liste - genau
     * die Fehlerklasse "angezeigt, wirkt nicht".
     */
    @Test
    fun `Schichtbezogene Vorlagen liefern ohne Schichtnamen keine Regel`() {
        assertNull(baueVorlagenRegel(SchnellstartVorlage.NACHTDIENST_RHYTHMUS, "x", null))
        assertNull(baueVorlagenRegel(SchnellstartVorlage.NACHTDIENST_RHYTHMUS, "x", "   "))
        assertNull(baueVorlagenRegel(SchnellstartVorlage.SCHICHT_AUSNEHMEN, "x", null))
        // Die Nacht-Vorlage braucht keine Schicht - sie ist universal.
        assertNotNull(baueVorlagenRegel(SchnellstartVorlage.NACHT_DIMMEN, "x", null))
    }

    // --- Der Speicherweg ----------------------------------------------------------------------

    /**
     * Der Schnellstart geht ueber `saveRule` und damit ueber `armiereFensterkettenNeu()` - eine
     * neu angelegte Regel verschiebt Dimm-Fenster, und "Nicht stoeren" im Modus "folgt dem
     * Dimmer" hat keine andere Fensterquelle. Die Reihenfolge Dimmer -> DND ist tragend (DND
     * liest die Dimm-Zeitleiste live), siehe [DimmerDndNachzugTest].
     */
    @Test
    fun `Schnellstart speichert die Regel und armiert beide Ketten in dieser Reihenfolge`() =
        runTest(dispatcher) {
            val f = buildFixture()

            f.vm.legeVorlageAn(SchnellstartVorlage.NACHT_DIMMEN, "Nacht-Dimmen")
            advanceUntilIdle()

            val captor = argumentCaptor<DimRule>()
            verify(f.ruleUseCase).saveRule(captor.capture())
            assertEquals(DimRule.SHIFT_UNIVERSAL, captor.firstValue.shiftPattern)

            val reihenfolge = inOrder(f.ruleUseCase, f.dimSchedule, f.dndSchedule)
            reihenfolge.verify(f.ruleUseCase).saveRule(any())
            reihenfolge.verify(f.dimSchedule).enable()
            reihenfolge.verify(f.dndSchedule).enable()
        }

    /** Kein Schichtname, kein Schreibvorgang - und erst recht keine Neuarmierung. */
    @Test
    fun `Schnellstart ohne Schichtnamen schreibt nichts`() = runTest(dispatcher) {
        val f = buildFixture()

        f.vm.legeVorlageAn(SchnellstartVorlage.SCHICHT_AUSNEHMEN, "Ohne Dimmen", null)
        advanceUntilIdle()

        verify(f.ruleUseCase, never()).saveRule(any())
        verify(f.dimSchedule, never()).enable()
        assertNull(f.vm.neueRegelId.value)
    }

    // --- Der Nutzer sieht, was entstanden ist --------------------------------------------------

    /**
     * Der Bildschirm oeffnet auf [DimmerRulesViewModel.neueRegelId] hin den Editor der neuen
     * Regel. Damit dort nicht ein LEERES Formular steht, muss `ruleById` die Regel schon kennen,
     * bevor der DataStore-Fluss sie emittiert hat - der Fluss ist in dieser Fixture bewusst leer.
     */
    @Test
    fun `nach dem Anlegen ist die neue Regel sofort ueber ihre Kennung auffindbar`() =
        runTest(dispatcher) {
            val f = buildFixture()

            f.vm.legeVorlageAn(SchnellstartVorlage.NACHT_DIMMEN, "Nacht-Dimmen")
            advanceUntilIdle()

            val id = f.vm.neueRegelId.value
            assertNotNull("Ohne Kennung oeffnet der Bildschirm den Editor nie", id)
            assertTrue(f.vm.rules.value.isEmpty())
            val gefunden = f.vm.ruleById(id)
            assertNotNull("Der Editor haette ein leeres Formular gezeigt", gefunden)
            assertEquals(id, gefunden!!.id)
            assertEquals("Nacht-Dimmen", gefunden.name)
        }

    /**
     * Das Signal ist ein EREIGNIS, kein Zustand: nach dem Abmelden darf ein Neuzusammensetzen des
     * Bildschirms den Editor nicht ein zweites Mal aufreissen.
     */
    @Test
    fun `das Signal wird nach dem Oeffnen zurueckgesetzt`() = runTest(dispatcher) {
        val f = buildFixture()

        f.vm.legeVorlageAn(SchnellstartVorlage.NACHT_DIMMEN, "Nacht-Dimmen")
        advanceUntilIdle()
        assertNotNull(f.vm.neueRegelId.value)

        f.vm.neueRegelGeoeffnet()

        assertNull(f.vm.neueRegelId.value)
    }
}
