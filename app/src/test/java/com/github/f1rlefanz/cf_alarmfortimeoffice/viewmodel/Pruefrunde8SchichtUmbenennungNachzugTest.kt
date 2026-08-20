package com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel

import com.github.f1rlefanz.cf_alarmfortimeoffice.di.state.CalendarStateHolder
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimRuleUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimScheduleUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.dnd.DndScheduleUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.error.ErrorHandler
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.HueRuleUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftConfig
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftDefinition
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.IAlarmUseCase
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.stub
import org.mockito.kotlin.verifyBlocking
import org.mockito.kotlin.whenever
import java.time.LocalTime

/**
 * Prüfrunde 8, Befund 2 - die Verdrahtung: Speichert der Nutzer eine UMBENANNTE
 * Schichtdefinition, müssen die Dimmer- UND die Hue-Regeln mitgezogen werden.
 *
 * DER FEHLER: Beide Regelarten binden über den NAMEN der Definition, der Editor ändert den Namen
 * aber bei gleichbleibender `id`. Es gab keinen einzigen Codepfad, der beim Speichern einer
 * ShiftConfig ein Regelmuster umgeschrieben hätte - eine reine Beschriftungsänderung legte damit
 * beide bewusst eingerichteten Funktionen lautlos still, während die Regellisten sie weiter als
 * aktiv zeigten.
 *
 * OHNE DEN FIX fällt der erste Test: `renameShiftPattern` wird nie gerufen.
 */
@OptIn(ExperimentalCoroutinesApi::class) // Dispatchers.setMain/resetMain, advanceUntilIdle
class Pruefrunde8SchichtUmbenennungNachzugTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun def(id: String, name: String, alarmTime: LocalTime = LocalTime.of(5, 30)) =
        ShiftDefinition(id = id, name = name, keywords = listOf(name), alarmTime = alarmTime)

    private class Umgebung(
        val vm: ShiftViewModel,
        val dim: DimRuleUseCase,
        val hue: HueRuleUseCase
    )

    private fun umgebung(
        bestand: ShiftConfig,
        dimErgebnis: Result<Int> = Result.success(1),
        hueErgebnis: Result<Int> = Result.success(1)
    ): Umgebung {
        val shiftUseCase = mock<IShiftUseCase>()
        whenever(shiftUseCase.shiftConfig).thenReturn(flowOf(bestand))
        shiftUseCase.stub {
            onBlocking { getCurrentShiftConfig() } doReturn Result.success(bestand)
            onBlocking { saveShiftConfig(any()) } doReturn Result.success(Unit)
            onBlocking { recognizeShiftsInEvents(any()) } doReturn Result.success(emptyList())
        }
        val dim = mock<DimRuleUseCase>()
        dim.stub { onBlocking { renameShiftPattern(any(), any()) } doReturn dimErgebnis }
        val hue = mock<HueRuleUseCase>()
        hue.stub { onBlocking { renameShiftPattern(any(), any()) } doReturn hueErgebnis }

        val vm = ShiftViewModel(
            shiftUseCase = shiftUseCase,
            alarmUseCase = mock<IAlarmUseCase>(),
            // Leer: `triggerAlarmCreationFromConfigUpdate` steigt dann sofort aus, der Alarm-Sync
            // ist hier nicht das Thema.
            calendarStateHolder = CalendarStateHolder(),
            errorHandler = mock<ErrorHandler>(),
            dimRuleUseCase = dagger.Lazy { dim },
            hueRuleUseCase = dagger.Lazy { hue },
            // Nur das Nacharmieren der Tick-Ketten - hier ohne Belang.
            dimScheduleUseCase = dagger.Lazy { mock<DimScheduleUseCase>() },
            dndScheduleUseCase = dagger.Lazy { mock<DndScheduleUseCase>() }
        )
        return Umgebung(vm, dim, hue)
    }

    @Test
    fun `Umbenennen zieht Dimmer- UND Hue-Regeln nach`() = runTest(dispatcher) {
        val u = umgebung(ShiftConfig(definitions = listOf(def("1", "AD1"))))
        advanceUntilIdle()

        u.vm.updateShiftConfig(ShiftConfig(definitions = listOf(def("1", "Abrufdienst"))))
        advanceUntilIdle()

        verifyBlocking(u.dim) { renameShiftPattern("AD1", "Abrufdienst") }
        verifyBlocking(u.hue) { renameShiftPattern("AD1", "Abrufdienst") }
        assertNull(u.vm.uiState.value.error)
    }

    @Test
    fun `eine geaenderte Weckzeit ist keine Umbenennung - es wird nichts umgeschrieben`() =
        runTest(dispatcher) {
            val u = umgebung(ShiftConfig(definitions = listOf(def("1", "AD1"))))
            advanceUntilIdle()

            u.vm.updateShiftConfig(
                ShiftConfig(definitions = listOf(def("1", "AD1", LocalTime.of(4, 15))))
            )
            advanceUntilIdle()

            verifyBlocking(u.dim, never()) { renameShiftPattern(any(), any()) }
            verifyBlocking(u.hue, never()) { renameShiftPattern(any(), any()) }
        }

    @Test
    fun `eine neu angelegte Schicht zieht nichts nach`() = runTest(dispatcher) {
        val u = umgebung(ShiftConfig(definitions = listOf(def("1", "AD1"))))
        advanceUntilIdle()

        u.vm.updateShiftConfig(
            ShiftConfig(definitions = listOf(def("1", "AD1"), def("2", "Frueh")))
        )
        advanceUntilIdle()

        verifyBlocking(u.dim, never()) { renameShiftPattern(any(), any()) }
        verifyBlocking(u.hue, never()) { renameShiftPattern(any(), any()) }
    }

    @Test
    fun `gescheiterter Nachzug wird dem Nutzer gemeldet, nicht verschwiegen`() = runTest(dispatcher) {
        // Die Umbenennung selbst ist gespeichert - aber die Regeln zeigen weiter ins Leere. Genau
        // hier darf die App nicht so tun, als sei alles in Ordnung.
        val u = umgebung(
            ShiftConfig(definitions = listOf(def("1", "AD1"))),
            dimErgebnis = Result.failure(IllegalStateException("Regeln nicht schreibbar"))
        )
        advanceUntilIdle()

        u.vm.updateShiftConfig(ShiftConfig(definitions = listOf(def("1", "Abrufdienst"))))
        advanceUntilIdle()

        assertNotNull(u.vm.uiState.value.error)
    }

    @Test
    fun `mehrdeutiger Name zieht nichts nach und meldet es`() = runTest(dispatcher) {
        // Zwei Definitionen mit demselben Namen: eine Zuordnung waere geraten. Lieber nichts
        // umschreiben - aber es sagen.
        val u = umgebung(
            ShiftConfig(definitions = listOf(def("1", "AD1"), def("2", "Abrufdienst")))
        )
        advanceUntilIdle()

        u.vm.updateShiftConfig(
            ShiftConfig(definitions = listOf(def("1", "Abrufdienst"), def("2", "Abrufdienst")))
        )
        advanceUntilIdle()

        verifyBlocking(u.dim, never()) { renameShiftPattern(any(), any()) }
        verifyBlocking(u.hue, never()) { renameShiftPattern(any(), any()) }
        assertNotNull(u.vm.uiState.value.error)
    }
}
