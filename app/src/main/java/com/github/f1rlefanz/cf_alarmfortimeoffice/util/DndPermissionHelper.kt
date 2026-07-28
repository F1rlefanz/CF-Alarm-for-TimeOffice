package com.github.f1rlefanz.cf_alarmfortimeoffice.util

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings

/**
 * Freigabe-Helfer fuer "Nicht stoeren" (ACCESS_NOTIFICATION_POLICY, bereits im Manifest deklariert).
 * Kein Pflicht-Onboarding-Gate wie [BatteryOptimizationHelper] - DND ist ein optionales Feature,
 * die Freigabe wird beim ersten Aktivieren eines der beiden Schalter in den DND-Einstellungen
 * angestossen. Deshalb auch keine Dismiss-Persistenz noetig.
 *
 * Nur ab Android 11 (API 30) unterstuetzt (siehe [com.github.f1rlefanz.cf_alarmfortimeoffice.dnd.DndScheduleUseCase.isSupported]) -
 * [isFeatureSupported] spiegelt dieselbe Grenze fuer die UI (Status-Card/Einstellungen zeigen einen
 * Fallback-Text statt eines funktionslosen Schalters).
 */
object DndPermissionHelper {

    /** Ob DND auf diesem Geraet ueberhaupt angeboten wird (API-Gate, unabhaengig von der Freigabe). */
    fun isFeatureSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    /** Ob der Nutzer den Benachrichtigungszugriff bereits erteilt hat. */
    fun isGranted(context: Context): Boolean {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return nm.isNotificationPolicyAccessGranted
    }

    /**
     * Oeffnet die System-Einstellungsseite fuer den Benachrichtigungszugriff. Anders als bei der
     * Akku-Ausnahme ([BatteryOptimizationHelper.requestExemption]) unterstuetzt
     * [Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS] KEINEN `package:`-Deep-Link - Android
     * zeigt eine App-Liste, in der der Nutzer CFAlarm selbst suchen muss. Der aufrufende UI-Text
     * muss das vorwegnehmen, sonst wirkt es wie ein Bug.
     */
    fun requestAccess(context: Context) {
        val intent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
