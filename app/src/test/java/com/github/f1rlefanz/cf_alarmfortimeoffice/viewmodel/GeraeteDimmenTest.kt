package com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel

import androidx.lifecycle.viewModelScope
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimOverlayPrefs
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimScheduleUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.stub
import org.mockito.kotlin.whenever
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * Die Geraete-Einstellungen des Dimmens (#225): Farbwaerme und die 10-Sekunden-Vorschau.
 *
 * **Die Vorschau-Faelle sind umgezogen**, zuletzt aus `DimmerRulesViewModelPreviewCleanupTest`
 * (davor `DimmerViewModel.previewDim()`). Sie halten fest, dass die Vorschau immer hinter sich
 * aufraeumt. Der Fehler, den das verhindert: die Vorschau schreibt mit `setPreviewOverlay(…)` einen
 * PERSISTENTEN Zustand; haengt das Zuruecksetzen am `viewModelScope` oder fehlt das `finally`, laeuft
 * `applyCurrentState()` beim Verlassen der App nie, und `DimAccessibilityService` (unabhaengige
 * Lebensdauer, beobachtet nur `DimOverlayPrefs.renderState`) verdunkelt systemweit weiter.
 *
 * Dieselbe Klasse `DimmVorschau` traegt bis #226 auch die Vorschau im Regel-Editor
 * (`Pruefrunde6DimmPreviewStampTest`).
 */
@OptIn(ExperimentalCoroutinesApi::class) // Dispatchers.setMain/resetMain, advanceUntilIdle
class GeraeteDimmenTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun mockPrefs(): DimOverlayPrefs {
        val prefs = mock<DimOverlayPrefs>()
        whenever(prefs.warmth).thenReturn(flowOf(DimOverlayPrefs.DEFAULT_WARMTH))
        // Die Vorschau liest die Werte als suspend-Funktionen - unstubbed waeren sie `null`.
        prefs.stub {
            on { strengthNow() } doReturn 61
            on { warmthNow() } doReturn 17
        }
        return prefs
    }

    /** Zaehlt Einschalten und Aufraeumen; die Vorschau laeuft auf Dispatchers.IO. */
    private class Spur(prefs: DimOverlayPrefs) {
        val eingeschaltet = Semaphore(0)
        val aufgeraeumt = Semaphore(0)
        val werte = mutableListOf<Triple<Int, Int, Long>>()
        val dimSchedule = mock<DimScheduleUseCase>()

        init {
            prefs.stub {
                on { setPreviewOverlay(any(), any(), any()) } doAnswer { aufruf ->
                    werte += Triple(aufruf.getArgument(0), aufruf.getArgument(1), aufruf.getArgument(2))
                    eingeschaltet.release()
                    null
                }
            }
            dimSchedule.stub { on { applyCurrentState() } doAnswer { aufgeraeumt.release() } }
        }

        fun warteAufEinschalten() =
            assertTrue("Vorschau nicht eingeschaltet", eingeschaltet.tryAcquire(10, TimeUnit.SECONDS))

        fun warteAufAufraeumen(meldung: String) =
            assertTrue(meldung, aufgeraeumt.tryAcquire(5, TimeUnit.SECONDS))
    }

    private fun Job.beendet(): Boolean = runBlocking { withTimeoutOrNull(5_000) { join() } } != null

    @Test
    fun `Farbwaerme speichert den Wert und ruft direkt danach enable`() = runTest(dispatcher) {
        val prefs = mockPrefs()
        val dimSchedule = mock<DimScheduleUseCase>()
        val viewModel = GeraeteDimmenViewModel(prefs, dimSchedule)

        viewModel.setzeFarbwaerme(72)
        advanceUntilIdle()

        val reihenfolge = inOrder(prefs, dimSchedule)
        reihenfolge.verify(prefs).setWarmth(72)
        reihenfolge.verify(dimSchedule).enable()
    }

    @Test
    fun `Vorschau zeigt 10 Sekunden lang die Geraetewerte und schreibt den Ablauf mit`() {
        val prefs = mockPrefs()
        val spur = Spur(prefs)
        val viewModel = GeraeteDimmenViewModel(prefs, spur.dimSchedule)

        val vorher = System.currentTimeMillis()
        val vorschau = viewModel.zeigeVorschau()
        spur.warteAufEinschalten()
        val nachher = System.currentTimeMillis()

        val (staerke, waerme, ablauf) = spur.werte.single()
        assertEquals(61, staerke)
        assertEquals(17, waerme)
        val dauer = 10_000L + DimOverlayPrefs.PREVIEW_EXPIRY_GRACE_MS
        assertTrue("Ablauf $ablauf", ablauf in (vorher + dauer)..(nachher + dauer))
        vorschau.cancel()
    }

    /** Nutzer verlaesst die App; spaeter bricht die Vorschau ab. Beides darf sie nicht haengen lassen. */
    @Test
    fun `Vorschau haengt nicht am viewModelScope und raeumt bei Abbruch auf`() {
        val prefs = mockPrefs()
        val spur = Spur(prefs)
        val viewModel = GeraeteDimmenViewModel(prefs, spur.dimSchedule)
        val vorschau = viewModel.zeigeVorschau()
        spur.warteAufEinschalten()

        // Genau das macht onCleared().
        viewModel.viewModelScope.cancel()
        assertTrue("Die Vorschau darf mit dem viewModelScope nicht enden", vorschau.isActive)

        vorschau.cancel()
        spur.warteAufAufraeumen("Nach dem Abbruch bliebe der Bildschirm systemweit verdunkelt")
    }

    /**
     * Liefe das Aufraeumen der ersten Vorschau NACH dem Einschalten der zweiten, waere das Overlay
     * sofort wieder aus - oder die zweite Vorschau bliebe haengen.
     */
    @Test
    fun `Ein zweiter Tipp raeumt die laufende Vorschau zuerst auf`() {
        val prefs = mockPrefs()
        val spur = Spur(prefs)
        val viewModel = GeraeteDimmenViewModel(prefs, spur.dimSchedule)

        val erste = viewModel.zeigeVorschau()
        spur.warteAufEinschalten()
        val zweite = viewModel.zeigeVorschau()

        assertTrue("Der zweite Tipp muss die erste Vorschau beenden", erste.beendet())
        spur.warteAufEinschalten()
        spur.warteAufAufraeumen("Die erste Vorschau muss aufgeraeumt haben")
        zweite.cancel()
        spur.warteAufAufraeumen("Die zweite Vorschau muss aufgeraeumt haben")

        runBlocking {
            val reihenfolge = inOrder(prefs, spur.dimSchedule)
            reihenfolge.verify(prefs).setPreviewOverlay(any(), any(), any())
            reihenfolge.verify(spur.dimSchedule).applyCurrentState()
            reihenfolge.verify(prefs).setPreviewOverlay(any(), any(), any())
            reihenfolge.verify(spur.dimSchedule).applyCurrentState()
        }
    }
}
