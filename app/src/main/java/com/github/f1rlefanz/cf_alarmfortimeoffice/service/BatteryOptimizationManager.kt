package com.github.f1rlefanz.cf_alarmfortimeoffice.service

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags

/**
 * Simplified Battery Optimization Manager - Focus on reliable core functionality.
 *
 * REFACTORED: Removed complex OnePlus-specific configuration tracking and monitoring.
 * This version focuses on basic battery optimization checks that work across all devices.
 *
 * Core Features:
 * - Basic battery optimization status check
 * - Simple device type detection
 * - Intent to open battery optimization settings
 *
 * Philosophy: If the basic functionality works (and it does!), keep it simple.
 */
class BatteryOptimizationManager(private val context: Context) {

    companion object {
        /**
         * Simple check if device is OnePlus
         */
        fun isOnePlusDevice(): Boolean {
            return Build.MANUFACTURER.equals("OnePlus", ignoreCase = true)
        }
    }

    /**
     * Check if app is exempted from battery optimization
     */
    fun isIgnoringBatteryOptimizations(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                powerManager.isIgnoringBatteryOptimizations(context.packageName)
            } catch (e: Exception) {
                Logger.e(
                    LogTags.BATTERY_OPTIMIZATION,
                    "❌ Error checking battery optimization status",
                    e
                )
                false
            }
        } else {
            true // Older Android versions don't have battery optimization
        }
    }

    /**
     * Create intent to open battery optimization settings
     */
    fun createBatteryOptimizationIntent(): Intent? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:${context.packageName}")
                }
            } else {
                null // Not needed on older Android versions
            }
        } catch (e: Exception) {
            Logger.e(
                LogTags.BATTERY_OPTIMIZATION,
                "❌ Error creating battery optimization intent",
                e
            )
            null
        }
    }

    /**
     * Simple status check for basic information
     */
    fun getBasicStatus(): String {
        val isOptimized = isIgnoringBatteryOptimizations()
        val deviceType = if (isOnePlusDevice()) "OnePlus" else "Standard"

        return "Device: $deviceType, Battery Optimization: ${if (isOptimized) "Disabled" else "Enabled"}"
    }
}
