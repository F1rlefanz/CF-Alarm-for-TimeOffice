package com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel

import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimOverlayPrefs
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimRule
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimRuleUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimScheduleUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftConfig
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.IShiftUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
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
import org.mockito.kotlin.mock
import org.mockito.kotlin.stub
import org.mockito.kotlin.whenever
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Pruefrunde 6, Befund 6 - die AUFRUFER-Seite: beide Vorschauen muessen ihren Ablaufzeitpunkt MIT
 * auf die Platte schreiben.
 *
 * Der Ablauf, der ohne das kaputt ging: die Vorschau schaltete die systemweite Verdunkelung
 * persistent EIN und nahm sie ausschliesslich ueber `delay()` + `finally` IM PROZESS zurueck. Ein
 * Prozesstod in diesem Fenster fuehrt kein `finally` aus; Android bindet den
 * `DimAccessibilityService` neu, der findet `dim_overlay_on = true` und verdunkelt weiter - bis zum
 * naechsten `applyCurrentState()`, im unguenstigsten Fall erst nach dem 6h-Wartungslauf.
 *
 * `DimmerRulesViewModel.previewRule()` ist der Zwilling derselben Konstruktion und wird deshalb
 * hier mitgeprueft: er war in einer frueheren Runde zu Unrecht freigesprochen worden.
 */
@OptIn(ExperimentalCoroutinesApi::class) // Dispatchers.setMain/resetMain
class Pruefrunde6DimmPreviewStampTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        // viewModelScope laeuft auf Dispatchers.Main.immediate - ohne Main-Dispatcher scheitert
        // schon die Konstruktion (stateIn im Property-Initializer).
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** Faengt den geschriebenen Ablaufzeitpunkt ab - die Vorschau laeuft auf Dispatchers.IO. */
    private class Stamp {
        val value = AtomicLong(Long.MIN_VALUE)
        val written = CountDownLatch(1)
    }

    private fun mockPrefs(stamp: Stamp): DimOverlayPrefs {
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
        prefs.stub {
            onBlocking { strengthNow() } doReturn DimOverlayPrefs.DEFAULT_STRENGTH
            onBlocking { warmthNow() } doReturn DimOverlayPrefs.DEFAULT_WARMTH
            onBlocking { setPreviewOverlay(any(), any(), any()) } doAnswer { invocation ->
                stamp.value.set(invocation.getArgument<Long>(2))
                stamp.written.countDown()
                null
            }
        }
        return prefs
    }

    private fun assertStamped(stamp: Stamp, startedAt: Long, seconds: Int) {
        assertTrue(
            "Die Vorschau hat keinen Ablaufzeitpunkt auf die Platte geschrieben - nach einem " +
                "Prozesstod bliebe der Bildschirm systemweit verdunkelt",
            stamp.written.await(10, TimeUnit.SECONDS)
        )
        val expiry = stamp.value.get()
        assertTrue(
            "Der Ablaufzeitpunkt muss die Vorschaudauer abdecken (war $expiry)",
            expiry >= startedAt + seconds * 1000L
        )
        assertTrue(
            "Der Ablaufzeitpunkt darf nicht beliebig weit in der Zukunft liegen - er ist der " +
                "Auffangpfad, keine zweite Dimm-Quelle (war $expiry)",
            expiry <= System.currentTimeMillis() + seconds * 1000L + DimOverlayPrefs.PREVIEW_EXPIRY_GRACE_MS
        )
    }

    @Test
    fun `previewDim schreibt den Ablaufzeitpunkt mit`() {
        val stamp = Stamp()
        val prefs = mockPrefs(stamp)
        val shiftUseCase = mock<IShiftUseCase>()
        whenever(shiftUseCase.shiftConfig).thenReturn(flowOf(ShiftConfig()))
        val viewModel = DimmerViewModel(prefs, mock<DimScheduleUseCase>(), shiftUseCase)

        val startedAt = System.currentTimeMillis()
        viewModel.previewDim(seconds = 5)

        assertStamped(stamp, startedAt, seconds = 5)
    }

    /** Der Zwilling: dieselbe Konstruktion im Regel-Editor, derselbe Ausfall. */
    @Test
    fun `previewRule schreibt den Ablaufzeitpunkt mit`() {
        val stamp = Stamp()
        val prefs = mockPrefs(stamp)
        val shiftUseCase = mock<IShiftUseCase>()
        whenever(shiftUseCase.shiftConfig).thenReturn(flowOf(ShiftConfig()))
        val ruleUseCase = mock<DimRuleUseCase>()
        whenever(ruleUseCase.rules).thenReturn(flowOf(emptyList<DimRule>()))
        val viewModel = DimmerRulesViewModel(ruleUseCase, shiftUseCase, mock<DimScheduleUseCase>(), prefs)

        val startedAt = System.currentTimeMillis()
        viewModel.previewRule(strength = 60, warmth = 30, seconds = 5)

        assertStamped(stamp, startedAt, seconds = 5)
    }
}
