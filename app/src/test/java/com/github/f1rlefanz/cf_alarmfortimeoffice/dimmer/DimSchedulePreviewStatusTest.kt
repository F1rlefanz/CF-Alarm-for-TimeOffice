package com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer

import com.github.f1rlefanz.cf_alarmfortimeoffice.tageslicht.TageslichtQuelle
import android.content.Context
import com.github.f1rlefanz.cf_alarmfortimeoffice.masterpause.MasterPausePrefs
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.AlarmInfo
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftConfig
import com.github.f1rlefanz.cf_alarmfortimeoffice.shift.ShiftSpanStore
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.IAlarmUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.freietage.keineFreienTage
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * [DimScheduleUseCase.previewTimelineWithStatus] - der Status-Kanal, ueber den ein Lesefehler des
 * Alarm-Bestands die Grenze vom Dimmer zu DND ueberquert.
 *
 * WARUM eigenstaendig getestet: die Fenster-Berechnung des Dimmers verflacht einen transienten
 * Lesefehler bewusst auf "keine Fenster" (fail-open, es wird dann NICHT gedimmt). Fuer den
 * Aufrufer ist das damit nicht mehr von "heute gibt es einfach kein Fenster" zu unterscheiden - und
 * genau diese Unterscheidung braucht DND-Modus 1 ("Schlaf-Fenster folgt dem Dimmer"), um seinen
 * 15-Minuten-Retry statt des 6-Stunden-Keep-alive zu planen. Vorher blieb DND nach einem
 * Lesefehler bis zu 6 h aus, obwohl sich der Dimmer selbst nach 15 min erholte und die Nacht dimmte.
 *
 * Die Einbahnstrasse bleibt gewahrt: der Dimmer liefert nur einen Wert, er weiss nichts von DND.
 */
class DimSchedulePreviewStatusTest {

    /** Eine Nacht-Regel fuer jeden Tag, ueber die echte Migration an die feste Zeile gebracht (#217). */
    private val mitFenster: ShiftConfig = konfigAusRegeln(
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

    private suspend fun sut(
        alarms: Result<List<AlarmInfo>>,
        konfig: Result<ShiftConfig> = Result.success(mitFenster)
    ): DimScheduleUseCase {
        val prefs = mock<DimOverlayPrefs>()
        // Suspend-Getter liefern ungestubbt `null` (Mockito-Default ueber Any?).
        whenever(prefs.strengthNow()).thenReturn(55)
        whenever(prefs.warmthNow()).thenReturn(40)

        val alarmUseCase = mock<IAlarmUseCase>()
        whenever(alarmUseCase.getAllAlarms()).thenReturn(alarms)
        val masterPausePrefs = mock<MasterPausePrefs>()
        whenever(masterPausePrefs.pausedNow()).thenReturn(false)

        val spanStore = mock<ShiftSpanStore>()
        whenever(spanStore.spansNow()).thenReturn(Result.success(emptyList()))
        // Seit #217 ist die Konfiguration (Fenster am Typ) die einzige Fenster-Quelle und wird zuerst gelesen.
        return DimScheduleUseCase(
            mock<Context>(),
            alarmUseCase,
            spanStore,
            keineFreienTage(),
            konfigRepo(konfig),
            prefs,
            mock<DimCorrectionNotifier>(),
            masterPausePrefs,
            TageslichtQuelle.OHNE
        )
    }

    @Test
    fun `Ein Lesefehler des Alarm-Bestands wird als alarmReadFailed nach aussen gemeldet`() = runTest {
        val preview = sut(Result.failure(RuntimeException("Kalender/Token kaputt"))).previewTimelineWithStatus()

        assertTrue(preview.intervals.isEmpty())
        assertTrue(
            "Ohne dieses Flag ist der Lesefehler von einer legitim leeren Nacht nicht zu " +
                "unterscheiden - DND-Modus 1 plant dann den 6-Stunden-Keep-alive statt des Retrys",
            preview.alarmReadFailed
        )
    }

    @Test
    fun `Eine legitim leere Fensterliste ist KEIN Lesefehler`() = runTest {
        // Kein Typ hat ein Fenster (seit #217 der Nachfolger von "Dimmer aus"): leer, aber
        // fehlerfrei. "Leere Fensterliste" ist bedeutungstragend (z. B. Nachtdienst-Unterdrueckung)
        // und darf keinen Retry-Tick ausloesen.
        val preview = sut(Result.success(emptyList()), konfig = Result.success(ShiftConfig())).previewTimelineWithStatus()

        assertTrue(preview.intervals.isEmpty())
        assertFalse(preview.alarmReadFailed)
    }

    @Test
    fun `Eine unlesbare Konfiguration wird als alarmReadFailed gemeldet, nicht als leere Nacht`() = runTest {
        // #217: Die Fenster liegen am Typ. Ist die Konfiguration nicht lesbar (etwa vor der ersten
        // Entsperrung), darf das nie zu "kein Typ hat Fenster" werden - dann bestellte sich die
        // Kette ab, und DND-Modus 1 plante den 6-h-Keep-alive statt des Retrys.
        val preview = sut(
            Result.success(emptyList()),
            konfig = Result.failure(IllegalStateException("vor der ersten Entsperrung"))
        ).previewTimelineWithStatus()

        assertTrue(preview.intervals.isEmpty())
        assertTrue(preview.alarmReadFailed)
    }
}
