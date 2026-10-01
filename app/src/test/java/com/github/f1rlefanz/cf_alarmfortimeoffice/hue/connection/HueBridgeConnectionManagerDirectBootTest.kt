package com.github.f1rlefanz.cf_alarmfortimeoffice.hue.connection

import android.content.Context
import android.os.UserManager
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.api.HueApiClient
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.NetworkStateMonitor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.util.concurrent.atomic.AtomicInteger

/**
 * Vor der ersten Entsperrung liest der Hue-Verbindungsmanager NICHTS - und verbraucht seinen
 * Einmal-Waechter nicht, damit das Nachholen nach dem Entsperren greift.
 *
 * HERGANG (01.10.2026, Emulator mit PIN, Stacktrace am settings-Store gemessen): `initialize()`
 * startete im Direct-Boot-Prozess die Gesundheitsschleife, die `MasterPausePrefs.pausedNow()`
 * las. Ein CE-DataStore-Read vor dem Entsperren liefert still LEER, und DataStore haelt das im
 * Speicher - derselbe Prozess las danach den ganzen settings-Store leer: Alarm-Bestand
 * "keiner gespeichert" (manuelle Wecker weg), Master-Pause "nicht pausiert". Dasselbe traf
 * `hue_settings` (Bridge "nicht verbunden").
 */
class HueBridgeConnectionManagerDirectBootTest {

    private class ZaehlenderStore : DataStore<Preferences> {
        val lesezugriffe = AtomicInteger(0)
        override val data: Flow<Preferences> = flow {
            lesezugriffe.incrementAndGet()
            emit(emptyPreferences())
        }
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            transform(emptyPreferences())
    }

    @Test
    fun `gesperrt liest initialize nichts und holt nach dem Entsperren nach`() {
        var entsperrt = false
        val userManager = mock<UserManager>()
        whenever(userManager.isUserUnlocked).thenAnswer { entsperrt }
        val context = mock<Context>()
        whenever(context.applicationContext).thenReturn(context)
        whenever(context.getSystemService(UserManager::class.java)).thenReturn(userManager)

        val store = ZaehlenderStore()
        val scheduler = mock<SmartSchedulerHandle>()
        val manager = HueBridgeConnectionManager.createForTesting(
            context = context,
            networkMonitor = mock<NetworkStateMonitor>(),
            apiClient = mock<HueApiClient>(),
            testHueDataStore = store,
            testSmartScheduler = scheduler
        )

        manager.initialize()
        Thread.sleep(300)

        assertEquals("Im gesperrten Zustand darf hue_settings nicht gelesen werden", 0, store.lesezugriffe.get())
        verify(scheduler, never()).initializeSmartScheduling()

        // Nach dem Entsperren: der Waechter wurde nicht verbraucht, die echte Initialisierung laeuft.
        entsperrt = true
        manager.initialize()
        Thread.sleep(300)

        verify(scheduler, times(1)).initializeSmartScheduling()
        assertTrue("Nach dem Entsperren muss die Verbindung gelesen werden", store.lesezugriffe.get() >= 1)
        manager.cleanup()
    }
}
