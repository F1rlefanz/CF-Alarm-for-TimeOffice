package com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel

import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimAnchor
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimOverlayPrefs
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimRule
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimRuleUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimScheduleUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimWindow
import com.github.f1rlefanz.cf_alarmfortimeoffice.dnd.DndScheduleUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftConfig
import com.github.f1rlefanz.cf_alarmfortimeoffice.shift.ShiftSpanStore
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.IShiftUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * Jede Aenderung, die DIMM-FENSTERGRENZEN verschiebt, muss BEIDE Zeitketten neu armieren -
 * erst den Dimmer, dann DND.
 *
 * HERGANG (23.08.2026, am Fairphone 6 reproduziert): Der Nutzer wachte um 08:48 auf und fand
 * Dimmer UND "Nicht stoeren" aktiv. Ursache des Dimmens war die Konfiguration (Nacht-Standard
 * endet an Schichttagen an der WECKZEIT, an dem Tag 12:30 wegen Spaetdienst). Er stellte daraufhin
 * um 09:59 um: Regeln AN, Nacht-Standard AUS. Der Dimmer rechnete sofort neu und schaltete ab -
 * im Datei-Log steht `Naechster Dimm-Wechsel geplant: 22:00`. Danach kam KEINE einzige
 * `CFAlarm.Dnd`-Zeile mehr, und `settings get global zen_mode` lieferte weiterhin 1. "Nicht
 * stoeren" blieb bis zum naechsten DND-Tick um 12:30 stehen - knapp drei Stunden ohne Grund.
 *
 * WARUM DAS PASSIEREN KONNTE: DND-Modus 1 ("folgt dem Dimmer") hat keine eigene Fensterquelle, er
 * liest die Dimm-Zeitleiste ueber `DimScheduleUseCase.previewTimelineWithStatus()`. Acht Setter in
 * den beiden Dimmer-ViewModels riefen aber nur `dimSchedule.enable()`; `DndScheduleUseCase` war in
 * keinem der beiden ueberhaupt injiziert. Die Invariante war zu dem Zeitpunkt bereits im Code
 * ausformuliert (`ShiftViewModel`: "ein geaendertes Dimm-Fenster zieht die DND-Kette sehr wohl
 * mit") - umgesetzt war sie nur bei der Schicht-Umbenennung und im Konfigurations-Import.
 *
 * WARUM ES NICHT NUR KOSMETIK IST: Die Selbstheilung kommt spaetestens mit der 6h-Wartung. In
 * einer Rufbereitschaftsnacht sind drei Stunden ungewolltes "Nicht stoeren" aber genau die
 * verlorenen Anrufe, gegen die der Rufbereitschaft-Cutoff ueberhaupt gebaut wurde.
 *
 * Die Gegenprobe - reine DARSTELLUNGS-Setter duerfen DND NICHT anwerfen - steht in
 * [DimmerViewModelRenderSettersTest]: eine Farbaenderung verschiebt keine Fenstergrenze, und ein
 * `enable()` dort waere eine komplette zusaetzliche Fensterberechnung ohne jede Wirkung.
 */
@OptIn(ExperimentalCoroutinesApi::class) // Dispatchers.setMain/resetMain, advanceUntilIdle
class DimmerDndNachzugTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private class Fixture(
        val dimmer: DimmerViewModel,
        val regeln: DimmerRulesViewModel,
        val dimSchedule: DimScheduleUseCase,
        val dndSchedule: DndScheduleUseCase
    )

    private fun buildFixture(): Fixture {
        val prefs = mock<DimOverlayPrefs>()
        whenever(prefs.toggles).thenReturn(
            flowOf(
                DimOverlayPrefs.Toggles(
                    wellnessEnabled = false,
                    rulesEnabled = false,
                    nightDefaultEnabled = true
                )
            )
        )
        whenever(prefs.strength).thenReturn(flowOf(DimOverlayPrefs.DEFAULT_STRENGTH))
        whenever(prefs.warmth).thenReturn(flowOf(DimOverlayPrefs.DEFAULT_WARMTH))
        whenever(prefs.windDownMinutes).thenReturn(flowOf(DimOverlayPrefs.DEFAULT_WINDDOWN_MIN))
        whenever(prefs.nightDefaultStartMinutes)
            .thenReturn(flowOf(DimOverlayPrefs.DEFAULT_NIGHT_DEFAULT_START_MIN))
        whenever(prefs.nightDefaultFreeEndMinutes)
            .thenReturn(flowOf(DimOverlayPrefs.DEFAULT_NIGHT_DEFAULT_FREE_END_MIN))
        whenever(prefs.nightDefaultExcludedShifts).thenReturn(flowOf(emptySet()))
        whenever(prefs.nightDefaultStrength).thenReturn(flowOf(DimOverlayPrefs.DEFAULT_STRENGTH))
        whenever(prefs.nightDefaultWarmth).thenReturn(flowOf(DimOverlayPrefs.DEFAULT_WARMTH))

        val shiftUseCase = mock<IShiftUseCase>()
        whenever(shiftUseCase.shiftConfig).thenReturn(flowOf(ShiftConfig()))

        val ruleUseCase = mock<DimRuleUseCase>()
        whenever(ruleUseCase.rules).thenReturn(flowOf(emptyList()))

        val dimSchedule = mock<DimScheduleUseCase>()
        val dndSchedule = mock<DndScheduleUseCase>()

        return Fixture(
            dimmer = DimmerViewModel(prefs, dimSchedule, { dndSchedule }, shiftUseCase),
            regeln = DimmerRulesViewModel(
                ruleUseCase, shiftUseCase, dimSchedule, { dndSchedule }, prefs, mock<ShiftSpanStore>()
            ),
            dimSchedule = dimSchedule,
            dndSchedule = dndSchedule
        )
    }

    /**
     * Die Reihenfolge ist tragend, nicht Geschmack: `DndScheduleUseCase.computeWindows()` liest im
     * Modus 1 die Dimm-Zeitleiste LIVE. `dataStore.edit {}` kehrt erst nach persistiertem Write
     * zurueck, das anschliessende DND-`enable()` sieht also den neuen Stand - aber nur, wenn der
     * Dimmer vorher dran war.
     */
    private suspend fun Fixture.erwarteBeideKettenInReihenfolge() {
        val reihenfolge = inOrder(dimSchedule, dndSchedule)
        reihenfolge.verify(dimSchedule).enable()
        reihenfolge.verify(dndSchedule).enable()
    }

    @Test
    fun `Regeln-Schalter armiert beide Ketten - der Fall vom 23 08 2026`() = runTest(dispatcher) {
        val f = buildFixture()

        f.dimmer.setRulesEnabled(true)
        advanceUntilIdle()

        f.erwarteBeideKettenInReihenfolge()
    }

    @Test
    fun `Nacht-Standard-Schalter armiert beide Ketten - die zweite Haelfte desselben Falls`() = runTest(dispatcher) {
        val f = buildFixture()

        f.dimmer.setNightDefaultEnabled(false)
        advanceUntilIdle()

        f.erwarteBeideKettenInReihenfolge()
    }

    @Test
    fun `Wellness-Schalter armiert beide Ketten`() = runTest(dispatcher) {
        val f = buildFixture()

        f.dimmer.setWellnessEnabled(true)
        advanceUntilIdle()

        f.erwarteBeideKettenInReihenfolge()
    }

    @Test
    fun `Nacht-Standard-Startzeit armiert beide Ketten`() = runTest(dispatcher) {
        val f = buildFixture()

        f.dimmer.setNightDefaultStartMinutes(21 * 60)
        advanceUntilIdle()

        f.erwarteBeideKettenInReihenfolge()
    }

    @Test
    fun `Nacht-Standard-Ende an freien Tagen armiert beide Ketten`() = runTest(dispatcher) {
        val f = buildFixture()

        f.dimmer.setNightDefaultFreeEndMinutes(8 * 60)
        advanceUntilIdle()

        f.erwarteBeideKettenInReihenfolge()
    }

    /**
     * Die Ausnahmenliste ist ein Eingang von `computeWindows()` (`isExcluded`) - ein Toggle daran
     * verschiebt die Fenster genauso wie eine geaenderte Regel. Dieselbe Begruendung steht in
     * `ShiftViewModel` fuer den Umbenennungs-Nachzug.
     */
    @Test
    fun `Ausnahme-Schicht des Nacht-Standards armiert beide Ketten`() = runTest(dispatcher) {
        val f = buildFixture()

        f.dimmer.toggleNightDefaultExcludedShift("Nachtschicht")
        advanceUntilIdle()

        f.erwarteBeideKettenInReihenfolge()
    }

    @Test
    fun `Wind-down-Dauer armiert beide Ketten`() = runTest(dispatcher) {
        val f = buildFixture()

        f.dimmer.setWindDownMinutes(90)
        advanceUntilIdle()

        f.erwarteBeideKettenInReihenfolge()
    }

    @Test
    fun `Regel speichern armiert beide Ketten`() = runTest(dispatcher) {
        val f = buildFixture()

        f.regeln.saveRule(
            DimRule(
                id = "r1",
                name = "Taeglich",
                shiftPattern = DimRule.SHIFT_UNIVERSAL,
                enabled = true,
                windows = listOf(
                    DimWindow(
                        startAnchor = DimAnchor.CLOCK,
                        startClockMinutes = 22 * 60,
                        endAnchor = DimAnchor.CLOCK,
                        endClockMinutes = 6 * 60
                    )
                ),
                strength = 55,
                warmth = 40
            )
        )
        advanceUntilIdle()

        f.erwarteBeideKettenInReihenfolge()
    }

    @Test
    fun `Regel loeschen armiert beide Ketten`() = runTest(dispatcher) {
        val f = buildFixture()

        f.regeln.deleteRule("r1")
        advanceUntilIdle()

        f.erwarteBeideKettenInReihenfolge()
    }
}
