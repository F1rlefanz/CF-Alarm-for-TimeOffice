package com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * Unit-Tests fuer den LESEPFAD von [DimOverlayPrefs] bei einem Store-Fehler.
 *
 * Hintergrund: die Flows waren blanke `dataStore.data.map{}` ohne ein einziges `.catch`. Der
 * `ReplaceFileCorruptionHandler` faengt nur Korruption - eine IOException reicht DataStore durch,
 * und im [DimAccessibilityService] (SupervisorJob ohne CoroutineExceptionHandler) haette sie den
 * PROZESS beendet, der die Alarme haelt.
 *
 * Fail-safe Richtung fuer den Dimmer: im Zweifel NICHT verdunkeln. Ein unerwartet dunkler
 * Bildschirm ist schlimmer als ein unerwartet heller - bei voller Verdunkelung kann der Nutzer sein
 * Geraet nicht mehr bedienen und den Dimmer nicht mehr abschalten.
 */
class DimOverlayPrefsReadFailureTest {

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
    fun `renderState wirft bei Lesefehler nicht, sondern degradiert auf AUS`() = runTest {
        val prefs = DimOverlayPrefs(FailingDataStore())

        val state = prefs.renderState.first()

        assertFalse("Bei unlesbaren Einstellungen darf NICHT gedimmt werden", state.overlayOn)
        assertEquals(DimOverlayPrefs.DEFAULT_STRENGTH, state.strength)
        assertEquals(DimOverlayPrefs.DEFAULT_WARMTH, state.warmth)
    }

    @Test
    fun `toggles wirft bei Lesefehler nicht, sondern degradiert auf alle AUS`() = runTest {
        val prefs = DimOverlayPrefs(FailingDataStore())

        val toggles = prefs.togglesNow()

        assertFalse(toggles.wellnessEnabled)
        assertFalse(toggles.rulesEnabled)
        assertFalse(toggles.nightDefaultEnabled)
    }

    @Test
    fun `override wirft bei Lesefehler nicht, sondern degradiert auf keinen Override`() = runTest {
        val prefs = DimOverlayPrefs(FailingDataStore())

        val override = prefs.overrideNow()

        assertEquals(0, override.strengthDelta)
        assertFalse(override.paused)
        assertEquals(0L, override.windowEnd)
        assertEquals(0, override.windowStrength)
    }

    @Test
    fun `intakter Store wird unveraendert durchgereicht`() = runTest {
        val store = FakeDataStore(
            mutablePreferencesOf().apply {
                this[booleanPreferencesKey("dim_overlay_on")] = true
                this[intPreferencesKey("dim_render_strength")] = 40
                this[intPreferencesKey("dim_render_warmth")] = 30
            }
        )
        val prefs = DimOverlayPrefs(store)

        val state = prefs.renderState.first()

        assertTrue(state.overlayOn)
        assertEquals(40, state.strength)
        assertEquals(30, state.warmth)
    }
}
