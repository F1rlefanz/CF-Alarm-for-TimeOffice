package com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel

import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimAnchor
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimOverlayPrefs
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimRule
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimRuleUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimScheduleUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimWindow
import com.github.f1rlefanz.cf_alarmfortimeoffice.dnd.DndScheduleUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftConfig
import com.github.f1rlefanz.cf_alarmfortimeoffice.shift.ShiftSpan
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.time.LocalDate
import java.time.ZoneId

/**
 * Prüfrunde 8, Nachbesserung: **Die Regelliste zeigte eine verdrängte Regel weiter als aktiv.**
 *
 * `DimWindowResolver.buildRuleSpans` entscheidet an einem Tag mit mehreren Diensten zugunsten der
 * Regel der frühesten Schicht. Die unterlegene Regel wirkt an diesem Tag nicht — gemeldet wurde
 * das ausschließlich per `Logger.w`, ohne State-Feld und ohne Renderstelle. Dieser Test hält fest,
 * dass die Oberfläche die Auskunft bekommt: [DimmerRulesViewModel.verdraengteRegeln] nennt Regel,
 * Anzahl der Tage und die jeweils siegreiche Regel; `DimmerSettingsScreen` rendert genau das an
 * der Karte der betroffenen Regel.
 *
 * Der Sichtbarkeits-Zustand hat bewusst zwei Leer-Ausgänge (Regel-Quelle aus, Schichtspannen nicht
 * lesbar) — lieber kein Hinweis als ein falscher; beide sind hier festgehalten.
 */
@OptIn(ExperimentalCoroutinesApi::class) // Dispatchers.setMain/resetMain, advanceUntilIdle
class Pruefrunde8RegelkonfliktAnzeigeTest {

    private val dispatcher = StandardTestDispatcher()
    private val zone: ZoneId = ZoneId.systemDefault()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun nachtfenster(von: Int, bis: Int) = DimWindow(
        startAnchor = DimAnchor.CLOCK, startClockMinutes = von * 60,
        endAnchor = DimAnchor.CLOCK, endClockMinutes = bis * 60
    )

    private val frueh = DimRule(
        id = "f", name = "Frühdienst", shiftPattern = "Fruehdienst", enabled = true,
        windows = listOf(nachtfenster(22, 7)), strength = 65
    )
    private val ruf = DimRule(
        id = "r", name = "Rufbereitschaft", shiftPattern = "Rufbereitschaft", enabled = true,
        windows = listOf(nachtfenster(20, 23)), strength = 80
    )

    /** Ein Wecken zur Stunde [h] am Kalendertag `heute + [inTagen]`, in der System-Zeitzone. */
    private fun spanne(name: String, inTagen: Long, h: Int): ShiftSpan {
        val weckzeit = LocalDate.now(zone).plusDays(inTagen).atTime(h, 0)
            .atZone(zone).toInstant().toEpochMilli()
        return ShiftSpan(
            shiftName = name,
            startTime = weckzeit,
            endTime = weckzeit,
            alarmTriggerTime = weckzeit
        )
    }

    private class Fixture(
        val viewModel: DimmerRulesViewModel,
        val ruleUseCase: DimRuleUseCase,
        val spanStore: ShiftSpanStore,
        val prefs: DimOverlayPrefs
    )

    private suspend fun fixture(
        regeln: List<DimRule>,
        spannen: Result<List<ShiftSpan>>,
        rulesEnabled: Boolean = true
    ): Fixture {
        val ruleUseCase = mock<DimRuleUseCase>()
        val shiftUseCase = mock<IShiftUseCase>()
        val dimSchedule = mock<DimScheduleUseCase>()
        val prefs = mock<DimOverlayPrefs>()
        val spanStore = mock<ShiftSpanStore>()

        // Wird im Property-Initializer abonniert - ohne Stub scheitert schon die Konstruktion.
        whenever(ruleUseCase.rules).thenReturn(flowOf(regeln))
        whenever(shiftUseCase.shiftConfig).thenReturn(flowOf(ShiftConfig()))
        whenever(ruleUseCase.getAllRules()).thenReturn(regeln)
        // Auswahl wie `DimRuleUseCase.findRuleForShift`, hier explizit statt nachgebaut.
        whenever(ruleUseCase.findRuleForShift(eq("Fruehdienst"), any()))
            .thenReturn(regeln.firstOrNull { it.shiftPattern == "Fruehdienst" })
        whenever(ruleUseCase.findRuleForShift(eq("Rufbereitschaft"), any()))
            .thenReturn(regeln.firstOrNull { it.shiftPattern == "Rufbereitschaft" })
        whenever(spanStore.spansNow()).thenReturn(spannen)
        whenever(prefs.togglesNow()).thenReturn(
            DimOverlayPrefs.Toggles(
                wellnessEnabled = false,
                rulesEnabled = rulesEnabled,
                nightDefaultEnabled = false
            )
        )

        return Fixture(
            DimmerRulesViewModel(ruleUseCase, shiftUseCase, dimSchedule, { mock<DndScheduleUseCase>() }, prefs, spanStore),
            ruleUseCase, spanStore, prefs
        )
    }

    @Test
    fun `Die verdraengte Regel wird mit Tageszahl und Siegerin gemeldet`() = runTest {
        // Zwei Dienste am selben Kalendertag, je eine eigene Regel: es gilt die des früheren.
        val f = fixture(
            regeln = listOf(frueh, ruf),
            spannen = Result.success(
                listOf(spanne("Fruehdienst", 1, 5), spanne("Rufbereitschaft", 1, 16))
            )
        )

        f.viewModel.refreshVerdraengteRegeln()
        advanceUntilIdle()

        val map = f.viewModel.verdraengteRegeln.value
        // Genau die unterlegene Regel steht drin - die siegreiche NICHT (sonst stünde an beiden
        // Karten ein Hinweis und keiner wüsste mehr, welche denn nun gilt).
        assertEquals(setOf("r"), map.keys)
        assertEquals(1, map.getValue("r").tage)
        assertEquals(listOf("Frühdienst"), map.getValue("r").gewinnerNamen)
    }

    @Test
    fun `Ohne Konflikt bleibt die Auskunft leer`() = runTest {
        // Nur ein Dienst am Tag: der Normalfall darf keinen Hinweis erzeugen.
        val f = fixture(
            regeln = listOf(frueh, ruf),
            spannen = Result.success(listOf(spanne("Fruehdienst", 1, 5)))
        )

        f.viewModel.refreshVerdraengteRegeln()
        advanceUntilIdle()

        assertTrue(f.viewModel.verdraengteRegeln.value.isEmpty())
    }

    @Test
    fun `Mehrere Konflikttage zaehlen als Tage, nicht als Schichten`() = runTest {
        val f = fixture(
            regeln = listOf(frueh, ruf),
            spannen = Result.success(
                listOf(
                    spanne("Fruehdienst", 1, 5), spanne("Rufbereitschaft", 1, 16),
                    spanne("Fruehdienst", 2, 5), spanne("Rufbereitschaft", 2, 16)
                )
            )
        )

        f.viewModel.refreshVerdraengteRegeln()
        advanceUntilIdle()

        assertEquals(2, f.viewModel.verdraengteRegeln.value.getValue("r").tage)
    }

    @Test
    fun `Ist die Regel-Quelle ganz aus, wird kein Konflikt behauptet`() = runTest {
        // Dann wirkt KEINE Regel. Ein Hinweis "diese hier wirkt an 1 Tag nicht" wäre eine
        // Halbwahrheit, die die eigentliche Ursache (Schalter aus) verdeckt.
        val f = fixture(
            regeln = listOf(frueh, ruf),
            spannen = Result.success(
                listOf(spanne("Fruehdienst", 1, 5), spanne("Rufbereitschaft", 1, 16))
            ),
            rulesEnabled = false
        )

        f.viewModel.refreshVerdraengteRegeln()
        advanceUntilIdle()

        assertTrue(f.viewModel.verdraengteRegeln.value.isEmpty())
    }

    @Test
    fun `Sind die Schichtspannen nicht lesbar, wird kein Konflikt behauptet`() = runTest {
        // Ohne Dienstplan ist unbekannt, an welchen Tagen mehrere Dienste zusammentreffen -
        // dieselbe Richtung wie der fail-open des Schedulers: lieber nichts sagen als falsch.
        val f = fixture(
            regeln = listOf(frueh, ruf),
            spannen = Result.failure(IllegalStateException("DataStore kaputt"))
        )

        f.viewModel.refreshVerdraengteRegeln()
        advanceUntilIdle()

        assertTrue(f.viewModel.verdraengteRegeln.value.isEmpty())
    }
}
