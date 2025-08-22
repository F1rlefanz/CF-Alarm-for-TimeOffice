package com.github.f1rlefanz.cf_alarmfortimeoffice.util

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings

object BatteryOptimizationHelper {
    
    private const val PREFS_NAME = "cf_alarm_prefs"
    private const val KEY_ONEPLUS_HINT_SHOWN = "oneplus_hint_shown"
    
    /**
     * Prüft ob es ein OnePlus-Gerät ist und zeigt einmalig einen Hinweis
     */
    fun checkAndShowHintIfNeeded(context: Context) {
        if (!isOnePlus()) return
        
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val hintShown = prefs.getBoolean(KEY_ONEPLUS_HINT_SHOWN, false)
        
        if (!hintShown) {
            showOnePlusHint(context)
            prefs.edit().putBoolean(KEY_ONEPLUS_HINT_SHOWN, true).apply()
        }
    }
    
    /**
     * Prüft ob das Gerät von OnePlus ist
     */
    fun isOnePlus(): Boolean {
        return Build.MANUFACTURER.equals("OnePlus", ignoreCase = true) ||
               Build.BRAND.equals("OnePlus", ignoreCase = true)
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
                    // Falls die Einstellungen nicht geöffnet werden können
                }
                dialog.dismiss()
            }
            .setCancelable(false)
            .show()
    }
}
