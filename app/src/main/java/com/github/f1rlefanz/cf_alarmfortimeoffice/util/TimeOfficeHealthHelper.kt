package com.github.f1rlefanz.cf_alarmfortimeoffice.util

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
 * Hilt EntryPoint that lets the plain [TimeOfficeHealthHelper] object reach the Hilt-managed
 * @MainDataStore instance — same pattern as [BatteryOptimizationHelperEntryPoint] /
 * [UnusedAppRestrictionsHelperEntryPoint].
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface TimeOfficeHealthHelperEntryPoint {
    @MainDataStore
    fun mainDataStore(): DataStore<Preferences>
}

/**
 * CFAlarms gesamte Funktion haengt an einer Kette, die ausserhalb dieser App beginnt: TimeOffice
 * (de.pradtke.timeoffice) schreibt den Dienstplan lokal in einen Google-Kalender
 * ("Timeoffice Dienstplanfeed"), aus dem CFAlarm dann Alarme baut. Live am 30.07.2026 nachgewiesen
 * (Fairphone 6): TimeOffice selbst war von "App bei Nichtnutzung pausieren" (aktiv) UND
 * Akku-Optimierung "Optimiert" betroffen — der Dienstplan-Sync blieb tagelang stehen, obwohl
 * TimeOffice die Krankschreibung laengst intern kannte. Analog zu [BatteryOptimizationHelper] /
 * [UnusedAppRestrictionsHelper], nur fuer ein FREMDES Package.
 *
 * WICHTIGE EINSCHRAENKUNG: Es gibt keine oeffentliche API, um den "Nicht verwendete Apps"/
 * Hibernation-Status einer ANDEREN App abzufragen (`PackageManagerCompat.getUnusedAppRestrictionsStatus()`
 * arbeitet nur fuer die aufrufende App selbst). Nur die Akku-Optimierungs-Ausnahme ist fuer fremde
 * Pakete prüfbar (`PowerManager.isIgnoringBatteryOptimizations(anderesPackage)` akzeptiert jeden
 * Package-Namen). Deshalb bewusst KEIN `isHibernated()`/aehnliche Funktion hier — ein
 * vorgetaeuschter Status waere schlimmer als gar keiner.
 *
 * Statt des riskanten `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`-System-Dialogs fuer ein
 * fremdes Package (nicht dokumentiert, ob Android das ueberhaupt zulaesst) fuehrt der einzige
 * Aktions-Weg hier direkt auf TimeOffices eigene App-Info-Seite — dort liegen beide relevanten
 * Schalter nebeneinander, und genau dieser Weg wurde am 30.07.2026 live erfolgreich manuell
 * begangen.
 */
object TimeOfficeHealthHelper {

    const val TIMEOFFICE_PACKAGE = "de.pradtke.timeoffice"

    private val KEY_PROMPT_DISMISSED = booleanPreferencesKey("timeoffice_health_prompt_dismissed")

    private fun mainDataStore(context: Context): DataStore<Preferences> =
        EntryPointAccessors
            .fromApplication(
                context.applicationContext,
                TimeOfficeHealthHelperEntryPoint::class.java
            )
            .mainDataStore()

    /**
     * Ist TimeOffice ueberhaupt installiert? Braucht die `<queries>`-Deklaration in
     * AndroidManifest.xml (Android 11+ Package-Visibility) — ohne sie liefert
     * `getPackageInfo()` fuer ein fremdes Package immer `NameNotFoundException`, egal ob
     * installiert oder nicht.
     */
    fun isInstalled(context: Context): Boolean = try {
        context.packageManager.getPackageInfo(TIMEOFFICE_PACKAGE, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    } catch (e: Exception) {
        Logger.e(LogTags.TIMEOFFICE_HEALTH, "Failed to check if TimeOffice is installed", e)
        false
    }

    /** Ist TimeOffice von der Akku-Optimierung ausgenommen? Delegiert an [BatteryOptimizationHelper]. */
    fun isBatteryExempted(context: Context): Boolean =
        BatteryOptimizationHelper.isExempted(context, TIMEOFFICE_PACKAGE)

    /** Deep-Link auf TimeOffices eigene App-Info-Seite — dort liegen Akku- UND Nichtnutzungs-Schalter. */
    fun createAppInfoIntent(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = "package:$TIMEOFFICE_PACKAGE".toUri()
        }

    /** Hat der Nutzer diesen Onboarding-Schritt bereits uebersprungen? Persistiert. */
    suspend fun isPromptDismissed(context: Context): Boolean =
        mainDataStore(context).data.first()[KEY_PROMPT_DISMISSED] ?: false

    suspend fun setPromptDismissed(context: Context) {
        mainDataStore(context).edit { it[KEY_PROMPT_DISMISSED] = true }
    }
}
