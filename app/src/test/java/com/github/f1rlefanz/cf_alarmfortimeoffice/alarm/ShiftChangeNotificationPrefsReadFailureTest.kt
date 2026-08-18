package com.github.f1rlefanz.cf_alarmfortimeoffice.alarm

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * Unit-Tests fuer den LESEPFAD von [ShiftChangeNotificationPrefs] bei einem Store-Fehler.
 *
 * Hintergrund: bis v1.26.2 war dies die einzige der drei Notification-Prefs ohne `.catch`
 * (CalendarUnavailablePrefs und DimOverlayPrefs hatten eines). Der `ReplaceFileCorruptionHandler`
 * faengt nur Korruption - eine IOException reicht DataStore durch.
 *
 * Der Weg des Wurfs ist der gefaehrliche Teil, nicht die verschwiegene Meldung: [enabledNow] ist
 * ein `first()` auf diesen Flow, `ShiftChangeNotifier.notifyX()` ruft es als erste Anweisung, und
 * die notify-Methoden laufen MITTEN IN `AlarmUseCase.syncAlarms()`. Ein Wurf haette den laufenden
 * Alarm-Sync abgebrochen - mit halb angelegten Alarmen.
 *
 * Fail-safe Richtung hier: degradiert auf AN, "im Zweifel melden". Eine ueberfluessige Meldung ist
 * harmlos, eine verschwiegene Dienstplan-Aenderung nicht. Das ist die GEGENRICHTUNG zum Dimmer
 * (dort: im Zweifel nicht verdunkeln) - beide Richtungen sind je Store bewusst gewaehlt.
 *
 * Der Fix ist v1.26.2, Befund 6 der Pruefrunde 4; er hing bis dahin an keinem einzigen Test.
 */
class ShiftChangeNotificationPrefsReadFailureTest {

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
    fun `enabled wirft bei Lesefehler nicht, sondern degradiert auf AN`() = runTest {
        val prefs = ShiftChangeNotificationPrefs(FailingDataStore())

        val enabled = prefs.enabled.first()

        assertTrue("Bei unlesbaren Einstellungen wird im Zweifel gemeldet", enabled)
    }

    @Test
    fun `enabledNow wirft bei Lesefehler nicht - der laufende Alarm-Sync darf nicht abbrechen`() =
        runTest {
            val prefs = ShiftChangeNotificationPrefs(FailingDataStore())

            // Der eigentliche Schaden waere nicht der falsche Wert, sondern die Exception:
            // enabledNow() laeuft mitten in AlarmUseCase.syncAlarms().
            assertTrue(prefs.enabledNow())
        }

    @Test
    fun `intakter Store wird unveraendert durchgereicht - auch der abgeschaltete Zustand`() =
        runTest {
            val store = FakeDataStore(
                mutablePreferencesOf().apply {
                    this[booleanPreferencesKey("shift_change_notification_enabled")] = false
                }
            )
            val prefs = ShiftChangeNotificationPrefs(store)

            assertFalse(
                "Ein lesbares AUS darf nicht von der Degradierung ueberschrieben werden",
                prefs.enabled.first()
            )
        }

    @Test
    fun `fehlender Schluessel ist AN - Default des Feature-Bereichs`() = runTest {
        val prefs = ShiftChangeNotificationPrefs(FakeDataStore(mutablePreferencesOf()))

        assertTrue(prefs.enabled.first())
    }
}
