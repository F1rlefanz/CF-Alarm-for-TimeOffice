package com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Pruefrunde 6, Befund 6: die Dimm-VORSCHAU schaltete die systemweite Verdunkelung persistent EIN,
 * nahm sie aber ausschliesslich ueber einen Timer IM PROZESS zurueck (`delay()` + `finally` unter
 * `NonCancellable`).
 *
 * Was ohne den Ablaufzeitpunkt kaputt ging: `NonCancellable` deckt nur Coroutine-Cancellation ab -
 * ein Prozesstod (Absturz, "Beenden erzwingen", App-Update, OEM-Task-Killer) fuehrt kein `finally`
 * aus. Android bindet den `DimAccessibilityService` danach neu, der liest `dim_overlay_on = true`
 * und baut das Overlay sofort wieder auf: der Bildschirm ist SYSTEMWEIT bis zu 85 % verdunkelt, in
 * jeder App, und in Screenshots nicht einmal sichtbar. Geheilt haette das erst ein
 * `applyCurrentState()` - und wer die Vorschau zum Ausprobieren nutzt, hat typischerweise noch
 * keine Fenster-Quelle an, also auch keinen rollenden Tick. Bis zum 6h-Wartungslauf konnten so
 * Stunden vergehen.
 *
 * Die Zusicherung, die diese Tests halten: der Ablaufzeitpunkt geht MIT auf die Platte, und jeder
 * spaetere Leser setzt ihn von allein durch - ohne Schreibzugriff und ohne dass irgendjemand den
 * Prozesstod bemerken muesste.
 */
class Pruefrunde6DimmPreviewExpiryTest {

    private companion object {
        val KEY_OVERLAY_ON = booleanPreferencesKey("dim_overlay_on")
        val KEY_PREVIEW_UNTIL = longPreferencesKey("dim_overlay_preview_until")
        val KEY_RENDER_STRENGTH = intPreferencesKey("dim_render_strength")
        val KEY_RENDER_WARMTH = intPreferencesKey("dim_render_warmth")
    }

    private class FakeDataStore(initial: Preferences) : DataStore<Preferences> {
        private val flow = MutableStateFlow(initial)
        override val data: Flow<Preferences> = flow
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            val updated = transform(flow.value)
            flow.value = updated
            return updated
        }

        suspend fun snapshot(): Preferences = flow.first()
    }

    private fun storeWith(overlayOn: Boolean, previewUntil: Long?): FakeDataStore =
        FakeDataStore(
            mutablePreferencesOf().apply {
                this[KEY_OVERLAY_ON] = overlayOn
                this[KEY_RENDER_STRENGTH] = 80
                this[KEY_RENDER_WARMTH] = 30
                if (previewUntil != null) this[KEY_PREVIEW_UNTIL] = previewUntil
            }
        )

    /**
     * DER Fall aus dem Befund: der Prozess starb waehrend der Vorschau, der Dienst bindet neu und
     * liest den Zustand von der Platte. Der Ablaufzeitpunkt ist da laengst vorbei - es darf NICHT
     * gerendert werden.
     *
     * Ohne den Fix liefert `renderState` hier `overlayOn = true`, und der Bildschirm bleibt
     * systemweit dunkel, bis Stunden spaeter die 6h-Wartung `applyCurrentState()` ruft.
     */
    @Test
    fun `abgelaufene Vorschau wird nach Prozesstod nicht mehr gerendert`() = runTest {
        val store = storeWith(overlayOn = true, previewUntil = System.currentTimeMillis() - 1)
        val prefs = DimOverlayPrefs(store)

        val state = prefs.renderState.first()

        assertFalse(
            "Eine abgelaufene Vorschau darf der neu gebundene Dienst nicht wieder aufbauen",
            state.overlayOn
        )
    }

    /**
     * Die zweite Haelfte derselben Luecke: stirbt der Prozess und bindet der Dienst SOFORT wieder,
     * ist der Ablaufzeitpunkt noch in der Zukunft. Dann muss der Leser ihn abwarten und das Aus
     * selbst nachreichen - sonst kaeme nie wieder ein Wert nach (es schreibt ja niemand mehr) und
     * die Verdunkelung bliebe genauso haengen wie vorher.
     */
    @Test
    fun `laufende Vorschau erlischt von allein, ohne dass jemand schreibt`() = runTest {
        val store = storeWith(overlayOn = true, previewUntil = System.currentTimeMillis() + 60_000)
        val prefs = DimOverlayPrefs(store)

        val states = try {
            // Der Store emittiert genau EINMAL; das zweite Element kann nur aus der
            // Selbstdurchsetzung des Ablaufzeitpunkts kommen. Ohne sie wartet toList() ewig.
            // Die Wartezeit ist VIRTUELL (runTest-Scheduler) - das Fenster darf deshalb gross
            // genug sein, dass eine langsame Maschine den Test nicht flaky macht.
            withTimeout(5 * 60_000L) { prefs.renderState.take(2).toList() }
        } catch (e: TimeoutCancellationException) {
            fail(
                "Nach dem Ablauf der Vorschau kam kein Aus nach - der Dienst rendert weiter, " +
                    "obwohl niemand mehr schreibt (${e.message})"
            )
            return@runTest
        }

        assertTrue("Waehrend der Vorschau wird noch verdunkelt", states.first().overlayOn)
        assertFalse("Nach dem Ablauf muss das Aus von allein kommen", states.last().overlayOn)
        // Die Render-Werte bleiben erhalten - abgeschaltet wird, nicht umgefaerbt.
        assertEquals(80, states.last().strength)
    }

    /**
     * Der Scheduler ist die Wahrheit ueber den regulaeren Zustand: sein Schreibvorgang beendet jede
     * Vorschau. Bliebe der Ablaufzeitpunkt liegen, wuerde ein voellig regulaeres, gerade
     * eingeschaltetes Dimm-Fenster faelschlich als abgelaufene Vorschau abgeschaltet - der Fix
     * haette dann ein neues Loch gerissen.
     */
    @Test
    fun `setActiveOverlay raeumt den Vorschau-Ablauf weg`() = runTest {
        val store = storeWith(overlayOn = true, previewUntil = System.currentTimeMillis() - 1)
        val prefs = DimOverlayPrefs(store)

        prefs.setActiveOverlay(true, 55, 40)

        assertNull(
            "Nach einem Scheduler-Schreibvorgang darf kein Vorschau-Ablauf mehr im Store stehen",
            store.snapshot()[KEY_PREVIEW_UNTIL]
        )
        assertTrue(
            "Ein regulaer eingeschaltetes Dimm-Fenster darf nicht als abgelaufene Vorschau gelten",
            prefs.renderState.first().overlayOn
        )
    }

    /** Ohne Vorschau-Ablauf bleibt der Lesepfad unveraendert - kein Verhaltenswechsel fuer den Scheduler. */
    @Test
    fun `regulaeres Dimm-Fenster ohne Vorschau-Ablauf wird unveraendert gerendert`() = runTest {
        val prefs = DimOverlayPrefs(storeWith(overlayOn = true, previewUntil = null))

        val state = prefs.renderState.first()

        assertTrue(state.overlayOn)
        assertEquals(80, state.strength)
        assertEquals(30, state.warmth)
    }

    /** Die Vorschau schreibt den Ablauf mit - sonst kann ihn kein spaeterer Leser durchsetzen. */
    @Test
    fun `setPreviewOverlay schreibt den Ablaufzeitpunkt mit auf die Platte`() = runTest {
        val store = storeWith(overlayOn = false, previewUntil = null)
        val prefs = DimOverlayPrefs(store)
        val expiry = System.currentTimeMillis() + 7_000

        prefs.setPreviewOverlay(70, 20, expiry)

        val snapshot = store.snapshot()
        assertTrue("Die Vorschau schaltet ein", snapshot[KEY_OVERLAY_ON] == true)
        assertEquals(expiry, snapshot[KEY_PREVIEW_UNTIL] ?: -1L)
        assertEquals(70, snapshot[KEY_RENDER_STRENGTH] ?: -1)
        assertEquals(20, snapshot[KEY_RENDER_WARMTH] ?: -1)
    }
}
