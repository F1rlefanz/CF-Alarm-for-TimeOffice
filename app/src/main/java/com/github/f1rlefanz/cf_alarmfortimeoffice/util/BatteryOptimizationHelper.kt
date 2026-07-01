package com.github.f1rlefanz.cf_alarmfortimeoffice.util

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.net.toUri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import com.github.f1rlefanz.cf_alarmfortimeoffice.di.qualifiers.MainDataStore
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Hilt EntryPoint that lets the plain [BatteryOptimizationHelper] object reach the
 * Hilt-managed @MainDataStore instance (mirrors the pattern in HueSmartScheduler /
 * HueBridgeConnectionManager — a getInstance()/object-style singleton that pulls its
 * Hilt dependencies through an access point instead of being @Inject-constructed).
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface BatteryOptimizationHelperEntryPoint {
    @MainDataStore
    fun mainDataStore(): DataStore<Preferences>
}

/**
 * Battery Optimization Helper with OEM-specific detection
 *
 * FEATURES:
 * - Battery exemption check and request
 * - OEM-specific detection (Xiaomi, OnePlus, Samsung, etc.)
 * - Educational dialogs for users
 * - Direct links to dontkillmyapp.com guides
 */
object BatteryOptimizationHelper {

    // MIGRATION (Juli 2026): Die beiden "hint shown"-Flags liegen jetzt im @MainDataStore
    // ("settings") statt in den alten "cf_alarm_prefs" SharedPreferences. Damit ist die
    // dritte "cf_alarm_prefs"-Insel vollständig aufgelöst (last_maintenance_time zog bereits
    // in den @MainDataStore um, siehe AlarmMaintenanceService).
    // BEWUSST kein Migrationscode für die Altwerte – aktuell nutzt nur der Entwickler die
    // App (Projekt-Konvention). Im schlimmsten Fall erscheint ein Hinweis einmalig erneut.
    private val KEY_ONEPLUS_HINT_SHOWN = booleanPreferencesKey("oneplus_hint_shown")
    private const val KEY_OEM_HINT_SHOWN_PREFIX = "oem_hint_shown"

    // Request code for battery exemption activity result
    const val REQUEST_CODE_BATTERY_EXEMPTION = 1001

    /**
     * Resolves the Hilt-managed @MainDataStore via [BatteryOptimizationHelperEntryPoint].
     */
    private fun mainDataStore(context: Context): DataStore<Preferences> =
        EntryPointAccessors
            .fromApplication(
                context.applicationContext,
                BatteryOptimizationHelperEntryPoint::class.java
            )
            .mainDataStore()

    /** Per-OEM key so each manufacturer's warning is shown only once. */
    private fun oemHintKey(oemType: OEMType) =
        booleanPreferencesKey("${KEY_OEM_HINT_SHOWN_PREFIX}_${oemType.name}")
    
    /**
     * OEM manufacturer types
     */
    enum class OEMType {
        STANDARD,
        XIAOMI,     // Very aggressive
        ONEPLUS,    // Aggressive
        SAMSUNG,    // Moderate
        HUAWEI,     // Extremely aggressive
        OPPO,       // Very aggressive
        VIVO,       // Very aggressive
        REALME      // Aggressive
    }
    
    /**
     * Checks if app is exempted from battery optimization
     */
    fun isExempted(context: Context): Boolean {
        // minSdk is 26, no need to check for M (23)
        return try {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            powerManager.isIgnoringBatteryOptimizations(context.packageName)
        } catch (e: Exception) {
            Logger.e(LogTags.BATTERY, "Failed to check battery exemption", e)
            false
        }
    }
    
    /**
     * Requests battery exemption from user with result callback
     * @param activity Activity to launch permission request
     * @param onResult Callback with result after user action
     */
    fun requestExemption(activity: Activity, onResult: ((Boolean) -> Unit)? = null) {
        // Das tatsächliche Ergebnis wird unten per postDelayed + isExempted() geprüft und
        // via onResult zurückgegeben; ein persistiertes "pending"-Flag war write-only (nie
        // gelesen) und entfiel mit der cf_alarm_prefs-Auflösung.
        try {
            @Suppress("BatteryLife")
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = "package:${activity.packageName}".toUri()
            }
            
            activity.startActivityForResult(intent, REQUEST_CODE_BATTERY_EXEMPTION)
            
            // Schedule a check after returning from settings
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                val isExempted = isExempted(activity)
                onResult?.invoke(isExempted)
            }, 500) // Small delay to ensure settings are applied
            
            Logger.d(LogTags.BATTERY, "Battery exemption request launched")
            
        } catch (e: Exception) {
            Logger.e(LogTags.BATTERY, "Failed to request battery exemption, opening settings", e)
            
            // Fallback: Open battery optimization settings
            try {
                val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                activity.startActivity(intent)
                
                // Schedule check for fallback case
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    val isExempted = isExempted(activity)
                    onResult?.invoke(isExempted)
                }, 1000)
            } catch (e2: Exception) {
                Logger.e(LogTags.BATTERY, "Failed to open battery settings", e2)
                onResult?.invoke(false)
            }
        }
    }
    
    /**
     * Detects OEM manufacturer type
     */
    fun getOEMType(): OEMType {
        val manufacturer = Build.MANUFACTURER.lowercase()
        
        return when {
            "xiaomi" in manufacturer -> OEMType.XIAOMI
            "oneplus" in manufacturer -> OEMType.ONEPLUS
            "samsung" in manufacturer -> OEMType.SAMSUNG
            "huawei" in manufacturer -> OEMType.HUAWEI
            "oppo" in manufacturer -> OEMType.OPPO
            "vivo" in manufacturer -> OEMType.VIVO
            "realme" in manufacturer -> OEMType.REALME
            else -> OEMType.STANDARD
        }
    }
    
    /**
     * Returns dontkillmyapp.com URL for specific OEM
     */
    fun getOEMHelpURL(oemType: OEMType): String {
        val oemName = when (oemType) {
            OEMType.XIAOMI -> "xiaomi"
            OEMType.ONEPLUS -> "oneplus"
            OEMType.SAMSUNG -> "samsung"
            OEMType.HUAWEI -> "huawei"
            OEMType.OPPO -> "oppo"
            OEMType.VIVO -> "vivo"
            OEMType.REALME -> "realme"
            OEMType.STANDARD -> return "https://dontkillmyapp.com"
        }
        
        return "https://dontkillmyapp.com/$oemName"
    }
    
    /**
     * Checks if OEM-specific warning should be shown
     */
    fun shouldShowOEMWarning(oemType: OEMType): Boolean {
        return oemType != OEMType.STANDARD
    }
    
    /**
     * Gets localized OEM name for display
     */
    fun getOEMDisplayName(oemType: OEMType): String {
        return when (oemType) {
            OEMType.XIAOMI -> "Xiaomi/MIUI"
            OEMType.ONEPLUS -> "OnePlus"
            OEMType.SAMSUNG -> "Samsung"
            OEMType.HUAWEI -> "Huawei/EMUI"
            OEMType.OPPO -> "Oppo/ColorOS"
            OEMType.VIVO -> "Vivo/FuntouchOS"
            OEMType.REALME -> "Realme/RealmeUI"
            OEMType.STANDARD -> "Standard"
        }
    }
    
    /**
     * Gets OEM-specific aggressiveness level description
     */
    fun getOEMAggressivenessDescription(oemType: OEMType): String {
        return when (oemType) {
            OEMType.XIAOMI -> "Sehr aggressives Energiemanagement"
            OEMType.ONEPLUS -> "Aggressives Energiemanagement"
            OEMType.SAMSUNG -> "Moderates Energiemanagement"
            OEMType.HUAWEI -> "Extrem aggressives Energiemanagement"
            OEMType.OPPO -> "Sehr aggressives Energiemanagement"
            OEMType.VIVO -> "Sehr aggressives Energiemanagement"
            OEMType.REALME -> "Aggressives Energiemanagement"
            OEMType.STANDARD -> "Standard Android"
        }
    }
    
    /**
     * Prüft ob es ein OnePlus-Gerät ist und zeigt einmalig einen Hinweis.
     *
     * Das "shown"-Flag wird jetzt asynchron aus dem @MainDataStore gelesen/geschrieben,
     * daher suspend. Aufrufer starten dies aus einem Coroutine-Kontext (z.B.
     * lifecycleScope in der MainActivity). Der Dialog wird auf dem Main-Thread gezeigt.
     */
    suspend fun checkAndShowHintIfNeeded(context: Context) {
        if (!isOnePlus()) return

        val dataStore = mainDataStore(context)
        val hintShown = dataStore.data.first()[KEY_ONEPLUS_HINT_SHOWN] ?: false

        if (!hintShown) {
            withContext(Dispatchers.Main) { showOnePlusHint(context) }
            dataStore.edit { it[KEY_ONEPLUS_HINT_SHOWN] = true }
        }
    }
    
    /**
     * Prüft ob das Gerät von OnePlus ist
     */
    fun isOnePlus(): Boolean {
        return getOEMType() == OEMType.ONEPLUS
    }
    
    /**
     * Zeigt den einmaligen OnePlus-Hinweis-Dialog
     */
    private fun showOnePlusHint(context: Context) {
        AlertDialog.Builder(context)
            .setTitle("⚠️ OnePlus-Gerät erkannt")
            .setMessage(
                "OnePlus-Geräte haben aggressives Energiemanagement.\n\n" +
                "Für zuverlässige Alarme:\n" +
                "• Einstellungen → Akku → Akku-Optimierung\n" +
                "• CF-Alarm suchen → \"Nicht optimieren\"\n\n" +
                "Dieser Hinweis erscheint nur einmal."
            )
            .setPositiveButton("Verstanden") { dialog, _ ->
                dialog.dismiss()
            }
            .setNeutralButton("Zu Einstellungen") { dialog, _ ->
                try {
                    val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    context.startActivity(intent)
                } catch (e: Exception) {
                    Logger.e(LogTags.BATTERY, "Failed to open battery settings", e)
                }
                dialog.dismiss()
            }
            .setCancelable(false)
            .show()
    }
    
    /**
     * Shows OEM-specific warning dialog (einmalig pro OEM-Typ).
     *
     * Wie [checkAndShowHintIfNeeded] liegt das "shown"-Flag jetzt im @MainDataStore,
     * daher suspend. Das Flag wird direkt nach dem Anzeigen gesetzt (statt wie früher
     * im OnDismiss-Listener), damit der Schreibvorgang im Coroutine-Kontext bleibt –
     * "gezeigt" heißt hier "dem Nutzer eingeblendet".
     */
    suspend fun showOEMWarningDialog(context: Context, oemType: OEMType) {
        val dataStore = mainDataStore(context)
        val hintKey = oemHintKey(oemType)
        val hintShown = dataStore.data.first()[hintKey] ?: false

        if (hintShown) return

        val oemName = getOEMDisplayName(oemType)
        val helpUrl = getOEMHelpURL(oemType)

        withContext(Dispatchers.Main) {
            AlertDialog.Builder(context)
                .setTitle("⚠️ $oemName-Gerät")
                .setMessage(
                    "${getOEMAggressivenessDescription(oemType)}\n\n" +
                    "Für zuverlässige Hintergrund-Alarme befolgen Sie bitte die herstellerspezifischen " +
                    "Schritte in unserer detaillierten Anleitung.\n\n" +
                    "Die App-Berechtigung alleine reicht möglicherweise nicht aus."
                )
                .setPositiveButton("Anleitung öffnen") { dialog, _ ->
                    try {
                        val intent = Intent(Intent.ACTION_VIEW, helpUrl.toUri())
                        context.startActivity(intent)
                    } catch (e: Exception) {
                        Logger.e(LogTags.BATTERY, "Failed to open OEM help URL", e)
                    }
                    dialog.dismiss()
                }
                .setNegativeButton("Später") { dialog, _ ->
                    dialog.dismiss()
                }
                .show()
        }

        dataStore.edit { it[hintKey] = true }
    }
}
