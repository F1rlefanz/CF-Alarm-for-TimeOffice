package com.github.f1rlefanz.cf_alarmfortimeoffice.util

import android.app.Activity
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
import kotlinx.coroutines.flow.first

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

    // MIGRATION (Juli 2026): Die "hint shown"-Flags liegen im @MainDataStore ("settings")
    // statt in den alten "cf_alarm_prefs" SharedPreferences. Damit ist die dritte
    // "cf_alarm_prefs"-Insel vollständig aufgelöst (last_maintenance_time zog bereits in den
    // @MainDataStore um, siehe AlarmMaintenanceService).
    // BEWUSST kein Migrationscode für Altwerte – aktuell nutzt nur der Entwickler die App
    // (Projekt-Konvention). Im schlimmsten Fall erscheint ein Hinweis einmalig erneut.
    private const val KEY_OEM_HINT_SHOWN_PREFIX = "oem_hint_shown"
    private val KEY_BATTERY_PROMPT_DISMISSED = booleanPreferencesKey("battery_prompt_dismissed")

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
     * Checks if the given package is exempted from battery optimization. Defaults to this app's
     * own package; [TimeOfficeHealthHelper] reuses this with a foreign package name —
     * `PowerManager.isIgnoringBatteryOptimizations()` accepts any installed package, not just
     * the caller's own.
     */
    fun isExempted(context: Context, packageName: String = context.packageName): Boolean {
        // minSdk is 26, no need to check for M (23)
        return try {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            powerManager.isIgnoringBatteryOptimizations(packageName)
        } catch (e: Exception) {
            Logger.e(LogTags.BATTERY, "Failed to check battery exemption for $packageName", e)
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
     * Oeffnet die dontkillmyapp.com-Anleitung fuer den gegebenen OEM. War vorher in
     * [com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.OEMWarningScreen] und
     * [com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.tabs.SettingsTabContent]
     * fast wortgleich dupliziert.
     */
    fun openOEMHelpUrl(context: Context, oemType: OEMType) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, getOEMHelpURL(oemType).toUri())
            context.startActivity(intent)
        } catch (e: Exception) {
            Logger.e(LogTags.BATTERY, "Failed to open OEM help URL", e)
        }
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
     * True wenn der volle OEM-Warnscreen fuer diesen Typ noch nie gezeigt wurde UND das
     * Geraet ueberhaupt einen der bekannten aggressiven Hersteller hat. Einzige verbliebene
     * OEM-Hinweis-Logik (Konsolidierung Juli 2026): frueher gab es vier unabhaengige, teils
     * ungegatete Auslösepunkte (Dialog beim Landen auf dem Akku-Screen, vollflaechiger Screen
     * ungegatet, ein zweiter Dialog gegated, ein dritter OnePlus-spezifischer Dialog gegated) -
     * jetzt genau ein Weg über [NavigationState.OEMWarning], mit dieser Sperre.
     */
    suspend fun shouldNavigateToOemWarningScreen(context: Context, oemType: OEMType): Boolean {
        if (!shouldShowOEMWarning(oemType)) return false
        return mainDataStore(context).data.first()[oemHintKey(oemType)] != true
    }

    /** Markiert den OEM-Warnscreen fuer diesen Typ als gezeigt (einmalig, dauerhaft). */
    suspend fun markOemWarningScreenShown(context: Context, oemType: OEMType) {
        mainDataStore(context).edit { it[oemHintKey(oemType)] = true }
    }

    /**
     * Akku-Prompt-Skip: der Nutzer kann den Akku-Ausnahme-Screen mit "Spaeter" ueberspringen.
     * Persistiert (anders als frueher, wo ein reines Session-Flag den Prompt nach jedem
     * App-Neustart erneut zeigte) - gleiches Muster wie [UnusedAppRestrictionsHelper].
     */
    suspend fun isBatteryPromptDismissed(context: Context): Boolean =
        mainDataStore(context).data.first()[KEY_BATTERY_PROMPT_DISMISSED] ?: false

    suspend fun setBatteryPromptDismissed(context: Context) {
        mainDataStore(context).edit { it[KEY_BATTERY_PROMPT_DISMISSED] = true }
    }
}
