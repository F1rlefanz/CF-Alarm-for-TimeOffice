package com.github.f1rlefanz.cf_alarmfortimeoffice.hue.connection

import android.content.Context
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.api.HueApiClient
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.NetworkStateMonitor
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * Unit tests for the "off-network pre-flight" guard in [HueBridgeConnectionManager.getValidatedConnection].
 *
 * Background: real debug logs (2026-07-20/22) showed the app attempting a real HTTPS request
 * to the Hue Bridge - and waiting out a 10s [java.net.SocketTimeoutException] - even though the
 * phone was demonstrably not on the bridge's network. That happened because the cached
 * `ConnectionState.CONNECTED` fast path in `getValidatedConnection()` returned the bridge
 * IP/credentials with no reachability check at all. These tests lock in the fix: when
 * [NetworkStateMonitor.isReachableSubnet] says the bridge isn't reachable, the manager must
 * fail fast WITHOUT ever calling the real API client, and must not downgrade the cached
 * connection state (a transient "wrong network" isn't a bridge/credential failure).
 */
class HueBridgeConnectionManagerTest {

    private val bridgeIp = "192.168.178.24"
    private val username = "test-user"

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

    // NOTE: block bodies (not `= runBlocking { ... }` expression bodies) are deliberate here -
    // JUnit4 requires @Test methods to return void, and an expression body whose last statement
    // is a `verify(mock).suspendFun(...)` call would otherwise infer a non-Unit return type
    // (the suspend function's return type), which JUnit4 rejects with InvalidTestClassError.

    @Test
    fun `off-subnet cached connection throws without ever calling the real API client`() {
        runBlocking {
            val (manager, _, apiClient) = buildManager(isReachable = false)

            assertThrows(IllegalStateException::class.java) {
                runBlocking { manager.getValidatedConnection() }
            }

            verify(apiClient, never()).getBridgeConfig(any(), any())
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
}
