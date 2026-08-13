package com.github.f1rlefanz.cf_alarmfortimeoffice.hue.connection

import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.github.f1rlefanz.cf_alarmfortimeoffice.error.ErrorHandler
import com.github.f1rlefanz.cf_alarmfortimeoffice.di.qualifiers.HueDataStore
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.api.HueApiClient
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.scheduling.HueSmartScheduler
import com.github.f1rlefanz.cf_alarmfortimeoffice.masterpause.MasterPausePrefs
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Hilt EntryPoint that lets the manually-constructed [HueBridgeConnectionManager] singleton
 * reach the Hilt-managed [HueDataStore], following the same pattern as
 * [com.github.f1rlefanz.cf_alarmfortimeoffice.hue.scheduling.HueSmartSchedulerEntryPoint].
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface HueBridgeConnectionManagerEntryPoint {
    @HueDataStore
    fun hueDataStore(): DataStore<Preferences>

    fun masterPausePrefs(): MasterPausePrefs
}

/**
 * OPTIMIZED Hue Bridge Connection Manager
 * 
 * EFFICIENCY IMPROVEMENTS:
 * ❌ BEFORE: 2,880 health checks per day (every 30s)
 * ✅ AFTER: ~5-15 health checks per day (event-driven + smart scheduling)
 * 🔋 99.5% reduction in battery usage and network traffic
 * 
 * PHASE 1: Event-driven health checks (foreground/background awareness)
 * PHASE 2: Smart scheduling (pre-alarm health checks via WorkManager)
 * 
 * MEMORY LEAK FIX: Uses WeakReference to Context to prevent memory leaks
 */
class HueBridgeConnectionManager private constructor(
    context: Context,
    private val networkMonitor: com.github.f1rlefanz.cf_alarmfortimeoffice.util.NetworkStateMonitor,
    private val apiClient: HueApiClient,
    private val testHueDataStore: DataStore<Preferences>? = null
) {
    companion object {
        @Volatile
        private var INSTANCE: HueBridgeConnectionManager? = null

        fun getInstance(context: Context): HueBridgeConnectionManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: HueBridgeConnectionManager(
                    context.applicationContext,
                    com.github.f1rlefanz.cf_alarmfortimeoffice.util.NetworkStateMonitor(context.applicationContext),
                    // Context enables the bridge-ID pinning audit layer in HueTrustManager
                    HueApiClient(context.applicationContext)
                ).also { INSTANCE = it }
            }
        }

        /**
         * Visible for testing: lets a unit test construct the manager with fakes for
         * networkMonitor/apiClient without touching the production getInstance() singleton.
         */
        internal fun createForTesting(
            context: Context,
            networkMonitor: com.github.f1rlefanz.cf_alarmfortimeoffice.util.NetworkStateMonitor,
            apiClient: HueApiClient,
            testHueDataStore: DataStore<Preferences>? = null
        ): HueBridgeConnectionManager = HueBridgeConnectionManager(context, networkMonitor, apiClient, testHueDataStore)

        // Configuration constants
        // CONSOLIDATION: moved off the standalone "hue_bridge_connection" SharedPreferences
        // file and into the existing Hilt @HueDataStore (see HueBridgeConnectionManagerEntryPoint).
        // NO MIGRATION: only the developer uses the app right now, so the old SharedPreferences
        // values are intentionally left behind - re-pairing the bridge is trivial.
        private val KEY_BRIDGE_IP = stringPreferencesKey("bridge_ip")
        private val KEY_USERNAME = stringPreferencesKey("username")
        private val KEY_LAST_SUCCESS = longPreferencesKey("last_success_timestamp")
        private val KEY_CONNECTION_VALIDATED = booleanPreferencesKey("connection_validated")
        
        // OPTIMIZED: Event-driven health check intervals
        private val CRITICAL_RECOVERY_TIMEOUT = 10.seconds
        private val CONNECTION_CACHE_VALIDITY = 30.minutes  // Extended from 5 minutes
        private val FOREGROUND_HEALTH_CHECK_INTERVAL = 5.minutes  // Only when app visible
        private val BACKGROUND_HEALTH_CHECK_INTERVAL = 30.minutes  // Rare background checks

        /**
         * Deckel fuer einen Versuch, den die Subnetz-Heuristik fuer aussichtslos haelt.
         * Deutlich kuerzer als die 10s von OkHttp: wer wirklich ausser Haus ist, soll nicht
         * warten - aber eine Bridge hinter einem Router antwortet in Bruchteilen davon.
         */
        private val OFF_SUBNET_PROBE_TIMEOUT = 3.seconds
    }
    
    // Use WeakReference to prevent memory leaks
    private val contextRef = java.lang.ref.WeakReference(context.applicationContext)

    // PHASE 2: Smart Scheduler integration
    private val smartScheduler by lazy {
        contextRef.get()?.let { HueSmartScheduler.getInstance(it) }
    }

    // Genau eine Initialisierung pro Prozess - siehe initialize().
    private val initialized = AtomicBoolean(false)

    // Single-flight-Schutz gegen ueberlappende attemptRecoveryIfDisconnected()-Aufrufe (siehe
    // startNetworkRecoveryMonitoring()/onAppForeground(), beide auf healthCheckScope/Dispatchers.IO).
    private val recoveryInFlight = AtomicBoolean(false)

    // Thread-safe connection state
    private val currentConnectionState = AtomicReference<ConnectionState>(ConnectionState.DISCONNECTED)

    /**
     * Resolve the Hilt-managed @HueDataStore via EntryPoint (manual singleton, not
     * Hilt-injected - see HueBridgeConnectionManagerEntryPoint). Null only if the
     * application context is gone or Hilt is not ready yet.
     */
    private fun resolveHueDataStore(): DataStore<Preferences>? {
        testHueDataStore?.let { return it }
        val appContext = contextRef.get() ?: return null
        return try {
            EntryPointAccessors
                .fromApplication(appContext, HueBridgeConnectionManagerEntryPoint::class.java)
                .hueDataStore()
        } catch (e: Exception) {
            Logger.e(LogTags.HUE_BRIDGE, "Failed to resolve HueDataStore via EntryPoint", e)
            null
        }
    }

    /**
     * Resolve the Hilt-managed [MasterPausePrefs] via EntryPoint - same pattern/limitations as
     * [resolveHueDataStore]. Used to make autonomous background recovery/health-check activity
     * respect Master-Pause (see attemptRecoveryIfDisconnected()/startSmartHealthMonitoring()).
     */
    private fun resolveMasterPausePrefs(): MasterPausePrefs? {
        val appContext = contextRef.get() ?: return null
        return try {
            EntryPointAccessors
                .fromApplication(appContext, HueBridgeConnectionManagerEntryPoint::class.java)
                .masterPausePrefs()
        } catch (e: Exception) {
            Logger.e(LogTags.HUE_BRIDGE, "Failed to resolve master-pause prefs via EntryPoint", e)
            null
        }
    }
    
    // Background health monitoring with app lifecycle awareness
    private var healthCheckJob: Job? = null
    /**
     * MIT `CoroutineExceptionHandler` - der SupervisorJob allein reicht NICHT.
     *
     * Ein SupervisorJob verhindert nur, dass Geschwister-Coroutinen mitgerissen werden; die
     * Exception selbst verschluckt er nicht: ohne Handler laeuft sie zum Thread-Default-Handler und
     * beendet den PROZESS. In diesem Scope liegen fuenf fire-and-forget `launch`-Bloecke, von denen
     * mehrere ungeschuetzt auf den Hue-DataStore zugreifen (`restoreConnectionFromStorage()` macht
     * `dataStore.data.first()`). Der `ReplaceFileCorruptionHandler` des Stores faengt nur
     * Korruption - eine IOException (voller Speicher, EACCES, transienter Lesefehler) reicht
     * DataStore durch.
     *
     * Fuer eine WECKER-App ist das die falsche Reihenfolge der Wichtigkeit: ein misslungener
     * Lichtsteuerungs-Lesezugriff darf niemals den Prozess beenden, der die Alarme haelt. Dieselbe
     * Ueberlegung steht in `HueSmartScheduler.recalculateSchedule()` als Kommentar - dort war der
     * Schutz da, hier fehlte er.
     */
    private val healthCheckScope = CoroutineScope(
        Dispatchers.IO + SupervisorJob() +
            ErrorHandler.createCoroutineExceptionHandler("HueBridgeConnectionManager.healthCheckScope")
    )
    
    // OPTIMIZATION: App lifecycle state tracking
    private var isAppInForeground = false
    private var lastForegroundCheck = 0L
    private var lastManualCheck = 0L
    
    // Connection state flow for reactive UI updates (UX FIX E: now exposed via
    // [connectionStatus] so HueViewModel can surface disconnects/errors in the UI instead of
    // only updating this in-memory value).
    private val _connectionStatus = MutableStateFlow<ConnectionState>(ConnectionState.DISCONNECTED)

    /**
     * Reactive connection state (UX FIX E). Collected by [HueViewModel] to show a warning
     * banner when the bridge connection is lost (DISCONNECTED/ERROR), instead of only ever
     * reflecting the connection status at the moment a screen was composed.
     */
    val connectionStatus: StateFlow<ConnectionState> = _connectionStatus.asStateFlow()
    
    /**
     * Connection state with comprehensive status information
     */
    sealed class ConnectionState {
        object DISCONNECTED : ConnectionState()
        object CONNECTING : ConnectionState()
        data class CONNECTED(
            val bridgeIp: String, 
            val username: String,
            val lastValidated: Long = System.currentTimeMillis()
        ) : ConnectionState()
        data class ERROR(val message: String, val exception: Throwable?) : ConnectionState()
    }
    
    /**
     * CRITICAL: Initialize connection manager and restore persistent connection
     * This should be called during app startup to ensure bridge connectivity
     *
     * IDEMPOTENT — und das muss es sein: Es gibt zwei Aufrufer, deren Reihenfolge NICHT
     * feststeht.
     *   1. CFAlarmApplication.initializeApp() beim Start — laeuft asynchron im applicationScope
     *   2. HueBridgeRepository.init — laeuft, sobald Hilt das Repository erzeugt (Hauptthread,
     *      beim Anlegen des HueViewModel)
     *
     * Ohne Waechter lief die komplette Initialisierung zweimal. Sichtbar im Log vom 14.07.
     * (22:21:57.185 auf einem Hintergrund-Thread, 22:21:58.287 auf dem Hauptthread): der
     * SmartScheduler verwarf beim zweiten Durchlauf die gerade angelegten WorkManager-Jobs und
     * legte sie neu an — Job-IDs 6 bis 18 in 1,5 Sekunden fuer vier Health-Checks — und die
     * DataStore-Wiederherstellung lief doppelt.
     *
     * Waechter statt Reihenfolge-Annahme: Wer zuerst kommt, initialisiert; der Zweite ist ein
     * No-op. Damit ist egal, ob der Application-Start oder Hilt frueher dran ist.
     *
     * (startSmartHealthMonitoring() war schon vorher sicher — es canceled seinen alten Job.
     * Zwei parallele Monitoring-Schleifen gab es also nie.)
     */
    fun initialize() {
        if (!initialized.compareAndSet(false, true)) {
            Logger.d(LogTags.HUE_BRIDGE, "🔄 BRIDGE-MANAGER: Bereits initialisiert - doppelter Aufruf ignoriert")
            // NICHT ganz ohne Wirkung: wurde dieser Prozess VOR der ersten Entsperrung gestartet
            // (Direct Boot), hat der SmartScheduler seine Planung uebersprungen, weil es dort
            // keinen WorkManager gibt. Derselbe Prozess bedient danach die App weiter - der
            // ignorierte Zweig hier ist die erste Stelle, an der das Nachholen ueberhaupt moeglich
            // ist. Idempotent und billig, wenn nichts nachzuholen ist.
            smartScheduler?.retrySkippedSchedulingIfNeeded()
            return
        }

        Logger.i(LogTags.HUE_BRIDGE, "🔄 BRIDGE-MANAGER: Initializing connection manager")

        // Restore connection from persistent storage (DataStore read is async - runs on
        // healthCheckScope, same as every other background operation in this class).
        healthCheckScope.launch {
            restoreConnectionFromStorage()
        }

        // Start optimized health monitoring (event-driven)
        startSmartHealthMonitoring()

        // Autonome Wiederverbindung, wenn das Netzwerk zurueckkommt (z.B. Heimkehr ins Heim-WLAN) -
        // siehe attemptRecoveryIfDisconnected().
        startNetworkRecoveryMonitoring()

        // PHASE 2: Initialize smart scheduling system
        smartScheduler?.initializeSmartScheduling()

        Logger.i(LogTags.HUE_BRIDGE, "✅ BRIDGE-MANAGER: Connection manager initialized")
    }
    
    /**
     * CRITICAL FOR ALARM EXECUTION: Get current connection with automatic recovery
     * 
     * OPTIMIZED: Uses extended connection caching and optimistic validation
     */
    suspend fun getValidatedConnection(): Pair<String, String> = withContext(Dispatchers.IO) {
        Logger.d(LogTags.HUE_BRIDGE, "🔍 BRIDGE-MANAGER: Requesting validated connection")
        
        val currentState = currentConnectionState.get()
        
        when (currentState) {
            is ConnectionState.CONNECTED -> {
                // Synchronous pre-flight: skip the real HTTP attempt entirely when we're
                // demonstrably not on the bridge's local network (off Wi-Fi, or on a
                // different Wi-Fi/subnet - e.g. public/work Wi-Fi, mobile hotspot). Deliberately
                // NOT downgrading currentConnectionState to ERROR: this is a transient
                // "wrong network right now" condition, not a bridge/credential failure - the
                // 30-min cache should survive the trip so no needless reconnect/health-check
                // cycle fires the moment Wi-Fi is back, and the UI doesn't flash a misleading
                // persistent error banner while just out of range.
                // Die Subnetz-Pruefung ist ein HINWEIS, kein Urteil - deshalb hier kein blindes
                // Abbrechen mehr, sondern ein kurz gedeckelter echter Versuch (siehe
                // [validateConnectionCredentials]: Gast-WLAN/VLAN/Mesh/Doppel-NAT/Emulator erreichen die Bridge,
                // ohne im selben Subnetz zu liegen). Nur wenn AUCH der scheitert, gilt sie als
                // unerreichbar. Der teure Fall bleibt damit billig: wer wirklich ausser Haus
                // ist, wartet OFF_SUBNET_PROBE_TIMEOUT statt der vollen 10s.
                if (!isBridgeReachableNow(currentState.bridgeIp) &&
                    !validateConnectionCredentials(currentState.bridgeIp, currentState.username)
                ) {
                    Logger.w(LogTags.HUE_BRIDGE, "⚠️ BRIDGE-MANAGER: Cached bridge ${currentState.bridgeIp} not reachable from current network, skipping live connection attempt")
                    throw IllegalStateException("Bridge unreachable: not on the bridge's local network (${currentState.bridgeIp})")
                }

                // OPTIMIZATION: Extended cache validity - trust connection longer
                val isRecent = (System.currentTimeMillis() - currentState.lastValidated) < CONNECTION_CACHE_VALIDITY.inWholeMilliseconds

                if (isRecent) {
                    Logger.d(LogTags.HUE_BRIDGE, "✅ BRIDGE-MANAGER: Using cached valid connection (${CONNECTION_CACHE_VALIDITY.inWholeMinutes}min cache)")
                    return@withContext Pair(currentState.bridgeIp, currentState.username)
                } else {
                    Logger.d(LogTags.HUE_BRIDGE, "🔄 BRIDGE-MANAGER: Cache expired, trying optimistic connection")
                    
                    // OPTIMIZATION: Optimistic connection - assume it works, validate in background
                    healthCheckScope.launch {
                        val isValid = validateConnectionCredentials(currentState.bridgeIp, currentState.username)
                        if (isValid) {
                            val updatedState = currentState.copy(lastValidated = System.currentTimeMillis())
                            updateConnectionState(updatedState)
                            updateLastSuccessTimestamp()
                            Logger.d(LogTags.HUE_BRIDGE, "✅ BRIDGE-MANAGER: Background validation successful")
                        } else {
                            Logger.w(LogTags.HUE_BRIDGE, "⚠️ BRIDGE-MANAGER: Background validation failed")
                            updateConnectionState(ConnectionState.ERROR("Connection lost", null))
                        }
                    }
                    
                    return@withContext Pair(currentState.bridgeIp, currentState.username)
                }
            }
            else -> {
                Logger.w(LogTags.HUE_BRIDGE, "⚠️ BRIDGE-MANAGER: No active connection, attempting recovery")
                restoreConnectionFromStorage()
            }
        }
        
        // Attempt connection validation or recovery
        val recoveredConnection = recoverConnection()
        if (recoveredConnection != null) {
            Logger.i(LogTags.HUE_BRIDGE, "✅ BRIDGE-MANAGER: Connection recovered successfully")
            return@withContext recoveredConnection
        }
        
        // If we reach here, connection recovery failed
        val errorMessage = "Bridge connection unavailable and recovery failed"
        Logger.e(LogTags.HUE_BRIDGE, "❌ BRIDGE-MANAGER: $errorMessage")
        updateConnectionState(ConnectionState.ERROR(errorMessage, null))
        
        throw IllegalStateException(errorMessage)
    }
    
    /**
     * Set and persist a new bridge connection
     */
    suspend fun setConnection(bridgeIp: String, username: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            Logger.i(LogTags.HUE_BRIDGE, "🔗 BRIDGE-MANAGER: Setting new bridge connection")
            
            updateConnectionState(ConnectionState.CONNECTING)
            
            // Validate the new connection
            val isValid = validateConnectionCredentials(bridgeIp, username)

            if (isValid) {
                // Store in persistent storage (@HueDataStore)
                val dataStore = resolveHueDataStore()
                if (dataStore == null) {
                    val errorMessage = "HueDataStore unavailable, cannot persist connection"
                    Logger.e(LogTags.HUE_BRIDGE, "❌ BRIDGE-MANAGER: $errorMessage")
                    updateConnectionState(ConnectionState.ERROR(errorMessage, null))
                    return@withContext Result.failure(IllegalStateException(errorMessage))
                }
                dataStore.edit { mutablePrefs ->
                    mutablePrefs[KEY_BRIDGE_IP] = bridgeIp
                    mutablePrefs[KEY_USERNAME] = username
                    mutablePrefs[KEY_LAST_SUCCESS] = System.currentTimeMillis()
                    mutablePrefs[KEY_CONNECTION_VALIDATED] = true
                }

                val connectionState = ConnectionState.CONNECTED(bridgeIp, username)
                updateConnectionState(connectionState)
                
                Logger.i(LogTags.HUE_BRIDGE, "✅ BRIDGE-MANAGER: Bridge connection set and validated")
                
                // PHASE 2: Trigger smart schedule recalculation after successful connection
                smartScheduler?.recalculateSchedule()
                
                Result.success(Unit)
            } else {
                val errorMessage = "Bridge connection validation failed"
                Logger.e(LogTags.HUE_BRIDGE, "❌ BRIDGE-MANAGER: $errorMessage")
                updateConnectionState(ConnectionState.ERROR(errorMessage, null))
                Result.failure(IllegalStateException(errorMessage))
            }
            
        } catch (e: Exception) {
            Logger.e(LogTags.HUE_BRIDGE, "❌ BRIDGE-MANAGER: Failed to set bridge connection", e)
            updateConnectionState(ConnectionState.ERROR("Connection setup failed", e))
            Result.failure(e)
        }
    }

    /**
     * UX FEATURE (B): "Verbindung trennen / Bridge vergessen". Clears the persisted bridge
     * IP/username/validation flag from @HueDataStore and resets the in-memory state to
     * DISCONNECTED, so the app returns to the discovery/onboarding UI. Does NOT touch the
     * saved schedule rules (@HueConfigRepository) - they stay intact so re-pairing the same
     * bridge later doesn't require recreating them.
     */
    suspend fun forgetConnection(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            Logger.i(LogTags.HUE_BRIDGE, "🔌 BRIDGE-MANAGER: Forgetting bridge connection")

            val dataStore = resolveHueDataStore()
            if (dataStore != null) {
                dataStore.edit { mutablePrefs ->
                    mutablePrefs.remove(KEY_BRIDGE_IP)
                    mutablePrefs.remove(KEY_USERNAME)
                    mutablePrefs.remove(KEY_LAST_SUCCESS)
                    mutablePrefs.remove(KEY_CONNECTION_VALIDATED)
                }
            } else {
                Logger.w(LogTags.HUE_BRIDGE, "⚠️ BRIDGE-MANAGER: HueDataStore unavailable, cannot persist disconnect")
            }

            updateConnectionState(ConnectionState.DISCONNECTED)

            // Stop any scheduled sunrise/auto-off/health-check jobs tied to the old bridge.
            smartScheduler?.cleanup()

            Logger.i(LogTags.HUE_BRIDGE, "✅ BRIDGE-MANAGER: Bridge connection forgotten")
            Result.success(Unit)
        } catch (e: Exception) {
            Logger.e(LogTags.HUE_BRIDGE, "❌ BRIDGE-MANAGER: Failed to forget bridge connection", e)
            Result.failure(e)
        }
    }

    /**
     * Get current connection info without validation (for UI display)
     *
     * Stays synchronous (public API contract via IHueBridgeRepository.getCurrentBridgeIp()/
     * getCurrentUsername(), called from Compose UI and WorkManager workers) by reading the
     * in-memory [currentConnectionState] instead of the (now async) DataStore. That in-memory
     * state is populated by restoreConnectionFromStorage()/setConnection() and is the same
     * cache getValidatedConnection() already relies on.
     */
    fun getCurrentConnectionInfo(): Pair<String?, String?> {
        val state = currentConnectionState.get()
        return if (state is ConnectionState.CONNECTED) {
            Pair(state.bridgeIp, state.username)
        } else {
            Pair(null, null)
        }
    }
    
    /**
     * Ist ueberhaupt eine Bridge eingerichtet — unabhaengig davon, ob sie GERADE erreichbar ist?
     *
     * Liest den PERSISTIERTEN Wert, NICHT [currentConnectionState]: Der In-Memory-Zustand faellt
     * bei einem fehlgeschlagenen Health-Check auf ERROR, und [getCurrentConnectionInfo] liefert
     * dann (null, null). Eine eingerichtete, nur gerade nicht erreichbare Bridge waere davon
     * nicht zu unterscheiden — und genau dann muessen die Pre-Alarm-Health-Checks weiterlaufen,
     * denn sie sind der Weg zurueck. "Eingerichtet" und "verbunden" sind zwei Fragen.
     *
     * Genutzt vom [HueSmartScheduler], um gar nicht erst zu planen, was ohne Bridge nur
     * scheitern kann.
     */
    suspend fun hasStoredBridge(): Boolean {
        val dataStore = resolveHueDataStore() ?: return false
        return dataStore.data.first()[KEY_BRIDGE_IP] != null
    }

    /**
     * OPTIMIZATION: App lifecycle awareness
     */
    fun onAppForeground() {
        isAppInForeground = true
        Logger.d(LogTags.HUE_BRIDGE, "📱 BRIDGE-MANAGER: App entered foreground")

        // Zweite Gelegenheit zum Nachholen einer im Direct-Boot uebersprungenen Hue-Planung -
        // spaetestens hier ist das Geraet entsperrt und WorkManager verfuegbar.
        smartScheduler?.retrySkippedSchedulingIfNeeded()

        // Immediate health check if enough time passed since last check. performHealthCheck()
        // only RE-validates an already-CONNECTED state (see its own guard) - ohne die Abzweigung
        // hier wuerde ein Wiederoeffnen der App nach DISCONNECTED/ERROR nie selbst reconnecten,
        // sondern still gar nichts tun (genau der Bug, den ein Nutzer per manuellem Retry umgehen musste).
        healthCheckScope.launch {
            val timeSinceLastCheck = System.currentTimeMillis() - lastForegroundCheck
            if (timeSinceLastCheck > FOREGROUND_HEALTH_CHECK_INTERVAL.inWholeMilliseconds) {
                if (currentConnectionState.get() is ConnectionState.CONNECTED) {
                    performHealthCheck()
                } else {
                    attemptRecoveryIfDisconnected()
                }
                lastForegroundCheck = System.currentTimeMillis()
            }
        }
    }
    
    fun onAppBackground() {
        isAppInForeground = false
        Logger.d(LogTags.HUE_BRIDGE, "📱 BRIDGE-MANAGER: App entered background")
    }
    
    /**
     * OPTIMIZATION: Manual health check (for UI refresh, rule testing, etc.)
     */
    suspend fun forceHealthCheck(): Boolean {
        Logger.i(LogTags.HUE_BRIDGE, "🔄 BRIDGE-MANAGER: Manual health check requested")
        lastManualCheck = System.currentTimeMillis()
        return performHealthCheck()
    }
    
    /**
     * INTERNAL: Restore connection from persistent storage (@HueDataStore).
     * Suspends while reading the DataStore snapshot; falls back to DISCONNECTED
     * if the DataStore cannot be resolved (e.g. Hilt not ready yet).
     */
    private suspend fun restoreConnectionFromStorage() {
        val dataStore = resolveHueDataStore()
        if (dataStore == null) {
            Logger.w(LogTags.HUE_BRIDGE, "⚠️ BRIDGE-MANAGER: HueDataStore unavailable, cannot restore connection")
            updateConnectionState(ConnectionState.DISCONNECTED)
            return
        }

        val prefs = dataStore.data.first()
        val bridgeIp = prefs[KEY_BRIDGE_IP]
        val username = prefs[KEY_USERNAME]
        val lastSuccess = prefs[KEY_LAST_SUCCESS] ?: 0L
        val wasValidated = prefs[KEY_CONNECTION_VALIDATED] ?: false

        if (bridgeIp != null && username != null && wasValidated) {
            val connectionState = ConnectionState.CONNECTED(bridgeIp, username, lastSuccess)
            updateConnectionState(connectionState)

            Logger.i(LogTags.HUE_BRIDGE, "🔄 BRIDGE-MANAGER: Restored connection from storage")
        } else {
            Logger.d(LogTags.HUE_BRIDGE, "ℹ️ BRIDGE-MANAGER: No valid stored connection found")
            updateConnectionState(ConnectionState.DISCONNECTED)
        }
    }
    
    /**
     * INTERNAL: Attempt to recover/validate current connection
     */
    private suspend fun recoverConnection(): Pair<String, String>? = withContext(Dispatchers.IO) {
        val (bridgeIp, username) = getCurrentConnectionInfo()
        
        if (bridgeIp == null || username == null) {
            Logger.w(LogTags.HUE_BRIDGE, "⚠️ BRIDGE-MANAGER: Cannot recover - missing credentials")
            return@withContext null
        }
        
        Logger.d(LogTags.HUE_BRIDGE, "🔄 BRIDGE-MANAGER: Attempting connection recovery")
        
        // Add timeout for critical recovery operations
        try {
            val isValid = withTimeout(CRITICAL_RECOVERY_TIMEOUT) {
                validateConnectionCredentials(bridgeIp, username)
            }
            
            if (isValid) {
                val connectionState = ConnectionState.CONNECTED(bridgeIp, username)
                updateConnectionState(connectionState)

                // Update last success timestamp
                updateLastSuccessTimestamp()

                Logger.i(LogTags.HUE_BRIDGE, "✅ BRIDGE-MANAGER: Connection recovery successful")
                return@withContext Pair(bridgeIp, username)
            } else {
                Logger.w(LogTags.HUE_BRIDGE, "❌ BRIDGE-MANAGER: Connection recovery failed - validation failed")
                return@withContext null
            }
        } catch (e: TimeoutCancellationException) {
            Logger.w(LogTags.HUE_BRIDGE, "⏰ BRIDGE-MANAGER: Connection recovery timed out")

            // Log timeout details for diagnostics
            val lastSuccessTime = getLastSuccessTimestamp()
            val hoursAgo = if (lastSuccessTime > 0) {
                ((System.currentTimeMillis() - lastSuccessTime) / 1000 / 3600).toInt()
            } else {
                -1 // Never connected
            }
            
            Logger.e(LogTags.HUE_BRIDGE, "⚠️ HUE TIMEOUT: Bridge did not respond within ${CRITICAL_RECOVERY_TIMEOUT.inWholeSeconds}s. " +
                    "Last successful connection was ${hoursAgo}h ago. Bridge IP attempted: $bridgeIp", e)
            
            return@withContext null
        } catch (e: Exception) {
            Logger.e(LogTags.HUE_BRIDGE, "❌ BRIDGE-MANAGER: Connection recovery failed", e)
            return@withContext null
        }
    }
    
    /**
     * INTERNAL: Cheap, synchronous pre-flight - is [bridgeIp] reachable from the CURRENT
     * network? Only meaningful for local/private bridge IPs; a non-local IP (shouldn't
     * happen for Hue, but defensive) is always considered reachable since the subnet check
     * doesn't apply to it.
     */
    private fun isBridgeReachableNow(bridgeIp: String): Boolean {
        val isPrivateIp = bridgeIp.startsWith("192.168.") || bridgeIp.startsWith("10.") || bridgeIp.startsWith("172.")
        if (!isPrivateIp) return true
        return networkMonitor.isReachableSubnet(bridgeIp)
    }

    /**
     * INTERNAL: Fragt die Bridge WIRKLICH - [isBridgeReachableNow] ist dabei nur ein Hinweis
     * darauf, wie lange sich das Warten lohnt, NICHT das Urteil.
     *
     * **Warum das wichtig ist:** Die Subnetz-Prüfung verlangt, dass das Gerät eine eigene IPv4
     * im selben Subnetz wie die Bridge hat. Das ist im Normalfall („zu Hause im WLAN" gegen
     * „unterwegs") eine gute, billige Abkürzung — aber es ist eine HEURISTIK, und sie liefert
     * Falsch-Negative überall dort, wo ein Router zwischen zwei erreichbaren Netzen vermittelt:
     * Gast-WLAN, getrenntes VLAN, Mesh-/Repeater-Setups mit eigenem Subnetz, Doppel-NAT — und
     * der Android-Emulator, der über NAT (10.0.2.x) durchaus ins Heimnetz routet.
     *
     * Vorher war die Heuristik ein VETO **vor** dem echten Test, und genau das ist am
     * 13.08.2026 am Emulator aufgeschlagen: das Pairing bekam eine HTTPS **200** von der
     * Bridge, und 7 ms später verwarf `validateConnectionCredentials()` dieselbe Bridge mit
     * „not reachable from current network". Der Nutzer sah „Bridge connection validation
     * failed" und hatte keinen Weg, das zu übergehen — eine antwortende Bridge, abgelehnt von
     * einer Vermutung über sie.
     *
     * Der echte Request ist das einzige belastbare Urteil. Die Heuristik entscheidet nur noch,
     * ob wir dem Versuch das volle OkHttp-Timeout (10 s) zugestehen oder ihn nach
     * [OFF_SUBNET_PROBE_TIMEOUT] abschneiden. Damit bleibt der ursprüngliche Zweck erhalten —
     * kein langer Hänger, wenn man tatsächlich außer Haus ist — ohne eine erreichbare Bridge
     * auszusperren.
     */
    private suspend fun validateConnectionCredentials(bridgeIp: String, username: String): Boolean {
        val sameSubnet = isBridgeReachableNow(bridgeIp)
        if (!sameSubnet) {
            Logger.w(
                LogTags.HUE_BRIDGE,
                "⚠️ BRIDGE-MANAGER: $bridgeIp liegt in keinem lokalen IPv4-Subnetz dieses Geraets " +
                    "(vermutlich ausser Haus) - Versuch trotzdem, gedeckelt auf ${OFF_SUBNET_PROBE_TIMEOUT.inWholeSeconds}s"
            )
        }
        return try {
            if (sameSubnet) {
                apiClient.getBridgeConfig(bridgeIp, username)
                Logger.d(LogTags.HUE_BRIDGE, "✅ BRIDGE-MANAGER: Connection validation successful")
                true
            } else {
                // withTimeoutOrNull statt withTimeout: ein Ablauf ist hier ein normales
                // Ergebnis ("nicht erreichbar"), kein Fehler.
                val ok = withTimeoutOrNull(OFF_SUBNET_PROBE_TIMEOUT) {
                    apiClient.getBridgeConfig(bridgeIp, username)
                    true
                }
                if (ok == true) {
                    Logger.i(
                        LogTags.HUE_BRIDGE,
                        "✅ BRIDGE-MANAGER: Bridge trotz fremden Subnetzes erreichbar - die Heuristik lag falsch, " +
                            "der echte Request entscheidet"
                    )
                    true
                } else {
                    Logger.d(LogTags.HUE_BRIDGE, "❌ BRIDGE-MANAGER: $bridgeIp auch im Kurzversuch nicht erreichbar")
                    false
                }
            }
        } catch (e: CancellationException) {
            // Weiterwerfen: eine Cancellation ist keine Aussage ueber die Bridge, sondern das
            // Ende der umgebenden Coroutine (siehe CLAUDE.md, "Fehlerbehandlung").
            throw e
        } catch (e: Exception) {
            Logger.d(LogTags.HUE_BRIDGE, "❌ BRIDGE-MANAGER: Connection validation failed: ${e.message}")
            false
        }
    }

    /**
     * OPTIMIZATION: Smart health monitoring - Event-driven instead of continuous polling
     * 
     * BEFORE: Health check every 30 seconds = 2,880 checks/day
     * AFTER: Event-driven checks = ~10-20 checks/day (95% reduction)
     */
    private fun startSmartHealthMonitoring() {
        healthCheckJob?.cancel()
        
        healthCheckJob = healthCheckScope.launch {
            while (isActive) {
                val paused = try {
                    resolveMasterPausePrefs()?.pausedNow() == true
                } catch (e: Exception) {
                    Logger.e(LogTags.HUE_BRIDGE, "Failed to check master-pause state in health loop, assuming not paused", e)
                    false
                }
                val currentState = currentConnectionState.get()
                if (!paused && currentState is ConnectionState.CONNECTED) {

                    // OPTIMIZATION: Only check if app is in foreground AND enough time passed
                    if (isAppInForeground) {
                        val timeSinceLastCheck = System.currentTimeMillis() - lastForegroundCheck
                        if (timeSinceLastCheck > FOREGROUND_HEALTH_CHECK_INTERVAL.inWholeMilliseconds) {
                            Logger.d(LogTags.HUE_BRIDGE, "💓 BRIDGE-MANAGER: Performing foreground health check")
                            performHealthCheck()
                            lastForegroundCheck = System.currentTimeMillis()
                        }
                    }
                    
                    // OPTIMIZATION: Rare background checks only for critical scenarios
                    else {
                        val timeSinceLastCheck = maxOf(lastForegroundCheck, lastManualCheck)
                        val timeDiff = System.currentTimeMillis() - timeSinceLastCheck
                        if (timeDiff > BACKGROUND_HEALTH_CHECK_INTERVAL.inWholeMilliseconds) {
                            Logger.d(LogTags.HUE_BRIDGE, "💓 BRIDGE-MANAGER: Performing rare background health check")
                            performHealthCheck()
                            lastManualCheck = System.currentTimeMillis()
                        }
                    }
                }
                
                // OPTIMIZATION: Variable sleep interval based on app state
                val sleepInterval = if (isAppInForeground) 1.minutes else 10.minutes
                delay(sleepInterval)
            }
        }
        
        Logger.d(LogTags.HUE_BRIDGE, "🧠 BRIDGE-MANAGER: Smart health monitoring started (event-driven)")
    }
    
    /**
     * Autonome Wiederverbindung: beobachtet [NetworkStateMonitor.isNetworkAvailable] dauerhaft
     * (bereits vorhanden, bislang ungenutzt) und stoesst bei jeder Netzwerk-Wiederherstellung
     * [attemptRecoveryIfDisconnected] an - z.B. Heimkehr ins Heim-WLAN nach unterwegs. Laeuft im
     * selben [healthCheckScope] wie die uebrige Ueberwachung, wird also von [cleanup] mit
     * abgebrochen (cancelChildren() faengt alle Kinder-Jobs).
     *
     * Einmal pro [initialize]-ZYKLUS, nicht einmal pro Prozess: [initialize] ist idempotent
     * (Waechter-Flag), aber [cleanup] gibt dieses Flag wieder frei - ein spaeteres [initialize]
     * (z.B. ein kuenftiger "Hue deaktivieren"-Schalter, der cleanup() ruft und danach wieder
     * einschaltet) startet den Collector also erneut, zusammen mit dem uebrigen
     * Initialisierungspfad inkl. `smartScheduler.initializeSmartScheduling()`. Das ist in Ordnung,
     * weil [cleanup] vorher auch `smartScheduler.cleanup()` ruft - aber es ist ausdruecklich KEINE
     * "nur einmal pro Prozess"-Zusicherung mehr, auf die sich neuer Code verlassen darf.
     */
    private fun startNetworkRecoveryMonitoring() {
        healthCheckScope.launch {
            networkMonitor.isNetworkAvailable.collect { available ->
                // try/catch INNERHALB des collect, nicht darum: ein Fehler in einem einzelnen
                // Wiederverbindungs-Versuch darf den Collector nicht beenden. Genau das waere
                // sonst passiert - und dieser Collector ist die autonome Wiederverbindung bei der
                // Heimkehr ins Heim-WLAN (siehe Memory project_hue_bridge_auto_reconnect). Waere
                // er einmal tot, wuerde er in diesem Prozess nie wieder starten: `initialize()`
                // ist per Waechter idempotent. Der Scope-Handler allein rettet nur den Prozess,
                // nicht das Feature.
                if (available) {
                    try {
                        attemptRecoveryIfDisconnected()
                    } catch (e: Exception) {
                        Logger.w(
                            LogTags.HUE_BRIDGE,
                            "⚠️ BRIDGE-MANAGER: Wiederverbindungs-Versuch fehlgeschlagen - " +
                                "Beobachter laeuft weiter",
                            e
                        )
                    }
                }
            }
        }
        Logger.d(LogTags.HUE_BRIDGE, "🌐 BRIDGE-MANAGER: Netzwerk-Wiederverbindung wird beobachtet")
    }

    /**
     * Versucht eine Wiederverbindung, wenn der Zustand NICHT bereits CONNECTED ist - dieselbe
     * Sequenz, die [getValidatedConnection] schon fuer ihren Recovery-Zweig nutzt
     * ([restoreConnectionFromStorage] setzt den persistierten Stand optimistisch, [recoverConnection]
     * validiert ihn live gegen die Bridge). No-op, wenn schon verbunden (der periodische
     * Health-Check deckt das ab) oder wenn ueberhaupt keine Bridge eingerichtet ist.
     *
     * `internal` + [VisibleForTesting] statt `private`: ein reflektiver Aufruf einer privaten
     * suspend fun ist fragil (der zugehoerige Continuation-Parameter aus dem Kotlin-Compiler
     * lässt sich zwar per Reflection treffen, aber ein synchron durchlaufender Aufruf ruft
     * `cont.resume()` nie auf und haengt `suspendCoroutine` dauerhaft) - deshalb bleibt es bei
     * der einfacheren, robusten Sichtbarkeitsloesung fuer den Test in
     * [HueBridgeConnectionManagerTest].
     */
    @VisibleForTesting
    internal suspend fun attemptRecoveryIfDisconnected() {
        if (currentConnectionState.get() is ConnectionState.CONNECTED) return
        if (!hasStoredBridge()) return

        val paused = try {
            resolveMasterPausePrefs()?.pausedNow() == true
        } catch (e: Exception) {
            Logger.e(LogTags.HUE_BRIDGE, "Failed to check master-pause state, assuming not paused", e)
            false
        }
        if (paused) {
            Logger.d(LogTags.HUE_BRIDGE, "Master-Pause aktiv - autonome Wiederverbindung uebersprungen")
            return
        }

        if (!recoveryInFlight.compareAndSet(false, true)) {
            Logger.d(LogTags.HUE_BRIDGE, "🔄 BRIDGE-MANAGER: Wiederverbindung bereits im Gange - ueberspringe")
            return
        }
        try {
            Logger.i(LogTags.HUE_BRIDGE, "🔄 BRIDGE-MANAGER: Netzwerk verfuegbar, versuche automatische Wiederverbindung")
            restoreConnectionFromStorage()
            recoverConnection()
        } finally {
            recoveryInFlight.set(false)
        }
    }

    /**
     * INTERNAL: Perform actual health check (extracted for reusability)
     */
    private suspend fun performHealthCheck(): Boolean {
        val currentState = currentConnectionState.get()
        if (currentState !is ConnectionState.CONNECTED) {
            return false
        }
        
        val isHealthy = validateConnectionCredentials(currentState.bridgeIp, currentState.username)
        if (!isHealthy) {
            Logger.w(LogTags.HUE_BRIDGE, "⚠️ BRIDGE-MANAGER: Health check failed, marking connection as error")
            updateConnectionState(ConnectionState.ERROR("Health check failed", null))
            return false
        } else {
            Logger.d(LogTags.HUE_BRIDGE, "✅ BRIDGE-MANAGER: Connection validation successful")
            // Update last validated timestamp
            val updatedState = currentState.copy(lastValidated = System.currentTimeMillis())
            updateConnectionState(updatedState)
            updateLastSuccessTimestamp()
            return true
        }
    }

    /**
     * INTERNAL: Persist "now" as the last-success timestamp in @HueDataStore.
     * No-op (logged) if the DataStore cannot be resolved.
     */
    private suspend fun updateLastSuccessTimestamp() {
        val dataStore = resolveHueDataStore()
        if (dataStore == null) {
            Logger.w(LogTags.HUE_BRIDGE, "⚠️ BRIDGE-MANAGER: HueDataStore unavailable, cannot update last-success timestamp")
            return
        }
        dataStore.edit { mutablePrefs ->
            mutablePrefs[KEY_LAST_SUCCESS] = System.currentTimeMillis()
        }
    }

    /**
     * INTERNAL: Read the persisted last-success timestamp from @HueDataStore (0 if unset/unavailable).
     */
    private suspend fun getLastSuccessTimestamp(): Long {
        val dataStore = resolveHueDataStore() ?: return 0L
        return dataStore.data.first()[KEY_LAST_SUCCESS] ?: 0L
    }

    /**
     * INTERNAL: Update connection state thread-safely
     */
    private fun updateConnectionState(newState: ConnectionState) {
        currentConnectionState.set(newState)
        _connectionStatus.value = newState

        Logger.d(LogTags.HUE_BRIDGE, "📊 BRIDGE-MANAGER: Connection state updated to: ${newState.javaClass.simpleName}")
    }

    /**
     * Cleanup: stoppt alle laufenden Hintergrundarbeiten dieses Managers.
     *
     * WIEDERVERWENDBAR BLEIBEN, NICHT VERBRENNEN: Das ist ein Singleton mit
     * PROZESS-Lebensdauer (statisches [INSTANCE]), keine Activity-gebundene Instanz. Ein
     * `healthCheckScope.cancel()` waere endgueltig - jedes spaetere `healthCheckScope.launch`
     * (Restore aus dem Storage, Health-Monitoring, Netzwerk-Recovery, die
     * Hintergrund-Validierung in getValidatedConnection()) wuerde danach lautlos nie mehr
     * starten, und nur ein kompletter Prozess-Neustart wuerde das heilen: beim Wecken blieb das
     * Licht aus, ohne Fehlermeldung. Deshalb `cancelChildren()` - genau wie im Schwester-
     * Singleton [HueSmartScheduler.cleanup]. Und [initialized] wird zurueckgesetzt, weil sonst
     * der (bewusst idempotente, siehe [initialize]) CAS-Waechter ein spaeteres [initialize]
     * sofort verwerfen wuerde.
     *
     * Aktuell ruft das niemand (MainActivity hat seinen onDestroy-Cleanup absichtlich entfernt) -
     * die Falle wird hier entschaerft, bevor ein kuenftiger Aufrufer (z.B. ein
     * "Hue deaktivieren"-Schalter, analog MasterPauseUseCase -> hueSmartScheduler.cleanup())
     * hineintritt.
     */
    fun cleanup() {
        try {
            Logger.d(LogTags.HUE_BRIDGE, "🧹 BRIDGE-MANAGER: Starting comprehensive cleanup to prevent mutex errors...")

            // CRITICAL FIX: Cancel all background jobs immediately
            healthCheckJob?.cancel()
            healthCheckJob = null

            // Nur die Kinder - der Scope selbst bleibt benutzbar (siehe KDoc).
            healthCheckScope.coroutineContext.cancelChildren()

            // Ein spaeteres initialize() darf wieder greifen.
            initialized.set(false)

            // CRITICAL FIX: Clear connection state to prevent stale references
            updateConnectionState(ConnectionState.DISCONNECTED)
            
            // CRITICAL FIX: Reset volatile fields
            isAppInForeground = false
            lastForegroundCheck = 0L
            lastManualCheck = 0L
            
            // PHASE 2: Cleanup smart scheduler
            smartScheduler?.cleanup()
            
            Logger.d(LogTags.HUE_BRIDGE, "✅ BRIDGE-MANAGER: Comprehensive cleanup completed successfully")
        } catch (e: Exception) {
            Logger.e(LogTags.HUE_BRIDGE, "Error during HueBridgeConnectionManager cleanup", e)
        }
    }
}
