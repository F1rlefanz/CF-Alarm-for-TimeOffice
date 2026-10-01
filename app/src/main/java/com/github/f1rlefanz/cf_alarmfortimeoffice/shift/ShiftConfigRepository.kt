package com.github.f1rlefanz.cf_alarmfortimeoffice.shift

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.github.f1rlefanz.cf_alarmfortimeoffice.error.AppError
import com.github.f1rlefanz.cf_alarmfortimeoffice.error.SafeExecutor
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftConfig
import com.github.f1rlefanz.cf_alarmfortimeoffice.repository.interfaces.IShiftConfigRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

// Extension property for Context to create DataStore
// corruptionHandler: siehe DataModule - ohne ihn blockiert eine beschaedigte preferences_pb nicht
// nur jedes Lesen, sondern dauerhaft auch jedes Schreiben (DataStore liest vor jedem Write erneut).
private val Context.shiftDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "shift_prefs",
    corruptionHandler = ReplaceFileCorruptionHandler(produceNewData = { emptyPreferences() })
)

/**
 * Dekodier-Ergebnis der persistierten Schicht-Konfiguration.
 *
 * Die Unterscheidung ist der eigentliche Kern: „noch nie konfiguriert" ([NotConfigured]) und
 * „vorhanden, aber unlesbar" ([Broken]) durften nie beide zu „Erfolg mit Standardkonfiguration"
 * werden. Genau das hat zwei Dinge gleichzeitig verursacht: Wecker zu den DEFAULT-Zeiten statt zu
 * den gepflegten, UND ein anschliessendes Bearbeiten schrieb den Default persistent ueber die
 * echte Konfiguration (ShiftUseCase liest `getCurrentShiftConfig()`, kopiert eine Aenderung hinein
 * und speichert alles zurueck).
 */
internal sealed interface ShiftConfigDecodeResult {
    data class Ok(val config: ShiftConfig) : ShiftConfigDecodeResult
    data object NotConfigured : ShiftConfigDecodeResult
    data class Broken(val raw: String, val cause: Throwable) : ShiftConfigDecodeResult

    /**
     * "Nichts da" AUS EINEM GESPERRTEN CE-STORAGE - also gar keine Aussage ueber die
     * Konfiguration.
     *
     * Der dritte Fall musste dazu, weil er sonst als [NotConfigured] gilt und damit die
     * Standardkonfiguration ALS ERFOLG liefert. Vor der ersten Entsperrung wirft ein CE-Read
     * nicht, er liefert still leere Preferences - der Store sieht exakt aus wie "noch nie
     * konfiguriert". Bearbeitet der Nutzer danach seine Schichten, schreibt `saveShiftConfig`
     * den Default ueber die echte Konfiguration. Genau die Datenverlust-Stelle, die die
     * Trennung Ok/NotConfigured/Broken schon einmal geschlossen hat.
     */
    data object LockedStorage : ShiftConfigDecodeResult
}

/**
 * Reine, Android-freie Dekodier-Funktion (deshalb top-level + `internal`: direkt testbar).
 *
 * Faengt bewusst [Exception], nicht nur [SerializationException]: der projekteigene
 * `LocalTimeSerializer` wirft bei einem nicht parsbaren `HH:mm`-Wert eine `DateTimeParseException`
 * (eine `RuntimeException`), die sonst ungefangen aus dem Flow herauslaufen wuerde.
 *
 * @param userUnlocked ob der CE-Storage ueberhaupt lesbar war. Nur bei `true` darf ein fehlender
 *        Eintrag als [ShiftConfigDecodeResult.NotConfigured] gelten; sonst ist er
 *        [ShiftConfigDecodeResult.LockedStorage]. Default `true`, damit reine Dekodier-Aufrufer
 *        (und die Tests der Dekodierung) unveraendert bleiben.
 */
internal fun decodeShiftConfig(
    json: Json,
    raw: String?,
    userUnlocked: Boolean = true
): ShiftConfigDecodeResult {
    if (raw == null) {
        return if (userUnlocked) ShiftConfigDecodeResult.NotConfigured
        else ShiftConfigDecodeResult.LockedStorage
    }
    return try {
        ShiftConfigDecodeResult.Ok(json.decodeFromString<ShiftConfig>(raw))
    } catch (e: Exception) {
        ShiftConfigDecodeResult.Broken(raw, e)
    }
}

/**
 * Verwaltet Schicht-Konfigurationen mit DataStore Preferences und einem 30-s-Cache.
 */
@Singleton
class ShiftConfigRepository @Inject constructor(
    @param:ApplicationContext private val context: Context
) : IShiftConfigRepository {

    private val dataStore = context.shiftDataStore
    private val shiftConfigKey = stringPreferencesKey("shift_config")

    /**
     * Ist der Nutzer entsperrt, also CREDENTIAL-ENCRYPTED Storage lesbar?
     *
     * Gleiche Umsetzung und gleiche Fehlerrichtung wie in `AlarmRepository` und
     * `BackgroundServiceManager`: im Zweifel `true`. Ein falsch-positives "gesperrt" wuerde die
     * Konfiguration dauerhaft als unlesbar melden.
     */
    private val userUnlocked: Boolean
        get() = try {
            context.getSystemService(android.os.UserManager::class.java)?.isUserUnlocked ?: true
        } catch (e: Exception) {
            Logger.w(LogTags.SHIFT_CONFIG, "UserManager nicht abfragbar - Nutzer gilt als entsperrt", e)
            true
        }

    /** Sicherung der rohen, nicht dekodierbaren Konfiguration - siehe [backupBrokenConfig]. */
    private val brokenConfigKey = stringPreferencesKey(BROKEN_CONFIG_KEY_NAME)

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Volatile
    private var cachedConfig: ShiftConfig? = null
    @Volatile
    private var cacheTimestamp: Long = 0L
    
    private companion object {
        const val CACHE_VALIDITY_MS = 30000L // 30 seconds cache validity
        const val BROKEN_CONFIG_KEY_NAME = "shift_config_broken"
    }

    /**
     * Legt das rohe, nicht dekodierbare JSON einmalig unter einem eigenen Schluessel ab, BEVOR
     * irgendein Schreibpfad die Chance hat, es zu ueberschreiben - so bleibt die Konfiguration
     * rekonstruierbar (z. B. nach einem Downgrade oder einem Serialisierungs-Fix).
     *
     * Ueberschreibt eine bereits vorhandene Sicherung NICHT: die ERSTE (dem Original naechste)
     * Version ist die wertvolle. Fehler beim Sichern werden nur geloggt - eine gescheiterte
     * Sicherung darf die Fehlermeldung an den Aufrufer nicht ersetzen.
     */
    private suspend fun backupBrokenConfig(raw: String) {
        try {
            dataStore.edit { preferences ->
                if (preferences[brokenConfigKey] == null) {
                    preferences[brokenConfigKey] = raw
                    Logger.w(
                        LogTags.SHIFT_CONFIG,
                        "Defekte Schicht-Konfiguration (${raw.length} Zeichen) unter '$BROKEN_CONFIG_KEY_NAME' gesichert"
                    )
                }
            }
        } catch (e: Exception) {
            Logger.e(LogTags.SHIFT_CONFIG, "Sicherung der defekten Schicht-Konfiguration fehlgeschlagen", e)
        }
    }

    /**
     * ANZEIGE-Flow. Darf degradieren, aber niemals crashen: `.catch` faengt sowohl eine defekte
     * preferences_pb (Upstream) als auch alles, was aus dem `map{}` kommen koennte - Vorbild
     * `DataStoreTokenRepository.observe()`. Ohne das wuerde der Fehler an den
     * CoroutineExceptionHandler der drei `stateIn(viewModelScope, ...)`-Konsumenten gehen
     * (DimmerRulesViewModel/DimmerViewModel/DndViewModel) und die App beim Oeffnen des
     * Dimmer-/DND-Tabs beenden.
     *
     * WICHTIG: bei einer DEFEKTEN Konfiguration wird der Default NICHT in den Cache geschrieben -
     * sonst wuerde `getCurrentShiftConfig()` ihn als Cache-Hit als echte Konfiguration ausliefern
     * und der Schreibpfad ihn ueber die (noch vorhandenen) Rohdaten schreiben.
     *
     * REIHENFOLGE IST TRAGEND: `.catch` steht HINTER dem `.map`, nicht davor. Davor emittierte es
     * `emptyPreferences()` in das `map` hinein - und das kann einen leeren Store nicht von einem
     * unlesbaren unterscheiden: es landete im `NotConfigured`-Zweig und schrieb GENAU DEN Default
     * in den Cache, den der `Broken`-Zweig eine Zeile darueber bewusst nicht cacht. Danach gab
     * `getCurrentShiftConfig()` fuer 30 s (CACHE_VALIDITY_MS) `Result.success(Standard-
     * konfiguration)` zurueck, ohne den frischen Read ueberhaupt zu erreichen - der Fehler kam bei
     * keinem der vier Konsumenten an (ShiftViewModel, AlarmMaintenanceService, CFAlarmApplication,
     * CalendarViewModel), `syncAlarms()` lief mit Standardzeiten und der Delta-Sync loeschte die
     * Alarme nicht mehr erkannter Schichten. Ausloeser: eine IOException auf `shift_prefs` - genau
     * der Fall, fuer den dieses `.catch` existiert (der ReplaceFileCorruptionHandler faengt nur
     * CorruptionException). Wer das `.catch` wieder nach oben zieht, baut das neu.
     */
    override val shiftConfig: Flow<ShiftConfig> = dataStore.data
        .map { preferences ->
            when (val decoded = decodeShiftConfig(json, preferences[shiftConfigKey], userUnlocked)) {
                is ShiftConfigDecodeResult.Ok -> {
                    // Update cache when config flows change
                    cachedConfig = decoded.config
                    cacheTimestamp = System.currentTimeMillis()
                    decoded.config
                }

                is ShiftConfigDecodeResult.Broken -> {
                    Logger.e(
                        LogTags.SHIFT_CONFIG,
                        "Schicht-Konfiguration ist DEFEKT (${decoded.raw.length} Zeichen) - Anzeige zeigt " +
                            "Standardwerte, die echte Konfiguration wird NICHT ueberschrieben",
                        decoded.cause
                    )
                    ShiftConfig.getDefaultConfig()
                }

                ShiftConfigDecodeResult.NotConfigured -> {
                    val defaultConfig = ShiftConfig.getDefaultConfig()
                    cachedConfig = defaultConfig
                    cacheTimestamp = System.currentTimeMillis()
                    defaultConfig
                }

                ShiftConfigDecodeResult.LockedStorage -> {
                    // Wie im Broken-Zweig: die ANZEIGE darf degradieren, der CACHE nicht. Ein
                    // Cache-Eintrag hier wuerde `getCurrentShiftConfig()` fuer 30 s die
                    // Standardkonfiguration als ERFOLG liefern lassen - und der naechste
                    // Bearbeitungsschritt schriebe sie ueber die echte Konfiguration.
                    Logger.w(
                        LogTags.SHIFT_CONFIG,
                        "🔒 shift_prefs vor der ersten Entsperrung gelesen - Anzeige zeigt " +
                            "Standardwerte, die echte Konfiguration wird NICHT ueberschrieben"
                    )
                    ShiftConfig.getDefaultConfig()
                }
            }
        }
        .catch { e ->
            // Steht hinter dem `map{}` (REIHENFOLGE IST TRAGEND). Die Anzeige degradiert, die
            // Schreibwahrheit nicht: Default nur an den Collector, NICHT in den Cache. Nur `cachedConfig`
            // wird genullt - alle Cache-Treffer sind mit `cachedConfig?.let` bewacht.
            // Skill cfalarm-persistenz-und-auth (Kurzregel 1).
            cachedConfig = null
            Logger.e(LogTags.SHIFT_CONFIG, "shift_prefs nicht lesbar - Anzeige degradiert auf Standardkonfiguration, Cache verworfen", e)
            emit(ShiftConfig.getDefaultConfig())
        }

    /** Erzwingt beim naechsten Read ein frisches Laden (nach Aenderungen von aussen). */
    fun invalidateCache() {
        cachedConfig = null
        cacheTimestamp = 0L
        Logger.d(LogTags.SHIFT_CONFIG, "🗑️ SINGLETON-CACHE: Config cache invalidated")
    }

    override suspend fun saveShiftConfig(config: ShiftConfig): Result<Unit> = 
        SafeExecutor.safeExecute("ShiftConfigRepository.saveShiftConfig") {
            val jsonString = try {
                json.encodeToString(config)
            } catch (e: SerializationException) {
                throw AppError.DataStoreError(
                    message = "Fehler beim Serialisieren der Schicht-Konfiguration",
                    cause = e
                )
            }
            
            dataStore.edit { preferences ->
                preferences[shiftConfigKey] = jsonString
            }
            
            cachedConfig = config
            cacheTimestamp = System.currentTimeMillis()
            
            Logger.d(LogTags.SHIFT_CONFIG, "✅ SINGLETON-SAVE: Shift config saved with ${config.definitions.size} definitions and cache updated")
        }

    override suspend fun getCurrentShiftConfig(): Result<ShiftConfig> = 
        SafeExecutor.safeExecute("ShiftConfigRepository.getCurrentShiftConfig") {
            val currentTime = System.currentTimeMillis()
            
            cachedConfig?.let { cached ->
                val cacheAge = currentTime - cacheTimestamp
                if (cacheAge < CACHE_VALIDITY_MS) {
                    Logger.d(LogTags.SHIFT_CONFIG, "✅ SINGLETON-CACHE-HIT: Returning cached config (${cacheAge}ms old) with ${cached.definitions.size} definitions")
                    return@safeExecute cached
                } else {
                    Logger.d(LogTags.SHIFT_CONFIG, "⏰ SINGLETON-CACHE-EXPIRED: Cache is ${cacheAge}ms old, refreshing")
                }
            }
            
            // KEIN READ IM GESPERRTEN ZUSTAND - und zwar VOR dem Zugriff, nicht erst bei der
            // Auswertung. Ein CE-Read vor der ersten Entsperrung wirft nicht, er liefert still
            // leere Preferences; DataStore legt genau dieses leere Ergebnis in seinen
            // In-Memory-Cache und gibt es fuer die restliche PROZESSLAUFZEIT zurueck (die Version
            // steigt nur bei einem erfolgreichen Write, der im gesperrten CE-Storage scheitert).
            // Ein einziger Read hier wuerde also auch jeden spaeteren, laengst entsperrten
            // Aufrufer dieses Prozesses "noch nie konfiguriert" sehen lassen - und das heisst
            // Standardkonfiguration als Erfolg, Wecker zu Standardzeiten und beim naechsten
            // Bearbeiten den Default ueber die echte Konfiguration.
            if (!userUnlocked) {
                throw AppError.DataStoreError(
                    message = "Schicht-Konfiguration vor der ersten Entsperrung nicht lesbar " +
                        "(CREDENTIAL-ENCRYPTED Storage) - es wird KEIN Default gemeldet"
                )
            }

            val preferences = dataStore.data.first()
            val config = when (val decoded = decodeShiftConfig(json, preferences[shiftConfigKey], userUnlocked)) {
                is ShiftConfigDecodeResult.Ok -> decoded.config

                // KEIN stiller Default: eine vorhandene, aber unlesbare Konfiguration wird als
                // FEHLER gemeldet. Sonst weckt die Pipeline zu Standardzeiten (statt zu den
                // gepflegten) und das naechste Bearbeiten schreibt den Default endgueltig
                // ueber die echte Konfiguration. Der bewusste Weg zum Default ist der Knopf
                // "Auf Standardwerte zurücksetzen" und gehoert dem Nutzer.
                is ShiftConfigDecodeResult.Broken -> {
                    backupBrokenConfig(decoded.raw)
                    throw AppError.DataStoreError(
                        message = "Schicht-Konfiguration ist nicht lesbar (Sicherung unter '$BROKEN_CONFIG_KEY_NAME')",
                        cause = decoded.cause
                    )
                }

                ShiftConfigDecodeResult.NotConfigured -> ShiftConfig.getDefaultConfig()

                // Zweites Netz zum Gate oben: gaebe der UserManager zwischen Gate und Read
                // "gesperrt" zurueck, waere "leer" wieder keine Aussage. Auch hier KEIN
                // Default als Erfolg.
                ShiftConfigDecodeResult.LockedStorage -> throw AppError.DataStoreError(
                    message = "Schicht-Konfiguration vor der ersten Entsperrung nicht lesbar " +
                        "(CREDENTIAL-ENCRYPTED Storage) - es wird KEIN Default gemeldet"
                )
            }

            cachedConfig = config
            cacheTimestamp = currentTime

            Logger.d(LogTags.SHIFT_CONFIG, "✅ SINGLETON-FRESH-LOAD: Config loaded with ${config.definitions.size} definitions and cached")
            config
        }
    
    override suspend fun resetToDefaults(): Result<Unit> = 
        SafeExecutor.safeExecute("ShiftConfigRepository.resetToDefaults") {
            val defaultConfig = ShiftConfig.getDefaultConfig()
            val jsonString = json.encodeToString(defaultConfig)
            
            dataStore.edit { preferences ->
                preferences[shiftConfigKey] = jsonString
            }
            
            cachedConfig = defaultConfig
            cacheTimestamp = System.currentTimeMillis()
            
            Logger.d(LogTags.SHIFT_CONFIG, "✅ SINGLETON-RESET: Shift config reset to defaults and cache updated")
        }
}
