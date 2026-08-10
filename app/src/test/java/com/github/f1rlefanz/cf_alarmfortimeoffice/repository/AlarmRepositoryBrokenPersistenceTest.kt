package com.github.f1rlefanz.cf_alarmfortimeoffice.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.github.f1rlefanz.cf_alarmfortimeoffice.alarm.DirectBootAlarmEntry
import com.github.f1rlefanz.cf_alarmfortimeoffice.alarm.DirectBootAlarmStore
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.AlarmInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify

/**
 * Was passiert beim naechsten SCHREIBEN, nachdem der Init-Load still auf "keine Alarme"
 * degradiert ist?
 *
 * Genau das war die Luecke: ein nicht dekodierbarer `active_alarms`-Wert wurde generisch gefangen,
 * `_activeAlarms` auf `emptyList()` gesetzt und das Bereit-Signal trotzdem erfuellt - `getAllAlarms()`
 * lieferte die Notlage-Leere als bestaetigte Wahrheit. Der Delta-Sync hielt daraufhin jede erkannte
 * Schicht fuer neu, `saveAlarm()` -> `persistToDataStore()` schrieb die neue Liste ueber das defekte
 * JSON UND spiegelte sie in den Direct-Boot-Store. Verloren waren damit genau die Alarme, die sich
 * NICHT aus dem Kalender rekonstruieren lassen (manuelle Alarme) - plus der Spiegel, der der
 * einzige Weg zurueck vor der ersten Entsperrung nach einem Reboot ist.
 */
class AlarmRepositoryBrokenPersistenceTest {

    private val alarmsKey = stringPreferencesKey("active_alarms")
    private val brokenKey = stringPreferencesKey(AlarmRepository.BROKEN_ALARMS_KEY_NAME)
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

    private val brokenJson = """[{"id":1,"shiftId":"s1","shiftName":"Frueh"""" // abgeschnitten

    private fun storeWithBrokenJson() =
        FakePreferencesDataStore(mutablePreferencesOf().apply { this[alarmsKey] = brokenJson })

    private suspend fun FakePreferencesDataStore.raw(key: Preferences.Key<String>): String? =
        data.first()[key]

    private fun futureAlarm(id: Int) = AlarmInfo(
        id = id,
        shiftId = "shift$id",
        shiftName = "Frueh$id",
        triggerTime = now + 60 * 60 * 1000L,
        formattedTime = "t$id"
    )

    @Test
    fun `saveAlarm ueberschreibt einen nicht dekodierbaren Bestand nicht`() = runTest {
        val store = storeWithBrokenJson()
        val directBoot = mock<DirectBootAlarmStore>()
        val repo = AlarmRepository(store, directBoot)

        repo.saveAlarm(futureAlarm(7)).getOrThrow()

        assertEquals(
            "Der defekte Rohbestand muss unangetastet liegen bleiben",
            brokenJson,
            store.raw(alarmsKey)
        )
        verify(directBoot, never()).saveAll(any<List<DirectBootAlarmEntry>>())
    }

    @Test
    fun `ein nicht dekodierbarer Bestand wird zuerst gesichert`() = runTest {
        val store = storeWithBrokenJson()
        val repo = AlarmRepository(store, mock<DirectBootAlarmStore>())

        // Loest den Init-Load aus und wartet ihn ab.
        repo.getAllAlarms().getOrThrow()

        assertEquals(
            "Ohne Sicherung ist der Bestand nach einem Fix/Downgrade nicht rekonstruierbar",
            brokenJson,
            store.raw(brokenKey)
        )
    }

    @Test
    fun `ein intakter Bestand wird ganz normal weitergeschrieben`() = runTest {
        // Gegenprobe: die Sperre darf NUR im Defektfall greifen.
        val store = FakePreferencesDataStore(mutablePreferencesOf())
        val directBoot = mock<DirectBootAlarmStore>()
        val repo = AlarmRepository(store, directBoot)

        repo.saveAlarm(futureAlarm(7)).getOrThrow()

        assertEquals(listOf(7), repo.getAllAlarms().getOrThrow().map { it.id })
        assertNull("Ohne Defekt darf keine Sicherung entstehen", store.raw(brokenKey))
        verify(directBoot).saveAll(any<List<DirectBootAlarmEntry>>())
    }

    @Test
    fun `deleteAllAlarms raeumt auch bei blockierter Persistenz wirklich`() = runTest {
        // Master-Pause/autoAlarmEnabled=false MUSS durchgehen - sonst re-armt ein
        // Direct-Boot-Restore genau die Alarme, die der Nutzer gerade pausiert hat.
        val store = storeWithBrokenJson()
        val directBoot = mock<DirectBootAlarmStore>()
        val repo = AlarmRepository(store, directBoot)

        repo.deleteAllAlarms().getOrThrow()

        assertEquals("Der Bestand muss geleert sein", "[]", store.raw(alarmsKey))
        verify(directBoot).saveAll(emptyList())
    }
}
