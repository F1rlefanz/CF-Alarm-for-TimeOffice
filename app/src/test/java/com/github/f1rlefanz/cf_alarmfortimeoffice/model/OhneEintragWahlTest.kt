package com.github.f1rlefanz.cf_alarmfortimeoffice.model

import com.github.f1rlefanz.cf_alarmfortimeoffice.di.state.CalendarStateHolder
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.ZeitkettenArmierer
import com.github.f1rlefanz.cf_alarmfortimeoffice.error.ErrorHandler
import com.github.f1rlefanz.cf_alarmfortimeoffice.shift.tageOhneEintrag
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.typ.OhneEintragTexte
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.IAlarmUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.IShiftUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.ShiftViewModel
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.atLeast
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.stub
import org.mockito.kotlin.verifyBlocking
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * Die Wahl „Tage ohne Eintrag verhalten sich“ an der festen Zeile „Ohne Kalendereintrag“
 * (Umbau Etappe 1, #177, SPEC E5, 3.3 Punkt 2).
 *
 * Drei Schichten: die reinen Funktionen ([OhneEintragWahl]), die Texte der Zeile
 * ([OhneEintragTexte]) und der Schreibweg im [ShiftViewModel] - denn ein Test nur der reinen
 * Funktion fiele nicht auf, wenn der Bildschirm wieder `definitions - definition` rechnete.
 */
@OptIn(ExperimentalCoroutinesApi::class) // Dispatchers.setMain/resetMain, advanceUntilIdle
class OhneEintragWahlTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun typ(
        id: String,
        kategorie: ShiftKategorie = ShiftKategorie.DIENST,
        weckerAn: Boolean = true,
        aktiv: Boolean = true
    ) = ShiftDefinition(
        id = id,
        name = "Typ $id",
        keywords = listOf(id),
        alarmTime = LocalTime.of(5, 30),
        isEnabled = aktiv,
        weckerAn = weckerAn
    ).mitKategorie(kategorie)

    private val dienst = typ("F")
    private val frei = typ("+", ShiftKategorie.FREI, weckerAn = false)
    private val ignorieren = typ("TB", ShiftKategorie.IGNORIEREN, weckerAn = false)
    private val krank = typ("K", ShiftKategorie.KRANK)
    private val aus = typ("Z", aktiv = false)

    private fun config(wie: String? = null) = ShiftConfig(
        definitions = listOf(dienst, frei, ignorieren, krank, aus),
        ohneKalendereintragWie = wie
    )

    // ---- Was zur Wahl steht -------------------------------------------------------------------

    @Test
    fun `zur Wahl steht jeder Typ ausser Ignorieren, in gespeicherter Reihenfolge`() {
        val ziele = OhneEintragWahl.verweisbareTypen(config().definitions)

        assertEquals(listOf("F", "+", "K", "Z"), ziele.map { it.id })
    }

    @Test
    fun `ein Ignorieren-Typ kann nicht gewaehlt werden`() {
        assertNull(OhneEintragWahl.waehle(config(), "TB"))
    }

    // ---- Wahl setzen und zuruecksetzen --------------------------------------------------------

    @Test
    fun `die Wahl setzen und wieder auf Eigene Einstellungen zuruecksetzen`() {
        val gewaehlt = OhneEintragWahl.waehle(config(), "+")!!
        assertEquals("+", gewaehlt.ohneKalendereintragWie)
        assertEquals("+", OhneEintragWahl.gewaehlterTyp(gewaehlt)?.id)
        assertEquals("nur der Verweis aendert sich", config().definitions, gewaehlt.definitions)

        val zurueck = OhneEintragWahl.waehle(gewaehlt, null)!!
        assertNull(zurueck.ohneKalendereintragWie)
        assertNull(OhneEintragWahl.gewaehlterTyp(zurueck))
    }

    @Test
    fun `dieselbe Wahl nochmal oder ein unbekannter Typ aendert nichts`() {
        assertNull("schon Eigene Einstellungen", OhneEintragWahl.waehle(config(), null))
        assertNull("schon so gewaehlt", OhneEintragWahl.waehle(config(wie = "F"), "F"))
        assertNull("diesen Typ gibt es nicht", OhneEintragWahl.waehle(config(), "gibt-es-nicht"))
    }

    @Test
    fun `ein Verweis ins Leere liest sich als Eigene Einstellungen und laesst sich aufraeumen`() {
        val verwaist = config(wie = "geloescht")

        assertNull(OhneEintragWahl.gewaehlterTyp(verwaist))
        assertNull("Eigene Einstellungen raeumt den toten Verweis ab", OhneEintragWahl.waehle(verwaist, null)!!.ohneKalendereintragWie)
    }

    @Test
    fun `ein Verweis auf einen inzwischen ignorierten Typ gilt als Eigene Einstellungen`() {
        // Der Typ wurde im Editor nachtraeglich auf Ignorieren umgestellt; die Wahl steht dann nicht mehr
        // zur Verfuegung und darf auch nicht als angehakt erscheinen.
        assertNull(OhneEintragWahl.gewaehlterTyp(config(wie = "TB")))
    }

    // ---- Loeschen des Verweistyps -------------------------------------------------------------

    @Test
    fun `das Loeschen des Verweistyps setzt die Wahl im selben Ergebnis zurueck`() {
        val vorher = config(wie = "F")

        val nachher = OhneEintragWahl.ohneTyp(vorher, dienst)

        assertEquals(listOf("+", "TB", "K", "Z"), nachher.definitions.map { it.id })
        assertNull("Loeschen und Zuruecksetzen sind EIN Schreibvorgang", nachher.ohneKalendereintragWie)
    }

    @Test
    fun `das Loeschen eines anderen Typs laesst die Wahl stehen`() {
        val nachher = OhneEintragWahl.ohneTyp(config(wie = "F"), frei)

        assertEquals(listOf("F", "TB", "K", "Z"), nachher.definitions.map { it.id })
        assertEquals("F", nachher.ohneKalendereintragWie)
    }

    // ---- Wann die Wahl einen Wecker stellt: dieselbe Antwort wie der Sync ---------------------

    @Test
    fun `weckt() sagt dasselbe wie tageOhneEintrag, fuer jeden Zustand des Verweistyps`() {
        val jetzt = LocalDateTime.of(2026, 10, 19, 4, 0)
        val horizont = jetzt.plusDays(7)
        val einTermin = CalendarEvent(
            id = "e", title = "x", startTime = jetzt.plusDays(1).withHour(6), endTime = jetzt.plusDays(1).withHour(14),
            calendarId = "cal"
        )

        for (kandidat in listOf(dienst, frei, krank, aus, typ("U", ShiftKategorie.URLAUB), typ("FB", ShiftKategorie.FORTBILDUNG))) {
            val konfig = ShiftConfig(definitions = listOf(kandidat), ohneKalendereintragWie = kandidat.id)
            val stelltWecker = tageOhneEintrag(listOf(einTermin), emptyList(), konfig, emptySet(), jetzt, horizont).isNotEmpty()

            assertEquals("${kandidat.id} (${kandidat.kategorie}, an=${kandidat.isEnabled}, wecker=${kandidat.weckerAn})",
                stelltWecker, OhneEintragWahl.weckt(kandidat))
        }
    }

    // ---- Die Zeile in der Liste ---------------------------------------------------------------

    @Test
    fun `die Zeile sagt kein Wecker, solange nichts gewaehlt ist`() {
        assertEquals("kein Wecker", OhneEintragTexte.kurzform(config()))
        assertEquals("ein Verweis ins Leere ist nichts gewaehlt", "kein Wecker", OhneEintragTexte.kurzform(config(wie = "geloescht")))
    }

    @Test
    fun `die Zeile nennt den Typ, wie ihn auch die Wahl nennt`() {
        val gewaehlt = config(wie = "F")

        assertEquals("verhält sich wie Typ F (F)", OhneEintragTexte.kurzform(gewaehlt))
        assertEquals("Wie Typ F (F)", OhneEintragTexte.wieLabel(dienst))
    }

    @Test
    fun `die Zeile verschweigt nicht, wenn der Verweistyp keinen Wecker stellt`() {
        assertEquals("verhält sich wie Typ + (+) · kein Wecker", OhneEintragTexte.kurzform(config(wie = "+")))
        assertEquals("verhält sich wie Typ Z (Z) · kein Wecker", OhneEintragTexte.kurzform(config(wie = "Z")))
        assertEquals("verhält sich wie Typ K (K) · kein Wecker", OhneEintragTexte.kurzform(config(wie = "K")))
    }

    // ---- Der Schreibweg im ViewModel ----------------------------------------------------------

    private class Aufbau(val vm: ShiftViewModel, val shiftUseCase: IShiftUseCase)

    private fun aufbauen(config: ShiftConfig): Aufbau {
        val holder = CalendarStateHolder()
        val shiftUseCase = mock<IShiftUseCase>()
        shiftUseCase.stub {
            on { shiftConfig } doReturn flowOf(config)
            on { getCurrentShiftConfig() } doReturn Result.success(config)
            on { saveShiftConfig(any()) } doReturn Result.success(Unit)
            on { recognizeShiftsInEvents(any()) } doReturn Result.success(emptyList())
        }
        val alarmUseCase = mock<IAlarmUseCase>()
        alarmUseCase.stub {
            on { syncAlarms(any(), any(), anyOrNull()) } doReturn Result.success(emptyList())
        }
        val vm = ShiftViewModel(
            shiftUseCase = shiftUseCase,
            alarmUseCase = alarmUseCase,
            calendarStateHolder = holder,
            errorHandler = mock<ErrorHandler>(),
            dimRuleUseCase = dagger.Lazy { mock<com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimRuleUseCase>() },
            hueRuleUseCase = dagger.Lazy { mock<com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.HueRuleUseCase>() },
            armierer = mock<ZeitkettenArmierer>(),
            dndPrefs = dagger.Lazy { mock<com.github.f1rlefanz.cf_alarmfortimeoffice.dnd.DndPrefs>() },
            shiftSpanStore = dagger.Lazy { mock<com.github.f1rlefanz.cf_alarmfortimeoffice.shift.ShiftSpanStore>() },
            dimOverlayPrefs = dagger.Lazy { mock<com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimOverlayPrefs>() },
            hueConfigRepository = dagger.Lazy {
                mock<com.github.f1rlefanz.cf_alarmfortimeoffice.hue.repository.interfaces.IHueConfigRepository>()
            }
        )
        return Aufbau(vm, shiftUseCase)
    }

    private fun Aufbau.gespeichert(): List<ShiftConfig> {
        val erfasst = argumentCaptor<ShiftConfig>()
        verifyBlocking(shiftUseCase, atLeast(0)) { saveShiftConfig(erfasst.capture()) }
        return erfasst.allValues
    }

    @Test
    fun `die Wahl wird gespeichert und wieder zurueckgenommen`() = runTest(dispatcher) {
        val a = aufbauen(config())
        advanceUntilIdle()

        a.vm.waehleOhneEintrag("F")
        advanceUntilIdle()
        assertEquals("F", a.gespeichert().single().ohneKalendereintragWie)

        a.vm.waehleOhneEintrag(null)
        advanceUntilIdle()
        val alle = a.gespeichert()
        assertEquals(2, alle.size)
        assertNull(alle.last().ohneKalendereintragWie)
    }

    @Test
    fun `eine Wahl, die nichts aendert oder nicht zur Wahl steht, schreibt nichts`() = runTest(dispatcher) {
        val a = aufbauen(config(wie = "F"))
        advanceUntilIdle()

        a.vm.waehleOhneEintrag("F")
        a.vm.waehleOhneEintrag("TB")
        a.vm.waehleOhneEintrag("gibt-es-nicht")
        advanceUntilIdle()

        verifyBlocking(a.shiftUseCase, never()) { saveShiftConfig(any()) }
    }

    @Test
    fun `Loeschen des Verweistyps ist genau ein Schreibvorgang und setzt die Wahl zurueck`() = runTest(dispatcher) {
        val a = aufbauen(config(wie = "F"))
        advanceUntilIdle()

        a.vm.loescheSchichttyp(dienst)
        advanceUntilIdle()

        val geschrieben = a.gespeichert()
        assertEquals("ein Schreibvorgang, kein zweiter fuer das Zuruecksetzen", 1, geschrieben.size)
        assertEquals(listOf("+", "TB", "K", "Z"), geschrieben.single().definitions.map { it.id })
        assertNull(geschrieben.single().ohneKalendereintragWie)
    }

    @Test
    fun `Loeschen eines anderen Typs laesst die Wahl im Schreibvorgang stehen`() = runTest(dispatcher) {
        val a = aufbauen(config(wie = "F"))
        advanceUntilIdle()

        a.vm.loescheSchichttyp(frei)
        advanceUntilIdle()

        val geschrieben = a.gespeichert().single()
        assertFalse(geschrieben.definitions.any { it.id == "+" })
        assertEquals("F", geschrieben.ohneKalendereintragWie)
        assertTrue(geschrieben.definitions.any { it.id == "F" })
        assertNotNull(OhneEintragWahl.gewaehlterTyp(geschrieben))
    }
}
