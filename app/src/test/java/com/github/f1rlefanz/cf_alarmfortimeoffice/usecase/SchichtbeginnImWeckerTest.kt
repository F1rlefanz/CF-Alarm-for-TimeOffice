package com.github.f1rlefanz.cf_alarmfortimeoffice.usecase

import com.github.f1rlefanz.cf_alarmfortimeoffice.alarm.FakeFeedNeueinlesenStore
import com.github.f1rlefanz.cf_alarmfortimeoffice.alarm.FakeKalenderVorausschauPrefs
import com.github.f1rlefanz.cf_alarmfortimeoffice.alarm.FakeSyncHorizonStore
import com.github.f1rlefanz.cf_alarmfortimeoffice.alarm.KrankmeldungNotifier
import com.github.f1rlefanz.cf_alarmfortimeoffice.alarm.ShiftChangeNotifier
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.ZeitkettenArmierer
import com.github.f1rlefanz.cf_alarmfortimeoffice.freietage.keineFreienTage
import com.github.f1rlefanz.cf_alarmfortimeoffice.masterpause.MasterPausePrefs
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.AlarmInfo
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.AlarmSkipState
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftConfig
import com.github.f1rlefanz.cf_alarmfortimeoffice.repository.interfaces.IAlarmRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.repository.interfaces.IShiftConfigRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.service.AlarmManagerService
import com.github.f1rlefanz.cf_alarmfortimeoffice.shift.ShiftMatch
import com.github.f1rlefanz.cf_alarmfortimeoffice.shift.ShiftRecognitionEngine
import com.github.f1rlefanz.cf_alarmfortimeoffice.shift.ShiftSpanStore
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.AlarmSkipResult
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.IAlarmSkipUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.SkipProcessResult
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.business.DateTimeFormats
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * #183: Ein Wecker ohne bekannten Schichtbeginn (`AlarmInfo.shiftStartTime = 0` - manueller
 * Wecker, Wecker an einem Tag ohne Kalendereintrag) zeigt im Vollbild und in der
 * Wecker-Benachrichtigung keinen Satz "Deine Schicht beginnt um ..." mehr. Vorher stand dort die
 * Weckzeit, weil der Ersatz-Termin aus [AlarmUseCase.scheduleSystemAlarm] auf sie zurueckfaellt.
 *
 * Geprueft wird die Kette bis zu dem Text, den [AlarmManagerService] als
 * `AlarmReceiver.EXTRA_SHIFT_START_TIME` in den Wecker-Intent schreibt: [AlarmUseCase] baut den
 * Ersatz-Termin, [AlarmManagerService.schichtbeginnText] macht daraus den Text. Das Intent selbst
 * ist im JVM-Test nicht lesbar (kein Robolectric, `isReturnDefaultValues`), deshalb endet die
 * Kette am Text. Dass ein leerer Text den Satz weglaesst, regeln Vollbild, Weckton-Notification
 * und Notausgang je mit `isBlank()`/`isNotBlank()`.
 */
class SchichtbeginnImWeckerTest {

    private class FakeShiftConfigRepository(private val config: ShiftConfig) : IShiftConfigRepository {
        override val shiftConfig: Flow<ShiftConfig> = flowOf(config)
        override suspend fun saveShiftConfig(config: ShiftConfig): Result<Unit> = Result.success(Unit)
        override suspend fun getCurrentShiftConfig(): Result<ShiftConfig> = Result.success(config)
    }

    /** Wird von scheduleSystemAlarm nicht gelesen - nur der Konstruktor braucht ihn. */
    private class LeeresAlarmRepository : IAlarmRepository {
        private val state = MutableStateFlow(emptyList<AlarmInfo>())
        override val activeAlarms: Flow<List<AlarmInfo>> = state
        override suspend fun isPersistenceBlocked(): Boolean = false
        override suspend fun istLetzterSchreibvorgangGescheitert(): Boolean = false
        override suspend fun saveAlarm(alarmInfo: AlarmInfo): Result<Unit> = Result.success(Unit)
        override suspend fun getAllAlarms(): Result<List<AlarmInfo>> = Result.success(state.value)
        override suspend fun deleteAlarm(alarmId: Int): Result<Unit> = Result.success(Unit)
        override suspend fun deleteAllAlarms(): Result<Unit> = Result.success(Unit)
    }

    /** Nichts uebersprungen - der Skip-Backstop laesst den Wecker durch. */
    private class NichtsUebersprungen : IAlarmSkipUseCase {
        override suspend fun skipNextAlarm(): Result<AlarmSkipResult> =
            Result.failure(UnsupportedOperationException("nicht benutzt"))
        override suspend fun cancelSkip(): Result<Unit> = Result.success(Unit)
        override suspend fun checkAndProcessSkip(alarmId: Int): Result<SkipProcessResult> =
            Result.success(SkipProcessResult.ALARM_EXECUTED)
        override suspend fun getSkipStatus(): Result<AlarmSkipState> = Result.success(AlarmSkipState())
        override suspend fun clearExpiredSkip(): Result<Boolean> = Result.success(false)
        override val skipStatusFlow: Flow<AlarmSkipState> = flowOf(AlarmSkipState())
    }

    // Weit in der Zukunft, damit kein Gate die Weckzeit als verstrichen verwirft.
    private val weckzeit = LocalDateTime.of(2035, 6, 4, 5, 30)
    private val schichtbeginn = LocalDateTime.of(2035, 6, 4, 6, 0)

    private fun millis(zeit: LocalDateTime) = zeit.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    private fun alsText(zeit: LocalDateTime) =
        zeit.format(DateTimeFormatter.ofPattern(DateTimeFormats.STANDARD_DATETIME))

    private fun wecker(shiftStartTime: Long) = AlarmInfo(
        id = 4711,
        shiftId = "manual",
        shiftName = "Manueller Wecker",
        triggerTime = millis(weckzeit),
        formattedTime = "05:30",
        eventId = "manual_4711",
        eventChecksum = "c",
        shiftStartTime = shiftStartTime
    )

    /** Stellt [alarmInfo] ueber scheduleSystemAlarm und liefert den Text fuer das Intent-Extra. */
    private suspend fun schichtbeginnExtraFuer(alarmInfo: AlarmInfo): String {
        val config = ShiftConfig(autoAlarmEnabled = true, definitions = emptyList())
        val manager = mock<AlarmManagerService>()
        whenever(manager.setAlarmFromShiftMatch(any(), any(), any())).thenReturn(
            AlarmManagerService.AlarmStatus(
                systemAlarmSet = true,
                canScheduleExactAlarms = true,
                alarmStatusMessage = null
            )
        )
        val useCase = AlarmUseCase(
            LeeresAlarmRepository(),
            manager,
            FakeShiftConfigRepository(config),
            ShiftRecognitionEngine(FakeShiftConfigRepository(config)),
            NichtsUebersprungen(),
            mock<ShiftChangeNotifier>(),
            mock<MasterPausePrefs>(),
            mock<ShiftSpanStore>(),
            FakeSyncHorizonStore(),
            FakeFeedNeueinlesenStore(),
            keineFreienTage(),
            FakeKalenderVorausschauPrefs(FakeKalenderVorausschauPrefs.UEBER_ALLE_TESTDATEN_TAGE),
            dagger.Lazy { mock<ZeitkettenArmierer>() },
            mock<KrankmeldungNotifier>()
        )

        val result = useCase.scheduleSystemAlarm(alarmInfo)

        assertTrue("Der Wecker muss gestellt werden: $result", result.isSuccess)
        val captor = argumentCaptor<ShiftMatch>()
        verify(manager).setAlarmFromShiftMatch(captor.capture(), any(), any())
        return AlarmManagerService.schichtbeginnText(captor.firstValue)
    }

    @Test
    fun `ohne Schichtbeginn bleibt das Extra leer - die Weckzeit erscheint nicht als Schichtbeginn`() = runTest {
        val extra = schichtbeginnExtraFuer(wecker(shiftStartTime = 0L))

        assertEquals(
            "shiftStartTime = 0 heisst unbekannt: kein Satz 'Deine Schicht beginnt um', " +
                "vor allem nicht mit der Weckzeit ${alsText(weckzeit)}",
            "",
            extra
        )
    }

    @Test
    fun `mit echtem Schichtbeginn steht der Schichtbeginn im Extra, nicht die Weckzeit`() = runTest {
        val extra = schichtbeginnExtraFuer(wecker(shiftStartTime = millis(schichtbeginn)))

        assertEquals(alsText(schichtbeginn), extra)
        assertNotEquals(alsText(weckzeit), extra)
    }
}
