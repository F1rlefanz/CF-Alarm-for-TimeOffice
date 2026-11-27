package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.typ

import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import com.github.f1rlefanz.cf_alarmfortimeoffice.di.state.CalendarStateHolder
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.Blockposition
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimAnchor
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.ZeitkettenArmierer
import com.github.f1rlefanz.cf_alarmfortimeoffice.error.ErrorHandler
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.OhneEintragEinstellungen
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftConfig
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftDefinition
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftKategorie
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.TypDimmen
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.IAlarmUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.IShiftUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.ShiftViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.atLeast
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.stub
import org.mockito.kotlin.times
import org.mockito.kotlin.verifyBlocking
import java.time.LocalTime

/**
 * Die eigenen Dimm- und „Nicht stören“-Einstellungen der festen Zeile „Ohne Kalendereintrag“ (Umbau
 * Etappe 3, #223, SPEC E5, 3.3 Punkt 2, BILDSCHIRME B3o).
 *
 * Drei Ebenen: welche Abschnitte die Seite zeigt ([ohneEintragAbschnitt] mit einem mitschreibenden
 * `LazyListScope` - die Karten selbst brauchen Compose und werden am Geraet geprueft), was die
 * wiederverwendeten Karten von der festen Zeile bekommen und zurueckgeben
 * ([OhneEintragAbschnittLogik]), und der Schreibweg im [ShiftViewModel] (sofort, ohne Entwurf, beide
 * Zeitketten).
 */
@OptIn(ExperimentalCoroutinesApi::class) // Dispatchers.setMain/resetMain, advanceUntilIdle
class OhneEintragEinstellungenTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun typ(id: String, kategorie: ShiftKategorie = ShiftKategorie.DIENST) = ShiftDefinition(
        id = id,
        name = "Typ $id",
        keywords = listOf(id),
        alarmTime = LocalTime.of(5, 30)
    ).mitKategorie(kategorie)

    private val dienst = typ("F")
    private val frei = typ("+", ShiftKategorie.FREI)

    private fun config(wie: String? = null, eigene: OhneEintragEinstellungen = OhneEintragEinstellungen()) = ShiftConfig(
        definitions = listOf(dienst, frei),
        ohneKalendereintragWie = wie,
        ohneKalendereintrag = eigene
    )

    // ---- Welche Abschnitte die Seite zeigt ----------------------------------------------------

    /** Schreibt die Schluessel der Listenelemente mit, in der Reihenfolge, in der die Seite sie anlegt. */
    private class Mitschrift : LazyListScope {
        val schluessel = mutableListOf<Any?>()

        override fun item(key: Any?, contentType: Any?, content: @Composable LazyItemScope.() -> Unit) {
            schluessel += key
        }
    }

    private fun abschnitte(config: ShiftConfig): List<Any?> = Mitschrift().apply {
        ohneEintragAbschnitt(config = config, onWaehle = {}, onAendere = {}, onTypOeffnen = {})
    }.schluessel

    @Test
    fun `bei Eigene Einstellungen stehen Dimmen und Nicht stoeren unter der Wahl`() {
        for (wie in listOf(null, "geloescht")) {
            assertEquals(
                "wie=$wie",
                listOf("ohne-eintrag-typ", "ohne-eintrag-wahl", "ohne-eintrag-dimmen", "ohne-eintrag-nichtstoeren"),
                abschnitte(config(wie = wie))
            )
        }
    }

    @Test
    fun `bei wie Typ erscheinen keine Abschnitte, nur der Kasten mit dem Verweis`() {
        assertEquals(
            listOf("ohne-eintrag-typ", "ohne-eintrag-wahl", "ohne-eintrag-richtet-sich-nach"),
            abschnitte(config(wie = "F"))
        )
    }

    // ---- Was die wiederverwendeten Karten bekommen und zurueckgeben ----------------------------

    private fun dimmen(json: String): TypDimmen = Json.decodeFromString(TypDimmen.serializer(), json)

    @Test
    fun `die Dimm-Karte der festen Zeile hat weder Wecker-Ende noch Blocktage`() {
        val entwurf = OhneEintragAbschnittLogik.dimmEntwurf(OhneEintragEinstellungen())

        DimWann.entries.forEach { wann ->
            assertFalse("„Schon beim Wecker enden“ bei $wann", DimFensterAbbildung.zeigeBeimWecker(wann, entwurf.weckerAn))
        }
        assertTrue(DimmenAbschnittLogik.zeigtAbschnitt(entwurf.kategorie))
        assertFalse("keine Blocktage", DimmenAbschnittLogik.zeigtBlocktage(entwurf.kategorie))
    }

    @Test
    fun `ein neues Fenster der festen Zeile endet an seiner Uhrzeit und gilt an jedem Tag`() {
        val nachher = OhneEintragAbschnittLogik.nachDimmAenderung(OhneEintragEinstellungen()) { e ->
            e.copy(dimmen = DimmenAbschnittLogik.fensterDazu(e.dimmen!!, e.weckerAn))
        }

        val fenster = nachher.dimmen.fenster.single().fenster
        assertEquals(DimAnchor.CLOCK, fenster.startAnchor)
        assertEquals(DimAnchor.CLOCK, fenster.endAnchor)
        assertTrue(fenster.startAmVorabend)
        assertEquals(Blockposition.ALLE, fenster.blockPositionen)
    }

    @Test
    fun `eine Dimm-Aenderung behaelt Farbwaerme, undeutbare Fenster und Nicht stoeren`() {
        val eigene = OhneEintragEinstellungen(
            dimmen = dimmen("""{"an":true,"fenster":[{"fenster":{"startAnchor":"SPAETER_ERFUNDEN"},"staerke":20}],"farbwaerme":12}"""),
            nichtStoerenSolangeGedimmt = true
        )
        assertEquals(1, eigene.dimmen.unlesbareFenster)

        val nachher = OhneEintragAbschnittLogik.nachDimmAenderung(eigene) { e ->
            e.copy(dimmen = DimmenAbschnittLogik.fensterDazu(e.dimmen!!.copy(an = false), e.weckerAn))
        }

        assertFalse(nachher.dimmen.an)
        assertEquals(1, nachher.dimmen.fenster.size)
        assertEquals("das undeutbare Fenster bleibt gespeichert", 1, nachher.dimmen.unlesbareFenster)
        assertEquals(12, nachher.dimmen.farbwaerme)
        assertTrue(nachher.nichtStoerenSolangeGedimmt)
    }

    @Test
    fun `von Nicht stoeren fliesst nur Solange gedimmt wird zurueck`() {
        val eigene = OhneEintragEinstellungen(dimmen = TypDimmen(an = false, farbwaerme = 7))

        assertFalse(OhneEintragAbschnittLogik.dndWerte(eigene).solangeGedimmt)
        assertFalse("„Während des Kalendereintrags“ gibt es hier nicht", OhneEintragAbschnittLogik.dndWerte(eigene).waehrendEintrag)

        val nachher = OhneEintragAbschnittLogik.nachDndAenderung(eigene) {
            it.copy(solangeGedimmt = true, waehrendEintrag = true, endetSpaetestensMinuten = 1)
        }

        assertEquals(eigene.copy(nichtStoerenSolangeGedimmt = true), nachher)
        assertTrue(OhneEintragAbschnittLogik.dndWerte(nachher).solangeGedimmt)
    }

    // ---- Der Schreibweg im ViewModel ----------------------------------------------------------

    private class Aufbau(val vm: ShiftViewModel, val shiftUseCase: IShiftUseCase, val armierer: ZeitkettenArmierer)

    private val speicherdauerMs = 50L

    private fun aufbauen(config: ShiftConfig, speichern: Result<Unit> = Result.success(Unit)): Aufbau {
        val shiftUseCase = mock<IShiftUseCase>()
        shiftUseCase.stub {
            on { shiftConfig } doReturn flowOf(config)
            on { getCurrentShiftConfig() } doReturn Result.success(config)
            // Wie DataStore: das Speichern unterbricht. Ohne Unterbrechung waere der erste Auftrag fertig,
            // bevor der zweite beginnt - und die Faelle „vor dem Speichern“ pruefte keiner.
            on { saveShiftConfig(any()) } doSuspendableAnswer {
                delay(speicherdauerMs)
                speichern
            }
            on { recognizeShiftsInEvents(any()) } doReturn Result.success(emptyList())
        }
        val alarmUseCase = mock<IAlarmUseCase>()
        alarmUseCase.stub {
            on { syncAlarms(any(), any(), anyOrNull()) } doReturn Result.success(emptyList())
        }
        val armierer = mock<ZeitkettenArmierer>()
        val vm = ShiftViewModel(
            shiftUseCase = shiftUseCase,
            alarmUseCase = alarmUseCase,
            calendarStateHolder = CalendarStateHolder(),
            errorHandler = mock<ErrorHandler> { on { getErrorMessage(any()) } doReturn "Fehler" },
            dimRuleUseCase = dagger.Lazy { mock<com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimRuleUseCase>() },
            hueRuleUseCase = dagger.Lazy { mock<com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.HueRuleUseCase>() },
            armierer = armierer,
            dndPrefs = dagger.Lazy { mock<com.github.f1rlefanz.cf_alarmfortimeoffice.dnd.DndPrefs>() },
            shiftSpanStore = dagger.Lazy { mock<com.github.f1rlefanz.cf_alarmfortimeoffice.shift.ShiftSpanStore>() },
            dimOverlayPrefs = dagger.Lazy { mock<com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimOverlayPrefs>() },
            hueConfigRepository = dagger.Lazy {
                mock<com.github.f1rlefanz.cf_alarmfortimeoffice.hue.repository.interfaces.IHueConfigRepository>()
            }
        )
        return Aufbau(vm, shiftUseCase, armierer)
    }

    private fun Aufbau.gespeichert(): List<ShiftConfig> {
        val erfasst = argumentCaptor<ShiftConfig>()
        verifyBlocking(shiftUseCase, atLeast(0)) { saveShiftConfig(erfasst.capture()) }
        return erfasst.allValues
    }

    private fun mitFenster(eigene: OhneEintragEinstellungen): OhneEintragEinstellungen =
        OhneEintragAbschnittLogik.nachDimmAenderung(eigene) { e ->
            e.copy(dimmen = DimmenAbschnittLogik.fensterDazu(e.dimmen!!, e.weckerAn))
        }

    private fun gedimmt(an: Boolean): (OhneEintragEinstellungen) -> OhneEintragEinstellungen =
        { eigene -> OhneEintragAbschnittLogik.nachDndAenderung(eigene) { it.copy(solangeGedimmt = an) } }

    @Test
    fun `Schreiben der festen Zeile aendert keine Definition und armiert beide Ketten`() = runTest(dispatcher) {
        val ausgang = config()
        val a = aufbauen(ausgang)
        advanceUntilIdle()

        a.vm.aendereOhneEintrag(::mitFenster)
        advanceUntilIdle()

        val geschrieben = a.gespeichert().single()
        assertEquals(1, geschrieben.ohneKalendereintrag.dimmen.fenster.size)
        assertEquals("keine Definition angefasst", ausgang.definitions, geschrieben.definitions)
        assertEquals(ausgang.copy(ohneKalendereintrag = geschrieben.ohneKalendereintrag), geschrieben)
        verifyBlocking(a.armierer, times(1)) { armiere(any(), eq(true), eq(true)) }
    }

    @Test
    fun `eine Aenderung, die nichts aendert, schreibt und armiert nicht`() = runTest(dispatcher) {
        val a = aufbauen(config())
        advanceUntilIdle()

        a.vm.aendereOhneEintrag(gedimmt(false))
        a.vm.aendereOhneEintrag { it }
        advanceUntilIdle()

        verifyBlocking(a.shiftUseCase, never()) { saveShiftConfig(any()) }
        verifyBlocking(a.armierer, never()) { armiere(any(), any(), any()) }
    }

    /** Sofort-Schreiben ohne Entwurf: die zweite Aenderung darf nicht vom Stand VOR der ersten rechnen. */
    @Test
    fun `zwei Aenderungen vor dem Speichern gehen beide durch`() = runTest(dispatcher) {
        val a = aufbauen(config())
        advanceUntilIdle()

        a.vm.aendereOhneEintrag(::mitFenster)
        a.vm.aendereOhneEintrag(gedimmt(true))
        assertEquals(
            "die Seite zeigt den beauftragten Stand schon vor dem Speichern",
            1, a.vm.uiState.value.ohneEintragStand?.ohneKalendereintrag?.dimmen?.fenster?.size
        )
        advanceUntilIdle()

        val letzte = a.gespeichert().last().ohneKalendereintrag
        assertEquals(1, letzte.dimmen.fenster.size)
        assertTrue(letzte.nichtStoerenSolangeGedimmt)
        assertEquals(a.gespeichert().last(), a.vm.uiState.value.currentShiftConfig)
        assertEquals(a.vm.uiState.value.currentShiftConfig, a.vm.uiState.value.ohneEintragStand)
    }

    /** Die zweite Aenderung stellt den alten Stand wieder her - gegen den verglichen, armierte sie nicht. */
    @Test
    fun `auch das Zuruecknehmen vor dem Speichern armiert noch einmal`() = runTest(dispatcher) {
        val a = aufbauen(config())
        advanceUntilIdle()

        a.vm.aendereOhneEintrag(gedimmt(true))
        a.vm.aendereOhneEintrag(gedimmt(false))
        advanceUntilIdle()

        assertEquals(listOf(true, false), a.gespeichert().map { it.ohneKalendereintrag.nichtStoerenSolangeGedimmt })
        verifyBlocking(a.armierer, times(2)) { armiere(any(), eq(true), eq(true)) }
    }

    @Test
    fun `eine Wahl direkt nach einer Aenderung ueberschreibt sie nicht`() = runTest(dispatcher) {
        val a = aufbauen(config())
        advanceUntilIdle()

        a.vm.aendereOhneEintrag(gedimmt(true))
        a.vm.waehleOhneEintrag("F")
        advanceUntilIdle()

        val letzte = a.gespeichert().last()
        assertEquals("F", letzte.ohneKalendereintragWie)
        assertTrue(letzte.ohneKalendereintrag.nichtStoerenSolangeGedimmt)
    }

    @Test
    fun `scheitert das Speichern, zeigt die Seite wieder den gespeicherten Stand`() = runTest(dispatcher) {
        val ausgang = config()
        val a = aufbauen(ausgang, speichern = Result.failure(IllegalStateException("voll")))
        advanceUntilIdle()

        a.vm.aendereOhneEintrag(gedimmt(true))
        advanceUntilIdle()

        assertEquals(ausgang, a.vm.uiState.value.ohneEintragStand)
        verifyBlocking(a.armierer, never()) { armiere(any(), any(), any()) }
    }
}
