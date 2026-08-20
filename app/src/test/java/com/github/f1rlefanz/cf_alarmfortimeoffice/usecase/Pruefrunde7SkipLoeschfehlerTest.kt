package com.github.f1rlefanz.cf_alarmfortimeoffice.usecase

import com.github.f1rlefanz.cf_alarmfortimeoffice.model.AlarmInfo
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.AlarmSkipState
import com.github.f1rlefanz.cf_alarmfortimeoffice.repository.interfaces.IAlarmRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.repository.interfaces.IAlarmSkipRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.service.AlarmManagerService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.IOException

/**
 * Haelt fest, was passiert, wenn beim Ueberspringen das LOESCHEN des Alarms scheitert.
 *
 * DER FEHLER (Pruefrunde 7): der Fehlschlag wurde nur geloggt. Der Skip-Merker blieb gesetzt, die
 * Oberflaeche meldete "uebersprungen" - und der Eintrag lag weiter im Bestand und im
 * Direct-Boot-Spiegel. Der BootReceiver armiert daraus jeden Zukunfts-Eintrag ungefiltert wieder
 * (er kennt keinen Skip, und der Merker liegt im CE-Storage, vor der ersten Entsperrung ohnehin
 * unlesbar), die Hue-Tagesplanung baut daraus ihre Sonnenaufgangs-Starts. Der "uebersprungene"
 * Wecker klingelte nach einem naechtlichen Neustart also doch.
 *
 * Seitdem gilt: einmal nachfassen, sonst Ueberspringen zuruecknehmen und laut scheitern - mit
 * [SkipRolledBackException], damit der Aufrufer den bereits gecancelten Systemalarm wieder
 * stellen kann.
 */
class Pruefrunde7SkipLoeschfehlerTest {

    private val now = System.currentTimeMillis()

    private fun futureAlarm(id: Int) = AlarmInfo(
        id = id,
        shiftId = "shift$id",
        shiftName = "Frueh$id",
        triggerTime = now + 60 * 60 * 1000L,
        formattedTime = "t$id"
    )

    /** Alarm-Repository, dessen `deleteAlarm` die ersten [fehlschlaege] Versuche verweigert. */
    private class LoeschUnwilligesRepository(
        initial: List<AlarmInfo>,
        private val fehlschlaege: Int
    ) : IAlarmRepository {
        private val state = MutableStateFlow(initial)
        var deleteVersuche = 0

        override suspend fun isPersistenceBlocked(): Boolean = false
        // Schreibfehler-Merker: fuer diese Tests immer heil - die Trennung der beiden
        // Signale wird in Pruefrunde8SignaltrennungTest geprueft.
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
            if (deleteVersuche <= fehlschlaege) {
                return Result.failure(IOException("DataStore schreibt gerade nicht"))
            }
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

    private class FakeSkipRepository(private val clearGelingt: Boolean = true) : IAlarmSkipRepository {
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
            return if (clearGelingt) {
                gesetztFuer = null
                Result.success(Unit)
            } else {
                Result.failure(IOException("DataStore schreibt gerade nicht"))
            }
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
    fun `dauerhaft fehlschlagendes Loeschen nimmt das Ueberspringen zurueck und scheitert laut`() = runTest {
        val alarm = futureAlarm(id = 7)
        val repo = LoeschUnwilligesRepository(listOf(alarm), fehlschlaege = Int.MAX_VALUE)
        val skipRepo = FakeSkipRepository()
        val useCase = AlarmSkipUseCase(skipRepo, repo, mockManager())

        val ergebnis = useCase.skipNextAlarm()

        assertTrue("Ein liegengebliebener Alarm darf nicht als Erfolg gemeldet werden", ergebnis.isFailure)
        val fehler = ergebnis.exceptionOrNull()
        assertTrue(
            "Der Aufrufer braucht die alarmId, um den gecancelten Systemalarm wieder zu stellen - " +
                "stattdessen kam ${fehler?.javaClass?.simpleName}",
            fehler is SkipRolledBackException
        )
        fehler as SkipRolledBackException
        assertEquals(7, fehler.alarmId)
        assertTrue("Der Merker wurde geraeumt, das muss der Fehler auch sagen", fehler.skipFlagCleared)
        assertEquals("Der Merker muss zurueckgenommen sein", 1, skipRepo.clearAufrufe)
        assertEquals("Der Skip-Merker darf nicht stehenbleiben", null, skipRepo.gesetztFuer)
        assertEquals(
            "Ein voruebergehender Schreibfehler verdient genau einen zweiten Versuch",
            AlarmSkipUseCase.DELETE_ATTEMPTS,
            repo.deleteVersuche
        )
    }

    @Test
    fun `gescheiterte Ruecknahme des Merkers wird im Fehler ausgewiesen`() = runTest {
        val repo = LoeschUnwilligesRepository(listOf(futureAlarm(id = 8)), fehlschlaege = Int.MAX_VALUE)
        val skipRepo = FakeSkipRepository(clearGelingt = false)
        val useCase = AlarmSkipUseCase(skipRepo, repo, mockManager())

        val fehler = useCase.skipNextAlarm().exceptionOrNull()

        assertTrue(fehler is SkipRolledBackException)
        assertFalse(
            "Bei gesetztem Merker weist der Skip-Backstop jedes Re-Arming ab - der Aufrufer darf " +
                "dem Nutzer also nichts versprechen",
            (fehler as SkipRolledBackException).skipFlagCleared
        )
    }

    @Test
    fun `ein einmaliger Schreibfehler beim Loeschen kostet das Ueberspringen nicht`() = runTest {
        val repo = LoeschUnwilligesRepository(listOf(futureAlarm(id = 9)), fehlschlaege = 1)
        val skipRepo = FakeSkipRepository()
        val useCase = AlarmSkipUseCase(skipRepo, repo, mockManager())

        val ergebnis = useCase.skipNextAlarm()

        assertTrue("Der zweite Versuch war erfolgreich", ergebnis.isSuccess)
        assertEquals(2, repo.deleteVersuche)
        assertEquals("Kein Grund zur Ruecknahme", 0, skipRepo.clearAufrufe)
        assertEquals("Der Merker bleibt gesetzt", 9, skipRepo.gesetztFuer)
    }

    @Test
    fun `Abbruch waehrend der Nachfass-Wartezeit laesst kein halb durchgefuehrtes Ueberspringen zurueck`() =
        runTest {
            val repo = LoeschUnwilligesRepository(listOf(futureAlarm(id = 10)), fehlschlaege = Int.MAX_VALUE)
            val skipRepo = FakeSkipRepository()
            val useCase = AlarmSkipUseCase(skipRepo, repo, mockManager())

            val auftrag = launch { runCatching { useCase.skipNextAlarm() } }

            // Mitten in die Wartezeit zwischen den beiden Loeschversuchen laufen lassen: dort ist
            // der Systemalarm bereits gecancelt und der Merker gesetzt, der Eintrag aber noch da.
            advanceTimeBy(AlarmSkipUseCase.DELETE_RETRY_DELAY_MS / 2)
            assertEquals("Vorbedingung: wir stehen wirklich in der Wartezeit", 1, repo.deleteVersuche)
            assertEquals("Vorbedingung: der Merker ist gesetzt", 10, skipRepo.gesetztFuer)

            // Jetzt raeumt Android das ViewModel ab - genau der Abbruch, der die Ruecknahme frueher
            // verschluckt hat.
            auftrag.cancel()
            advanceUntilIdle()

            assertEquals(
                "Der Abbruch darf den Vorgang nicht auf halbem Weg stehenlassen - beide " +
                    "Loeschversuche gehoeren zu Ende gefuehrt",
                AlarmSkipUseCase.DELETE_ATTEMPTS,
                repo.deleteVersuche
            )
            assertEquals(
                "Ein gesetzter Merker bei gecanceltem Systemalarm ist der stumme Wecker - die " +
                    "Ruecknahme muss auch nach dem Abbruch laufen",
                null,
                skipRepo.gesetztFuer
            )
            assertEquals(1, skipRepo.clearAufrufe)
        }
}
