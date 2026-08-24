package com.github.f1rlefanz.cf_alarmfortimeoffice.hue.connection

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.api.HueApiClient
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.NetworkStateMonitor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.stub
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Unit tests for the "off-network pre-flight" guard in [HueBridgeConnectionManager.getValidatedConnection].
 *
 * Background: real debug logs (2026-07-20/22) showed the app attempting a real HTTPS request
 * to the Hue Bridge - and waiting out a 10s [java.net.SocketTimeoutException] - even though the
 * phone was demonstrably not on the bridge's network. That happened because the cached
 * `ConnectionState.CONNECTED` fast path in `getValidatedConnection()` returned the bridge
 * IP/credentials with no reachability check at all.
 *
 * NACHGESCHAERFT (v1.24.x): Der erste Fix machte [NetworkStateMonitor.isReachableSubnet] zum
 * VETO - "fail fast WITHOUT ever calling the real API client". Das ging zu weit. Die Pruefung
 * verlangt eine eigene IPv4 im selben Subnetz und liefert Falsch-Negative, sobald ein Router
 * zwischen zwei erreichbaren Netzen vermittelt: Gast-WLAN, getrenntes VLAN, Mesh mit eigenem
 * Subnetz, Doppel-NAT - und der Emulator, der ueber NAT (10.0.2.x) sehr wohl ins Heimnetz
 * routet. Am 13.08.2026 real aufgeschlagen: das Pairing bekam eine HTTPS 200 von der Bridge,
 * und Millisekunden spaeter verwarf die Validierung genau diese Bridge als "not reachable".
 *
 * Heutige Zusicherung: die Heuristik entscheidet ueber das TIMEOUT, nicht ueber das Ergebnis.
 * Off-subnet wird ein KURZ gedeckelter echter Versuch gemacht; nur wenn auch der scheitert,
 * gilt die Bridge als unerreichbar. Der urspruengliche Zweck (kein 10s-Haenger ausser Haus)
 * bleibt erhalten, ebenso das Nicht-Herabstufen des Verbindungszustands - ein transientes
 * "falsches Netz" ist kein Bridge-/Zugangsdaten-Fehler.
 */
class HueBridgeConnectionManagerTest {

    private val bridgeIp = "192.168.178.24"
    private val username = "test-user"

    /** Minimaler, echter In-Memory-DataStore<Preferences> - deckt data/updateData (und edit{}) ab.
     * Reimplementiert lokal (statt aus AlarmRepositoryTest importiert) nach demselben Muster,
     * das dort schon fuer genau diesen Zweck existiert. */
    private class FakePreferencesDataStore(initial: Preferences) : DataStore<Preferences> {
        private val flow = MutableStateFlow(initial)
        override val data: Flow<Preferences> = flow
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            val updated = transform(flow.value)
            flow.value = updated
            return updated
        }
    }

    private fun buildManager(
        isReachable: Boolean,
    ): Triple<HueBridgeConnectionManager, NetworkStateMonitor, HueApiClient> {
        val context = mock<Context>()
        whenever(context.applicationContext).thenReturn(context)

        val networkMonitor = mock<NetworkStateMonitor>()
        whenever(networkMonitor.isReachableSubnet(any())).thenReturn(isReachable)

        val apiClient = mock<HueApiClient>()

        val manager = HueBridgeConnectionManager.createForTesting(context, networkMonitor, apiClient)
        seedConnectedState(manager)

        return Triple(manager, networkMonitor, apiClient)
    }

    /**
     * Baut einen Manager fuer die attemptRecoveryIfDisconnected()-Tests: echtes (Fake-)DataStore
     * statt EntryPoint-Aufloesung, KEIN geseedeter CONNECTED-Zustand (bleibt beim echten Default
     * DISCONNECTED) - im Gegensatz zu [buildManager], das gezielt fuer die bereits-verbunden-Faelle
     * von getValidatedConnection() gebaut ist.
     */
    private fun buildManagerForRecoveryTests(
        hasStoredBridge: Boolean,
    ): Triple<HueBridgeConnectionManager, NetworkStateMonitor, HueApiClient> {
        val context = mock<Context>()
        whenever(context.applicationContext).thenReturn(context)

        val networkMonitor = mock<NetworkStateMonitor>()
        whenever(networkMonitor.isReachableSubnet(any())).thenReturn(true)

        val apiClient = mock<HueApiClient>()

        val prefs = if (hasStoredBridge) {
            mutablePreferencesOf().apply {
                this[stringPreferencesKey("bridge_ip")] = bridgeIp
                this[stringPreferencesKey("username")] = username
                this[booleanPreferencesKey("connection_validated")] = true
            }
        } else {
            emptyPreferences()
        }
        val fakeDataStore = FakePreferencesDataStore(prefs)

        val manager = HueBridgeConnectionManager.createForTesting(
            context,
            networkMonitor,
            apiClient,
            testHueDataStore = fakeDataStore,
        )

        return Triple(manager, networkMonitor, apiClient)
    }

    /** Seeds a CONNECTED state via the private `updateConnectionState` method - there is no
     * public setter, and driving it through [HueBridgeConnectionManager.setConnection] would
     * require a real Hilt-provided DataStore, which isn't available in a plain unit test.
     * Goes through `updateConnectionState` rather than poking the `currentConnectionState`
     * field directly, since that method is also the only thing that keeps the separate
     * `_connectionStatus` StateFlow (what [HueBridgeConnectionManager.connectionStatus] reads)
     * in sync - setting just the field would leave `connectionStatus.value` stuck at its
     * initial DISCONNECTED. */
    private fun seedConnectedState(manager: HueBridgeConnectionManager) {
        val connectedState = HueBridgeConnectionManager.ConnectionState.CONNECTED(bridgeIp, username)
        val method = HueBridgeConnectionManager::class.java.getDeclaredMethod(
            "updateConnectionState",
            HueBridgeConnectionManager.ConnectionState::class.java,
        )
        method.isAccessible = true
        method.invoke(manager, connectedState)
    }

    /**
     * Setzt den privaten `recoveryInFlight`-AtomicBoolean-Guard direkt per Reflection - simuliert
     * einen bereits laufenden, gleichzeitigen Wiederverbindungsversuch, ohne echte Nebenlaeufigkeit
     * im Test orchestrieren zu muessen.
     */
    private fun setRecoveryInFlight(manager: HueBridgeConnectionManager, value: Boolean) {
        val field = HueBridgeConnectionManager::class.java.getDeclaredField("recoveryInFlight")
        field.isAccessible = true
        (field.get(manager) as AtomicBoolean).set(value)
    }

    // NOTE: attemptRecoveryIfDisconnected() is `internal` + @VisibleForTesting (not `private`)
    // specifically so these tests can call it directly instead of via reflection. A first attempt
    // reflectively invoked the private suspend fun by synthesizing a Continuation via
    // suspendCoroutine { cont -> method.invoke(manager, cont) } - that deadlocks whenever the
    // invoked function body completes SYNCHRONOUSLY (no real suspension point actually reached,
    // e.g. because the fake DataStore's MutableStateFlow.first() and the mocked EntryPoint
    // resolution never truly suspend): method.invoke(...) then returns the real result directly
    // instead of the COROUTINE_SUSPENDED marker, nobody ever calls cont.resume(...), and
    // suspendCoroutine waits forever. Reproduced live (gradle testDebugUnitTest hung indefinitely
    // on this test class). `internal` + @VisibleForTesting avoids the whole class of problems.

    // NOTE: block bodies (not `= runBlocking { ... }` expression bodies) are deliberate here -
    // JUnit4 requires @Test methods to return void, and an expression body whose last statement
    // is a `verify(mock).suspendFun(...)` call would otherwise infer a non-Unit return type
    // (the suspend function's return type), which JUnit4 rejects with InvalidTestClassError.

    /**
     * ABGELOEST die fruehere Zusicherung "off-subnet wirft, OHNE den API-Client je aufzurufen".
     *
     * Die alte Fassung schrieb fest, dass die Subnetz-Heuristik ein VETO ist. Genau das war der
     * Fehler: `isReachableSubnet()` verlangt eine eigene IPv4 im selben Subnetz und liefert
     * Falsch-Negative bei Gast-WLAN, getrenntem VLAN, Mesh mit eigenem Subnetz, Doppel-NAT und
     * am Emulator (NAT 10.0.2.x, routet aber ins Heimnetz). Am 13.08.2026 real aufgeschlagen:
     * das Pairing bekam eine HTTPS 200 von der Bridge, und Millisekunden spaeter verwarf die
     * Validierung dieselbe Bridge als "not reachable" - eine antwortende Bridge, abgelehnt von
     * einer Vermutung ueber sie.
     *
     * Die Heuristik entscheidet jetzt nur noch ueber das TIMEOUT. Der echte Request ist das
     * Urteil - deshalb wird er auch off-subnet versucht.
     */
    @Test
    fun `off-subnet - der echte Request wird trotzdem versucht, nicht uebersprungen`() {
        runBlocking {
            val (manager, _, apiClient) = buildManager(isReachable = false)

            runCatching { manager.getValidatedConnection() }

            // Der Kern der Aenderung: GENAU EIN echter Versuch statt null.
            verify(apiClient, times(1)).getBridgeConfig(any(), any())
        }
    }

    /** Scheitert auch der Kurzversuch, bleibt es beim alten, richtigen Ausgang. */
    @Test
    fun `off-subnet - scheitert auch der Kurzversuch, wird geworfen`() {
        runBlocking {
            val (manager, _, apiClient) = buildManager(isReachable = false)
            apiClient.stub {
                on { getBridgeConfig(any(), any()) } doAnswer { throw java.io.IOException("no route") }
            }

            assertThrows(IllegalStateException::class.java) {
                runBlocking { manager.getValidatedConnection() }
            }
        }
    }

    /**
     * Der eigentliche Zweck des Fixes: antwortet die Bridge, gilt sie als erreichbar - auch wenn
     * die Subnetz-Heuristik das Gegenteil behauptet.
     */
    @Test
    fun `off-subnet - antwortet die Bridge trotzdem, wird die Verbindung benutzt`() {
        runBlocking {
            val (manager, _, _) = buildManager(isReachable = false)

            val (returnedIp, returnedUser) = manager.getValidatedConnection()

            assertTrue(returnedIp == bridgeIp && returnedUser == username)
        }
    }

    @Test
    fun `off-subnet cached connection does not downgrade connection state to ERROR`() {
        runBlocking {
            val (manager, _, _) = buildManager(isReachable = false)

            runCatching { manager.getValidatedConnection() }

            assertTrue(manager.connectionStatus.value is HueBridgeConnectionManager.ConnectionState.CONNECTED)
        }
    }

    @Test
    fun `on-subnet cached connection returns cached credentials without calling the API`() {
        runBlocking {
            val (manager, _, apiClient) = buildManager(isReachable = true)

            val (returnedIp, returnedUser) = manager.getValidatedConnection()

            assertTrue(returnedIp == bridgeIp && returnedUser == username)
            // Fresh cache (just seeded) - still within CONNECTION_CACHE_VALIDITY, so no API call needed.
            verify(apiClient, never()).getBridgeConfig(any(), any())
        }
    }

    @Test
    fun `attemptRecoveryIfDisconnected - bereits verbunden loest keine Wiederverbindung aus`() {
        runBlocking {
            val (manager, _, apiClient) = buildManagerForRecoveryTests(hasStoredBridge = true)
            seedConnectedState(manager)

            manager.attemptRecoveryIfDisconnected()

            verify(apiClient, never()).getBridgeConfig(any(), any())
        }
    }

    @Test
    fun `attemptRecoveryIfDisconnected - keine gespeicherte Bridge loest keine Wiederverbindung aus`() {
        runBlocking {
            val (manager, _, apiClient) = buildManagerForRecoveryTests(hasStoredBridge = false)

            manager.attemptRecoveryIfDisconnected()

            verify(apiClient, never()).getBridgeConfig(any(), any())
        }
    }

    @Test
    fun `attemptRecoveryIfDisconnected - gespeicherte Bridge loest eine echte Wiederverbindung aus`() {
        runBlocking {
            val (manager, _, apiClient) = buildManagerForRecoveryTests(hasStoredBridge = true)

            manager.attemptRecoveryIfDisconnected()

            verify(apiClient).getBridgeConfig(any(), any())
        }
    }

    @Test
    fun `attemptRecoveryIfDisconnected - eine bereits laufende Wiederverbindung wird nicht doppelt gestartet`() {
        runBlocking {
            val (manager, _, apiClient) = buildManagerForRecoveryTests(hasStoredBridge = true)
            setRecoveryInFlight(manager, true)

            manager.attemptRecoveryIfDisconnected()

            verify(apiClient, never()).getBridgeConfig(any(), any())
        }
    }
}
