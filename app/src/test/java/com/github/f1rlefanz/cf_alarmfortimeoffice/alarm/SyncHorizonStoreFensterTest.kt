package com.github.f1rlefanz.cf_alarmfortimeoffice.alarm

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * #51: Der Sync-Merker speichert ZEITPUNKT UND FENSTER des tatsaechlich verarbeiteten Abrufs, und
 * der Horizont wird mit dem GESPEICHERTEN Fenster gerechnet - nicht mit der aktuellen Einstellung.
 * Sonst meldete eine vergroesserte Vorausschau (14 -> 56) sechs Wochen Dienstplan auf einen
 * Schlag als "Neue Schicht erkannt".
 *
 * ALTBESTAND: ein Merker aus einer Version mit festem Fenster hat keine Fensterangabe - und das war
 * immer 14 Tage.
 */
class SyncHorizonStoreFensterTest {

    private class FakeDataStore(initial: Preferences = mutablePreferencesOf()) : DataStore<Preferences> {
        private val zustand = MutableStateFlow(initial)
        val aktuell: Preferences get() = zustand.value
        override val data: Flow<Preferences> = zustand
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            val neu = transform(zustand.value)
            zustand.value = neu
            return neu
        }
    }

    private val keySync = longPreferencesKey(SyncHorizonStore.KEY_LAST_SYNC_NAME)
    private val keyFenster = intPreferencesKey(SyncHorizonStore.KEY_FENSTER_TAGE_NAME)
    private val tag = 24L * 60 * 60 * 1000

    @Test
    fun `Altbestand ohne Fensterangabe gilt als 14 Tage`() = runTest {
        val store = SyncHorizonStore(FakeDataStore(mutablePreferencesOf().apply { this[keySync] = 1_000L }))

        val merker = store.letzterVollstaendigerSync().getOrThrow()

        assertEquals(SyncHorizonStore.SyncMerker(1_000L, 14), merker)
        assertEquals(1_000L + 14 * tag, SyncHorizonStore.horizontEndeFuer(merker!!))
    }

    @Test
    fun `der Horizont rechnet mit dem gespeicherten Fenster`() = runTest {
        val store = SyncHorizonStore(
            FakeDataStore(mutablePreferencesOf().apply {
                this[keySync] = 1_000L
                this[keyFenster] = 56
            })
        )

        val merker = store.letzterVollstaendigerSync().getOrThrow()!!

        assertEquals(56, merker.fensterTage)
        assertEquals(1_000L + 56 * tag, SyncHorizonStore.horizontEndeFuer(merker))
    }

    @Test
    fun `ein unplausibles Fenster faellt auf den Altbestand zurueck`() = runTest {
        val store = SyncHorizonStore(
            FakeDataStore(mutablePreferencesOf().apply {
                this[keySync] = 1_000L
                this[keyFenster] = 0
            })
        )

        assertEquals(14, store.letzterVollstaendigerSync().getOrThrow()!!.fensterTage)
    }

    @Test
    fun `ohne Zeitpunkt gibt es keinen Merker - auch wenn ein Fenster herumliegt`() = runTest {
        val store = SyncHorizonStore(FakeDataStore(mutablePreferencesOf().apply { this[keyFenster] = 28 }))

        assertNull(store.letzterVollstaendigerSync().getOrThrow())
    }

    @Test
    fun `Zeitpunkt und Fenster werden gemeinsam fortgeschrieben`() = runTest {
        val dataStore = FakeDataStore(mutablePreferencesOf().apply { this[keySync] = 1_000L })
        val store = SyncHorizonStore(dataStore)

        store.merkeVollstaendigenSync(syncAt = 5_000L, fensterTage = 42)

        assertEquals(5_000L, dataStore.aktuell[keySync])
        assertEquals(42, dataStore.aktuell[keyFenster])
        assertEquals(SyncHorizonStore.SyncMerker(5_000L, 42), store.letzterVollstaendigerSync().getOrThrow())
    }
}
