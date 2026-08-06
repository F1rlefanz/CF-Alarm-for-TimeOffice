package com.github.f1rlefanz.cf_alarmfortimeoffice.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.github.f1rlefanz.cf_alarmfortimeoffice.alarm.DirectBootAlarmStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock

/**
 * Absicherung des Start-Fensters von [AlarmRepository].
 *
 * Der Init-Load laeuft als unabgewartete Coroutine aus dem `init{}`-Block. Bis er durch ist, war
 * der In-Memory-Cache leer - und `getAllAlarms()` lieferte diese leere Liste als WAHRHEIT: der
 * Delta-Sync hielt damit jede erkannte Schicht fuer neu. Umgekehrt schrieb der zurueckkehrende
 * Init-Load seinen alten Snapshot unbedingt in Cache, DataStore UND Direct-Boot-Spiegel und machte
 * damit alles zunichte, was in der Zwischenzeit geschrieben wurde (z. B. das Leeren durch die
 * Master-Pause). Beides deckt jetzt ein Bereit-Signal ab, das jede Operation zuerst abwartet.
 */
class AlarmRepositoryInitRaceTest {

    private val alarmsKey = stringPreferencesKey("active_alarms")
    private val seedJson = Json { encodeDefaults = true }
    private val now = System.currentTimeMillis()

    /** Minimaler, echter In-Memory-DataStore<Preferences> - deckt data/updateData (und edit{}) ab. */
    private class FakePreferencesDataStore(initial: Preferences) : DataStore<Preferences> {
        private val flow = MutableStateFlow(initial)
        override val data: Flow<Preferences> = flow
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            val updated = transform(flow.value)
            flow.value = updated
            return updated
        }
    }

    private fun alarmData(id: Int, offsetMs: Long) = AlarmInfoData(
        id = id,
        shiftId = "shift$id",
        shiftName = "Frueh$id",
        triggerTime = now + offsetMs,
        formattedTime = "t$id"
    )

    private fun storeWith(vararg alarms: AlarmInfoData): FakePreferencesDataStore {
        val prefs = if (alarms.isEmpty()) {
            emptyPreferences()
        } else {
            mutablePreferencesOf().apply { this[alarmsKey] = seedJson.encodeToString(alarms.toList()) }
        }
        return FakePreferencesDataStore(prefs)
    }

    private suspend fun FakePreferencesDataStore.persistedIds(): List<Int> {
        val raw = data.first()[alarmsKey] ?: return emptyList()
        return seedJson.decodeFromString<List<AlarmInfoData>>(raw).map { it.id }
    }

    @Test
    fun `getAllAlarms wartet auf den Init-Load statt faelschlich leer zu liefern`() = runTest {
        val store = storeWith(alarmData(id = 42, offsetMs = 60 * 60 * 1000L))

        // Direkt nach der Konstruktion gefragt - genau das Fenster, in dem der Init-Load noch laeuft.
        val repo = AlarmRepository(store, mock<DirectBootAlarmStore>())
        val alarms = repo.getAllAlarms().getOrThrow()

        assertEquals(
            "Der persistierte Alarm darf im Startfenster nicht als 'keine Alarme' erscheinen",
            listOf(42),
            alarms.map { it.id }
        )
    }

    @Test
    fun `deleteAllAlarms wird nicht vom nachtraeglichen Init-Load wieder aufgefuellt`() = runTest {
        // Ein abgelaufener Alarm im Bestand sorgt dafuer, dass der Init-Load selbst SCHREIBT
        // (bereinigte Liste) - genau der Pfad, der das Leeren ueberschrieben hat.
        val store = storeWith(
            alarmData(id = 1, offsetMs = -60 * 60 * 1000L),
            alarmData(id = 2, offsetMs = 60 * 60 * 1000L)
        )
        val repo = AlarmRepository(store, mock<DirectBootAlarmStore>())

        repo.deleteAllAlarms().getOrThrow()

        assertTrue("Cache muss leer bleiben", repo.getAllAlarms().getOrThrow().isEmpty())
        assertEquals("Der DataStore darf keinen Alarm zurueckbekommen", emptyList<Int>(), store.persistedIds())
    }
}
