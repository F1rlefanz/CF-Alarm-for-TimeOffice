package com.github.f1rlefanz.cf_alarmfortimeoffice.util

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * Sichert [readOrEmpty] ab - den gemeinsamen Lesepfad der Onboarding-/Gate-Merker.
 *
 * REALER BEFUND (Pruefrunde 14.08.2026, Dimension "Navigation und UI-Zustand"): Vier Reads auf
 * den @MainDataStore in der Gate-Kette waren blanke `data.first()` ohne jedes `.catch`. Der
 * `ReplaceFileCorruptionHandler` des Stores faengt nur eine CorruptionException; eine IOException
 * auf `settings.preferences_pb` reicht DataStore durch. Drei dieser Reads stehen direkt im
 * `LaunchedEffect` von `MainScreen` - die Exception haette die App beim Erreichen des
 * Hauptbereichs beendet, reboot-fest, solange der Lesefehler besteht.
 *
 * Die Degradationsrichtung ist die eigentliche Aussage dieses Tests: auf den Default, also
 * "NICHT abgelehnt" - der Hinweis wird im Zweifel GEZEIGT. Ein ueberzaehliger Hinweis ist
 * harmlos; ein unterdrueckter kostet die Akku-Ausnahme bzw. die Ausnahme von "App bei
 * Nichtnutzung pausieren", und beide haben in diesem Projekt schon Wecker verschluckt.
 */
class SafePreferencesReadTest {

    private companion object {
        val KEY_DISMISSED = booleanPreferencesKey("dismissed")
    }

    /** Store, dessen `data`-Flow wirft - genau das, was DataStore bei einer IOException tut. */
    private class FailingDataStore : DataStore<Preferences> {
        override val data: Flow<Preferences> = flow { throw IOException("simulierter Lesefehler") }
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            throw IOException("simulierter Schreibfehler")
    }

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
    fun `Lesefehler wirft nicht, sondern degradiert auf leere Preferences`() = runTest {
        val prefs = FailingDataStore().readOrEmpty("test", "Merker")

        assertFalse(
            "Ein unlesbarer Store darf nicht als 'schon abgelehnt' gelten - sonst wird der " +
                "Hinweis dauerhaft unterdrueckt",
            prefs[KEY_DISMISSED] ?: false
        )
    }

    @Test
    fun `ein tatsaechlich gesetzter Merker wird unveraendert gelesen`() = runTest {
        val store = FakeDataStore(mutablePreferencesOf(KEY_DISMISSED to true))

        val prefs = store.readOrEmpty("test", "Merker")

        assertTrue(
            "Die Degradation darf einen vorhandenen Wert nicht ueberschreiben",
            prefs[KEY_DISMISSED] ?: false
        )
    }
}
