package com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel

import androidx.lifecycle.viewModelScope
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimOverlayPrefs
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimScheduleUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftConfig
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.IShiftUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.stub
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Haelt fest, dass die Dimm-VORSCHAU immer hinter sich aufraeumt - auch dann, wenn der Nutzer
 * waehrend der 5 Sekunden die App verlaesst.
 *
 * Der Fehler, den das verhindert: `previewDim()` schrieb mit `setPreviewOverlay(…)` einen
 * PERSISTENTEN Zustand und stellte den regulaeren erst NACH `delay()` wieder her - alles im
 * `viewModelScope`. Zweimal Zurueck oder Wegwischen aus den Recents cancelte das `delay()`,
 * `applyCurrentState()` lief nie, und `DimAccessibilityService` (voellig unabhaengige Lebensdauer,
 * beobachtet nur `DimOverlayPrefs.renderState`) verdunkelte systemweit weiter. Geheilt haette das
 * erst der naechste Dimm-Tick - der bei komplett ausgeschalteten Fenster-Quellen (genau der
 * Zustand von jemandem, der die Vorschau zum AUSPROBIEREN nutzt) gar nicht kommen muss.
 *
 * Dieselbe Ueberlegung wie bei Hue (`HueLightUseCase.followUpScope`, siehe
 * `RulePreviewCleanupTest`): das Aufraeumen gehoert nicht an den Scope des ausloesenden
 * Bildschirms.
 */
@OptIn(ExperimentalCoroutinesApi::class) // Dispatchers.setMain/resetMain
class DimmerViewModelPreviewCleanupTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        // viewModelScope laeuft auf Dispatchers.Main.immediate - ohne Main-Dispatcher scheitert
        // schon die Konstruktion (uiState startet ein stateIn im Property-Initializer).
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /**
     * `uiState` kombiniert neun Prefs-Flows im Property-Initializer - alle muessen gestubbt sein,
     * sonst scheitert bereits die Konstruktion an einem `null`-Flow.
     */
    private fun mockPrefs(): DimOverlayPrefs {
        val prefs = mock<DimOverlayPrefs>()
        whenever(prefs.toggles).thenReturn(
            flowOf(
                DimOverlayPrefs.Toggles(
                    wellnessEnabled = false,
                    rulesEnabled = false,
                    nightDefaultEnabled = false
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
        // PFLICHT, nicht Kuer: previewDim() liest die beiden Werte als SUSPEND-Funktionen
        // (`setPreviewOverlay(prefs.strengthNow(), prefs.warmthNow(), …)`) - nicht ueber die
        // gleichnamigen Flows darueber. Unstubbed liefert Mockito fuer eine suspend-Funktion
        // `null`, was beim Entpacken nach `Int` eine NPE wirft; die faengt seit dem Fix der
        // CoroutineExceptionHandler des previewScope ab, und `setPreviewOverlay` wird nie
        // erreicht - der Test scheitert dann an der ersten Zusicherung und sieht aus, als sei
        // das Aufraeumen kaputt, obwohl nur der Stub fehlt.
        prefs.stub {
            on { strengthNow() } doReturn DimOverlayPrefs.DEFAULT_STRENGTH
            on { warmthNow() } doReturn DimOverlayPrefs.DEFAULT_WARMTH
        }
        return prefs
    }

    private fun buildViewModel(prefs: DimOverlayPrefs, dimSchedule: DimScheduleUseCase): DimmerViewModel {
        val shiftUseCase = mock<IShiftUseCase>()
        whenever(shiftUseCase.shiftConfig).thenReturn(flowOf(ShiftConfig()))
        return DimmerViewModel(prefs, dimSchedule, shiftUseCase)
    }

    /** Der eigentliche Fehlerfall: App waehrend der Vorschau verlassen. */
    @Test
    fun `Vorschau raeumt auf, auch wenn der viewModelScope waehrenddessen gecancelt wird`() {
        val prefs = mockPrefs()
        val overlayOn = CountDownLatch(1)
        // setPreviewOverlay() gibt das Preferences-Objekt von DataStore.edit() zurueck; die Vorschau
        // wertet es nicht aus, deshalb genuegt null als Antwort.
        prefs.stub {
            on { setPreviewOverlay(any(), any(), any()) } doAnswer {
                overlayOn.countDown()
                null
            }
        }

        val cleanedUp = CountDownLatch(1)
        val dimSchedule = mock<DimScheduleUseCase>()
        dimSchedule.stub { on { applyCurrentState() } doAnswer { cleanedUp.countDown() } }

        val viewModel = buildViewModel(prefs, dimSchedule)
        viewModel.previewDim(seconds = 1)

        assertTrue(
            "Die Vorschau muss das Overlay ueberhaupt erst eingeschaltet haben",
            overlayOn.await(10, TimeUnit.SECONDS)
        )
        // Nutzer verlaesst die App: genau das macht onCleared() mit dem viewModelScope.
        viewModel.viewModelScope.cancel()

        assertTrue(
            "Ohne Zuruecksetzen bleibt der Bildschirm systemweit verdunkelt - der alte Fehler",
            cleanedUp.await(10, TimeUnit.SECONDS)
        )
        runBlocking { verify(prefs).setPreviewOverlay(any(), any(), any()) }
    }

    /**
     * Ein zweiter Tipp waehrend einer laufenden Vorschau muss die erste sauber abloesen: erst deren
     * Aufraeumen zu Ende laufen lassen, dann neu einschalten. Liefe das Aufraeumen der ersten
     * Vorschau NACH dem Einschalten der zweiten, waere das Overlay danach aus, obwohl der Nutzer
     * gerade eine Vorschau angefordert hat - bzw. umgekehrt haengengeblieben.
     */
    @Test
    fun `Ein zweiter Tipp loest die laufende Vorschau in der richtigen Reihenfolge ab`() {
        val prefs = mockPrefs()
        val firstOverlayOn = CountDownLatch(1)
        prefs.stub {
            on { setPreviewOverlay(any(), any(), any()) } doAnswer {
                firstOverlayOn.countDown()
                null
            }
        }

        val cleanedUpTwice = CountDownLatch(2)
        val dimSchedule = mock<DimScheduleUseCase>()
        dimSchedule.stub { on { applyCurrentState() } doAnswer { cleanedUpTwice.countDown() } }

        val viewModel = buildViewModel(prefs, dimSchedule)

        viewModel.previewDim(seconds = 30) // laeuft noch, wenn der zweite Tipp kommt
        assertTrue(
            "Erste Vorschau muss eingeschaltet haben, bevor der zweite Tipp sie abloest",
            firstOverlayOn.await(10, TimeUnit.SECONDS)
        )
        viewModel.previewDim(seconds = 0)

        assertTrue(
            "Beide Vorschauen muessen aufgeraeumt haben",
            cleanedUpTwice.await(10, TimeUnit.SECONDS)
        )
        runBlocking {
            val order = inOrder(prefs, dimSchedule)
            order.verify(prefs).setPreviewOverlay(any(), any(), any())
            order.verify(dimSchedule).applyCurrentState()
            order.verify(prefs).setPreviewOverlay(any(), any(), any())
            order.verify(dimSchedule).applyCurrentState()
        }
    }
}
