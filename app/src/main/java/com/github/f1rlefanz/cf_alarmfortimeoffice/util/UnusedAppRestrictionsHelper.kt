package com.github.f1rlefanz.cf_alarmfortimeoffice.util

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.core.content.PackageManagerCompat
import androidx.core.content.PackageManagerCompat.UnusedAppRestrictionsStatus
import androidx.core.content.UnusedAppRestrictionsConstants
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import com.github.f1rlefanz.cf_alarmfortimeoffice.di.qualifiers.MainDataStore
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Hilt EntryPoint that lets the plain [UnusedAppRestrictionsHelper] object reach the
 * Hilt-managed @MainDataStore instance — same pattern as [BatteryOptimizationHelperEntryPoint].
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface UnusedAppRestrictionsHelperEntryPoint {
    @MainDataStore
    fun mainDataStore(): DataStore<Preferences>
}

/**
 * Androids "App bei Nichtnutzung pausieren" (Unused App Restrictions / App Hibernation) killt
 * die App per Force-Stop, sobald sie eine Weile nicht geoeffnet wurde — dabei werden alle
 * gesetzten AlarmManager-Alarme lautlos aus dem System entfernt, ohne jede Log-Spur (der Prozess
 * ist ja gerade tot). Fuer eine Wecker-App, die absichtlich NICHT taeglich geoeffnet werden muss,
 * ist das ein reales Risiko (am 20.07.2026 live gegen ein Fairphone 6 nachgewiesen: Boot-Events
 * ohne echten Reboot, Alarm um 12:30 verschwunden). Es gibt keinen zuverlaessigen
 * Manifest-Opt-out (android:autoRevokePermissions betrifft nur den Permission-Teil) - der
 * einzige Hebel ist, den Nutzer aktiv zum Abschalten zu fuehren, analog zu
 * [BatteryOptimizationHelper].
 */
object UnusedAppRestrictionsHelper {

    private val KEY_DISMISSED = booleanPreferencesKey("unused_app_restrictions_dismissed")

    private fun mainDataStore(context: Context): DataStore<Preferences> =
        EntryPointAccessors
            .fromApplication(
                context.applicationContext,
                UnusedAppRestrictionsHelperEntryPoint::class.java
            )
            .mainDataStore()

    /**
     * Reine, testbare Zuordnung: welche Status-Werte bedeuten "die Einschraenkung ist aktiv,
     * Nutzer sollte gefragt werden"? Fail-open bei ERROR/FEATURE_NOT_AVAILABLE/DISABLED - eine
     * unbekannte oder nicht unterstuetzte Situation darf das Onboarding niemals blockieren.
     */
    fun needsPrompt(@UnusedAppRestrictionsStatus status: Int): Boolean = when (status) {
        UnusedAppRestrictionsConstants.API_30_BACKPORT,
        UnusedAppRestrictionsConstants.API_30,
        UnusedAppRestrictionsConstants.API_31 -> true
        else -> false
    }

    /**
     * Prueft, ob die Einschraenkung fuer diese App aktuell aktiv ist. Fail-open bei jedem
     * Fehler (fehlende API, Timeout, etc.) - siehe [BatteryOptimizationHelper.isExempted] fuer
     * dasselbe Prinzip.
     */
    suspend fun isRestricted(context: Context): Boolean = try {
        needsPrompt(getUnusedAppRestrictionsStatusSuspend(context))
    } catch (e: kotlinx.coroutines.CancellationException) {
        // Rethrow for proper structured concurrency - callers run this in a Compose-tied
        // scope (LaunchedEffect/rememberCoroutineScope) that cancels it whenever the user
        // leaves the screen mid-check. That's normal lifecycle behavior, not a real error.
        Logger.d(LogTags.UNUSED_APP_RESTRICTIONS, "Unused-app-restrictions check cancelled (left composition)")
        throw e
    } catch (e: Exception) {
        Logger.e(LogTags.UNUSED_APP_RESTRICTIONS, "Failed to check unused-app-restrictions status", e)
        false
    }

    /** Hat der Nutzer diesen Onboarding-Schritt bereits uebersprungen? Persistiert. */
    suspend fun isDismissed(context: Context): Boolean =
        mainDataStore(context).readOrEmpty(LogTags.UNUSED_APP_RESTRICTIONS, "Unused-App-Merker")[KEY_DISMISSED] ?: false

    suspend fun setDismissed(context: Context) {
        mainDataStore(context).edit { it[KEY_DISMISSED] = true }
    }

    /**
     * Deep-Link auf die richtige Einstellungsseite - waehlt intern ACTION_AUTO_REVOKE_PERMISSIONS
     * (API 30) vs. ACTION_APPLICATION_DETAILS_SETTINGS (API 31+). Anders als Akku-Ausnahme KEIN
     * Ein-Klick-Dialog, sondern eine echte Mehrschritt-Einstellungsseite - der Nutzer muss den
     * Schalter dort selbst umlegen.
     */
    fun createSettingsIntent(context: Context): Intent =
        IntentCompat.createManageUnusedAppRestrictionsIntent(context, context.packageName)

    /**
     * ListenableFuture -> suspend Bruecke. Guava liegt bereits transitiv im Klassenpfad (u.a.
     * ueber Hilt/Dagger), daher reicht die Interface-API (addListener/get) - kein
     * kotlinx-coroutines-guava noetig.
     */
    private suspend fun getUnusedAppRestrictionsStatusSuspend(context: Context): Int =
        suspendCancellableCoroutine { cont ->
            val future = PackageManagerCompat.getUnusedAppRestrictionsStatus(context)
            future.addListener(
                {
                    try {
                        cont.resumeWith(Result.success(future.get()))
                    } catch (e: Exception) {
                        cont.resumeWith(Result.failure(e))
                    }
                },
                ContextCompat.getMainExecutor(context)
            )
            cont.invokeOnCancellation { future.cancel(true) }
        }
}
