package com.github.f1rlefanz.cf_alarmfortimeoffice.util

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

/**
 * Sichert [BatteryOptimizationHelper.markOemWarningScreenShown] gegen den Absturz-Befund ab.
 *
 * REALER BEFUND (Review #132): Seit der automatische Weg auch den OEM-Hinweis ansteuert, ruft der
 * Gate-`LaunchedEffect` von `MainScreen` den Merker-Write bei jedem Start mit faelligem Hinweis.
 * Ein blankes `edit()` liess eine IOException (volle Platte) durch, die App starb - und weil der
 * Merker nie geschrieben wurde, bei JEDEM Start erneut. Ohne den Fix wirft der erste Test.
 */
class OemHinweisMerkerTest {

    /** Lesen geht, Schreiben wirft - der Fall "Speicher voll". */
    private class SchreibFehlerStore(private val fehler: Exception) : DataStore<Preferences> {
        override val data: Flow<Preferences> = MutableStateFlow(emptyPreferences())
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            throw fehler
    }

    private class FakeDataStore : DataStore<Preferences> {
        val flow = MutableStateFlow(emptyPreferences())
        override val data: Flow<Preferences> = flow
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            val updated = transform(flow.value)
            flow.value = updated
            return updated
        }
    }

    @Test
    fun `Schreibfehler wirft nicht, sondern meldet false`() = runTest {
        val ergebnis = BatteryOptimizationHelper.markOemWarningScreenShown(
            SchreibFehlerStore(IOException("simulierter Schreibfehler")),
            BatteryOptimizationHelper.OEMType.SAMSUNG
        )

        assertFalse("Ein gescheiterter Write darf nicht als geschrieben gemeldet werden", ergebnis)
    }

    @Test
    fun `Abbruch wird weitergeworfen, nicht geschluckt`() = runTest {
        try {
            BatteryOptimizationHelper.markOemWarningScreenShown(
                SchreibFehlerStore(CancellationException("abgebrochen")),
                BatteryOptimizationHelper.OEMType.SAMSUNG
            )
            fail("CancellationException muss durchgereicht werden - sonst laeuft ein abgebrochener Effect weiter")
        } catch (_: CancellationException) {
            // erwartet
        }
    }

    @Test
    fun `erfolgreicher Write setzt den Merker genau fuer diesen Typ`() = runTest {
        val store = FakeDataStore()

        val ergebnis = BatteryOptimizationHelper.markOemWarningScreenShown(
            store,
            BatteryOptimizationHelper.OEMType.XIAOMI
        )

        assertTrue(ergebnis)
        assertEquals(true, store.flow.value[booleanPreferencesKey("oem_hint_shown_XIAOMI")])
        assertEquals(null, store.flow.value[booleanPreferencesKey("oem_hint_shown_SAMSUNG")])
    }
}
