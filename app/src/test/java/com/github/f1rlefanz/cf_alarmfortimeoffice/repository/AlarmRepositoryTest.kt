package com.github.f1rlefanz.cf_alarmfortimeoffice.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.github.f1rlefanz.cf_alarmfortimeoffice.alarm.DirectBootAlarmStore
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.AlarmInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify

/**
 * Unit-Tests fuer [AlarmRepository] - die Persistenzschicht des Alarm-Bestands.
 *
 * Abgesichert werden die Vertraege, an denen historisch Wecker verloren gingen:
 * - Init laedt persistierte, noch zukuenftige Alarme aus dem DataStore in den Cache
 * - Bereits abgelaufene Alarme werden beim Laden herausgefiltert (und die Liste re-persistiert)
 * - saveAlarm lehnt Alarme in der Vergangenheit ab (kein sofort-abgelaufener Wecker)
 * - deleteAlarm entfernt aus Cache UND schreibt den Direct-Boot-Spiegel nach
 *
 * Der DataStore ist ein echter In-Memory-Fake; der Android-gebundene [DirectBootAlarmStore]
 * (Device-Protected-SharedPreferences) ist gemockt.
 */
class AlarmRepositoryTest {

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

    private fun storeWith(vararg alarms: AlarmInfoData): FakePreferencesDataStore {
        val prefs = if (alarms.isEmpty()) {
            emptyPreferences()
        } else {
            mutablePreferencesOf().apply { this[alarmsKey] = seedJson.encodeToString(alarms.toList()) }
        }
        return FakePreferencesDataStore(prefs)
    }

    private fun alarmData(id: Int, offsetMs: Long) = AlarmInfoData(
        id = id,
        shiftId = "shift$id",
        shiftName = "Frueh$id",
        triggerTime = now + offsetMs,
        formattedTime = "t$id"
    )

    private fun futureAlarmInfo(id: Int, offsetMs: Long) = AlarmInfo(
        id = id,
        shiftId = "shift$id",
        shiftName = "Frueh$id",
        triggerTime = now + offsetMs,
        formattedTime = "t$id"
    )

    @Test
    fun `init laedt persistierte zukuenftige Alarme aus dem DataStore`() = runTest {
        val store = storeWith(alarmData(id = 42, offsetMs = 60 * 60 * 1000L))
        val repo = AlarmRepository(store, mock<DirectBootAlarmStore>())

        // Warte deterministisch, bis der asynchrone Init-Load den Cache befuellt hat.
        val loaded = repo.activeAlarms.first { list -> list.any { it.id == 42 } }

        assertEquals(1, loaded.size)
        assertEquals(42, loaded.first().id)
    }

    @Test
    fun `init filtert abgelaufene Alarme beim Laden heraus`() = runTest {
        val store = storeWith(
            alarmData(id = 1, offsetMs = -60 * 60 * 1000L), // Vergangenheit -> raus
            alarmData(id = 2, offsetMs = 60 * 60 * 1000L)   // Zukunft -> bleibt
        )
        val repo = AlarmRepository(store, mock<DirectBootAlarmStore>())

        val loaded = repo.activeAlarms.first { it.isNotEmpty() }

        assertEquals("Nur der zukuenftige Alarm darf ueberleben", listOf(2), loaded.map { it.id })
    }

    @Test
    fun `saveAlarm lehnt einen Alarm in der Vergangenheit ab`() = runTest {
        val repo = AlarmRepository(storeWith(), mock<DirectBootAlarmStore>())

        val past = futureAlarmInfo(id = 3, offsetMs = -60 * 60 * 1000L)
        val result = repo.saveAlarm(past)

        assertTrue("Vergangene Weckzeit muss abgelehnt werden", result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun `init laedt das persistierte Schichtende mit`() = runTest {
        val shiftEnd = now + 8 * 60 * 60 * 1000L
        val store = storeWith(alarmData(id = 7, offsetMs = 60 * 60 * 1000L).copy(shiftEndTime = shiftEnd))
        val repo = AlarmRepository(store, mock<DirectBootAlarmStore>())

        val loaded = repo.activeAlarms.first { list -> list.any { it.id == 7 } }

        assertEquals(shiftEnd, loaded.first { it.id == 7 }.shiftEndTime)
    }

    @Test
    fun `altes JSON ohne Schichtende defaultet auf 0 (Migration)`() = runTest {
        // Simuliert einen vor Einfuehrung des Feldes gespeicherten Alarm: kein shiftEndTime im JSON.
        val legacyJson =
            """[{"id":9,"shiftId":"s","shiftName":"F","triggerTime":${now + 60 * 60 * 1000L},"formattedTime":"t"}]"""
        val store = FakePreferencesDataStore(
            mutablePreferencesOf().apply { this[alarmsKey] = legacyJson }
        )
        val repo = AlarmRepository(store, mock<DirectBootAlarmStore>())

        val loaded = repo.activeAlarms.first { list -> list.any { it.id == 9 } }

        assertEquals(0L, loaded.first { it.id == 9 }.shiftEndTime)
    }

    @Test
    fun `init laedt das persistierte isSilent-Flag mit`() = runTest {
        val store = storeWith(alarmData(id = 8, offsetMs = 60 * 60 * 1000L).copy(isSilent = true))
        val repo = AlarmRepository(store, mock<DirectBootAlarmStore>())

        val loaded = repo.activeAlarms.first { list -> list.any { it.id == 8 } }

        assertTrue("isSilent muss aus dem DataStore geladen werden", loaded.first { it.id == 8 }.isSilent)
    }

    @Test
    fun `altes JSON ohne isSilent defaultet auf false (Migration)`() = runTest {
        // Simuliert einen vor Feature D gespeicherten Alarm: kein isSilent im JSON.
        val legacyJson =
            """[{"id":11,"shiftId":"s","shiftName":"F","triggerTime":${now + 60 * 60 * 1000L},"formattedTime":"t"}]"""
        val store = FakePreferencesDataStore(
            mutablePreferencesOf().apply { this[alarmsKey] = legacyJson }
        )
        val repo = AlarmRepository(store, mock<DirectBootAlarmStore>())

        val loaded = repo.activeAlarms.first { list -> list.any { it.id == 11 } }

        assertTrue("Fehlendes isSilent im Alt-JSON muss auf false defaulten",
            loaded.first { it.id == 11 }.isSilent == false)
    }

    @Test
    fun `saveAlarm persistiert isSilent = true und ueberlebt einen Reload`() = runTest {
        val directBoot = mock<DirectBootAlarmStore>()
        val store = storeWith()
        val repo = AlarmRepository(store, directBoot)
        repo.activeAlarms.first() // Init-Load abwarten (leerer Bestand)

        val silentAlarm = futureAlarmInfo(id = 99, offsetMs = 60 * 60 * 1000L).copy(isSilent = true)
        repo.saveAlarm(silentAlarm)

        // Aus einem frischen Repository (derselbe DataStore) geladen - beweist, dass isSilent
        // tatsaechlich im persistierten JSON steht und nicht nur im In-Memory-Cache.
        val reloaded = AlarmRepository(store, directBoot)
        val loaded = reloaded.activeAlarms.first { list -> list.any { it.id == 99 } }

        assertTrue("isSilent muss den Persist/Reload-Zyklus ueberleben", loaded.first { it.id == 99 }.isSilent)
    }

    @Test
    fun `deleteAlarm entfernt aus dem Cache und schreibt den Direct-Boot-Spiegel nach`() = runTest {
        val directBoot = mock<DirectBootAlarmStore>()
        val store = storeWith(
            alarmData(id = 10, offsetMs = 60 * 60 * 1000L),
            alarmData(id = 20, offsetMs = 2 * 60 * 60 * 1000L)
        )
        val repo = AlarmRepository(store, directBoot)

        // Erst Init-Load abwarten (beide Alarme im Cache), dann loeschen.
        repo.activeAlarms.first { it.size == 2 }
        val result = repo.deleteAlarm(10)

        assertTrue(result.isSuccess)
        val remaining = repo.getAllAlarms().getOrThrow()
        assertNull("Geloeschter Alarm ist weg", remaining.find { it.id == 10 })
        assertNotNull("Der andere Alarm bleibt", remaining.find { it.id == 20 })
        verify(directBoot, atLeastOnce()).saveAll(any())
    }
}
