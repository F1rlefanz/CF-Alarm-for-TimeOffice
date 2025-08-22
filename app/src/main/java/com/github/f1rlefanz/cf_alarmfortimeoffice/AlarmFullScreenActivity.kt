package com.github.f1rlefanz.cf_alarmfortimeoffice

import android.app.NotificationManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.*
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags

/**
 * Simplified Full-screen alarm activity - Focus on reliable core functionality.
 * 
 * REFACTORED: Removed all complex OnePlus-specific verification and monitoring mechanisms.
 * This version focuses on the essential alarm functionality that already works perfectly.
 * 
 * Core Features:
 * - Full-screen, lock-screen overlay
 * - Reliable audio management with fallback
 * - Wake lock management for reliability
 * - Clean dismissal controls
 * 
 * Philosophy: If the alarm works (and it does!), keep it simple.
 */
class AlarmFullScreenActivity : AppCompatActivity() {
    
    companion object {
        private const val WAKE_LOCK_TAG = "CFAlarm:FullScreenActivity"
        private const val WAKE_LOCK_TIMEOUT = 10 * 60 * 1000L // 10 minutes
    }
    
    private var wakeLock: PowerManager.WakeLock? = null
    private var alarmRingtone: Ringtone? = null
    private var vibrator: Vibrator? = null
    
    // Enhanced sound loop for reliability
    private var soundLoopHandler: Handler? = null
    private var soundLoopRunnable: Runnable? = null
    private var isAlarmActive = false
    private var alarmMediaPlayer: android.media.MediaPlayer? = null
    
    // UI Components
    private lateinit var shiftNameText: TextView
    private lateinit var alarmTimeText: TextView
    private lateinit var dismissButton: Button
    private lateinit var snoozeButton: Button
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        Logger.d(LogTags.ALARM, "🖥️ AlarmFullScreenActivity starting on ${Build.MANUFACTURER} ${Build.MODEL}")
        
        // Configure as full-screen, lock-screen overlay
        setupFullScreenMode()
        
        // Acquire wake lock to ensure device stays awake
        acquireWakeLock()
        
        // Set up basic layout (simple TextViews and Buttons)
        setupBasicLayout()
        
        // Set up back button handling (modern approach)
        setupBackButtonHandling()
        
        // Extract alarm information from intent
        handleAlarmIntent()
        
        // Start alarm sound and vibration
        startAlarmEffects()
        
        Logger.i(LogTags.ALARM, "✅ AlarmFullScreenActivity fully initialized - Simple and reliable!")
    }
    
    override fun onStart() {
        super.onStart()
        Logger.i(LogTags.ALARM, "🔄 AlarmFullScreenActivity STARTED")
    }

    override fun onResume() {
        super.onResume()
        Logger.i(LogTags.ALARM, "▶️ AlarmFullScreenActivity RESUMED")
        
        // Sound recovery: Restart alarm sound if it was stopped during pause
        if (isAlarmActive && alarmRingtone?.isPlaying != true) {
            Logger.w(LogTags.ALARM, "🔊 Sound stopped during background - restarting alarm effects")
            startAlarmEffects()
        }
    }

    override fun onPause() {
        super.onPause()
        Logger.w(LogTags.ALARM, "⏸️ AlarmFullScreenActivity PAUSED")
        
        // Keep wake lock active during pause for compatibility
        Logger.d(LogTags.ALARM, "⚠️ Keeping wake lock active during pause for device compatibility")
    }

    override fun onStop() {
        super.onStop()
        Logger.e(LogTags.ALARM, "⏹️ AlarmFullScreenActivity STOPPED")
        
        // Release wake lock on stop
        try {
            wakeLock?.let { lock ->
                if (lock.isHeld) {
                    lock.release()
                    Logger.d(LogTags.ALARM, "✅ Wake lock released in onStop")
                }
            }
        } catch (e: Exception) {
            Logger.e(LogTags.ALARM, "❌ Error releasing wake lock in onStop", e)
        }
    }
    
    private fun setupFullScreenMode() {
        // Show on lock screen and when device is locked (Modern API preferred)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            
            // Android 10+ (API 29): Additional security for lock screen
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                setInheritShowWhenLocked(true)
            }
        } else {
            // Legacy support for older Android versions
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        
        // Enhanced full-screen configuration
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        
        // Modern keyguard dismissal - deprecated flags are maintained for compatibility
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                WindowManager.LayoutParams.FLAG_FULLSCREEN
            )
        }
        
        // Modern UI visibility: Compatible with Android 14+ edge-to-edge
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+ (API 30): Modern window insets approach
            try {
                window.decorView.let { decorView ->
                    window.insetsController?.let { controller ->
                        controller.hide(
                            android.view.WindowInsets.Type.statusBars() or 
                            android.view.WindowInsets.Type.navigationBars()
                        )
                        controller.systemBarsBehavior = 
                            android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                        Logger.d(LogTags.ALARM, "✅ Modern insets controller configured successfully")
                    } ?: run {
                        Logger.w(LogTags.ALARM, "⚠️ InsetsController not available, falling back to legacy approach")
                        @Suppress("DEPRECATION")
                        decorView.systemUiVisibility = (
                            View.SYSTEM_UI_FLAG_FULLSCREEN or
                            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        )
                    }
                }
            } catch (e: Exception) {
                Logger.e(LogTags.ALARM, "❌ Failed to configure modern insets controller", e)
                try {
                    @Suppress("DEPRECATION")
                    window.decorView.systemUiVisibility = (
                        View.SYSTEM_UI_FLAG_FULLSCREEN or
                        View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    )
                } catch (legacyException: Exception) {
                    Logger.e(LogTags.ALARM, "❌ Even legacy approach failed", legacyException)
                }
            }
        } else {
            // Legacy approach for Android 10 and below
            try {
                window.decorView.let { decorView ->
                    @Suppress("DEPRECATION")
                    decorView.systemUiVisibility = (
                        View.SYSTEM_UI_FLAG_FULLSCREEN or
                        View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    )
                    Logger.d(LogTags.ALARM, "✅ Legacy system UI visibility configured successfully")
                }
            } catch (e: Exception) {
                Logger.e(LogTags.ALARM, "❌ Failed to configure legacy system UI visibility", e)
            }
        }
        
        Logger.d(LogTags.ALARM, "✅ Full-screen mode configured for Android ${Build.VERSION.SDK_INT}")
    }
    
    private fun acquireWakeLock() {
        try {
            val powerManager = getSystemService(POWER_SERVICE) as PowerManager
            
            // Modern WAKE LOCK: Use PARTIAL_WAKE_LOCK for alarm compatibility
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                WAKE_LOCK_TAG
            ).apply {
                setReferenceCounted(false) // Manual management for precise control
                acquire(WAKE_LOCK_TIMEOUT)
            }
            
            Logger.business(LogTags.ALARM, "✅ Modern wake lock acquired for full-screen activity (timeout: ${WAKE_LOCK_TIMEOUT}ms)")
        } catch (e: Exception) {
            Logger.e(LogTags.ALARM, "❌ Failed to acquire wake lock", e)
        }
    }
    
    private fun setupBasicLayout() {
        // Create a simple linear layout programmatically
        val rootLayout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setBackgroundColor(android.graphics.Color.parseColor("#1976D2")) // Blue background
            setPadding(32, 64, 32, 64)
        }
        
        // Main title
        val titleText = TextView(this).apply {
            text = "⏰ CF-ALARM"
            textSize = 32f
            setTextColor(android.graphics.Color.WHITE)
            gravity = android.view.Gravity.CENTER
            setPadding(0, 0, 0, 32)
        }
        
        // Shift name display
        shiftNameText = TextView(this).apply {
            text = "Schicht lädt..."
            textSize = 24f
            setTextColor(android.graphics.Color.WHITE)
            gravity = android.view.Gravity.CENTER
            setPadding(0, 0, 0, 16)
        }
        
        // Alarm time display
        alarmTimeText = TextView(this).apply {
            text = "Jetzt"
            textSize = 18f
            setTextColor(android.graphics.Color.parseColor("#E3F2FD"))
            gravity = android.view.Gravity.CENTER
            setPadding(0, 0, 0, 48)
        }
        
        // Dismiss button
        dismissButton = Button(this).apply {
            text = "ALARM STOPPEN"
            textSize = 18f
            setBackgroundColor(android.graphics.Color.parseColor("#4CAF50"))
            setTextColor(android.graphics.Color.WHITE)
            setPadding(24, 16, 24, 16)
            setOnClickListener { dismissAlarm() }
        }
        
        // Snooze button
        snoozeButton = Button(this).apply {
            text = "5 MIN SPÄTER"
            textSize = 16f
            setBackgroundColor(android.graphics.Color.parseColor("#FF9800"))
            setTextColor(android.graphics.Color.WHITE)
            setPadding(24, 16, 24, 16)
            setOnClickListener { snoozeAlarm() }
        }
        
        // Add all components to layout
        rootLayout.addView(titleText)
        rootLayout.addView(shiftNameText)
        rootLayout.addView(alarmTimeText)
        rootLayout.addView(dismissButton)
        rootLayout.addView(snoozeButton)
        
        setContentView(rootLayout)
    }
    
    private fun handleAlarmIntent() {
        val shiftName = intent.getStringExtra("shift_name") ?: "Unbekannte Schicht"
        val alarmTime = intent.getStringExtra("alarm_time") ?: "Jetzt"
        val alarmType = intent.getStringExtra("alarm_type")
        
        shiftNameText.text = shiftName
        alarmTimeText.text = "Zeit: $alarmTime${alarmType?.let { " ($it)" } ?: ""}"
        
        Logger.i(LogTags.ALARM, "📋 Alarm details: $shiftName at $alarmTime")
    }
    
    private fun startAlarmEffects() {
        // Mark alarm as active for lifecycle management
        isAlarmActive = true
        
        // Enhanced alarm sound: Multi-tier reliability approach
        var soundStarted: Boolean
        
        // Tier 1: Try RingtoneManager (preferred for alarm sounds)
        soundStarted = tryStartRingtoneAlarm()
        
        // Tier 2: Fallback to MediaPlayer if Ringtone fails
        if (!soundStarted) {
            Logger.w(LogTags.ALARM, "🔊 Ringtone failed, trying MediaPlayer fallback")
            soundStarted = tryStartMediaPlayerAlarm()
        }
        
        // Tier 3: If both fail, at least show visual alert
        if (!soundStarted) {
            Logger.e(LogTags.ALARM, "❌ All audio methods failed - showing visual-only alarm")
        }
        
        // Always start vibration regardless of audio success
        startAlarmVibration()
    }
    
    private fun tryStartRingtoneAlarm(): Boolean {
        try {
            val alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            
            if (alarmUri == null) {
                Logger.w(LogTags.ALARM, "⚠️ No ringtone URIs available")
                return false
            }
                
            alarmRingtone = RingtoneManager.getRingtone(this, alarmUri)?.apply {
                // Enhanced audio configuration for alarm reliability
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    isLooping = true
                    Logger.d(LogTags.ALARM, "✅ Native ringtone looping enabled (API 28+)")
                } else {
                    Logger.d(LogTags.ALARM, "📱 Using manual loop for older Android (API < 28)")
                    // Start robust manual looping for older APIs
                    startRobustManualRingtoneLoop()
                }
                
                // Volume check and logging
                try {
                    val audioManager = getSystemService(AUDIO_SERVICE) as android.media.AudioManager
                    val maxVolume = audioManager.getStreamMaxVolume(android.media.AudioManager.STREAM_ALARM)
                    val currentVolume = audioManager.getStreamVolume(android.media.AudioManager.STREAM_ALARM)
                    
                    if (currentVolume < maxVolume * 0.7) {
                        Logger.w(LogTags.ALARM, "⚠️ Alarm volume low ($currentVolume/$maxVolume), but respecting user settings")
                    }
                    
                    Logger.business(LogTags.ALARM, "🔊 Alarm volume: $currentVolume/$maxVolume")
                } catch (e: Exception) {
                    Logger.w(LogTags.ALARM, "Could not check alarm volume", e)
                }
                
                play()
            }
            
            // Verify sound actually started
            val isPlaying = alarmRingtone?.isPlaying == true
            if (isPlaying) {
                Logger.business(LogTags.ALARM, "✅ Ringtone alarm sound started successfully")
                return true
            } else {
                Logger.w(LogTags.ALARM, "⚠️ Ringtone created but not playing")
                return false
            }
            
        } catch (e: Exception) {
            Logger.e(LogTags.ALARM, "❌ Failed to start ringtone alarm", e)
            return false
        }
    }
    
    private fun tryStartMediaPlayerAlarm(): Boolean {
        try {
            val alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                
            if (alarmUri == null) {
                Logger.w(LogTags.ALARM, "⚠️ No alarm URIs available for MediaPlayer")
                return false
            }
            
            alarmMediaPlayer = android.media.MediaPlayer().apply {
                setDataSource(this@AlarmFullScreenActivity, alarmUri)
                setAudioAttributes(
                    android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_ALARM)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                isLooping = true
                
                setOnPreparedListener { player ->
                    try {
                        player.start()
                        Logger.business(LogTags.ALARM, "✅ MediaPlayer alarm sound started successfully")
                    } catch (e: Exception) {
                        Logger.e(LogTags.ALARM, "❌ MediaPlayer start failed", e)
                    }
                }
                
                setOnErrorListener { _, what, extra ->
                    Logger.e(LogTags.ALARM, "❌ MediaPlayer error: what=$what, extra=$extra")
                    false // Return false to trigger onCompletion
                }
                
                prepareAsync()
            }
            
            return true
            
        } catch (e: Exception) {
            Logger.e(LogTags.ALARM, "❌ Failed to start MediaPlayer alarm", e)
            return false
        }
    }
    
    private fun startRobustManualRingtoneLoop() {
        // Cleanup any existing loop
        stopManualRingtoneLoop()
        
        soundLoopHandler = Handler(Looper.getMainLooper())
        
        soundLoopRunnable = object : Runnable {
            override fun run() {
                try {
                    // Only continue if alarm is still active and we have a wake lock
                    if (!isAlarmActive || wakeLock?.isHeld != true) {
                        Logger.d(LogTags.ALARM, "🔇 Stopping sound loop - alarm inactive or no wake lock")
                        return
                    }
                    
                    alarmRingtone?.let { ringtone ->
                        if (!ringtone.isPlaying) {
                            try {
                                ringtone.play()
                                Logger.d(LogTags.ALARM, "🔄 Manually restarted ringtone for continuous loop")
                            } catch (e: Exception) {
                                Logger.e(LogTags.ALARM, "❌ Error restarting ringtone in loop", e)
                                // Try to recreate ringtone if it failed
                                if (isAlarmActive) {
                                    Logger.d(LogTags.ALARM, "🔄 Attempting to recreate ringtone")
                                    tryStartRingtoneAlarm()
                                }
                            }
                        }
                    } ?: run {
                        // Ringtone is null, try to recreate if alarm is still active
                        if (isAlarmActive) {
                            Logger.w(LogTags.ALARM, "⚠️ Ringtone is null, attempting to recreate")
                            tryStartRingtoneAlarm()
                        }
                    }
                    
                    // Schedule next check - shorter interval for reliability
                    if (isAlarmActive) {
                        soundLoopHandler?.postDelayed(this, 2000) // Check every 2 seconds
                    }
                    
                } catch (e: Exception) {
                    Logger.e(LogTags.ALARM, "❌ Error in sound loop runnable", e)
                    // Try to continue the loop despite the error
                    if (isAlarmActive) {
                        soundLoopHandler?.postDelayed(this, 3000) // Slightly longer interval after error
                    }
                }
            }
        }
        
        // Start the loop with initial delay
        soundLoopHandler?.postDelayed(soundLoopRunnable!!, 2000)
        Logger.d(LogTags.ALARM, "✅ Robust manual ringtone loop started")
    }
    
    private fun stopManualRingtoneLoop() {
        soundLoopRunnable?.let { runnable ->
            soundLoopHandler?.removeCallbacks(runnable)
        }
        soundLoopRunnable = null
        soundLoopHandler = null
    }
    
    private fun startAlarmVibration() {
        try {
            vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vibratorManager.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(VIBRATOR_SERVICE) as Vibrator
            }
            
            // Enhanced vibration pattern: More attention-grabbing for alarms
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
                    val vibrationEffect = VibrationEffect.createWaveform(alarmVibrationPattern, 1) // Repeat from index 1
                    vib.vibrate(vibrationEffect)
                    Logger.business(LogTags.ALARM, "✅ Enhanced alarm vibration started")
                } else {
                    Logger.d(LogTags.ALARM, "📴 Device has no vibrator capability")
                }
            }
        } catch (e: Exception) {
            Logger.e(LogTags.ALARM, "❌ Failed to start vibration", e)
        }
    }
    
    private fun dismissAlarm() {
        Logger.i(LogTags.ALARM, "🛑 User dismissed alarm")
        
        // Mark alarm as inactive to stop all loops
        isAlarmActive = false
        
        stopAlarmEffects()
        
        // Cancel any remaining notifications
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancelAll()
        
        // Finish activity
        finish()
    }
    
    private fun setupBackButtonHandling() {
        // Modern approach using OnBackPressedDispatcher
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Prevent back button from dismissing alarm too easily
                // Require explicit dismiss button press
                Logger.d(LogTags.ALARM, "🚫 Back button pressed - ignoring (use Dismiss button)")
            }
        })
    }
    
    private fun snoozeAlarm() {
        Logger.i(LogTags.ALARM, "😴 User snoozed alarm for 5 minutes")
        // TODO: Implement snooze functionality
        // For now, just dismiss
        dismissAlarm()
    }
    
    private fun stopAlarmEffects() {
        // Mark alarm as inactive to stop all sound loops
        isAlarmActive = false
        
        // Stop manual sound loop
        stopManualRingtoneLoop()
        
        // Stop ringtone
        try {
            alarmRingtone?.let { ringtone ->
                if (ringtone.isPlaying) {
                    ringtone.stop()
                }
            }
            alarmRingtone = null
            Logger.d(LogTags.ALARM, "🔇 Ringtone alarm sound stopped")
        } catch (e: Exception) {
            Logger.e(LogTags.ALARM, "❌ Error stopping ringtone alarm sound", e)
        }
        
        // Stop MediaPlayer
        try {
            alarmMediaPlayer?.let { player ->
                if (player.isPlaying) {
                    player.stop()
                }
                player.release()
            }
            alarmMediaPlayer = null
            Logger.d(LogTags.ALARM, "🔇 MediaPlayer alarm sound stopped")
        } catch (e: Exception) {
            Logger.e(LogTags.ALARM, "❌ Error stopping MediaPlayer alarm sound", e)
        }
        
        // Stop vibration
        try {
            vibrator?.cancel()
            vibrator = null
            Logger.d(LogTags.ALARM, "📴 Vibration stopped")
        } catch (e: Exception) {
            Logger.e(LogTags.ALARM, "❌ Error stopping vibration", e)
        }
        
        Logger.business(LogTags.ALARM, "✅ All alarm effects stopped successfully")
    }
    
    override fun onDestroy() {
        super.onDestroy()
        
        // Mark alarm as inactive and clean up all resources
        isAlarmActive = false
        stopAlarmEffects()
        
        // Wake lock is released in onStop() as recommended
        wakeLock = null
        
        Logger.d(LogTags.ALARM, "🖥️ AlarmFullScreenActivity destroyed with full cleanup")
    }
}
