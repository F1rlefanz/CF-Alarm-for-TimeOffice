package com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer

import com.github.f1rlefanz.cf_alarmfortimeoffice.tageslicht.TageslichtQuelle
import android.content.Context
import com.github.f1rlefanz.cf_alarmfortimeoffice.masterpause.MasterPausePrefs
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftConfig
import com.github.f1rlefanz.cf_alarmfortimeoffice.repository.interfaces.IShiftConfigRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.shift.ShiftSpanStore
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.IAlarmUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.freietage.keineFreienTage
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever

/**
 * Master-Pause-Backstop des Dimmers ([DimScheduleUseCase.applyCurrentState]).
 *
 * Vorher pruefte weder [DimScheduleUseCase] noch `DndScheduleUseCase` irgendwo
 * `MasterPausePrefs.pausedNow()` - die Pause wurde nur EINMALIG von `MasterPauseUseCase.pause()`
 * durchgesetzt. Danach genuegte ein einziger Regler/Toggle im Dimmer-Tab, um mitten in der Pause
 * wieder zu dimmen und die rollende Tick-Kette neu zu armieren, obwohl die UI "pausiert" anzeigt:
 * jeder ViewModel-Setter ruft `enable()` ungegatet. Gleiche Fehlerklasse (und gleiche Loesung) wie
 * der zentrale Master-Pause-Backstop in `AlarmUseCase.syncAlarms()`.
 *
 * Nur `applyCurrentState()` ist hier pruefbar; `scheduleNextTransition()` faellt in dieser
 * Umgebung ueber `PendingIntent.getBroadcast()` (Android-Stub) - dessen Pause-Gate wird am Geraet
 * verifiziert.
 */
class DimScheduleMasterPauseTest {

    private suspend fun sut(
        paused: Boolean,
        prefs: DimOverlayPrefs,
        notifier: DimCorrectionNotifier,
        alarmUseCase: IAlarmUseCase,
        eigenesRepo: IShiftConfigRepository? = null,
        context: Context = mock(),
    ): DimScheduleUseCase {
        val masterPausePrefs = mock<MasterPausePrefs>()
        whenever(masterPausePrefs.pausedNow()).thenReturn(paused)
        // Seit #217 ist die Konfiguration (Fenster am Typ) die EINZIGE Fenster-Quelle und wird
        // zuerst gelesen. Die feste Zeile traegt hier ein Nacht-Fenster, damit die Berechnung bis
        // zum Alarm-Bestand kommt (ohne wirksames Fenster endet sie davor).
        val repo = eigenesRepo ?: konfigRepo(Result.success(mitFenster()))
        val spanStore = mock<ShiftSpanStore>()
        whenever(spanStore.spansNow()).thenReturn(Result.success(emptyList()))
        return DimScheduleUseCase(context, alarmUseCase, spanStore, keineFreienTage(), repo, prefs, notifier, masterPausePrefs, TageslichtQuelle.OHNE)
    }

    private fun mitFenster(): ShiftConfig = konfigAusRegeln(
        listOf(
            DimRule(
                name = "Nacht", shiftPattern = DimRule.SHIFT_UNIVERSAL,
                windows = listOf(
                    DimWindow(
                        startAnchor = DimAnchor.CLOCK, startClockMinutes = 22 * 60,
                        endAnchor = DimAnchor.ALARM_SONST_CLOCK, endClockMinutes = 7 * 60
                    )
                )
            )
        ),
        schichtNamen = emptyList()
    )

    private suspend fun pausedPrefs(): DimOverlayPrefs {
        val prefs = mock<DimOverlayPrefs>()
        whenever(prefs.strengthNow()).thenReturn(55)
        whenever(prefs.warmthNow()).thenReturn(40)
        return prefs
    }

    @Test
    fun `Bei Master-Pause wird das Overlay abgeschaltet, nicht angeschaltet`() = runTest {
        val prefs = pausedPrefs()
        val notifier = mock<DimCorrectionNotifier>()
        val alarmUseCase = mock<IAlarmUseCase>()

        sut(paused = true, prefs = prefs, notifier = notifier, alarmUseCase = alarmUseCase).applyCurrentState()

        verify(prefs).setActiveOverlay(false, 55, 40)
        verify(prefs, never()).setActiveOverlay(eq(true), any(), any())
        verify(notifier).cancel()
        verify(notifier, never()).show(any(), any(), any(), any())
    }

    @Test
    fun `Bei Master-Pause wird der Alarm-Bestand gar nicht erst gelesen`() = runTest {
        // Der Backstop steht VOR jeder anderen Logik - keine Fenster-Berechnung, kein
        // Repository-/Kalender-Zugriff waehrend der Pause.
        val prefs = pausedPrefs()
        val alarmUseCase = mock<IAlarmUseCase>()

        sut(paused = true, prefs = prefs, notifier = mock(), alarmUseCase = alarmUseCase).applyCurrentState()

        verifyNoInteractions(alarmUseCase)
    }

    @Test
    fun `Ohne Master-Pause wird der Alarm-Bestand gelesen`() = runTest {
        // Gegenprobe: das Gate darf nicht dauerhaft blockieren. Ohne Pause laeuft die normale
        // Fenster-Berechnung an - hier ueber die seiteneffektfreie Vorschau geprueft, weil
        // applyCurrentState() danach den (in dieser Umgebung nicht stubbaren) Override-Mutex nutzt.
        val prefs = pausedPrefs()
        val alarmUseCase = mock<IAlarmUseCase>()
        whenever(alarmUseCase.getAllAlarms()).thenReturn(Result.success(emptyList()))

        sut(paused = false, prefs = prefs, notifier = mock(), alarmUseCase = alarmUseCase).previewTimeline()

        verify(alarmUseCase).getAllAlarms()
    }

    /**
     * #217: Der Backstop in `enable()` bleibt unveraendert (Pflichtregel) - auch jetzt, wo die
     * Fenster am Typ liegen. Unter Pause wird weder die Konfiguration noch der Alarm-Bestand
     * gelesen, das Overlay bleibt aus, und der rollende Tick wird abbestellt statt neu gestellt.
     */
    @Test
    fun `Master-Pause gatet enable - nichts gelesen, Overlay aus, Tick abbestellt`() = runTest {
        val prefs = pausedPrefs()
        val alarmUseCase = mock<IAlarmUseCase>()
        val repo = konfigRepo(Result.success(mitFenster()))
        mitDimmTick { context, am, pi ->
            sut(paused = true, prefs = prefs, notifier = mock(), alarmUseCase = alarmUseCase, eigenesRepo = repo, context = context)
                .enable()

            verify(am).cancel(pi)
            verify(am, never()).setExactAndAllowWhileIdle(any(), anyLong(), anyOrNull())
        }
        verifyNoInteractions(repo, alarmUseCase)
        verify(prefs, never()).setActiveOverlay(eq(true), any(), any())
    }
}
