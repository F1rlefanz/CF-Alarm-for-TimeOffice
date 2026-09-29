package com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel

import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.connection.HueBridgeConnectionManager
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.BridgeConnectionInfo
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.DiscoveryStatus
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.HueSchedule
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.scheduling.HueSmartScheduler
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.interfaces.IHueBridgeUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.interfaces.IHueLightUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.interfaces.IHueRuleUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.stub
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * Anlegen, Aendern und Loeschen einer Hue-Regel: im Erfolg werden Regelliste und vorgeplante
 * Hue-Jobs nachgezogen, im Fehlschlag steht die Meldung des UseCase (oder ein Standardtext)
 * im Zustand.
 */
@OptIn(ExperimentalCoroutinesApi::class) // Dispatchers.setMain/resetMain, advanceUntilIdle
class HueViewModelRegelAktionTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private val regel = HueSchedule(
        id = "rule_1",
        name = "Frueh",
        shiftPattern = "Frühdienst",
        timeRanges = emptyList()
    )

    private class Fixture(
        val viewModel: HueViewModel,
        val ruleUseCase: IHueRuleUseCase,
        val scheduler: HueSmartScheduler
    )

    private fun buildFixture(
        anlegen: Result<HueSchedule> = Result.success(regel),
        aendern: Result<HueSchedule> = Result.success(regel),
        loeschen: Result<Unit> = Result.success(Unit)
    ): Fixture {
        val bridgeUseCase = mock<IHueBridgeUseCase>()
        whenever(bridgeUseCase.getDiscoveryStatus()).thenReturn(emptyFlow<DiscoveryStatus>())
        bridgeUseCase.stub {
            on { getBridgeConnectionInfo() } doReturn Result.success(BridgeConnectionInfo(isConnected = false))
        }

        val connectionManager = mock<HueBridgeConnectionManager>()
        whenever(connectionManager.connectionStatus).thenReturn(
            MutableStateFlow<HueBridgeConnectionManager.ConnectionState>(
                HueBridgeConnectionManager.ConnectionState.DISCONNECTED
            )
        )

        val ruleUseCase = mock<IHueRuleUseCase>()
        ruleUseCase.stub {
            on { getAllRules() } doReturn Result.success(emptyList())
            on { createRule(any()) } doReturn anlegen
            on { updateRule(any()) } doReturn aendern
            on { deleteRule(any()) } doReturn loeschen
        }

        val scheduler = mock<HueSmartScheduler>()

        val viewModel = HueViewModel(
            hueBridgeUseCase = bridgeUseCase,
            hueLightUseCase = mock<IHueLightUseCase>(),
            hueRuleUseCase = ruleUseCase,
            hueBridgeConnectionManager = connectionManager,
            hueSmartScheduler = scheduler
        )
        return Fixture(viewModel, ruleUseCase, scheduler)
    }

    // ---- Erfolg ---------------------------------------------------------------------------

    @Test
    fun `createRule im Erfolg plant neu und laedt die Regeln`() = runTest {
        val f = buildFixture()
        advanceUntilIdle()

        f.viewModel.createRule(regel)
        advanceUntilIdle()

        verify(f.scheduler, times(1)).recalculateSchedule()
        verify(f.ruleUseCase, times(1)).getAllRules()
        assertFalse(f.viewModel.uiState.value.isLoading)
        assertNull(f.viewModel.uiState.value.error)
    }

    @Test
    fun `updateRule im Erfolg plant neu und laedt die Regeln`() = runTest {
        val f = buildFixture()
        advanceUntilIdle()

        f.viewModel.updateRule(regel)
        advanceUntilIdle()

        verify(f.scheduler, times(1)).recalculateSchedule()
        verify(f.ruleUseCase, times(1)).getAllRules()
        assertFalse(f.viewModel.uiState.value.isLoading)
        assertNull(f.viewModel.uiState.value.error)
    }

    @Test
    fun `deleteRule im Erfolg plant neu und laedt die Regeln`() = runTest {
        val f = buildFixture()
        advanceUntilIdle()

        f.viewModel.deleteRule("rule_1")
        advanceUntilIdle()

        verify(f.scheduler, times(1)).recalculateSchedule()
        verify(f.ruleUseCase, times(1)).getAllRules()
        assertFalse(f.viewModel.uiState.value.isLoading)
        assertNull(f.viewModel.uiState.value.error)
    }

    // ---- Fehlschlag mit Meldung ---------------------------------------------------------------

    @Test
    fun `createRule im Fehlschlag zeigt die Meldung und plant nicht neu`() = runTest {
        val f = buildFixture(anlegen = Result.failure(RuntimeException("x")))
        advanceUntilIdle()

        f.viewModel.createRule(regel)
        advanceUntilIdle()

        assertEquals("x", f.viewModel.uiState.value.error)
        assertFalse(f.viewModel.uiState.value.isLoading)
        verify(f.scheduler, never()).recalculateSchedule()
    }

    @Test
    fun `updateRule im Fehlschlag zeigt die Meldung und plant nicht neu`() = runTest {
        val f = buildFixture(aendern = Result.failure(RuntimeException("x")))
        advanceUntilIdle()

        f.viewModel.updateRule(regel)
        advanceUntilIdle()

        assertEquals("x", f.viewModel.uiState.value.error)
        assertFalse(f.viewModel.uiState.value.isLoading)
        verify(f.scheduler, never()).recalculateSchedule()
    }

    @Test
    fun `deleteRule im Fehlschlag zeigt die Meldung und plant nicht neu`() = runTest {
        val f = buildFixture(loeschen = Result.failure(RuntimeException("x")))
        advanceUntilIdle()

        f.viewModel.deleteRule("rule_1")
        advanceUntilIdle()

        assertEquals("x", f.viewModel.uiState.value.error)
        assertFalse(f.viewModel.uiState.value.isLoading)
        verify(f.scheduler, never()).recalculateSchedule()
    }

    // ---- Fehlschlag ohne Meldung --------------------------------------------------------------

    @Test
    fun `createRule ohne Fehlermeldung faellt auf den Standardtext`() = runTest {
        val f = buildFixture(anlegen = Result.failure(RuntimeException()))
        advanceUntilIdle()

        f.viewModel.createRule(regel)
        advanceUntilIdle()

        assertEquals("Failed to create rule", f.viewModel.uiState.value.error)
    }

    @Test
    fun `updateRule ohne Fehlermeldung faellt auf den Standardtext`() = runTest {
        val f = buildFixture(aendern = Result.failure(RuntimeException()))
        advanceUntilIdle()

        f.viewModel.updateRule(regel)
        advanceUntilIdle()

        assertEquals("Failed to update rule", f.viewModel.uiState.value.error)
    }

    @Test
    fun `deleteRule ohne Fehlermeldung faellt auf den Standardtext`() = runTest {
        val f = buildFixture(loeschen = Result.failure(RuntimeException()))
        advanceUntilIdle()

        f.viewModel.deleteRule("rule_1")
        advanceUntilIdle()

        assertEquals("Failed to delete rule", f.viewModel.uiState.value.error)
    }
}
