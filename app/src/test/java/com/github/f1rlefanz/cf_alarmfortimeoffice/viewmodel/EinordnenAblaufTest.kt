package com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel

import com.github.f1rlefanz.cf_alarmfortimeoffice.di.state.CalendarStateHolder
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimRuleUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.ZeitkettenArmierer
import com.github.f1rlefanz.cf_alarmfortimeoffice.error.ErrorHandler
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.HueRuleUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.CalendarEvent
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.SchichttypEinordnen
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftConfig
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftDefinition
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftKategorie
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.stub
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyBlocking
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * Der Ablauf „Im Kalender gefunden → Einordnen / Ignorieren“ (Umbau #176, SPEC E4, 3.1, 3.2) auf
 * ViewModel-Ebene: was gespeichert wird, wann, und was danach aus dem Fund wird.
 *
 * Die Entscheidungen selbst (Voreinstellung je Kategorie, Ziel des Ignorierens) stehen in
 * `SchichttypEinordnenTest`; hier geht es um den Weg bis zum Speichern und zum Alarm-Sync.
 * Der Sync braucht eine nachweislich VOLLSTAENDIGE Eventliste (Kerntest `ShiftViewModelSyncGateTest`),
 * deshalb legen alle Tests sie mit `complete = true` ab.
 */
@OptIn(ExperimentalCoroutinesApi::class) // Dispatchers.setMain/resetMain, advanceUntilIdle
class EinordnenAblaufTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun typ(id: String, name: String, vararg muster: String, kategorie: ShiftKategorie = ShiftKategorie.DIENST) =
        ShiftDefinition(
            id = id,
            name = name,
            keywords = muster.toList(),
            alarmTime = LocalTime.of(5, 30),
            isEnabled = true
        ).mitKategorie(kategorie)

    private fun termin(id: String, titel: String) = CalendarEvent(
        id = id,
        title = titel,
        startTime = LocalDateTime.of(2026, 8, 20, 6, 0),
        endTime = LocalDateTime.of(2026, 8, 20, 14, 0),
        calendarId = "cal-a"
    )

    /** Das ViewModel samt seiner Mocks - die Tests lesen `saveShiftConfig` und `syncAlarms` daran ab. */
    private class Aufbau(
        val vm: ShiftViewModel,
        val shiftUseCase: IShiftUseCase,
        val alarmUseCase: IAlarmUseCase
    )

    private fun aufbauen(config: ShiftConfig, events: List<CalendarEvent>): Aufbau {
        val holder = CalendarStateHolder()
        holder.updateEvents(events, complete = true)
        val shiftUseCase = mock<IShiftUseCase>()
        shiftUseCase.stub {
            on { shiftConfig } doReturn flowOf(config)
            on { getCurrentShiftConfig() } doReturn Result.success(config)
            on { saveShiftConfig(any()) } doReturn Result.success(Unit)
            on { recognizeShiftsInEvents(any()) } doReturn Result.success(emptyList())
        }
        val alarmUseCase = mock<IAlarmUseCase>()
        // Der Erfolgszweig von triggerAlarmCreationFromConfigUpdate() liest `alarms.size`.
        alarmUseCase.stub {
            on { syncAlarms(any(), any(), anyOrNull()) } doReturn Result.success(emptyList())
        }
        val vm = ShiftViewModel(
            shiftUseCase = shiftUseCase,
            alarmUseCase = alarmUseCase,
            calendarStateHolder = holder,
            errorHandler = mock<ErrorHandler>(),
            dimRuleUseCase = dagger.Lazy { mock<DimRuleUseCase>() },
            hueRuleUseCase = dagger.Lazy { mock<HueRuleUseCase>() },
            armierer = mock<ZeitkettenArmierer>(),
            dndPrefs = dagger.Lazy { mock<com.github.f1rlefanz.cf_alarmfortimeoffice.dnd.DndPrefs>() },
            shiftSpanStore = dagger.Lazy { mock<com.github.f1rlefanz.cf_alarmfortimeoffice.shift.ShiftSpanStore>() },
            dimOverlayPrefs = dagger.Lazy { mock<com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimOverlayPrefs>() },
            hueConfigRepository = dagger.Lazy {
                mock<com.github.f1rlefanz.cf_alarmfortimeoffice.hue.repository.interfaces.IHueConfigRepository>()
            }
        )
        return Aufbau(vm, shiftUseCase, alarmUseCase)
    }

    private fun Aufbau.gespeicherteKonfiguration(): ShiftConfig {
        val erfasst = argumentCaptor<ShiftConfig>()
        verifyBlocking(shiftUseCase) { saveShiftConfig(erfasst.capture()) }
        return erfasst.firstValue
    }

    private fun Aufbau.codesImFund(): List<String> = vm.uiState.value.codeSuggestions.suggestions.map { it.code }

    @Test
    fun `IMCF als gehoert zu F ergaenzt das Muster, stoesst den Sync an und leert den Fund`() = runTest(dispatcher) {
        val f = typ("f", "Frühdienst", "F")
        val events = listOf(termin("e1", "IMCF"), termin("e2", "F"))
        val a = aufbauen(ShiftConfig(definitions = listOf(f)), events)
        advanceUntilIdle()
        assertEquals("Ausgangslage: IMCF steht im Fund", listOf("IMCF"), a.codesImFund())

        a.vm.assignCodeToDefinition("IMCF", "f")
        advanceUntilIdle()

        assertEquals(listOf("F", "IMCF"), a.gespeicherteKonfiguration().definitions.single().keywords)
        val syncKonfig = argumentCaptor<ShiftConfig>()
        verify(a.alarmUseCase).syncAlarms(eq(events), syncKonfig.capture(), anyOrNull())
        assertEquals(listOf("F", "IMCF"), syncKonfig.firstValue.definitions.single().keywords)
        assertEquals(emptyList<String>(), a.codesImFund())
    }

    @Test
    fun `ein neuer Urlaub-Typ existiert erst nach dem Speichern und hat keinen Wecker`() = runTest(dispatcher) {
        val f = typ("f", "Frühdienst", "F")
        val a = aufbauen(ShiftConfig(definitions = listOf(f)), listOf(termin("e1", "FBE")))
        advanceUntilIdle()

        // Kategorie gewaehlt, Editor offen: die Vorlage ist noch NICHTS Gespeichertes.
        val vorlage = SchichttypEinordnen.neuerTypAusCode("FBE", ShiftKategorie.URLAUB) { "neu" }!!
        advanceUntilIdle()
        verifyBlocking(a.shiftUseCase, never()) { saveShiftConfig(any()) }
        assertEquals(listOf("f"), a.vm.uiState.value.currentShiftConfig!!.definitions.map { it.id })
        assertEquals("FBE bleibt im Fund, bis der Typ gespeichert ist", listOf("FBE"), a.codesImFund())

        // „Erstellen“ im Editor.
        a.vm.legeTypAusCodeAn("FBE", vorlage)
        advanceUntilIdle()

        val gespeichert = a.gespeicherteKonfiguration().definitions
        assertEquals(listOf("f", "neu"), gespeichert.map { it.id })
        val urlaub = gespeichert.last()
        assertEquals(ShiftKategorie.URLAUB, urlaub.kategorie)
        assertFalse("Urlaub stellt keinen Wecker", urlaub.weckerAn)
        assertEquals(listOf("FBE"), urlaub.keywords)
        assertEquals(emptyList<String>(), a.codesImFund())
    }

    @Test
    fun `Ignorieren nimmt den Code aus dem Fund und legt den Ignorieren-Typ an`() = runTest(dispatcher) {
        val f = typ("f", "Frühdienst", "F")
        val a = aufbauen(ShiftConfig(definitions = listOf(f)), listOf(termin("e1", "TB"), termin("e2", "F")))
        advanceUntilIdle()
        assertEquals(listOf("TB"), a.codesImFund())

        a.vm.ignoriereCode("TB")
        advanceUntilIdle()

        val ignoriert = a.gespeicherteKonfiguration().definitions.single { it.kategorie == ShiftKategorie.IGNORIEREN }
        assertEquals(listOf("TB"), ignoriert.keywords)
        assertFalse("Ein Ignorieren-Typ stellt nie einen Wecker", ignoriert.weckerAn)
        assertEquals(emptyList<String>(), a.codesImFund())
    }

    @Test
    fun `entfernt der Nutzer den Code im Editor, bleibt er im Fund`() = runTest(dispatcher) {
        val a = aufbauen(ShiftConfig(definitions = listOf(typ("f", "Frühdienst", "F"))), listOf(termin("e1", "FBE")))
        advanceUntilIdle()
        val vorlage = SchichttypEinordnen.neuerTypAusCode("FBE", ShiftKategorie.FORTBILDUNG) { "neu" }!!

        // Im Editor wurden Name UND Muster ersetzt (der Name zaehlt ab zwei Zeichen selbst als
        // Muster, `ShiftDefinition.matchesKeywords` - mit dem Namen "FBE" waere der Code weiter getroffen).
        a.vm.legeTypAusCodeAn("FBE", vorlage.copy(name = "Fortbildung intern", keywords = listOf("Fortbildung intern")))
        advanceUntilIdle()

        val neu = a.gespeicherteKonfiguration().definitions.last()
        assertEquals(listOf("Fortbildung intern"), neu.keywords)
        assertEquals(listOf("FBE"), a.codesImFund())
    }

    @Test
    fun `dieselbe Vorlage ein zweites Mal zu speichern legt den Typ nicht doppelt an`() = runTest(dispatcher) {
        val a = aufbauen(ShiftConfig(definitions = listOf(typ("f", "Frühdienst", "F"))), listOf(termin("e1", "FBE")))
        advanceUntilIdle()
        val vorlage = SchichttypEinordnen.neuerTypAusCode("FBE", ShiftKategorie.URLAUB) { "neu" }!!

        a.vm.legeTypAusCodeAn("FBE", vorlage)
        advanceUntilIdle()
        a.vm.legeTypAusCodeAn("FBE", vorlage)
        advanceUntilIdle()

        verifyBlocking(a.shiftUseCase, times(1)) { saveShiftConfig(any()) }
        assertEquals(listOf("f", "neu"), a.vm.uiState.value.currentShiftConfig!!.definitions.map { it.id })
    }

    @Test
    fun `ein im Editor ausgeschalteter neuer Typ wird ausgeschaltet gespeichert`() = runTest(dispatcher) {
        val a = aufbauen(ShiftConfig(definitions = listOf(typ("f", "Frühdienst", "F"))), listOf(termin("e1", "FBE")))
        advanceUntilIdle()
        val vorlage = SchichttypEinordnen.neuerTypAusCode("FBE", ShiftKategorie.DIENST) { "neu" }!!

        // Der Schalter „Schichtdefinition aktiviert“ im Editor ist auch bei einer Vorlage frei. Ein Dienst-Typ,
        // den der Nutzer bewusst ausschaltet, darf NICHT als eingeschalteter Typ landen (sonst Wecker an jedem FBE-Tag).
        a.vm.legeTypAusCodeAn("FBE", vorlage.copy(isEnabled = false))
        advanceUntilIdle()

        val neu = a.gespeicherteKonfiguration().definitions.last()
        assertEquals("neu", neu.id)
        assertFalse("im Editor ausgeschaltet, also ausgeschaltet gespeichert", neu.isEnabled)
        assertEquals(listOf("FBE"), neu.keywords)
        assertEquals("ein ausgeschalteter Typ zaehlt als Zuordnung", emptyList<String>(), a.codesImFund())
    }
}
