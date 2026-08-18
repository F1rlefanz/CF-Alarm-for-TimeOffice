package com.github.f1rlefanz.cf_alarmfortimeoffice.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import com.github.f1rlefanz.cf_alarmfortimeoffice.data.AlarmSkipPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Pruefrunde 6, Befund 13 und die Regression seines ersten Fixes.
 *
 * BEFUND 13: [AlarmSkipRepository.skipStatusFlow] war ein blankes `dataStore.data.map{}`. Der
 * `ReplaceFileCorruptionHandler` des `settings`-Stores faengt nur Korruption; eine IOException
 * reicht DataStore in den Flow durch, der Flow ENDETE daran - die Skip-Karte fror dauerhaft auf
 * ihrem letzten Stand ein.
 *
 * DIE REGRESSION: Der erste Fix war `.catch { emit(AlarmSkipState()) }`. `Flow.catch` faengt,
 * emittiert einmal und laesst den Flow danach NORMAL ABSCHLIESSEN - es abonniert nichts neu. Die
 * Terminierung blieb also bestehen, nur der eingefrorene Wert war ein anderer: "nicht
 * uebersprungen". Und ausgerechnet dieser Wert nimmt die Skip-Karte samt "Aufheben"-Knopf weg
 * (`WeckerTabContent` zeigt sie nur bei `hasActiveAlarms || isNextAlarmSkipped`, und beim
 * Ueberspringen eines MANUELLEN Weckers ist der Alarm vorher aus dem Repository geloescht) - der
 * einzige Weg an den gesicherten `skippedManualAlarm` waere verschwunden.
 *
 * Deshalb pruefen die beiden ersten Tests die zwei Haelften des Haus-Musters (`retryWhen` plus ein
 * letzter Fang OHNE `emit`), und die beiden letzten halten fest, dass die WECKERKETTE davon
 * unberuehrt bleibt: `getSkipStatus()`/`isAlarmSkipped()` melden einen Lesefehler weiterhin als
 * `Result.failure`, weil `AlarmViewModel.cancelSkip()` genau daran erkennt, dass es Flag und
 * Schnappschuss NICHT abraeumen darf.
 */
class Pruefrunde6SkipStatusFlowTest {

    /** Store, dessen `data`-Flow immer wirft - genau das, was DataStore bei einer IOException tut. */
    private class FailingDataStore : DataStore<Preferences> {
        override val data: Flow<Preferences> = flow { throw IOException("simulierter Lesefehler") }
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            throw IOException("simulierter Schreibfehler")
    }

    /** Wirft beim ERSTEN Abonnement und liefert ab dem zweiten - der transiente Lesefehler. */
    private class FlakyDataStore(private val prefs: Preferences) : DataStore<Preferences> {
        val abonnements = AtomicInteger(0)
        override val data: Flow<Preferences> = flow {
            if (abonnements.incrementAndGet() == 1) throw IOException("transienter Lesefehler")
            emit(prefs)
        }

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            throw UnsupportedOperationException("in diesem Test nicht benutzt")
    }

    private fun uebersprungen(): Preferences = mutablePreferencesOf(
        AlarmSkipPreferences.IS_NEXT_ALARM_SKIPPED to true,
        AlarmSkipPreferences.SKIPPED_ALARM_ID to 4711,
        AlarmSkipPreferences.SKIPPED_ALARM_TRIGGER_TIME to 1_234L
    )

    private class FakeDataStore(initial: Preferences) : DataStore<Preferences> {
        private val flow = MutableStateFlow(initial)
        override val data: Flow<Preferences> = flow
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            val updated = transform(flow.value)
            flow.value = updated
            return updated
        }
    }

    @Test
    fun `ein transienter Lesefehler wird NEU ABONNIERT statt den Flow zu beenden`() = runTest {
        val store = FlakyDataStore(uebersprungen())
        val repository = AlarmSkipRepository(store)

        val state = repository.skipStatusFlow.first()

        // MIT `.catch { emit(AlarmSkipState()) }` stuende hier `false`: der Fang haette den
        // Fehler einmal quittiert und den Flow beendet, ohne den Store je erneut zu lesen.
        assertTrue(
            "Nach einem transienten Lesefehler muss der ECHTE Zustand kommen, nicht ein Ersatzwert",
            state.isNextAlarmSkipped
        )
        assertEquals(4711, state.skippedAlarmId)
        assertEquals(
            "Der Store muss ein zweites Mal abonniert worden sein - genau das leistet `.catch` nicht",
            2,
            store.abonnements.get()
        )
    }

    @Test
    fun `ein dauerhafter Lesefehler emittiert NICHTS und nimmt damit den Aufheben-Knopf nicht weg`() = runTest {
        val repository = AlarmSkipRepository(FailingDataStore())

        val emissionen = repository.skipStatusFlow.toList()

        // MIT `.catch { emit(AlarmSkipState()) }` stuende hier genau eine Emission mit
        // isNextAlarmSkipped = false - die Skip-Karte verschwaende samt "Aufheben".
        assertTrue(
            "Kein Signal ist hier richtiger als ein falsches: eine Degradierung auf 'nicht " +
                "uebersprungen' nimmt den einzigen Weg an den gesicherten manuellen Wecker weg, " +
                "war aber $emissionen",
            emissionen.isEmpty()
        )
    }

    @Test
    fun `getSkipStatus meldet einen Lesefehler weiterhin als Fehler`() = runTest {
        // Diese Richtung ist die Gegenprobe: wuerde auch der Punkt-Read degradieren, raeumte
        // `cancelSkip()` Flag UND Schnappschuss ab, ohne sie je gelesen zu haben - der gesicherte
        // manuelle Wecker waere weg.
        val repository = AlarmSkipRepository(FailingDataStore())

        assertTrue(repository.getSkipStatus().isFailure)
        assertTrue(repository.isAlarmSkipped(42).isFailure)
    }

    @Test
    fun `skipStatusFlow liefert bei lesbarem Store den echten Zustand`() = runTest {
        val repository = AlarmSkipRepository(FakeDataStore(uebersprungen()))

        val state = repository.skipStatusFlow.first()

        assertTrue(state.isNextAlarmSkipped)
        assertEquals(4711, state.skippedAlarmId)
        assertEquals(1_234L, state.skippedAlarmTriggerTime)
        assertTrue(repository.getSkipStatus().isSuccess)
    }
}
