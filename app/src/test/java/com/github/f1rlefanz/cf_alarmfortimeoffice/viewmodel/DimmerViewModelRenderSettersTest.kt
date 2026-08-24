package com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel

import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimOverlayPrefs
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimScheduleUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.dnd.DndScheduleUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftConfig
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
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * Haelt die CLAUDE.md-Invariante fest: **jeder** Setter, der einen [DimOverlayPrefs]-Wert schreibt,
 * muss danach [DimScheduleUseCase.enable] anstossen.
 *
 * Die vier DARSTELLUNGS-Regler (Verdunkelung/Waerme global + Nacht-Standard) taten das bewusst
 * nicht, begruendet mit "der Dienst faerbt reaktiv neu". Das war falsch:
 * `DimAccessibilityService` beobachtet ausschliesslich `DimOverlayPrefs.renderState`, und das liest
 * `KEY_RENDER_STRENGTH`/`KEY_RENDER_WARMTH` mit den globalen Reglern nur als FALLBACK. Die
 * Render-Keys schreibt einzig `setActiveOverlay()`, also nur `applyCurrentState()`/die Vorschau -
 * nach dem ersten Scheduler-Lauf greift der Fallback nie mehr. Ein mitten im laufenden Fenster
 * verstellter Regler blieb dadurch bis zur naechsten Fenstergrenze wirkungslos (typischerweise das
 * Fenster-ENDE am Morgen) - dieselbe Falle wie beim Korrektur-Notification-Toggle (v1.22.1).
 *
 * Das `enable()` folgt UNENTPRELLT, direkt im selben Setter. Eine frueher hier verankerte
 * 300-ms-Entprellung ist entfallen: `DimmerTabContent.CommitOnReleaseSlider` meldet den Wert erst
 * beim Loslassen (`onValueChangeFinished`), also genau einmal pro Reglerbewegung - die Entprellung
 * schuetzte damit vor einem Frame-Sturm, der strukturell nicht mehr entsteht, hing aber am
 * viewModelScope und konnte beim Verlassen der App vor ihrem `delay()` sterben. Dann war der Wert
 * geschrieben, aber nie angewendet - genau die Luecke, die diese Tests zumauern.
 */
@OptIn(ExperimentalCoroutinesApi::class) // Dispatchers.setMain/resetMain, advanceUntilIdle
class DimmerViewModelRenderSettersTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        // viewModelScope laeuft auf Dispatchers.Main.immediate.
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private class Fixture(
        val viewModel: DimmerViewModel,
        val prefs: DimOverlayPrefs,
        val dimSchedule: DimScheduleUseCase,
        val dndSchedule: DndScheduleUseCase
    )

    /**
     * `uiState` wird im Property-Initializer aus neun Prefs-Flows kombiniert - die muessen alle
     * gestubbt sein, sonst scheitert schon die Konstruktion an einem `null`-Flow.
     */
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

        val dimSchedule = mock<DimScheduleUseCase>()
        val dndSchedule = mock<DndScheduleUseCase>()
        val shiftUseCase = mock<IShiftUseCase>()
        whenever(shiftUseCase.shiftConfig).thenReturn(flowOf(ShiftConfig()))

        return Fixture(
            DimmerViewModel(prefs, dimSchedule, { dndSchedule }, shiftUseCase),
            prefs,
            dimSchedule,
            dndSchedule
        )
    }

    @Test
    fun `Verdunkelung schreibt den Wert und wendet ihn auf das laufende Fenster an`() = runTest(dispatcher) {
        val f = buildFixture()

        f.viewModel.setStrength(30)
        advanceUntilIdle()

        verify(f.prefs).setStrength(30)
        verify(f.dimSchedule).enable()
        // Gegenprobe: eine reine FARB-Aenderung verschiebt keine Fenstergrenze - DND haette
        // hier nichts neu zu rechnen. Ohne diese Zeile zementierte der Test die Verschwendung.
        verify(f.dndSchedule, never()).enable()
    }

    @Test
    fun `Waerme schreibt den Wert und wendet ihn auf das laufende Fenster an`() = runTest(dispatcher) {
        val f = buildFixture()

        f.viewModel.setWarmth(70)
        advanceUntilIdle()

        verify(f.prefs).setWarmth(70)
        verify(f.dimSchedule).enable()
        // Gegenprobe: eine reine FARB-Aenderung verschiebt keine Fenstergrenze - DND haette
        // hier nichts neu zu rechnen. Ohne diese Zeile zementierte der Test die Verschwendung.
        verify(f.dndSchedule, never()).enable()
    }

    @Test
    fun `Nacht-Standard-Verdunkelung wirkt ebenfalls sofort`() = runTest(dispatcher) {
        val f = buildFixture()

        f.viewModel.setNightDefaultStrength(45)
        advanceUntilIdle()

        verify(f.prefs).setNightDefaultStrength(45)
        verify(f.dimSchedule).enable()
        // Gegenprobe: eine reine FARB-Aenderung verschiebt keine Fenstergrenze - DND haette
        // hier nichts neu zu rechnen. Ohne diese Zeile zementierte der Test die Verschwendung.
        verify(f.dndSchedule, never()).enable()
    }

    @Test
    fun `Nacht-Standard-Waerme wirkt ebenfalls sofort`() = runTest(dispatcher) {
        val f = buildFixture()

        f.viewModel.setNightDefaultWarmth(65)
        advanceUntilIdle()

        verify(f.prefs).setNightDefaultWarmth(65)
        verify(f.dimSchedule).enable()
        // Gegenprobe: eine reine FARB-Aenderung verschiebt keine Fenstergrenze - DND haette
        // hier nichts neu zu rechnen. Ohne diese Zeile zementierte der Test die Verschwendung.
        verify(f.dndSchedule, never()).enable()
    }

    @Test
    fun `Jeder Regler-Commit wendet seinen Wert an - kein verschluckter Zwischenstand`() = runTest(dispatcher) {
        val f = buildFixture()

        // Drei Reglerbewegungen hintereinander (jedes onValueChangeFinished = ein Commit). Eine
        // Entprellung wuerde die ersten beiden verwerfen; da die UI nur beim Loslassen meldet, ist
        // jeder dieser Commits ein echter Nutzer-Wunsch und muss auch angewendet werden.
        f.viewModel.setStrength(10)
        f.viewModel.setNightDefaultStrength(20)
        f.viewModel.setWarmth(30)
        advanceUntilIdle()

        verify(f.prefs).setStrength(10)
        verify(f.prefs).setNightDefaultStrength(20)
        verify(f.prefs).setWarmth(30)
        verify(f.dimSchedule, times(3)).enable()
    }
}
