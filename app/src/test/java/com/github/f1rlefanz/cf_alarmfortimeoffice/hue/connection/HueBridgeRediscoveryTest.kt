package com.github.f1rlefanz.cf_alarmfortimeoffice.hue.connection

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.api.HueApiClient
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.HueBridgeConfig
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.NetworkStateMonitor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.stub
import org.mockito.kotlin.whenever
import java.io.IOException

/**
 * Drei Zusicherungen, die alle daran haengen, dass die Hue-Anbindung sich SELBST erholt:
 *
 * **(D) Nach "Bridge vergessen" muss ein Neu-Koppeln die Planung wieder AUFBAUEN.**
 * `forgetConnection()` ruft `HueSmartScheduler.cleanup()` - das cancelt die Kinder des
 * Scheduler-Scope (und damit den Alarm-Beobachter) und nullt `alarmObserverJob`. Beide Nachplaner
 * entstehen aber ausschliesslich in `initializeSmartScheduling()`, und das lief pro Prozess genau
 * einmal beim App-Start. Ein blosses `recalculateSchedule()` beim Neu-Koppeln plante daher nur
 * EINMALIG fuer den gerade bekannten Alarmbestand: fuer jede spaeter angelegte Schicht entstand
 * kein SunriseStartWorker und kein Pre-Alarm-Health-Check mehr - bis der Prozess starb.
 *
 * **(G) Nach einem DHCP-Wechsel muss die bekannte Bridge unter der NEUEN Adresse gefunden werden.**
 * Die einzige automatische Wiederverbindung validierte nur die gespeicherte IP. Nach einem
 * Routerneustart war der Hue-Pfad dauerhaft tot - reparierbar nur von Hand samt Link-Button,
 * obwohl der Whitelist-Key auf der Bridge gueltig BLEIBT.
 *
 * **(D2/G2) Ein gescheiterter Versuch darf nicht als "verbunden" enden.**
 * `restoreConnectionFromStorage()` setzt CONNECTED optimistisch, bevor irgendetwas validiert
 * wurde. Blieb das nach einem Fehlschlag stehen, kehrte jeder weitere Anlauf in der ersten Zeile
 * von `attemptRecoveryIfDisconnected()` um - die autonome Wiederverbindung (D und G eingeschlossen)
 * war ein Einmal-pro-Prozess-Ereignis, und die Oberflaeche behauptete obendrein "verbunden".
 */
class HueBridgeRediscoveryTest {

    private val oldIp = "192.168.178.24"
    private val newIp = "192.168.178.51"
    private val username = "test-user"
    private val bridgeId = "001788FFFE123456"

    private class FakePreferencesDataStore(initial: Preferences) : DataStore<Preferences> {
        private val flow = MutableStateFlow(initial)
        override val data: Flow<Preferences> = flow
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            val updated = transform(flow.value)
            flow.value = updated
            return updated
        }
    }

    /** Zaehlt, was der Manager beim Scheduler ausloest - der echte braucht WorkManager. */
    private class FakeScheduler : SmartSchedulerHandle {
        var initializeCalls = 0
        var cleanupCalls = 0
        var retryCalls = 0
        override fun initializeSmartScheduling() { initializeCalls++ }
        override fun retrySkippedSchedulingIfNeeded() { retryCalls++ }
        override fun cleanup() { cleanupCalls++ }
    }

    private fun bridgeConfig(id: String) = HueBridgeConfig(
        name = "Hue Bridge",
        datastoreversion = "175",
        swversion = "1970074010",
        apiversion = "1.70.0",
        mac = "00:17:88:12:34:56",
        bridgeid = id,
        factorynew = false,
        replacesbridgeid = null,
        modelid = "BSB002",
    )

    private fun storedPrefs(withBridgeId: String? = null) = mutablePreferencesOf().apply {
        this[stringPreferencesKey("bridge_ip")] = oldIp
        this[stringPreferencesKey("username")] = username
        this[booleanPreferencesKey("connection_validated")] = true
        withBridgeId?.let { this[stringPreferencesKey("bridge_id")] = it }
    }

    private class Fixture(
        val manager: HueBridgeConnectionManager,
        val apiClient: HueApiClient,
        val dataStore: DataStore<Preferences>,
        val scheduler: FakeScheduler,
        val discoveryCalls: () -> Int,
    )

    private fun buildFixture(
        prefs: Preferences = storedPrefs(),
        discovered: List<String> = emptyList(),
    ): Fixture {
        val context = mock<Context>()
        whenever(context.applicationContext).thenReturn(context)

        val networkMonitor = mock<NetworkStateMonitor>()
        whenever(networkMonitor.isReachableSubnet(any())).thenReturn(true)

        val apiClient = mock<HueApiClient>()
        val dataStore = FakePreferencesDataStore(prefs)
        val scheduler = FakeScheduler()
        var discoveryCalls = 0

        val manager = HueBridgeConnectionManager.createForTesting(
            context,
            networkMonitor,
            apiClient,
            testHueDataStore = dataStore,
            testSmartScheduler = scheduler,
            testBridgeDiscovery = {
                discoveryCalls++
                discovered
            },
        )
        return Fixture(manager, apiClient, dataStore, scheduler) { discoveryCalls }
    }

    /** Nur die genannte IP antwortet; jede andere laeuft ins Leere (toter DHCP-Lease). */
    private fun onlyReachableAt(apiClient: HueApiClient, ip: String, id: String = bridgeId) {
        apiClient.stub {
            on { getBridgeConfig(any(), any()) } doAnswer { invocation ->
                if (invocation.getArgument<String>(0) == ip) {
                    bridgeConfig(id)
                } else {
                    throw IOException("no route to host")
                }
            }
        }
    }

    /**
     * Wie [onlyReachableAt], aber die erreichbare Adresse ist WAEHREND des Tests umschaltbar und
     * jeder Versuch wird gezaehlt. Beides braucht der Nachweis, dass ein zweiter Anlauf wirklich
     * wieder an der Bridge klopft, statt in der CONNECTED-Zeile umzukehren.
     */
    private class Reachability(var reachableIp: String?) {
        val probes = mutableMapOf<String, Int>()
        fun probesFor(ip: String): Int = probes[ip] ?: 0
    }

    private fun mutableReachability(
        apiClient: HueApiClient,
        reachableIp: String?,
        id: String = bridgeId,
    ): Reachability {
        val reachability = Reachability(reachableIp)
        apiClient.stub {
            on { getBridgeConfig(any(), any()) } doAnswer { invocation ->
                val ip = invocation.getArgument<String>(0)
                reachability.probes[ip] = reachability.probesFor(ip) + 1
                if (ip == reachability.reachableIp) {
                    bridgeConfig(id)
                } else {
                    throw IOException("no route to host")
                }
            }
        }
        return reachability
    }

    /**
     * Setzt die private Entprellungs-Uhr zurueck - simuliert "es ist mehr als
     * REDISCOVERY_MIN_INTERVAL vergangen", ohne eine Test-Naht in den Produktionscode zu ziehen.
     */
    private fun expireRediscoveryDebounce(manager: HueBridgeConnectionManager) {
        val field = HueBridgeConnectionManager::class.java.getDeclaredField("lastRediscoveryAttempt")
        field.isAccessible = true
        field.setLong(manager, 0L)
    }

    private fun readPrefs(dataStore: DataStore<Preferences>): Preferences =
        runBlocking { dataStore.data.first() }

    // ---------------------------------------------------------------- BEFUND D

    /**
     * REGRESSIONSFALL D: Vor dem Fix rief `setConnection()` nur `recalculateSchedule()` - der
     * Alarm-Beobachter und die Tagesplanung, die `forgetConnection()` per `cleanup()` abgeraeumt
     * hatte, kamen nie zurueck. Der Test faellt um, sobald man das zurueckdreht.
     */
    @Test
    fun `nach Bridge vergessen baut ein Neu-Koppeln die Planung wieder auf`() {
        runBlocking {
            val f = buildFixture()
            onlyReachableAt(f.apiClient, newIp)

            f.manager.forgetConnection()
            assertEquals("cleanup() muss beim Vergessen laufen", 1, f.scheduler.cleanupCalls)

            val result = f.manager.setConnection(newIp, username)

            assertTrue(result.isSuccess)
            assertEquals(
                "Neu-Koppeln muss die Nachplaner wieder aufbauen, nicht nur einmalig neu rechnen",
                1,
                f.scheduler.initializeCalls,
            )
        }
    }

    /** Bei dieser Gelegenheit wird die Bridge-Kennung nachgetragen - ohne zweiten Request. */
    @Test
    fun `setConnection persistiert die Bridge-Kennung aus derselben Antwort`() {
        runBlocking {
            val f = buildFixture()
            onlyReachableAt(f.apiClient, newIp)

            f.manager.setConnection(newIp, username)

            assertEquals(bridgeId, readPrefs(f.dataStore)[stringPreferencesKey("bridge_id")])
        }
    }

    // ---------------------------------------------------------------- BEFUND G

    /**
     * REGRESSIONSFALL G: Routerneustart, neue IP. Vor dem Fix endete
     * `attemptRecoveryIfDisconnected()` nach dem gescheiterten Versuch gegen die alte Adresse -
     * dauerhaft, bis der Nutzer von Hand neu koppelte.
     */
    @Test
    fun `nach einem DHCP-Wechsel wird die bekannte Bridge unter der neuen Adresse gefunden`() {
        runBlocking {
            val f = buildFixture(discovered = listOf(newIp))
            onlyReachableAt(f.apiClient, newIp)

            f.manager.attemptRecoveryIfDisconnected()

            val prefs = readPrefs(f.dataStore)
            assertEquals(newIp, prefs[stringPreferencesKey("bridge_ip")])
            assertEquals("Der Whitelist-Key bleibt gueltig und unveraendert", username, prefs[stringPreferencesKey("username")])
            assertEquals(bridgeId, prefs[stringPreferencesKey("bridge_id")])

            val state = f.manager.connectionStatus.value
            assertTrue(state is HueBridgeConnectionManager.ConnectionState.CONNECTED)
            assertEquals(newIp, (state as HueBridgeConnectionManager.ConnectionState.CONNECTED).bridgeIp)
        }
    }

    /**
     * Ein Fehlschlag darf NIE die gespeicherte Konfiguration abraeumen.
     *
     * EHRLICHKEIT ZUM TESTWERT: das ist ein SCHUTZZAUN, kein Regressionstest - schon vor der
     * Neusuche schrieb dieser Pfad nichts, der Test war auch am ungefixten Code gruen. Er haelt
     * fest, dass eine kuenftige "dann raeume ich das Kaputte halt auf"-Idee hier nicht
     * hineindarf. Was am Fehlschlag WIRKLICH neu ist, misst
     * `ein gescheiterter Versuch hinterlaesst keinen verbundenen Zustand`.
     */
    @Test
    fun `Neusuche ohne Treffer laesst die gespeicherte Konfiguration unangetastet`() {
        runBlocking {
            val f = buildFixture(discovered = listOf("192.168.178.99"))
            onlyReachableAt(f.apiClient, "10.0.0.1") // niemand aus der Liste antwortet

            f.manager.attemptRecoveryIfDisconnected()

            val prefs = readPrefs(f.dataStore)
            assertEquals(oldIp, prefs[stringPreferencesKey("bridge_ip")])
            assertEquals(username, prefs[stringPreferencesKey("username")])
        }
    }

    /**
     * Eine FREMDE Bridge, die zufaellig antwortet, darf die eigene nicht ersetzen - sobald eine
     * Kennung gespeichert ist, entscheidet sie.
     */
    @Test
    fun `ein Kandidat mit fremder Bridge-Kennung wird nicht uebernommen`() {
        runBlocking {
            val f = buildFixture(prefs = storedPrefs(withBridgeId = bridgeId), discovered = listOf(newIp))
            onlyReachableAt(f.apiClient, newIp, id = "AABBCCDDEEFF0000")

            f.manager.attemptRecoveryIfDisconnected()

            assertEquals(oldIp, readPrefs(f.dataStore)[stringPreferencesKey("bridge_ip")])
        }
    }

    /** Antwortet die gespeicherte Adresse, gibt es nichts zu suchen - keine unnoetige Discovery. */
    @Test
    fun `eine erfolgreiche Wiederverbindung loest keine Neusuche aus`() {
        runBlocking {
            val f = buildFixture(discovered = listOf(newIp))
            onlyReachableAt(f.apiClient, oldIp)

            f.manager.attemptRecoveryIfDisconnected()

            assertEquals(0, f.discoveryCalls())
            assertEquals(oldIp, readPrefs(f.dataStore)[stringPreferencesKey("bridge_ip")])
        }
    }

    // ------------------------------------------- BEFUND D2/G2: ehrlicher Zustand nach Fehlschlag

    /**
     * REGRESSIONSFALL D2/G2: `restoreConnectionFromStorage()` setzt CONNECTED, BEVOR irgendetwas
     * validiert wurde. Blieb dieser optimistische Zustand nach einem gescheiterten Versuch
     * stehen, kehrte jeder weitere Anlauf in der ersten Zeile von
     * `attemptRecoveryIfDisconnected()` sofort um - die autonome Wiederverbindung war ein
     * Einmal-pro-Prozess-Ereignis, und die Oberflaeche behauptete "verbunden".
     */
    @Test
    fun `ein gescheiterter Versuch hinterlaesst keinen verbundenen Zustand`() {
        runBlocking {
            val f = buildFixture(discovered = listOf("192.168.178.99"))
            onlyReachableAt(f.apiClient, "10.0.0.1") // niemand antwortet

            f.manager.attemptRecoveryIfDisconnected()

            val state = f.manager.connectionStatus.value
            assertTrue(
                "Nach einem Fehlversuch darf der Zustand nicht CONNECTED sein, sonst blockiert er jeden weiteren Anlauf",
                state !is HueBridgeConnectionManager.ConnectionState.CONNECTED,
            )
            assertTrue(
                "ERROR statt DISCONNECTED: die Bridge ist weiterhin EINGERICHTET, nur nicht erreichbar",
                state is HueBridgeConnectionManager.ConnectionState.ERROR,
            )
            // Die Statuskarte unterscheidet "Bridge gespeichert, aber nicht verbunden" von
            // "keine Bridge" ueber den PERSISTIERTEN Wert - der muss unangetastet bleiben.
            assertTrue("Die Bridge bleibt eingerichtet", f.manager.hasStoredBridge())
        }
    }

    /**
     * Der Kern von D2/G2: ein zweites Netzwerkereignis muss wieder einen ECHTEN Versuch
     * ausloesen. Vor dem Fix kehrte der zweite Aufruf an der CONNECTED-Zeile um - kam die Bridge
     * zurueck, merkte die App das bis zum Prozessende nicht.
     */
    @Test
    fun `ein zweiter Anlauf nach einem Fehlversuch findet die zurueckgekehrte Bridge`() {
        runBlocking {
            val f = buildFixture()
            val reachability = mutableReachability(f.apiClient, reachableIp = null)

            f.manager.attemptRecoveryIfDisconnected()
            assertEquals("Erster Anlauf muss die gespeicherte Adresse geprueft haben", 1, reachability.probesFor(oldIp))

            // Bridge ist wieder da (z.B. Router fertig hochgefahren).
            reachability.reachableIp = oldIp
            f.manager.attemptRecoveryIfDisconnected()

            assertEquals(
                "Der zweite Anlauf muss wirklich erneut an der Bridge klopfen",
                2,
                reachability.probesFor(oldIp),
            )
            val state = f.manager.connectionStatus.value
            assertTrue(state is HueBridgeConnectionManager.ConnectionState.CONNECTED)
            assertEquals(oldIp, (state as HueBridgeConnectionManager.ConnectionState.CONNECTED).bridgeIp)
            assertNotNull(
                "Eine echte Wiederverbindung schreibt den Erfolgszeitstempel fort",
                readPrefs(f.dataStore)[longPreferencesKey("last_success_timestamp")],
            )
        }
    }

    /**
     * Der Netzwerk-Beobachter feuert bei jedem WLAN-Wechsel. Ohne Entprellung liefe die (teure)
     * Discovery im Minutentakt.
     *
     * Beide Haelften werden gemessen, weil sie sich gegenseitig bedingen: die Entprellung darf
     * die Discovery daempfen (genau EIN Discovery-Lauf), aber den billigen Versuch gegen die
     * gespeicherte Adresse NICHT verhindern (zwei Proben) - sonst waere sie eine Sackgasse.
     * Mutationsprobe: mit `REDISCOVERY_MIN_INTERVAL = 0` oder ohne den Entprellungsblock werden
     * es zwei Discovery-Laeufe und der erste `assertEquals` faellt.
     */
    @Test
    fun `eine zweite Neusuche kurz danach wird entprellt - der Versuch selbst laeuft trotzdem`() {
        runBlocking {
            val f = buildFixture(discovered = listOf("192.168.178.99"))
            val reachability = mutableReachability(f.apiClient, reachableIp = null)

            f.manager.attemptRecoveryIfDisconnected()
            f.manager.attemptRecoveryIfDisconnected()

            assertEquals("Zweiter Anlauf darf keine zweite Discovery ausloesen", 1, f.discoveryCalls())
            assertEquals(
                "Die Entprellung daempft nur die Discovery - der Versuch gegen die gespeicherte Adresse laeuft weiter",
                2,
                reachability.probesFor(oldIp),
            )
        }
    }

    /** Und nach Ablauf des Fensters ist die Neusuche wieder erlaubt - Daempfung, keine Sperre. */
    @Test
    fun `nach Ablauf des Entprellungsfensters ist eine neue Suche wieder moeglich`() {
        runBlocking {
            val f = buildFixture(discovered = listOf(newIp))
            val reachability = mutableReachability(f.apiClient, reachableIp = null)

            f.manager.attemptRecoveryIfDisconnected()
            assertEquals(1, f.discoveryCalls())

            expireRediscoveryDebounce(f.manager)
            reachability.reachableIp = newIp // Bridge lebt jetzt unter der neuen Adresse
            f.manager.attemptRecoveryIfDisconnected()

            assertEquals("Nach Ablauf des Fensters muss erneut gesucht werden", 2, f.discoveryCalls())
            assertEquals(newIp, readPrefs(f.dataStore)[stringPreferencesKey("bridge_ip")])
        }
    }

    /** Ohne gespeicherten Username waere eine neue Adresse wertlos - dann gar nicht erst suchen. */
    @Test
    fun `ohne gespeicherten Username findet keine Neusuche statt`() {
        runBlocking {
            val prefs = mutablePreferencesOf().apply {
                this[stringPreferencesKey("bridge_ip")] = oldIp
            }
            val f = buildFixture(prefs = prefs, discovered = listOf(newIp))
            onlyReachableAt(f.apiClient, newIp)

            f.manager.attemptRecoveryIfDisconnected()

            assertEquals(0, f.discoveryCalls())
            assertNull(readPrefs(f.dataStore)[stringPreferencesKey("username")])
        }
    }
}
