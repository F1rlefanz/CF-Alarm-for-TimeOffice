package com.github.f1rlefanz.cf_alarmfortimeoffice.usecase

import com.github.f1rlefanz.cf_alarmfortimeoffice.model.AlarmInfo
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.AlarmSkipState
import com.github.f1rlefanz.cf_alarmfortimeoffice.repository.interfaces.IAlarmRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.repository.interfaces.IAlarmSkipRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.service.AlarmManagerService
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.ManualAlarmSnapshot
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.AlarmViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * Haelt fest, wie ein MANUELL gestellter Wecker das Ueberspringen ueberlebt - und wie NICHT.
 *
 * DER ERSTE FEHLER: `cancelSkip()` stiess als Wiederaufbau ausschliesslich den Kalender-Sync an.
 * Ein manueller Wecker entsteht aus keinem Kalender-Event - fuer genau diese Alarmart war
 * "Ueberspringen" damit endgueltig, obwohl die Oberflaeche mit "Aufheben" eine Umkehr verspricht.
 *
 * DER ZWEITE FEHLER war der erste Reparaturversuch: den Repository-Eintrag manueller Wecker beim
 * Ueberspringen einfach stehenzulassen. Das bricht eine Invariante, auf die sich mehrere
 * voneinander unabhaengige Stellen verlassen - der Direct-Boot-Spiegel, aus dem der
 * `BootReceiver` jeden Zukunfts-Eintrag am Skip-Backstop VORBEI wieder armiert, und die
 * Hue-Tagesplanung, die ihren Bestand ungefiltert aus `getAllAlarms()` liest. Der uebersprungene
 * Wecker klingelte nach einem naechtlichen Neustart trotzdem und schaltete morgens das Licht an.
 *
 * DESHALB GILT: geloescht wird IMMER (erst `cancelSystemAlarm()`, dann `deleteAlarm()`), und die
 * Umkehrbarkeit traegt ein vollstaendiger Schnappschuss im Skip-Zustand.
 */
class AlarmSkipManualAlarmTest {

    private val now = System.currentTimeMillis()

    private fun alarm(id: Int, shiftId: String, offsetMs: Long) = AlarmInfo(
        id = id,
        shiftId = shiftId,
        shiftName = "Frueh$id",
        triggerTime = now + offsetMs,
        formattedTime = "t$id"
    )

    private class FakeAlarmRepository(initial: List<AlarmInfo>) : IAlarmRepository {
        override suspend fun isPersistenceBlocked(): Boolean = false
        private val state = MutableStateFlow(initial)
        override val activeAlarms: Flow<List<AlarmInfo>> = state
        val current: List<AlarmInfo> get() = state.value

        override suspend fun saveAlarm(alarmInfo: AlarmInfo): Result<Unit> {
            state.value = state.value.filterNot { it.id == alarmInfo.id } + alarmInfo
            return Result.success(Unit)
        }

        override suspend fun getAllAlarms(): Result<List<AlarmInfo>> = Result.success(state.value)
        override suspend fun getAlarmById(alarmId: Int): Result<AlarmInfo?> =
            Result.success(state.value.find { it.id == alarmId })

        override suspend fun deleteAlarm(alarmId: Int): Result<Unit> {
            state.value = state.value.filterNot { it.id == alarmId }
            return Result.success(Unit)
        }

        override suspend fun deleteAllAlarms(): Result<Unit> {
            state.value = emptyList()
            return Result.success(Unit)
        }

        override suspend fun alarmExists(alarmId: Int): Result<Boolean> =
            Result.success(state.value.any { it.id == alarmId })
    }

    private class FakeSkipRepository : IAlarmSkipRepository {
        private var state: AlarmSkipState = AlarmSkipState()
        var setSkippedFor: Int? = null

        /** Was der UseCase als Schnappschuss uebergeben hat - null heisst "kein manueller Wecker". */
        var lastSnapshot: String? = null

        override suspend fun setNextAlarmSkipped(
            alarmId: Int,
            triggerTime: Long,
            reason: String,
            manualAlarmSnapshot: String?
        ): Result<Unit> {
            setSkippedFor = alarmId
            lastSnapshot = manualAlarmSnapshot
            state = state.copy(
                isNextAlarmSkipped = true,
                skippedAlarmId = alarmId,
                skippedAlarmTriggerTime = triggerTime,
                skipReason = reason,
                skippedManualAlarm = manualAlarmSnapshot
            )
            return Result.success(Unit)
        }

        override suspend fun clearSkipStatus(): Result<Unit> {
            state = AlarmSkipState()
            return Result.success(Unit)
        }

        override suspend fun isAlarmSkipped(alarmId: Int): Result<Boolean> =
            Result.success(state.isNextAlarmSkipped && state.skippedAlarmId == alarmId)

        override suspend fun getSkipStatus(): Result<AlarmSkipState> = Result.success(state)
        override val skipStatusFlow: Flow<AlarmSkipState> = flowOf(state)
    }

    private fun mockManager(): AlarmManagerService {
        val m = mock<AlarmManagerService>()
        whenever(m.cancelSystemAlarm(any())).thenReturn(
            AlarmManagerService.AlarmStatus(
                systemAlarmSet = true,
                canScheduleExactAlarms = true,
                alarmStatusMessage = null
            )
        )
        return m
    }

    @Test
    fun `skipNextAlarm - manueller Alarm wird geloescht UND vollstaendig gesichert`() = runTest {
        val manual = alarm(
            id = 11,
            shiftId = "${AlarmSkipUseCase.MANUAL_SHIFT_ID_PREFIX}frueh_20260820",
            offsetMs = 60 * 60 * 1000L
        )
        val repo = FakeAlarmRepository(listOf(manual))
        val skipRepo = FakeSkipRepository()
        val manager = mockManager()

        val result = AlarmSkipUseCase(skipRepo, repo, manager).skipNextAlarm()

        assertTrue(result.isSuccess)
        assertEquals("Skip muss fuer den manuellen Alarm gesetzt werden", 11, skipRepo.setSkippedFor)
        verify(manager).cancelSystemAlarm(11)
        assertNull(
            "Ein uebersprungener Alarm MUSS aus dem Bestand verschwinden - sonst armiert ihn der " +
                "Direct-Boot-Spiegel nach einem Neustart wieder und die Hue-Planung schaltet Licht",
            repo.current.find { it.id == 11 }
        )
        assertEquals(
            "Ohne den Schnappschuss waere 'Aufheben' fuer einen manuellen Wecker wirkungslos",
            manual,
            ManualAlarmSnapshot.decode(skipRepo.lastSnapshot).getOrThrow()
        )
    }

    @Test
    fun `skipNextAlarm - kalenderbasierter Alarm wird geloescht und NICHT gesichert`() = runTest {
        // Gegenprobe: ein Kalenderalarm entsteht beim "Aufheben" aus dem Kalenderstand neu. Ein
        // Schnappschuss waere hier nicht nur ueberfluessig, sondern falsch - er wuerde die
        // inzwischen moeglicherweise verschobene Schicht mit ihrer ALTEN Weckzeit zurueckholen.
        // Das `null` loescht ausserdem einen Schnappschuss aus einem frueheren manuellen Skip.
        val calendarAlarm = alarm(id = 12, shiftId = "shift-frueh", offsetMs = 60 * 60 * 1000L)
        val repo = FakeAlarmRepository(listOf(calendarAlarm))
        val skipRepo = FakeSkipRepository()
        val manager = mockManager()

        val result = AlarmSkipUseCase(skipRepo, repo, manager).skipNextAlarm()

        assertTrue(result.isSuccess)
        verify(manager).cancelSystemAlarm(12)
        assertNull(repo.current.find { it.id == 12 })
        assertNull("Kalenderalarme brauchen keinen Schnappschuss", skipRepo.lastSnapshot)
    }

    @Test
    fun `ManualAlarmSnapshot - Rundlauf erhaelt jedes Feld`() {
        // Mutationsprobe gegen das haeufigste Versehen: ein neues AlarmInfo-Feld wird beim
        // Serialisieren vergessen und der wiederhergestellte Wecker ist stillschweigend ein
        // anderer (z.B. isSilent verloren -> ein stiller Wecker klingelt ploetzlich).
        val alarm = AlarmInfo(
            id = 4711,
            shiftId = "manual_spaet_20260820",
            shiftName = "Spät (Manuell)",
            triggerTime = 1_760_000_000_000L,
            formattedTime = "20.08.2026 13:15",
            eventId = "event-abc",
            eventChecksum = "checksum-xyz",
            shiftEndTime = 1_760_030_000_000L,
            shiftStartTime = 1_760_010_000_000L,
            isSilent = true
        )

        val decoded = ManualAlarmSnapshot.decode(ManualAlarmSnapshot.encode(alarm)).getOrThrow()

        assertEquals(alarm, decoded)
    }

    @Test
    fun `ManualAlarmSnapshot - fehlender Schnappschuss ist null, kaputter ist ein Fehler`() {
        // Der Unterschied ist tragend: "kein Schnappschuss" heisst kalenderbasiert und ist normal.
        // Wuerde ein unlesbarer Schnappschuss ebenfalls zu null degradieren, verschwaende ein
        // manueller Wecker beim "Aufheben" wortlos.
        assertNull(ManualAlarmSnapshot.decode(null).getOrThrow())
        assertNull(ManualAlarmSnapshot.decode("").getOrThrow())
        assertTrue(
            "Ein kaputter Schnappschuss MUSS als Fehler sichtbar werden",
            ManualAlarmSnapshot.decode("{kein json").isFailure
        )
    }

    @Test
    fun `Manual-Praefix von UseCase und ViewModel duerfen nicht auseinanderlaufen`() {
        // Der UseCase darf nicht auf das ViewModel zugreifen und fuehrt das Praefix deshalb
        // selbst. Laeuft einer der beiden Werte weg, wird ein manueller Wecker beim Ueberspringen
        // nicht mehr gesichert und ist unwiederbringlich - dieser Test faellt dann vorher um.
        assertEquals(
            AlarmViewModel.ManualAlarmConstants.MANUAL_SHIFT_ID_PREFIX,
            AlarmSkipUseCase.MANUAL_SHIFT_ID_PREFIX
        )
    }
}
