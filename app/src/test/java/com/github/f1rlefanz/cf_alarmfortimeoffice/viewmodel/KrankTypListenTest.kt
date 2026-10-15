package com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel

import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimOverlayPrefs
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimRuleUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimScheduleUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.ZeitkettenArmierer
import com.github.f1rlefanz.cf_alarmfortimeoffice.dnd.DndPrefs
import com.github.f1rlefanz.cf_alarmfortimeoffice.dnd.DndScheduleUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftConfig
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftDefinition
import com.github.f1rlefanz.cf_alarmfortimeoffice.shift.ShiftSpanStore
import com.github.f1rlefanz.cf_alarmfortimeoffice.tageslicht.ITageslichtRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.tageslicht.TageslichtEinstellungen
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.IAlarmUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.IShiftUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
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
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.time.LocalTime

/**
 * #142: ein Krankmeldungs-Typ hat keine Dienstzeit und stellt nie einen Wecker. Als Regelmuster in
 * Dimmer und "Nicht stoeren" waehlbar, wuerde er nie greifen - die Listen filtern ihn heraus.
 * (Die Wecker-Auswahl prueft `Pruefrunde8ManuellerWeckerTest`, die Statuszeile `SchichtFolgenTest`.)
 */
@OptIn(ExperimentalCoroutinesApi::class) // Dispatchers.setMain/resetMain, advanceUntilIdle, runCurrent
class KrankTypListenTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private val frueh = ShiftDefinition(id = "early", name = "Frueh", keywords = listOf("F"), alarmTime = LocalTime.of(5, 0))
    private val krank = ShiftDefinition(
        id = "sick",
        name = "Krank",
        keywords = listOf("K"),
        alarmTime = LocalTime.MIDNIGHT,
        isSickDay = true
    )

    private fun shiftUseCase(): IShiftUseCase {
        val useCase = mock<IShiftUseCase>()
        whenever(useCase.shiftConfig).thenReturn(flowOf(ShiftConfig(definitions = listOf(frueh, krank))))
        return useCase
    }

    private fun TestScope.gesammelt(flow: StateFlow<List<String>>): List<String> {
        backgroundScope.launch { flow.collect { } }
        runCurrent()
        advanceUntilIdle()
        return flow.value
    }

    @Test
    fun `Dimmer-Regeleditor bietet keinen Krankmeldungs-Typ als Schichtmuster an`() = runTest(dispatcher) {
        val ruleUseCase = mock<DimRuleUseCase>()
        whenever(ruleUseCase.rules).thenReturn(flowOf(emptyList()))
        val prefs = mock<DimOverlayPrefs>()
        whenever(prefs.toggles).thenReturn(flowOf(DimOverlayPrefs.Toggles(dimEnabled = true)))
        val tageslicht = mock<ITageslichtRepository>()
        whenever(tageslicht.einstellungen).thenReturn(flowOf(TageslichtEinstellungen.LEER))
        val vm = DimmerRulesViewModel(
            ruleUseCase, shiftUseCase(), mock<DimScheduleUseCase>(), mock<ZeitkettenArmierer>(),
            prefs, mock<ShiftSpanStore>(), mock<IAlarmUseCase>(), tageslicht
        )

        assertEquals(listOf("Frueh"), gesammelt(vm.shiftNames))
    }

    @Test
    fun `Nicht-stoeren-Ausnahmen bieten keinen Krankmeldungs-Typ an`() = runTest(dispatcher) {
        val prefs = mock<DndPrefs>()
        whenever(prefs.toggles).thenReturn(flowOf(DndPrefs.Toggles(followDimmerEnabled = false, duringShiftEnabled = true)))
        whenever(prefs.shiftExcludedShifts).thenReturn(flowOf(emptySet()))
        whenever(prefs.onCallCutoffMinutes).thenReturn(flowOf(DndPrefs.DEFAULT_ONCALL_CUTOFF_MIN))
        whenever(prefs.policy).thenReturn(flowOf(DndPrefs.Policy()))
        val vm = DndViewModel(prefs, mock<DndScheduleUseCase>(), shiftUseCase())

        assertEquals(listOf("Frueh"), gesammelt(vm.shiftNames))
    }
}
