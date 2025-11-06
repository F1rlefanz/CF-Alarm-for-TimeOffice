package com.github.f1rlefanz.cf_alarmfortimeoffice.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags

/**
 * AlarmSoundService - Dedicated Foreground Service for Alarm Audio Management
 * 
 * ARCHITECTURE:
 * - Manages MediaPlayer lifecycle independent of Activity lifecycle
 * - Handles vibration patterns for alarm notifications
 * - Provides foreground notification while alarm is active
 * - Guarantees cleanup on service destruction
 * 
 * LIFECYCLE:
 * - START_STICKY: Auto-restart if killed by system (Android behavior)
 * - Foreground: No background execution limits, reliable alarm execution
 * - onDestroy: Guaranteed cleanup hook for all resources
 * - onTaskRemoved: Handles task killer apps gracefully
 * 
 * CRITICAL FEATURES:
 * - isShuttingDown flag prevents MediaPlayer race conditions
 * - OnPreparedListener checks shutdown state before starting playback
 * - Defensive cleanup in multiple lifecycle hooks
 * - Foreground service ensures Android doesn't kill during alarm
 * 
 * FIXES SNOOZE BUG:
 * Previous issue: MediaPlayer.prepareAsync() completed AFTER Activity.finish()
 * Solution: Service-managed MediaPlayer with shutdown flag guards
 * 
 * @author CF-Alarm Development Team
 * @since 1.4.4 - Snooze Bug Fix Release
 */
class AlarmSoundService : Service() {
    
    companion object {
        // Service Actions
        const val ACTION_START_ALARM = "com.github.f1rlefanz.cf_alarmfortimeoffice.START_ALARM"
        const val ACTION_STOP_ALARM = "com.github.f1rlefanz.cf_alarmfortimeoffice.STOP_ALARM"
        const val ACTION_SNOOZE_ALARM = "com.github.f1rlefanz.cf_alarmfortimeoffice.SNOOZE_ALARM"
        
        // Intent Extras
        const val EXTRA_SHIFT_NAME = "shift_name"
        const val EXTRA_ALARM_ID = "alarm_id"
        
        // Notification Configuration
        private const val NOTIFICATION_ID = 2002
        private const val CHANNEL_ID = "alarm_sound_service"
        private const val CHANNEL_NAME = "Alarm Sound Service"
    }
    
    // Service Binding
    private val binder = AlarmSoundBinder()
    
    // Audio Management
    private var alarmMediaPlayer: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    
    // Shutdown Flag - CRITICAL for preventing race conditions
    // Set to true when stopping service to prevent async callbacks from starting sound
    @Volatile
    private var isShuttingDown = false
    
    /**
     * Binder class for Activity to connect to this Service
     */
    inner class AlarmSoundBinder : Binder() {
        fun getService(): AlarmSoundService = this@AlarmSoundService
    }
    
    override fun onBind(intent: Intent?): IBinder {
        Logger.d(LogTags.ALARM, "📎 AlarmSoundService bound")
        return binder
    }
    
    override fun onUnbind(intent: Intent?): Boolean {
        Logger.d(LogTags.ALARM, "📎 AlarmSoundService unbound")
        return super.onUnbind(intent)
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_ALARM -> {
                val shiftName = intent.getStringExtra(EXTRA_SHIFT_NAME) ?: "Alarm"
                val alarmId = intent.getIntExtra(EXTRA_ALARM_ID, -1)
                
                Logger.business(
                    LogTags.ALARM,
                    "🔊 AlarmSoundService START_ALARM",
                    "Shift: $shiftName, ID: $alarmId"
                )
                
                // Reset shutdown flag for new alarm
                isShuttingDown = false
                
                // Create notification channel (idempotent, safe to call multiple times)
                createNotificationChannel()
                
                // Start as foreground service (Android 8+ requirement)
                val notification = createForegroundNotification(shiftName)
                startForeground(NOTIFICATION_ID, notification)
                Logger.d(LogTags.ALARM, "✅ Foreground service started with notification")
                
                // Start alarm sound and vibration
                startAlarmSound()
                startVibration()
            }
            
            ACTION_STOP_ALARM, ACTION_SNOOZE_ALARM -> {
                Logger.i(LogTags.ALARM, "🛑 AlarmSoundService STOP/SNOOZE_ALARM")
                
                // Stop all alarm effects immediately
                stopAlarmSound()
                stopVibration()
                
                // Stop foreground and remove notification
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } else {
                    @Suppress("DEPRECATION")
                    stopForeground(true)
                }
                
                // Stop the service
                stopSelf()
                Logger.d(LogTags.ALARM, "✅ AlarmSoundService stopped and cleaned up")
            }
            
            else -> {
                Logger.w(LogTags.ALARM, "⚠️ AlarmSoundService received unknown action: ${intent?.action}")
            }
        }
        
        // START_STICKY: System will restart service if killed (with null intent)
        return START_STICKY
    }
    
    override fun onDestroy() {
        super.onDestroy()
        Logger.i(LogTags.ALARM, "🛑 AlarmSoundService destroying - guaranteed cleanup")
        
        // Guaranteed cleanup on service destruction
        stopAlarmSound()
        stopVibration()
    }
    
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Logger.w(LogTags.ALARM, "⚠️ Task removed - stopping AlarmSoundService")
        
        // Handle task killer apps by stopping service
        stopSelf()
    }
    
    /**
     * Starts alarm sound using MediaPlayer
     * 
     * CRITICAL: Uses isShuttingDown flag to prevent race conditions
     * OnPreparedListener checks flag before starting playback
     */
    private fun startAlarmSound() {
        // Early exit if service is shutting down
        if (isShuttingDown) {
            Logger.w(LogTags.ALARM, "🚫 Service shutting down, not starting sound")
            return
        }
        
        try {
            // Get alarm sound URI with fallbacks
            val alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            
            if (alarmUri == null) {
                Logger.w(LogTags.ALARM, "⚠️ No alarm URIs available for MediaPlayer")
                return
            }
            
            Logger.d(LogTags.ALARM, "🎵 Starting MediaPlayer with URI: $alarmUri")
            
            // Create and configure MediaPlayer
            alarmMediaPlayer = MediaPlayer().apply {
                setDataSource(this@AlarmSoundService, alarmUri)
                
                // Configure audio attributes for alarm
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                
                // Enable looping
                isLooping = true
                
                // CRITICAL: OnPreparedListener with shutdown check
                setOnPreparedListener { player ->
                    // Check shutdown flag BEFORE starting playback
                    if (!isShuttingDown) {
                        try {
                            player.start()
                            Logger.business(
                                LogTags.ALARM,
                                "✅ MediaPlayer started successfully in service"
                            )
                        } catch (e: Exception) {
                            Logger.e(LogTags.ALARM, "❌ MediaPlayer start failed", e)
                        }
                    } else {
                        // Service is shutting down - release player immediately
                        Logger.d(
                            LogTags.ALARM,
                            "🚫 Shutdown flag set, not starting player - releasing"
                        )
                        try {
                            player.release()
                        } catch (e: Exception) {
                            Logger.e(LogTags.ALARM, "Error releasing player on shutdown", e)
                        }
                    }
                }
                
                // Error handler
                setOnErrorListener { _, what, extra ->
                    Logger.e(
                        LogTags.ALARM,
                        "❌ MediaPlayer error: what=$what, extra=$extra"
                    )
                    false // Return false to trigger onCompletion
                }
                
                // Start async preparation
                prepareAsync()
                Logger.d(LogTags.ALARM, "🔄 MediaPlayer preparing asynchronously")
            }
            
        } catch (e: Exception) {
            Logger.e(LogTags.ALARM, "❌ Failed to start alarm sound in service", e)
        }
    }
    
    /**
     * Stops alarm sound and releases MediaPlayer
     * 
     * CRITICAL: Sets isShuttingDown flag FIRST to prevent race conditions
     */
    private fun stopAlarmSound() {
        // CRITICAL: Set shutdown flag FIRST to prevent async callbacks from starting sound
        isShuttingDown = true
        
        try {
            alarmMediaPlayer?.let { player ->
                try {
                    // Stop playback if playing
                    if (player.isPlaying) {
                        player.stop()
                        Logger.d(LogTags.ALARM, "🔇 MediaPlayer stopped")
                    }
                } catch (e: Exception) {
                    Logger.w(LogTags.ALARM, "⚠️ Error stopping MediaPlayer", e)
                }
                
                try {
                    // Release resources
                    player.release()
                    Logger.d(LogTags.ALARM, "♻️ MediaPlayer released")
                } catch (e: Exception) {
                    Logger.e(LogTags.ALARM, "❌ Error releasing MediaPlayer", e)
                }
            }
            
            alarmMediaPlayer = null
            Logger.i(LogTags.ALARM, "✅ MediaPlayer stopped and released successfully")
            
        } catch (e: Exception) {
            Logger.e(LogTags.ALARM, "❌ Critical error stopping MediaPlayer", e)
        }
    }
    
    /**
     * Starts vibration pattern for alarm
     */
    private fun startVibration() {
        try {
            // Get vibrator using appropriate API for Android version
            vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Android 12+ (API 31): Use VibratorManager
                val vibratorManager = getSystemService(VibratorManager::class.java)
                vibratorManager.defaultVibrator
            } else {
                // Pre-Android 12: Use legacy Vibrator service
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            
            // Enhanced vibration pattern for alarm (in milliseconds)
            // Pattern: [delay, vibrate, pause, vibrate, pause, ...]
            val alarmVibrationPattern = longArrayOf(
                0,    // Start immediately
                1000, // Vibrate for 1 second
                300,  // Pause 300ms
                800,  // Vibrate for 800ms
                300,  // Pause 300ms
                1000, // Vibrate for 1 second
                500,  // Pause 500ms
                500   // Short vibration
            )
            
            vibrator?.let { vib ->
                if (vib.hasVibrator()) {
                    // Create waveform effect that repeats from index 1
                    val vibrationEffect = VibrationEffect.createWaveform(
                        alarmVibrationPattern,
                        1 // Repeat from index 1 (loops the pattern)
                    )
                    vib.vibrate(vibrationEffect)
                    Logger.business(LogTags.ALARM, "✅ Vibration started in service")
                } else {
                    Logger.d(LogTags.ALARM, "📴 Device has no vibrator capability")
                }
            } ?: run {
                Logger.w(LogTags.ALARM, "⚠️ Could not obtain vibrator service")
            }
            
        } catch (e: Exception) {
            Logger.e(LogTags.ALARM, "❌ Failed to start vibration in service", e)
        }
    }
    
    /**
     * Stops vibration
     */
    private fun stopVibration() {
        try {
            vibrator?.cancel()
            vibrator = null
            Logger.d(LogTags.ALARM, "✅ Vibration stopped")
        } catch (e: Exception) {
            Logger.e(LogTags.ALARM, "❌ Error stopping vibration", e)
        }
    }
    
    /**
     * Creates notification channel for foreground service
     * This is idempotent - safe to call multiple times
     */
    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_LOW // Low importance, we handle the alarm sound
        ).apply {
            description = "Shows when alarm is actively playing"
            // Silent channel - sound is handled by MediaPlayer
            setSound(null, null)
            enableVibration(false) // Vibration handled separately
        }
        
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
        
        Logger.d(LogTags.ALARM, "📢 Notification channel created: $CHANNEL_ID")
    }
    
    /**
     * Creates foreground notification for service
     */
    private fun createForegroundNotification(shiftName: String): android.app.Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Alarm aktiv")
            .setContentText("$shiftName läuft")
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true) // Cannot be dismissed by user
            .setShowWhen(true)
            .build()
    }
}
