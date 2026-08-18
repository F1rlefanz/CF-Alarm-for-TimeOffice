package com.github.f1rlefanz.cf_alarmfortimeoffice

import android.app.Activity
import android.app.Application
import android.app.Application.ActivityLifecycleCallbacks
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.UserManager
import androidx.core.content.ContextCompat
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.github.f1rlefanz.cf_alarmfortimeoffice.BuildConfig
import com.github.f1rlefanz.cf_alarmfortimeoffice.di.qualifiers.MainDataStore
import com.github.f1rlefanz.cf_alarmfortimeoffice.error.ErrorHandler
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.connection.HueBridgeConnectionManager
import com.github.f1rlefanz.cf_alarmfortimeoffice.masterpause.MasterPauseUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.service.BackgroundServiceManager
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.IShiftUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.DeviceLocalFlagsGuard
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.DeviceLocalStartupGate
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.SimpleFileTree
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

/**
 * MODERNIZED Application class with Hilt Dependency Injection
 * 
 * ARCHITECTURE UPGRADE:
 * ✅ Hilt DI for compile-time dependency resolution
 * ✅ Automatic dependency injection (no manual AppContainer)
 * ✅ Better testability and maintainability
 * 
 * FEATURES:
 * 🔄 HueBridgeConnectionManager initialization at app startup
 * 💓 Automatic connection health monitoring and recovery
 * 🚀 Guaranteed Hue Bridge availability for alarm operations
 * 📱 Background service initialization for alarm maintenance
 * 🔐 OAuth2 token system with encryption
 * 
 * Core Features:
 * - Lokaler Crash-Handler (schreibt letzten Absturz in last_crash.txt)
 * - Hilt dependency injection
 * - OAuth2 token system initialization
 * - Clean resource management
 */
@HiltAndroidApp
class CFAlarmApplication : Application() {
    
    // Application scope for long-running operations
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    // Hilt-injected dependencies
    @Inject
    lateinit var backgroundServiceManager: BackgroundServiceManager
    
    @Inject
    lateinit var shiftUseCase: IShiftUseCase

    /**
     * Der "settings"-Store. Gebraucht fuer [DeviceLocalFlagsGuard]: er enthaelt neben den echten
     * Einstellungen auch geraetelokale Onboarding-Flags, die nach einem Backup-Restore auf ein
     * neues Geraet nicht gelten duerfen.
     */
    @Inject
    @MainDataStore
    lateinit var mainDataStore: DataStore<Preferences>

    /**
     * Fuer das Aufheben einer Master-Pause beim Geraetewechsel - siehe initializeApp().
     *
     * BEWUSST `dagger.Lazy`, NICHT der Typ direkt. Der erste Wurf injizierte
     * `MasterPauseUseCase` unmittelbar - und der zieht ueber seinen Konstruktor
     * `HueSmartScheduler` in den Graphen, der wiederum `WorkManager.getInstance()` aufrief.
     * Ergebnis (am Emulator reproduziert, 11.08.2026): der Prozess, den das System VOR der ersten
     * Entsperrung fuer den directBootAware BootReceiver startet, starb mit "Unable to create
     * application / WorkManager is not initialized properly" - und damit lief der
     * Direct-Boot-Restore der Alarme nie. Dieselbe Falle, die CLAUDE.md fuer
     * `TinkEncryptionHelper` beschreibt: was am Application-Graphen haengt, wird in JEDEM
     * Prozessstart gebaut, auch im gesperrten.
     *
     * Mit `Lazy` entsteht die Instanz erst, wenn ein Geraetewechsel wirklich erkannt wurde - und
     * das setzt einen erfolgreichen Read aus dem CE-Storage voraus, also ein entsperrtes Geraet.
     * Wer diesen Wrapper entfernt, baut den Absturz zurueck.
     */
    @Inject
    lateinit var masterPauseUseCase: dagger.Lazy<MasterPauseUseCase>

    /**
     * Ist der Nutzer entsperrt, also CREDENTIAL-ENCRYPTED Storage lesbar?
     *
     * Gleiche Umsetzung und gleiche Fehlerrichtung wie in `AlarmRepository` und
     * `BackgroundServiceManager`: im Zweifel `true`. Ein faelschlich als "gesperrt" gewerteter
     * Zustand wuerde den Startblock dauerhaft verschieben; der Nachhol-Weg ueber
     * `ACTION_USER_UNLOCKED` feuert dann nicht mehr, weil das Entsperren laengst passiert ist.
     */
    private val userUnlocked: Boolean
        get() = try {
            getSystemService(UserManager::class.java)?.isUserUnlocked ?: true
        } catch (e: Exception) {
            Logger.w(LogTags.APP, "UserManager nicht abfragbar - Nutzer gilt als entsperrt", e)
            true
        }

    /** Sorgt dafuer, dass der geraetelokale Startblock je Prozess hoechstens EINMAL laeuft. */
    private val startupGate = DeviceLocalStartupGate()

    /**
     * Merker fuer den Datei-Log-Baum - siehe [plantFileLogTree] fuer den Ablauf, der ohne ihn
     * kaputt ging. Reines In-Prozess-Flag, kein Zugriff auf irgendetwas beim Bauen des Graphen.
     */
    private val fileLogTree = FileLogTreeInstaller()

    /** Schuetzt das Paar aus [unlockReceiver] und [unlockActivityWatcher] gegen zwei Melder. */
    private val unlockWatcherLock = Any()

    /** Nur gesetzt, solange auf `ACTION_USER_UNLOCKED` gewartet wird. */
    private var unlockReceiver: BroadcastReceiver? = null

    /** Zweites Netz - nur gesetzt, solange auf das Entsperren gewartet wird. */
    private var unlockActivityWatcher: ActivityLifecycleCallbacks? = null

    override fun onCreate() {
        super.onCreate()
        
        // Initialize ErrorHandler first
        ErrorHandler.initialize(this)

        // Lokaler Crash-Handler: schreibt Abstürze in last_crash.txt (Alpha-Test-Diagnose)
        installCrashHandler()
        
        // ✅ WICHTIG: File-Logging IMMER aktivieren (DEBUG + RELEASE)
        // Der Logcat-Baum steht ZUERST und unabhaengig vom Datei-Baum: er braucht kein
        // Verzeichnis und darf deshalb nicht mit dem Datei-Log zusammen ausfallen.
        if (BuildConfig.DEBUG) {
            try { Timber.plant(Timber.DebugTree()) } catch (_: Exception) { /* ignore */ }
            Logger.d(LogTags.APP, "Timber initialized in DEBUG mode with file logging")
        } else {
            Logger.i(LogTags.APP, "Timber initialized in RELEASE mode with file logging")
        }
        plantFileLogTree("Kaltstart")

        // Initialize app components
        initializeApp()
        
        Logger.i(LogTags.APP, "✅ CFAlarmApplication initialized with Hilt DI - Modern and reliable!")
    }
    
    /**
     * Richtet das Datei-Log ein, sobald das Verzeichnis wirklich da ist - und darf dafuer
     * MEHRFACH gerufen werden.
     *
     * WELCHER ABLAUF OHNE DAS KAPUTT GING: Startet der Prozess durch `LOCKED_BOOT_COMPLETED` vor
     * der ersten Entsperrung - also genau in dem Ablauf, in dem der `BootReceiver` die Alarme aus
     * dem Direct-Boot-Spiegel wiederherstellt -, ist External Storage noch nicht gemountet und
     * `getExternalFilesDir(null)` liefert `null`. Frueher blieb es fuer die GESAMTE Lebensdauer
     * dieses Prozesses dabei, denn gepflanzt wurde genau einmal in `onCreate()`. Im Release ist
     * der [SimpleFileTree] die einzige Senke (dort steht bewusst kein `DebugTree`), und Timber
     * verwirft ohne gepflanzten Baum ALLES still - auch `Logger.e`/`Logger.w`. Der Prozess
     * ueberlebt die Entsperrung aber und bedient danach die App: ausgerechnet nach einem Neustart
     * gab es damit kein einziges Beweismittel - kein WARN aus `visibilitySnapshot()`, keine
     * Meldung des Alarm-Restores, und die Datei, die der Nutzer per "Logs senden" schickt, endet
     * am Vortag. Im Debug faellt das nie auf, weil dort ein `DebugTree` steht.
     *
     * DESHALB WIRD NACH DEM ENTSPERREN ERNEUT ANGEKLOPFT ([onUnlockOpportunity],
     * [runDeviceLocalStartupChecks]) - dasselbe Muster, das `AlarmMaintenanceService` fuer die
     * Log-Bereinigung schon faehrt: das Verzeichnis erst dann aufloesen, wenn es da sein KANN.
     * Nicht vorgezogen: hier wird nur ein Context-Pfad abgefragt, kein WorkManager und kein
     * CE-DataStore - die Regel "nichts am Application-Graphen fasst beim Bauen WorkManager oder
     * CE-Storage an" bleibt unangetastet.
     *
     * Die Erfolgsmeldung ist bewusst WARN und nicht INFO: im Release schreibt der Baum nur WARN+,
     * eine INFO-Zeile stuende also nie in der Datei, die sie ankuendigt. Genau diese Zeile sagt
     * spaeter, ob der Baum beim Kaltstart oder erst nach dem Entsperren hochkam. Sie enthaelt
     * keine PII (nur einen Pfad).
     */
    private fun plantFileLogTree(anlass: String) {
        try {
            val gepflanzt = fileLogTree.install(
                resolveLogDir = { getExternalFilesDir(null) },
                plant = { logDir ->
                    // PII-Schutz: In Release nur WARN+ ins Datei-Log (INFO/business enthalten
                    // E-Mail/Kalendertitel); in Debug weiterhin alles fuer die Entwicklung.
                    val fileLogMinPriority =
                        if (BuildConfig.DEBUG) android.util.Log.VERBOSE else android.util.Log.WARN
                    Timber.plant(SimpleFileTree(logDir, minPriority = fileLogMinPriority))
                    Logger.w(
                        LogTags.APP,
                        "🗂️ Datei-Log aktiv ($anlass) - Aufbewahrung 8 Tage in: ${logDir.absolutePath}"
                    )
                }
            )
            if (!gepflanzt && !fileLogTree.isPlanted) {
                // Kein Verzeichnis. Kein Fehlerfall: vor der ersten Entsperrung erwartbar, und
                // der naechste Anlass versucht es erneut.
                Logger.d(LogTags.APP, "Datei-Log noch nicht moeglich ($anlass) - wird nachgeholt")
            }
        } catch (e: Exception) {
            // Ein Fehler hier darf onCreate NICHT crashen - sonst liefe der Direct-Boot-Restore
            // der Alarme im BootReceiver nie. Der Merker ist bereits zurueckgenommen, der
            // naechste Anlass versucht es erneut.
            Logger.w(LogTags.APP, "Datei-Log konnte nicht eingerichtet werden ($anlass)", e)
        }
    }

    private fun initializeApp() {
        applicationScope.launch {
            try {
                // ZUERST: geraetelokale Onboarding-Flags pruefen und den Pausen-Spiegel abgleichen.
                // Muss VOR allem anderen laufen, damit die Onboarding-Gates (Akku-Ausnahme, "Pause
                // bei Nichtnutzung") auf einem per Backup wiederhergestellten Geraet wieder
                // greifen - aber NUR bei entsperrtem Nutzer, siehe runDeviceLocalStartupChecks().
                try {
                    ensureDeviceLocalStartupRuns(
                        isUserUnlocked = { userUnlocked },
                        armUnlockWatchers = { armUnlockWatchers() },
                        runChecks = { runDeviceLocalStartupChecks() }
                    )
                } catch (e: Exception) {
                    Logger.e(LogTags.APP, "❌ STARTUP: Entsperr-Ueberwachung konnte nicht aufgebaut werden", e)
                }

                // CRITICAL: Initialize Hue Bridge Connection Manager first
                Logger.i(LogTags.HUE_BRIDGE, "🔄 STARTUP: Initializing robust Hue Bridge connection management")
                try {
                    val connectionManager = HueBridgeConnectionManager.getInstance(this@CFAlarmApplication)
                    connectionManager.initialize()
                    Logger.i(LogTags.HUE_BRIDGE, "✅ STARTUP: Hue Bridge connection manager initialized - alarm operations guaranteed")
                } catch (e: Exception) {
                    Logger.e(LogTags.HUE_BRIDGE, "❌ STARTUP: Failed to initialize Hue Bridge connection manager", e)
                }

                // ✅ CRITICAL FIX: Initialize WorkManager for automatic alarm synchronization
                Logger.business(LogTags.TOKEN, "🔄 STARTUP: Initializing background services")
                try {
                    backgroundServiceManager.initializeBackgroundServices()
                    Logger.business(LogTags.TOKEN, "✅ STARTUP: Background-Services aktiv – Wartung/Alarm-Sync via Exact Alarm (6h)")
                } catch (e: Exception) {
                    Logger.e(LogTags.TOKEN, "❌ STARTUP: Failed to initialize WorkManager", e)
                }
                
                // Initialize ShiftConfig early to prevent race conditions
                Logger.d(LogTags.SHIFT_CONFIG, "🔄 STARTUP: Initializing ShiftConfig early to prevent timing issues")
                launch {
                    try {
                        if (!userUnlocked) {
                            // Vor der ersten Entsperrung liegt shift_prefs im nicht lesbaren
                            // CE-Storage. Ein Read dort liefert still "leer" und vergiftet den
                            // DataStore-Cache fuer die restliche Prozesslaufzeit - dieses
                            // Vorwaermen hat also nichts zu gewinnen und alles zu verlieren.
                            Logger.i(
                                LogTags.SHIFT_CONFIG,
                                "🔒 STARTUP: Schicht-Konfiguration wird im gesperrten Zustand nicht gelesen"
                            )
                            return@launch
                        }
                        val currentConfig = shiftUseCase.getCurrentShiftConfig().getOrNull()

                        if (currentConfig != null) {
                            Logger.business(LogTags.SHIFT_CONFIG, "✅ STARTUP: ShiftConfig loaded successfully - autoAlarm=${currentConfig.autoAlarmEnabled}")
                        } else {
                            // KEIN Default-Fallback, der SCHREIBT. Dieselbe Fehlerklasse hatte drei
                            // Schreibstellen (hier, ShiftViewModel.loadShiftConfig() und
                            // CalendarViewModel.createAlarmsFromLoadedEvents()); alle drei sind
                            // geschlossen.
                            //
                            // `null` bedeutet hier NICHT mehr "noch nie konfiguriert": seit
                            // ShiftConfigRepository die Faelle trennt, liefert der
                            // Nicht-konfiguriert-Fall die Standardkonfiguration als ERFOLG. Ein
                            // Fehlschlag heisst also: vorhanden, aber unlesbar - und genau dort
                            // ist Ueberschreiben Datenverlust. Dieser Pfad laeuft bei JEDEM
                            // Kaltstart im applicationScope, auch bei rein hintergrund-getriebenen
                            // Prozessstarts, also ohne dass ein Nutzer etwas davon mitbekaeme.
                            Logger.e(
                                LogTags.SHIFT_CONFIG,
                                "❌ STARTUP: Schicht-Konfiguration nicht lesbar - sie wird NICHT mit " +
                                    "Standardwerten ueberschrieben. Rohdaten liegen als shift_config_broken."
                            )
                        }
                    } catch (e: Exception) {
                        Logger.e(LogTags.SHIFT_CONFIG, "❌ STARTUP: Exception during ShiftConfig initialization", e)
                    }
                }
                
                // ✅ Token encryption active (no migration needed - never released unencrypted version)
                Logger.d(LogTags.TOKEN, "🔐 STARTUP: Tink encryption active for OAuth2 tokens")
                
                Logger.i(LogTags.APP, "✅ App initialization completed successfully (with Hilt DI + WorkManager + Encryption)")
            } catch (e: Exception) {
                Logger.e(LogTags.APP, "Error during app initialization", e)
            }
        }
    }
    /**
     * Der geraetelokale Startblock: Geraetewechsel erkennen, eine mitgesicherte Master-Pause
     * aufheben, den Direct-Boot-Spiegel des Pausenzustands abgleichen.
     *
     * WARUM ER EIN GATE BRAUCHT (der Grund fuer diese Funktion):
     * Alles hier liest oder schreibt CREDENTIAL-ENCRYPTED Storage. Ein CE-Read VOR der ersten
     * Entsperrung wirft NICHT - er liefert still leere Preferences und meldet Erfolg. DataStore
     * legt dieses leere Ergebnis in seinen In-Memory-Cache und gibt es fuer die restliche
     * PROZESSLAUFZEIT unveraendert zurueck (die Version steigt nur bei einem erfolgreichen Write,
     * der im gesperrten CE-Storage scheitert). Ein Prozess, den das System vor der ersten
     * Entsperrung fuer den directBootAware BootReceiver startet, saehe den `settings`-Store danach
     * dauerhaft leer - inklusive aller spaeteren Leser.
     *
     * Deshalb laeuft dieser Block ausschliesslich bei entsperrtem Nutzer; im gesperrten Fall holen
     * ihn die beiden Netze aus [armUnlockWatchers] nach, plus die Nachpruefung in
     * [ensureDeviceLocalStartupRuns] fuer das Fenster dazwischen. [startupGate] stellt sicher,
     * dass er trotz dieser drei Anlaesse hoechstens einmal laeuft.
     *
     * Best-effort: ein Fehler hier darf den Start nicht aufhalten.
     */
    private suspend fun runDeviceLocalStartupChecks() {
        // Zweiter Anlass fuers Nachruesten des Datei-Logs: dieser Block laeuft ausschliesslich
        // bei entsperrtem Nutzer - also genau dann, wenn External Storage verfuegbar geworden ist.
        // Steht VOR dem Gate, damit auch der Lauf, der das Gate verliert, noch anklopft.
        plantFileLogTree("geraetelokaler Startblock")

        // Egal ueber welchen Anlass wir hier landen: die Wartekonstruktion wird nicht mehr
        // gebraucht. Steht VOR dem Gate, damit auch der verlierende zweite Anlass aufraeumt -
        // ein haengender Receiver bzw. ein haengender Lifecycle-Callback ist ein Leck.
        disarmUnlockWatchers()
        if (!startupGate.claimRun()) {
            Logger.d(LogTags.APP, "STARTUP: geraetelokale Pruefungen liefen bereits - kein zweiter Lauf")
            return
        }
        try {
            val deviceChanged = DeviceLocalFlagsGuard.resetIfDeviceChanged(mainDataStore)
            // `.get()` erst hier - siehe die Begruendung am Feld: die Konstruktion zieht
            // WorkManager in den Graphen und darf in einem Direct-Boot-Prozess nicht passieren.
            // Das Gate oben ist die Zusicherung dafuer.
            if (deviceChanged && masterPauseUseCase.get().paused.first()) {
                // Eine Master-Pause darf einen Geraetewechsel nicht ueberleben (sonst bleibt
                // der Wecker auf dem neuen Geraet still). Aufgehoben wird sie ueber
                // resume() und NICHT durch Loeschen des Flags: pause() schreibt auch den
                // Device-Protected-Spiegel, den der BootReceiver vor der ersten Entsperrung
                // liest, und reisst 6h-Wartung, Dimmer-/DND-Tick, Hue-Planung und
                // Pre-Alarm-Refresh ab. Nur resume() baut das alles wieder auf.
                Logger.w(
                    LogTags.MASTER_PAUSE,
                    "🔄 GERAETEWECHSEL: aktive Master-Pause wird aufgehoben - sie gilt fuer " +
                        "das alte Geraet. Hintergrundketten werden neu aufgebaut."
                )
                masterPauseUseCase.get().resume()
            }

            // Spiegel und CE-Wahrheit des Pausenzustands abgleichen. Steht INNERHALB des
            // Entsperrt-Gates, nicht daneben: im gesperrten Prozess liest die CE-Wahrheit still
            // "nicht pausiert" und der Abgleich wuerde einen AKTIVEN Pausen-Spiegel auf false
            // setzen - also genau die Information zerstoeren, die der BootReceiver vor der ersten
            // Entsperrung braucht. Frueher rettete das nur der Zufall, dass der Marker-Write
            // darueber im gesperrten Storage warf und dieser Aufruf gar nicht erreicht wurde.
            masterPauseUseCase.get().reconcileDirectBootMirror()
        } catch (e: Exception) {
            Logger.w(LogTags.APP, "⚠️ STARTUP: Geraete-Marker konnte nicht geprueft werden", e)
        }
    }

    /**
     * Baut die Wartekonstruktion fuer das Entsperren auf - ZWEI unabhaengige Netze.
     *
     * Warum zwei: `ACTION_USER_UNLOCKED` ist KEIN sticky Broadcast. Wer ihn verpasst, bekommt ihn
     * nie nachgeliefert, und dann liefe [runDeviceLocalStartupChecks] in diesem Prozess NIE -
     * waehrend der Prozess als ganz normaler App-Prozess weiterlebt. Damit fiele der
     * Geraetewechsel-Check aus (eine mitgesicherte Master-Pause bliebe aktiv) und vor allem der
     * Abgleich des Direct-Boot-Spiegels.
     */
    private fun armUnlockWatchers() {
        Logger.w(
            LogTags.APP,
            "🔒 STARTUP im gesperrten Zustand (Direct Boot): geraetelokale Pruefungen werden " +
                "ausgelassen und nach dem Entsperren nachgeholt."
        )
        registerUnlockReceiver()
        registerActivityUnlockWatcher()
    }

    /**
     * Netz 1: [runDeviceLocalStartupChecks] nachholen, sobald `ACTION_USER_UNLOCKED` eintrifft.
     *
     * BEWUSST DYNAMISCH REGISTRIERT und nicht im Manifest: der Nachholbedarf betrifft nur einen
     * Prozess, der VOR der Entsperrung hochkam. Ein Manifest-Receiver wuerde die App bei jedem
     * Entsperren eines beliebigen Nutzerprofils starten, ohne dass es etwas zu tun gaebe.
     *
     * `ACTION_USER_UNLOCKED` ist ein geschuetzter System-Broadcast; `RECEIVER_NOT_EXPORTED` ist
     * die richtige Angabe fuer die ab Android 14 erzwungene Export-Kennzeichnung.
     */
    private fun registerUnlockReceiver() {
        synchronized(unlockWatcherLock) {
            if (unlockReceiver != null) return
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    if (intent?.action != Intent.ACTION_USER_UNLOCKED) return
                    onUnlockOpportunity("ACTION_USER_UNLOCKED")
                }
            }
            unlockReceiver = receiver
            try {
                ContextCompat.registerReceiver(
                    this,
                    receiver,
                    IntentFilter(Intent.ACTION_USER_UNLOCKED),
                    ContextCompat.RECEIVER_NOT_EXPORTED
                )
            } catch (e: Exception) {
                unlockReceiver = null
                // Nichts weiter zu tun - Netz 2 und die Nachpruefung in
                // ensureDeviceLocalStartupRuns() tragen den Fall.
                Logger.e(LogTags.APP, "❌ Entsperr-Empfaenger konnte nicht registriert werden", e)
            }
        }
    }

    /**
     * Netz 2: der billigste Anlass, der nach dem Entsperren ohnehin eintritt - eine Activity.
     *
     * `registerActivityLifecycleCallbacks` ist eine reine In-Prozess-Liste: kein WorkManager, kein
     * CE-Zugriff, keine neue Kante im Application-Graphen, kein Timer, kein Polling. Eine Activity
     * ist KEIN Beweis fuer "entsperrt" - `AlarmFullScreenActivity` laeuft ausdruecklich ueber dem
     * Sperrbildschirm - deshalb ist sie hier nur der ANLASS, nachzusehen; entschieden wird in
     * [onUnlockOpportunity] weiterhin ueber den `UserManager`.
     */
    private fun registerActivityUnlockWatcher() {
        synchronized(unlockWatcherLock) {
            if (unlockActivityWatcher != null) return
            val watcher = object : ActivityLifecycleCallbacks {
                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                    onUnlockOpportunity("Activity-Start")
                }

                override fun onActivityResumed(activity: Activity) {
                    onUnlockOpportunity("Activity-Resume")
                }

                override fun onActivityStarted(activity: Activity) {}
                override fun onActivityPaused(activity: Activity) {}
                override fun onActivityStopped(activity: Activity) {}
                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
                override fun onActivityDestroyed(activity: Activity) {}
            }
            unlockActivityWatcher = watcher
            try {
                registerActivityLifecycleCallbacks(watcher)
            } catch (e: Exception) {
                unlockActivityWatcher = null
                Logger.e(LogTags.APP, "❌ Activity-Entsperrwaechter konnte nicht registriert werden", e)
            }
        }
    }

    /**
     * Ein Anlass, bei dem das Entsperren passiert sein KANN. Entschieden wird ueber den
     * `UserManager`, nicht ueber den Anlass selbst.
     *
     * Meldet der `UserManager` weiterhin "gesperrt", bleiben beide Netze scharf und es passiert
     * NICHTS: ein Lauf im gesperrten Zustand waere teurer als ein verpasster: er liest den
     * `settings`-Store still leer und vergiftet damit den DataStore-Cache fuer die restliche
     * Prozesslaufzeit - siehe [runDeviceLocalStartupChecks].
     */
    private fun onUnlockOpportunity(anlass: String) {
        handleUnlockOpportunity(
            isUserUnlocked = { userUnlocked },
            // NICHT synchron: `plantFileLogTree` macht echtes Platten-I/O -
            // `getExternalFilesDir(null)` prueft den Mount-Zustand und legt Verzeichnisse an, und
            // der `SimpleFileTree`-Konstruktor raeumt in seinem `init` alte Logdateien weg
            // (`listFiles()` + `delete()`). Beide Anlaesse dieses Aufrufs liegen auf dem
            // Hauptthread: der `ACTION_USER_UNLOCKED`-Empfaenger (10-Sekunden-Budget von
            // `onReceive`) und die Activity-Lifecycle-Rueckrufe - und sie treffen genau den
            // Moment mit der hoechsten I/O-Last, die erste Entsperrung nach einem Neustart.
            // Derselbe Aufruf aus `runDeviceLocalStartupChecks()` liegt laengst im
            // `applicationScope` (Dispatchers.IO); das hier war die einzige Ausnahme.
            // Die Reihenfolge bleibt: angestossen wird VOR dem Gate-Check, nur eben nebenlaeufig.
            // `FileLogTreeInstaller` sichert das Pflanzen per CAS ab, mehrere Anlaesse
            // gleichzeitig sind also unbedenklich.
            ensureFileLog = { applicationScope.launch { plantFileLogTree("entsperrt: $anlass") } },
            startupAlreadyRan = { startupGate.hasRun },
            disarmWatchers = { disarmUnlockWatchers() },
            runChecks = {
                Logger.i(
                    LogTags.APP,
                    "🔓 Nutzer entsperrt ($anlass) - geraetelokale Startpruefungen werden nachgeholt"
                )
                // Abgemeldet wird in runDeviceLocalStartupChecks(), damit auch der Fall aufraeumt,
                // in dem ein anderer Anlass den Lauf bereits beansprucht hat.
                applicationScope.launch { runDeviceLocalStartupChecks() }
            }
        )
    }

    /** Meldet beide Netze ab. Mehrfach aufrufbar; nach dem ersten Mal ein No-op. */
    private fun disarmUnlockWatchers() {
        synchronized(unlockWatcherLock) {
            unlockReceiver?.let { receiver ->
                unlockReceiver = null
                try {
                    unregisterReceiver(receiver)
                } catch (e: IllegalArgumentException) {
                    // Bereits abgemeldet - kein Fehlerfall.
                }
            }
            unlockActivityWatcher?.let { watcher ->
                unlockActivityWatcher = null
                try {
                    unregisterActivityLifecycleCallbacks(watcher)
                } catch (e: Exception) {
                    Logger.w(LogTags.APP, "Activity-Entsperrwaechter liess sich nicht abmelden", e)
                }
            }
        }
    }

    /**
     * Installiert einen einfachen Uncaught-Exception-Handler, der den letzten
     * Absturz (Stacktrace + Zeit + App-Version) in last_crash.txt schreibt.
     * Ermöglicht Alpha-Testern, Abstürze per "Logs senden" zu melden – ohne Cloud-SDK.
     */
    private fun installCrashHandler() {
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val crashFile = File(getExternalFilesDir(null), "last_crash.txt")
                val timestamp = java.text.SimpleDateFormat(
                    "yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()
                ).format(java.util.Date())
                val stackTrace = java.io.StringWriter().also { sw ->
                    throwable.printStackTrace(java.io.PrintWriter(sw))
                }.toString()
                crashFile.writeText(
                    "CF Alarm – letzter Absturz\n" +
                        "Zeit: $timestamp\n" +
                        "Version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n" +
                        "Thread: ${thread.name}\n\n" +
                        stackTrace
                )
            } catch (_: Throwable) {
                // Crash-Logging darf den Absturz nicht verschlimmern
            }
            // An den vorherigen Handler delegieren (System-Default beendet die App sauber)
            previousHandler?.uncaughtException(thread, throwable)
        }
    }

    // onTerminate() removed - it only runs in emulator and causes pthread_mutex crashes
    // Android handles all cleanup automatically when the app terminates
}

/**
 * Entscheidet, ob der geraetelokale Startblock SOFORT laeuft oder auf das Entsperren wartet -
 * und schliesst dabei das Fenster zwischen Abfrage und Wartekonstruktion.
 *
 * DER GRUND FUER DIE NACHPRUEFUNG (und fuer das `finally`):
 * Die Abfrage `isUserUnlocked()` und das Aufsetzen der Netze liegen zeitlich auseinander - der
 * ganze Block laeuft asynchron im Application-Scope. Entsperrt der Nutzer genau dazwischen, ist
 * `ACTION_USER_UNLOCKED` bereits verschickt; der Broadcast ist NICHT sticky und wird nicht
 * nachgeliefert. Ohne die zweite Abfrage liefe [runChecks] in diesem Prozess dann NIE, waehrend
 * der Prozess als normaler App-Prozess weiterlebt: kein Geraetewechsel-Check (eine mitgesicherte
 * Master-Pause bliebe aktiv) und vor allem kein Abgleich des Direct-Boot-Spiegels.
 *
 * Dasselbe gilt, wenn [armUnlockWatchers] scheitert - deshalb steht die Nachpruefung im
 * `finally`: Nichtstun waere die schlechteste Antwort. Gegen einen doppelten Lauf schuetzt
 * ausschliesslich das Gate in [runChecks]
 * ([com.github.f1rlefanz.cf_alarmfortimeoffice.util.DeviceLocalStartupGate], ein
 * `compareAndSet`) - hier wird bewusst nicht zusaetzlich gezaehlt.
 */
internal suspend fun ensureDeviceLocalStartupRuns(
    isUserUnlocked: () -> Boolean,
    armUnlockWatchers: () -> Unit,
    runChecks: suspend () -> Unit
) {
    if (isUserUnlocked()) {
        runChecks()
        return
    }
    try {
        armUnlockWatchers()
    } finally {
        if (isUserUnlocked()) runChecks()
    }
}

/**
 * Was bei einem Entsperr-ANLASS zu tun ist - herausgezogen, weil hier zwei verschiedene Dinge
 * nachgeholt werden, die NICHT dieselbe Bedingung haben.
 *
 * DER UNTERSCHIED, der die Reihenfolge traegt: der geraetelokale Startblock laeuft je Prozess
 * hoechstens einmal und ist danach fuer immer erledigt (Gate). Das Datei-Log haengt dagegen an
 * `getExternalFilesDir()` und kann auch dann noch fehlen, wenn der Startblock ueber einen anderen
 * Anlass laengst gelaufen ist. Stuende das Nachruesten des Logs hinter dem Gate-Check, faende ein
 * Direct-Boot-Prozess nie mehr zu einer Log-Senke - genau der Zustand, den
 * [CFAlarmApplication.plantFileLogTree] beschreibt.
 *
 * Im gesperrten Zustand passiert weiterhin NICHTS: External Storage ist dann ohnehin nicht da,
 * und ein Lauf des Startblocks waere teurer als ein verpasster (vergifteter DataStore-Cache).
 */
internal fun handleUnlockOpportunity(
    isUserUnlocked: () -> Boolean,
    ensureFileLog: () -> Unit,
    startupAlreadyRan: () -> Boolean,
    disarmWatchers: () -> Unit,
    runChecks: () -> Unit
) {
    val entsperrt = isUserUnlocked()
    if (entsperrt) ensureFileLog()
    if (startupAlreadyRan()) {
        disarmWatchers()
        return
    }
    if (!entsperrt) return
    runChecks()
}

/**
 * Pflanzt den Datei-Log-Baum genau EINMAL je Prozess - und wertet einen Fehlversuch ausdruecklich
 * NICHT als erledigt.
 *
 * WARUM DAS DER KERN DES FIXES IST: die alte Fassung pflanzte einmalig in `onCreate()`. Lieferte
 * `getExternalFilesDir(null)` dort `null` - der Normalfall im Direct-Boot-Prozess, weil External
 * Storage vor der ersten Entsperrung nicht gemountet ist -, gab es nie einen zweiten Versuch. Der
 * Release-Prozess lief dann fuer den Rest seines Lebens ohne jede Log-Senke, obwohl er die
 * Entsperrung ueberlebt und danach die App bedient. Deshalb wird der Merker erst gesetzt, wenn ein
 * Verzeichnis WIRKLICH vorlag, und bei einem Wurf des Pflanzens wieder zurueckgenommen.
 */
internal class FileLogTreeInstaller {

    private val planted = AtomicBoolean(false)

    /** Steht der Baum? Nur wahr, wenn tatsaechlich gepflanzt wurde. */
    val isPlanted: Boolean get() = planted.get()

    /**
     * @param resolveLogDir liefert das Log-Verzeichnis - oder `null`, solange es (noch) fehlt.
     * @param plant pflanzt den Baum. Ein Wurf wird weitergereicht, der Merker bleibt offen.
     * @return `true`, wenn in genau diesem Aufruf gepflanzt wurde.
     */
    fun install(resolveLogDir: () -> File?, plant: (File) -> Unit): Boolean {
        if (planted.get()) return false
        // Das Aufloesen steht VOR dem Merker: "kein Verzeichnis" heisst "spaeter nochmal",
        // nicht "erledigt".
        val logDir = resolveLogDir() ?: return false
        if (!planted.compareAndSet(false, true)) return false
        try {
            plant(logDir)
        } catch (e: Throwable) {
            // Ein gescheitertes Pflanzen ist kein erledigtes Pflanzen - sonst bliebe der Prozess
            // dauerhaft stumm, obwohl der naechste Anlass Erfolg haette.
            planted.set(false)
            throw e
        }
        return true
    }
}
