package com.github.f1rlefanz.cf_alarmfortimeoffice.model

import com.github.f1rlefanz.cf_alarmfortimeoffice.alarm.AlarmPrefs
import com.github.f1rlefanz.cf_alarmfortimeoffice.alarm.WecktonAnstieg
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimOverlayPrefs
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimRuleUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimScheduleUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.ZeitkettenArmierer
import com.github.f1rlefanz.cf_alarmfortimeoffice.dnd.DndPrefs
import com.github.f1rlefanz.cf_alarmfortimeoffice.dnd.DndScheduleUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.error.ErrorHandler
import com.github.f1rlefanz.cf_alarmfortimeoffice.freietage.tagFreigabeUseCaseOhneFreigaben
import com.github.f1rlefanz.cf_alarmfortimeoffice.masterpause.MasterPausePrefs
import com.github.f1rlefanz.cf_alarmfortimeoffice.repository.interfaces.IAlarmRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.shift.ShiftSpanStore
import com.github.f1rlefanz.cf_alarmfortimeoffice.tageslicht.ITageslichtRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.tageslicht.TageslichtEinstellungen
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.tabs.kostprobeTitel
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.IAlarmSkipUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.IAlarmUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.IShiftUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.AlarmViewModel
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.DimmerRulesViewModel
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.DndViewModel
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.stub
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.LocalDate
import java.time.LocalTime

/**
 * #171: welche Typen eine Auswahlliste anbieten darf - die EINE Funktion hinter manuellem Wecker,
 * Dimmer-Regel, DND-Ausnahme, Hue-Regel und Uebersicht.
 */
class SchichttypAuswahlTest {

    private fun typ(kategorie: ShiftKategorie, aktiv: Boolean = true, muster: String = "X") = ShiftDefinition(
        id = "id-${kategorie.schluessel}",
        name = "Typ ${kategorie.schluessel}",
        keywords = listOf(muster),
        alarmTime = LocalTime.of(6, 0),
        isEnabled = aktiv
    ).mitKategorie(kategorie)

    private val alle = ShiftKategorie.entries.map { typ(it) }

    @Test
    fun `Dienst Rufbereitschaft Frei Urlaub und Fortbildung bleiben waehlbar - Krank und Ignorieren nicht`() {
        assertEquals(
            listOf(
                ShiftKategorie.DIENST, ShiftKategorie.RUFBEREITSCHAFT, ShiftKategorie.FREI,
                ShiftKategorie.URLAUB, ShiftKategorie.FORTBILDUNG
            ),
            alle.waehlbareSchichttypen().map { it.kategorie }
        )
        assertFalse(typ(ShiftKategorie.KRANK).istWaehlbareSchicht())
        assertFalse(typ(ShiftKategorie.IGNORIEREN).istWaehlbareSchicht())
    }

    @Test
    fun `ein deaktivierter Typ ist in keiner Kategorie waehlbar`() {
        val ausgeschaltet = ShiftKategorie.entries.map { typ(it, aktiv = false) }

        assertEquals(emptyList<ShiftDefinition>(), ausgeschaltet.waehlbareSchichttypen())
    }

    @Test
    fun `die gespeicherte Reihenfolge bleibt erhalten und die Namen folgen ihr`() {
        val liste = listOf(
            typ(ShiftKategorie.FORTBILDUNG), typ(ShiftKategorie.IGNORIEREN),
            typ(ShiftKategorie.DIENST), typ(ShiftKategorie.KRANK), typ(ShiftKategorie.FREI)
        )

        assertEquals(
            listOf("Typ fortbildung", "Typ dienst", "Typ frei"),
            liste.waehlbareSchichtNamen()
        )
    }

    @Test
    fun `Krank bleibt so ausgeschlossen wie mit der alten Bedingung - auch nur ueber das Flag oder mit beiden Flags`() {
        // Alter Editor / Altbestand: nur die Flags, keine gespeicherte Kategorie.
        val nurFlag = ShiftDefinition(id = "a", name = "K", keywords = listOf("K"), alarmTime = LocalTime.MIDNIGHT, isSickDay = true)
        val beideFlags = nurFlag.copy(isOnCall = true)

        assertFalse(nurFlag.istWaehlbareSchicht())
        assertFalse(beideFlags.istWaehlbareSchicht())
    }

    @Test
    fun `ein Termin mit dem Muster eines aktiven Ignorieren-Typs gilt als ignoriert - andere nicht`() {
        val liste = listOf(
            typ(ShiftKategorie.IGNORIEREN, muster = "TB"),
            typ(ShiftKategorie.DIENST, muster = "F"),
            typ(ShiftKategorie.IGNORIEREN, aktiv = false, muster = "XX").copy(id = "aus", name = "Aus")
        )

        assertTrue(liste.istIgnorierterTermin("TB"))
        assertFalse("ein Dienst ist nicht ignoriert", liste.istIgnorierterTermin("F"))
        assertFalse("ein ausgeschalteter Ignorieren-Typ uebergeht nichts mehr", liste.istIgnorierterTermin("XX"))
        assertFalse(liste.istIgnorierterTermin("Zahnarzt"))
    }

    @Test
    fun `die Kostprobe der Karte Keine Schicht erkannt nennt weder Krank noch ignorierte Codes`() {
        val liste = listOf(
            typ(ShiftKategorie.KRANK, muster = "K"),
            typ(ShiftKategorie.IGNORIEREN, muster = "TB"),
            typ(ShiftKategorie.DIENST, muster = "F")
        )

        assertEquals(
            listOf("Zahnarzt", "Geburtstag", "Sport"),
            kostprobeTitel(
                titel = listOf("TB", "K", "Zahnarzt", "TB", "Geburtstag", "Sport", "Kino"),
                definitions = liste
            )
        )
    }
}

/**
 * #171: die Auswahllisten rufen WIRKLICH die gemeinsame Funktion. Die reine Funktion oben haelt
 * nichts davon ab, dass eine Stelle wieder eine eigene Bedingung schreibt - hier scheitert dann
 * genau diese Liste. (Hue-Karte und Zaehlung in der Uebersicht sind Einzeiler im Composable.)
 */
@OptIn(ExperimentalCoroutinesApi::class) // Dispatchers.setMain/resetMain, advanceUntilIdle, runCurrent
class SchichttypAuswahlVerdrahtungTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private val dienst = ShiftDefinition(id = "early", name = "Frueh", keywords = listOf("F"), alarmTime = LocalTime.of(5, 0))
    private val ignoriert = ShiftDefinition(
        id = "ign", name = "Ignoriert", keywords = listOf("TB"), alarmTime = LocalTime.of(7, 0)
    ).mitKategorie(ShiftKategorie.IGNORIEREN)
    private val ausgeschaltet = ShiftDefinition(
        id = "aus", name = "Aus", keywords = listOf("A"), alarmTime = LocalTime.of(8, 0), isEnabled = false
    )
    private val frei = ShiftDefinition(
        id = "frei", name = "Frei", keywords = listOf("+"), alarmTime = LocalTime.of(9, 0)
    ).mitKategorie(ShiftKategorie.FREI)

    private val definitionen = listOf(dienst, ignoriert, ausgeschaltet, frei)

    private fun shiftUseCase(): IShiftUseCase {
        val useCase = mock<IShiftUseCase>()
        whenever(useCase.shiftConfig).thenReturn(flowOf(ShiftConfig(definitions = definitionen)))
        return useCase
    }

    private fun TestScope.gesammelt(flow: StateFlow<List<String>>): List<String> {
        backgroundScope.launch { flow.collect { } }
        runCurrent()
        advanceUntilIdle()
        return flow.value
    }

    @Test
    fun `Dimmer-Regeleditor bietet weder Ignorieren- noch ausgeschaltete Typen an`() = runTest(dispatcher) {
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

        assertEquals(listOf("Frueh", "Frei"), gesammelt(vm.shiftNames))
    }

    @Test
    fun `Nicht-stoeren-Ausnahmen bieten weder Ignorieren- noch ausgeschaltete Typen an`() = runTest(dispatcher) {
        val prefs = mock<DndPrefs>()
        whenever(prefs.toggles).thenReturn(flowOf(DndPrefs.Toggles(followDimmerEnabled = false, duringShiftEnabled = true)))
        whenever(prefs.shiftExcludedShifts).thenReturn(flowOf(emptySet()))
        whenever(prefs.onCallCutoffMinutes).thenReturn(flowOf(DndPrefs.DEFAULT_ONCALL_CUTOFF_MIN))
        whenever(prefs.policy).thenReturn(flowOf(DndPrefs.Policy()))
        val vm = DndViewModel(prefs, mock<DndScheduleUseCase>(), shiftUseCase())

        assertEquals(listOf("Frueh", "Frei"), gesammelt(vm.shiftNames))
    }

    private lateinit var alarmUseCase: IAlarmUseCase
    private lateinit var shiftUseCase: IShiftUseCase

    private fun manuellerWecker(start: List<ShiftDefinition>): AlarmViewModel {
        alarmUseCase = mock<IAlarmUseCase>()
        whenever(alarmUseCase.activeAlarms).thenReturn(flowOf(emptyList()))
        alarmUseCase.stub {
            on { getAllAlarms() } doReturn Result.success(emptyList())
            on { scheduleSystemAlarm(any()) } doReturn Result.success(Unit)
        }
        val skipUseCase = mock<IAlarmSkipUseCase>()
        whenever(skipUseCase.skipStatusFlow).thenReturn(flowOf(AlarmSkipState()))
        skipUseCase.stub { on { getSkipStatus() } doReturn Result.success(AlarmSkipState()) }

        shiftUseCase = mock<IShiftUseCase>()
        whenever(shiftUseCase.shiftConfig).thenReturn(flowOf(ShiftConfig(definitions = start)))
        shiftUseCase.stub { on { getCurrentShiftConfig() } doReturn Result.success(ShiftConfig(definitions = start)) }

        val alarmPrefs = mock<AlarmPrefs>()
        whenever(alarmPrefs.snoozeMinutes).thenReturn(flowOf(9))
        whenever(alarmPrefs.wecktonAnstieg).thenReturn(flowOf(WecktonAnstieg.AUS))
        val masterPausePrefs = mock<MasterPausePrefs>()
        masterPausePrefs.stub { on { pausedNow() } doReturn false }
        val repositorium = mock<IAlarmRepository>().apply {
            stub {
                on { isPersistenceBlocked() } doReturn false
                on { istLetzterSchreibvorgangGescheitert() } doReturn false
            }
        }

        return AlarmViewModel(
            alarmUseCase = alarmUseCase,
            alarmSkipUseCase = skipUseCase,
            shiftUseCase = shiftUseCase,
            errorHandler = mock<ErrorHandler>(),
            masterPausePrefs = masterPausePrefs,
            alarmPrefs = alarmPrefs,
            tagFreigabeUseCase = tagFreigabeUseCaseOhneFreigaben(),
            zeitkettenArmierer = mock<ZeitkettenArmierer>(),
            wecktonProbe = mock(),
            alarmRepository = repositorium
        )
    }

    @Test
    fun `der manuelle Wecker bietet weder Ignorieren- noch ausgeschaltete Typen an`() = runTest(dispatcher) {
        val vm = manuellerWecker(definitionen)
        advanceUntilIdle()

        assertEquals(listOf("early", "frei"), vm.manualAlarmState.value.availableShifts.map { it.id })
    }

    @Test
    fun `wird der gewaehlte Typ vor dem Anlegen zum Ignorieren-Typ, entsteht KEIN Wecker`() = runTest(dispatcher) {
        // Die frische Abfrage im Erstellungspfad ist die zweite Stelle: zwischen Auswahl und
        // Knopfdruck kann der Typ umkategorisiert werden. Der Flow zeigt noch den alten Stand.
        val vm = manuellerWecker(listOf(dienst))
        advanceUntilIdle()
        vm.selectManualAlarmDate(LocalDate.now().plusDays(3))
        vm.selectManualAlarmShift(dienst)
        advanceUntilIdle()

        shiftUseCase.stub {
            on { getCurrentShiftConfig() } doReturn
                Result.success(ShiftConfig(definitions = listOf(dienst.mitKategorie(ShiftKategorie.IGNORIEREN))))
        }
        vm.createManualAlarm()
        advanceUntilIdle()

        verify(alarmUseCase, never()).saveAlarm(any())
        assertNotNull(vm.manualAlarmState.value.error)
    }
}
