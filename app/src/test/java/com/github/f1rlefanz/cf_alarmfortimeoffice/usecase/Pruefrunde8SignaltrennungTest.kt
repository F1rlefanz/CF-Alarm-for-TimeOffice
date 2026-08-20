package com.github.f1rlefanz.cf_alarmfortimeoffice.usecase

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import com.github.f1rlefanz.cf_alarmfortimeoffice.alarm.DirectBootAlarmStore
import com.github.f1rlefanz.cf_alarmfortimeoffice.alarm.ShiftChangeNotifier
import com.github.f1rlefanz.cf_alarmfortimeoffice.masterpause.MasterPausePrefs
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.AlarmInfo
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftConfig
import com.github.f1rlefanz.cf_alarmfortimeoffice.repository.AlarmRepoTestContext
import com.github.f1rlefanz.cf_alarmfortimeoffice.repository.AlarmRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.repository.interfaces.IShiftConfigRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.service.AlarmManagerService
import com.github.f1rlefanz.cf_alarmfortimeoffice.shift.ShiftRecognitionEngine
import com.github.f1rlefanz.cf_alarmfortimeoffice.shift.ShiftSpanStore
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.IAlarmSkipUseCase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.io.IOException

/**
 * ZWEI LAGEN, ZWEI SIGNALE - der Nachreview-Befund ueber der zweiten Fixwelle.
 *
 * Die zweite Welle machte den gescheiterten SCHREIBvorgang sichtbar (richtig und noetig), tat das
 * aber, indem sie ihn in `isPersistenceBlocked()` mit der Sperre VERODERTE. Damit bedeuteten zwei
 * voellig verschiedene Lagen dasselbe Signal:
 *
 *  (a) "der Bestand ist in diesem Prozess nicht lesbar" - der Init-Load ist gescheitert, der Cache
 *      steht auf einer Notlage-Leere;
 *  (b) "der letzte Schreibvorgang ist gescheitert" - voller Speicher, IOException; der Bestand im
 *      Arbeitsspeicher ist dabei VOLLSTAENDIG lesbar.
 *
 * `AlarmUseCase.clearInternalAlarms()` deutet das Signal als (a) und ueberspringt im Zweig
 * `keepManualAlarms = false` - also bei Master-Pause, "Automatische Alarme aus" und
 * `deleteAllAlarms` - bewusst die gesamte `cancelSystemAlarm`-Schleife, weil sie ohne Bestandsliste
 * ohnehin ins Leere liefe. Nach einem einzigen geworfenen Schreibvorgang wurde sie damit
 * FAELSCHLICH uebersprungen: die Master-Pause leerte Repository und Direct-Boot-Spiegel und liess
 * alle System-Alarme armiert zurueck. Sie feuern dann trotz Pause und sind ohne Bestandsliste durch
 * nichts mehr abbrechbar - genau die von CLAUDE.md verbotene Kombination "Raeumen ohne Cancellen".
 *
 * Diese Tests fahren den ECHTEN [AlarmRepository] gegen einen Speicher, dessen Schreibvorgang
 * wirklich wirft - eine Attrappe koennte den Unterschied gar nicht zeigen.
 */
class Pruefrunde8SignaltrennungTest {

    private val now = System.currentTimeMillis()

    /** Lesen geht, JEDER Schreibversuch wirft - so sieht ein voller Speicher aus. */
    private class NurLesbarerSpeicher : DataStore<Preferences> {
        private val inhalt = mutablePreferencesOf()
        override val data: Flow<Preferences> = flowOf(inhalt)
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            throw IOException("kein Platz auf dem Geraet")
    }

    private class FakeShiftConfigRepository(private val config: ShiftConfig) : IShiftConfigRepository {
        override val shiftConfig: Flow<ShiftConfig> = flowOf(config)
        override suspend fun saveShiftConfig(config: ShiftConfig): Result<Unit> = Result.success(Unit)
        override suspend fun getCurrentShiftConfig(): Result<ShiftConfig> = Result.success(config)
        override suspend fun resetToDefaults(): Result<Unit> = Result.success(Unit)
        override suspend fun hasValidConfig(): Result<Boolean> = Result.success(true)
    }

    private fun alarm(id: Int) = AlarmInfo(
        id = id,
        shiftId = "shift$id",
        shiftName = "Frueh$id",
        triggerTime = now + 60 * 60 * 1000L,
        formattedTime = "t$id"
    )

    private fun mockManager(): AlarmManagerService {
        val m = mock<AlarmManagerService>()
        val status = AlarmManagerService.AlarmStatus(
            systemAlarmSet = true,
            canScheduleExactAlarms = true,
            alarmStatusMessage = null
        )
        whenever(m.cancelSystemAlarm(any())).thenReturn(status)
        return m
    }

    private fun useCase(repo: AlarmRepository, manager: AlarmManagerService): AlarmUseCase {
        val config = ShiftConfig()
        return AlarmUseCase(
            repo,
            manager,
            FakeShiftConfigRepository(config),
            ShiftRecognitionEngine(FakeShiftConfigRepository(config)),
            mock<IAlarmSkipUseCase>(),
            mock<ShiftChangeNotifier>(),
            mock<MasterPausePrefs>(),
            mock<ShiftSpanStore>()
        )
    }

    @Test
    fun `nach einem Schreibfehler wird beim Raeumen trotzdem jeder System-Alarm gecancelt`() = runTest {
        // OHNE DIE TRENNUNG: isPersistenceBlocked() meldet wegen des Schreibfehlers "gesperrt",
        // clearInternalAlarms() ueberspringt die Cancel-Schleife und leert nur den Spiegel - der
        // System-Alarm bleibt armiert und klingelt mitten in der Master-Pause.
        val repo = AlarmRepository(
            NurLesbarerSpeicher(),
            mock<DirectBootAlarmStore>(),
            AlarmRepoTestContext.unlocked()
        )
        val manager = mockManager()

        // Der Schreibvorgang wirft, der Alarm liegt danach NUR im Arbeitsspeicher - und genau
        // deshalb ist er dort lesbar und abbrechbar.
        repo.saveAlarm(alarm(11))
        assertTrue(
            "Vorbedingung: der Schreibvorgang ist wirklich gescheitert",
            repo.istLetzterSchreibvorgangGescheitert()
        )
        assertFalse(
            "Ein Schreibfehler macht den Bestand NICHT unlesbar - sonst faellt das Raeumen in " +
                "den Notpfad ohne Cancellen",
            repo.isPersistenceBlocked()
        )

        val ergebnis = useCase(repo, manager).deleteAllAlarms()

        assertTrue(ergebnis.isSuccess)
        verify(manager).cancelSystemAlarm(eq(11))
        assertTrue(
            "Und der Bestand ist danach wirklich leer",
            repo.getAllAlarms().getOrThrow().isEmpty()
        )
    }
}
