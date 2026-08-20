package com.github.f1rlefanz.cf_alarmfortimeoffice.usecase

import com.github.f1rlefanz.cf_alarmfortimeoffice.model.AlarmInfo
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.AlarmSkipState
import com.github.f1rlefanz.cf_alarmfortimeoffice.repository.interfaces.IAlarmRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.repository.interfaces.IAlarmSkipRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.service.AlarmManagerService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * Haelt fest, dass das Ueberspringen die GESPERRTE PERSISTENZ bemerkt.
 *
 * DER FEHLER (Regression aus Pruefrunde 7): die neue Ruecknahme haengt am Fehlersignal von
 * `deleteAlarm()` - und genau im wichtigsten Fall sendet das Repository keines.
 * `AlarmRepository.deleteAlarm()` entfernt den Eintrag aus `_activeAlarms` und ruft
 * `persistToDataStore()`; das kehrt bei gesperrter Persistenz (gescheiterter Init-Load) sofort
 * zurueck, ohne zu schreiben und ohne zu werfen. `deleteAlarm()` meldete also Erfolg, obwohl der
 * Alarm nur aus dem Arbeitsspeicher verschwunden war und in der Preferences-Datei UND im
 * Direct-Boot-Spiegel unveraendert lag. Die Oberflaeche meldete "wird uebersprungen", der
 * Systemalarm war gecancelt - und ein naechtlicher Neustart liess den BootReceiver den Eintrag
 * ungefiltert wieder armieren: der uebersprungene Wecker klingelte am uebersprungenen Morgen.
 *
 * Seitdem fragt der UseCase die Sperre selbst: vor dem ersten Eingriff (dann bleibt der Wecker
 * unangetastet stehen und klingelt) und nach jedem "erfolgreichen" Loeschen (dann greift die
 * Ruecknahme).
 */
class Pruefrunde7SkipPersistenzGesperrtTest {

    private val now = System.currentTimeMillis()

    private fun futureAlarm(id: Int) = AlarmInfo(
        id = id,
        shiftId = "shift$id",
        shiftName = "Frueh$id",
        triggerTime = now + 60 * 60 * 1000L,
        formattedTime = "t$id"
    )

    /**
     * Repository, das sich wie `AlarmRepository` bei gesperrter Persistenz verhaelt: `deleteAlarm`
     * raeumt nur den Arbeitsspeicher und meldet Erfolg, geschrieben wird nichts.
     */
    private class GesperrtesRepository(
        initial: List<AlarmInfo>,
        private var gesperrt: Boolean,
        /** Sperre entsteht erst mit dem ersten Loeschversuch (nebenlaeufig gescheiterter Nachlade-Versuch). */
        private val sperrtErstBeimLoeschen: Boolean = false
    ) : IAlarmRepository {
        private val state = MutableStateFlow(initial)
        var deleteVersuche = 0

        override suspend fun isPersistenceBlocked(): Boolean = gesperrt
        // Der zweite Merker bleibt heil: dieser Test prueft die UNLESBARKEIT des Bestands,
        // nicht den Schreibfehler - die beiden sind bewusst getrennte Signale.
        override suspend fun istLetzterSchreibvorgangGescheitert(): Boolean = false
        override val activeAlarms: Flow<List<AlarmInfo>> = state
        override suspend fun saveAlarm(alarmInfo: AlarmInfo): Result<Unit> {
            state.value = state.value.filterNot { it.id == alarmInfo.id } + alarmInfo
            return Result.success(Unit)
        }

        override suspend fun getAllAlarms(): Result<List<AlarmInfo>> = Result.success(state.value)
        override suspend fun getAlarmById(alarmId: Int): Result<AlarmInfo?> =
            Result.success(state.value.find { it.id == alarmId })

        override suspend fun deleteAlarm(alarmId: Int): Result<Unit> {
            deleteVersuche++
            if (sperrtErstBeimLoeschen) gesperrt = true
            // Nur der Arbeitsspeicher - genau wie im Original bei blockierter Persistenz.
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
        var gesetztFuer: Int? = null
        var clearAufrufe = 0

        override suspend fun setNextAlarmSkipped(
            alarmId: Int,
            triggerTime: Long,
            reason: String,
            manualAlarmSnapshot: String?
        ): Result<Unit> {
            gesetztFuer = alarmId
            return Result.success(Unit)
        }

        override suspend fun clearSkipStatus(): Result<Unit> {
            clearAufrufe++
            gesetztFuer = null
            return Result.success(Unit)
        }

        override suspend fun isAlarmSkipped(alarmId: Int): Result<Boolean> =
            Result.success(gesetztFuer == alarmId)

        override suspend fun getSkipStatus(): Result<AlarmSkipState> = Result.success(AlarmSkipState())
        override val skipStatusFlow: Flow<AlarmSkipState> = flowOf(AlarmSkipState())
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
    fun `bei gesperrter Persistenz wird gar nicht erst uebersprungen`() = runTest {
        val manager = mockManager()
        val repo = GesperrtesRepository(listOf(futureAlarm(id = 11)), gesperrt = true)
        val skipRepo = FakeSkipRepository()
        val useCase = AlarmSkipUseCase(skipRepo, repo, manager)

        val ergebnis = useCase.skipNextAlarm()

        assertTrue(
            "Ein Ueberspringen, das nur im Arbeitsspeicher stattfindet, darf kein Erfolg sein",
            ergebnis.isFailure
        )
        assertEquals("Der Merker darf gar nicht erst gesetzt werden", null, skipRepo.gesetztFuer)
        assertEquals("Nichts zurueckzunehmen - es wurde nichts angefasst", 0, skipRepo.clearAufrufe)
        assertEquals("Der Alarm bleibt im Bestand", 0, repo.deleteVersuche)
        verify(manager, never()).cancelSystemAlarm(any())
    }

    @Test
    fun `eine erst beim Loeschen entstehende Sperre loest die Ruecknahme aus`() = runTest {
        val repo = GesperrtesRepository(
            listOf(futureAlarm(id = 12)),
            gesperrt = false,
            sperrtErstBeimLoeschen = true
        )
        val skipRepo = FakeSkipRepository()
        val useCase = AlarmSkipUseCase(skipRepo, repo, mockManager())

        val ergebnis = useCase.skipNextAlarm()

        val fehler = ergebnis.exceptionOrNull()
        assertTrue(
            "Der Aufrufer muss den bereits gecancelten Systemalarm wieder stellen koennen - " +
                "stattdessen kam ${fehler?.javaClass?.simpleName}",
            fehler is SkipRolledBackException
        )
        fehler as SkipRolledBackException
        assertEquals(12, fehler.alarmId)
        assertTrue("Der Merker wurde geraeumt", fehler.skipFlagCleared)
        assertEquals("Der Skip-Merker darf nicht stehenbleiben", null, skipRepo.gesetztFuer)
        assertEquals(
            "Auch hier gilt: genau einmal nachfassen, dann zuruecknehmen",
            AlarmSkipUseCase.DELETE_ATTEMPTS,
            repo.deleteVersuche
        )
    }

    @Test
    fun `ohne Sperre bleibt das Ueberspringen unveraendert moeglich`() = runTest {
        val repo = GesperrtesRepository(listOf(futureAlarm(id = 13)), gesperrt = false)
        val skipRepo = FakeSkipRepository()
        val useCase = AlarmSkipUseCase(skipRepo, repo, mockManager())

        val ergebnis = useCase.skipNextAlarm()

        assertTrue("Der Regelfall darf durch die Pruefung nicht leiden", ergebnis.isSuccess)
        assertEquals(1, repo.deleteVersuche)
        assertEquals("Der Merker bleibt gesetzt", 13, skipRepo.gesetztFuer)
        assertEquals(0, skipRepo.clearAufrufe)
    }
}
