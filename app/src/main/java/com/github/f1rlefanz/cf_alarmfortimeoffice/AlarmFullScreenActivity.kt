package com.github.f1rlefanz.cf_alarmfortimeoffice

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.toColorInt
import com.github.f1rlefanz.cf_alarmfortimeoffice.service.AlarmSoundService
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Full-screen alarm activity with service-based audio management
 * 
 * REFACTORED v2.0: Service-based architecture for reliable sound management
 * - AlarmSoundService handles all audio/vibration
 * - Activity focuses on UI and user interaction
 * - Cleanup in onStop() instead of onDestroy() for faster response
 * - Snooze-safe: No race conditions with MediaPlayer
 * 
 * Core Features:
 * - Full-screen, lock-screen overlay
 * - Service-managed audio (survives activity lifecycle)
 * - Wake lock management for reliability
 * - Clean dismissal and snooze controls
 * 
 * Philosophy: Separation of concerns = Reliability
 */
class AlarmFullScreenActivity : AppCompatActivity() {
    
    companion object {
        private const val WAKE_LOCK_TAG = "CFAlarm:FullScreenActivity"
        private const val WAKE_LOCK_TIMEOUT = 10 * 60 * 1000L // 10 minutes
    }
    
    private var wakeLock: PowerManager.WakeLock? = null
    
    // Service connection for alarm sound management
    private var soundServiceBound = false
    private var soundService: AlarmSoundService? = null
    
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as AlarmSoundService.AlarmSoundBinder
            soundService = binder.getService()
            soundServiceBound = true
            Logger.d(LogTags.ALARM, "✅ Connected to AlarmSoundService")
        }
        
        override fun onServiceDisconnected(name: ComponentName?) {
            soundServiceBound = false
            soundService = null
            Logger.d(LogTags.ALARM, "🔌 Disconnected from AlarmSoundService")
        }
    }
    
    // UI Components
    private lateinit var shiftNameText: TextView
    private lateinit var alarmTimeText: TextView
    private lateinit var dismissButton: Button
    private lateinit var snoozeButton: Button
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        Logger.d(LogTags.ALARM, "🖥️ AlarmFullScreenActivity starting (Service-based v2.0)")
        
        // Configure as full-screen, lock-screen overlay
        setupFullScreenMode()
        
        // Acquire wake lock to ensure device stays awake
        acquireWakeLock()
        
        // Set up basic layout
        setupBasicLayout()
        
        // Set up back button handling
        setupBackButtonHandling()
        
        // Extract alarm information from intent
        handleAlarmIntent()
        
        // Start and bind to AlarmSoundService
        startAlarmSoundService()
        bindAlarmSoundService()
        
        Logger.i(LogTags.ALARM, "✅ AlarmFullScreenActivity initialized with service integration")
    }
    
    override fun onStart() {
        super.onStart()
        Logger.i(LogTags.ALARM, "🔄 AlarmFullScreenActivity STARTED")
    }

    override fun onResume() {
        super.onResume()
        Logger.i(LogTags.ALARM, "▶️ AlarmFullScreenActivity RESUMED")
    }

    override fun onPause() {
        super.onPause()
        Logger.w(LogTags.ALARM, "⏸️ AlarmFullScreenActivity PAUSED")
    }

    override fun onStop() {
        super.onStop()
        Logger.e(LogTags.ALARM, "⏹️ AlarmFullScreenActivity STOPPED")
        
        // CRITICAL: Stop service in onStop() for immediate cleanup
        // This is called before onDestroy() and provides faster response
        stopAlarmSoundService()
        
        // Unbind from service
        if (soundServiceBound) {
            try {
                unbindService(serviceConnection)
                soundServiceBound = false
                Logger.d(LogTags.ALARM, "✅ Service unbound in onStop")
            } catch (e: Exception) {
                Logger.w(LogTags.ALARM, "⚠️ Service already unbound", e)
            }
        }
        
        // Release wake lock
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
    
    override fun onDestroy() {
        super.onDestroy()
        
        // Defensive cleanup - service should already be stopped in onStop()
        stopAlarmSoundService()
        
        // Ensure unbind (defensive)
        if (soundServiceBound) {
            try {
                unbindService(serviceConnection)
            } catch (e: Exception) {
                Logger.w(LogTags.ALARM, "Service already unbound in onDestroy", e)
            }
            soundServiceBound = false
        }
        
        wakeLock = null
        Logger.d(LogTags.ALARM, "🖥️ AlarmFullScreenActivity destroyed with service cleanup")
    }
    
    private fun setupFullScreenMode() {
        // Show on lock screen and when device is locked (Modern API preferred)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                setInheritShowWhenLocked(true)
            }
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                WindowManager.LayoutParams.FLAG_FULLSCREEN
            )
        }
        
        // Modern UI visibility
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                window.insetsController?.let { controller ->
                    controller.hide(
                        android.view.WindowInsets.Type.statusBars() or 
                        android.view.WindowInsets.Type.navigationBars()
                    )
                    controller.systemBarsBehavior = 
                        android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            } catch (e: Exception) {
                Logger.e(LogTags.ALARM, "❌ Failed to configure modern insets", e)
            }
        } else {
            try {
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                )
            } catch (e: Exception) {
                Logger.e(LogTags.ALARM, "❌ Failed to configure legacy UI", e)
            }
        }
        
        Logger.d(LogTags.ALARM, "✅ Full-screen mode configured")
    }
    
    private fun acquireWakeLock() {
        try {
            val powerManager = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                WAKE_LOCK_TAG
            ).apply {
                setReferenceCounted(false)
                acquire(WAKE_LOCK_TIMEOUT)
            }
            Logger.business(LogTags.ALARM, "✅ Wake lock acquired for full-screen activity")
        } catch (e: Exception) {
            Logger.e(LogTags.ALARM, "❌ Failed to acquire wake lock", e)
        }
    }
    
    private fun setupBasicLayout() {
        val rootLayout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setBackgroundColor("#1976D2".toColorInt())
            setPadding(32, 64, 32, 64)
        }
        
        val titleText = TextView(this).apply {
            text = getString(R.string.alarm_title)
            textSize = 32f
            setTextColor(android.graphics.Color.WHITE)
            gravity = android.view.Gravity.CENTER
            setPadding(0, 0, 0, 32)
        }
        
        shiftNameText = TextView(this).apply {
            text = getString(R.string.alarm_shift_loading)
            textSize = 24f
            setTextColor(android.graphics.Color.WHITE)
            gravity = android.view.Gravity.CENTER
            setPadding(0, 0, 0, 16)
        }
        
        alarmTimeText = TextView(this).apply {
            text = getString(R.string.alarm_time_now)
            textSize = 18f
            setTextColor("#E3F2FD".toColorInt())
            gravity = android.view.Gravity.CENTER
            setPadding(0, 0, 0, 48)
        }
        
        dismissButton = Button(this).apply {
            text = getString(R.string.alarm_dismiss_button)
            textSize = 18f
            setBackgroundColor("#4CAF50".toColorInt())
            setTextColor(android.graphics.Color.WHITE)
            setPadding(24, 16, 24, 16)
            setOnClickListener { dismissAlarm() }
        }
        
        snoozeButton = Button(this).apply {
            text = getString(R.string.alarm_snooze_button)
            textSize = 16f
            setBackgroundColor("#FF9800".toColorInt())
            setTextColor(android.graphics.Color.WHITE)
            setPadding(24, 16, 24, 16)
            setOnClickListener { snoozeAlarm() }
        }
        
        rootLayout.addView(titleText)
        rootLayout.addView(shiftNameText)
        rootLayout.addView(alarmTimeText)
        rootLayout.addView(dismissButton)
        rootLayout.addView(snoozeButton)
        
        setContentView(rootLayout)
    }
    
    private fun handleAlarmIntent() {
        val shiftName = intent.getStringExtra("shift_name") ?: getString(R.string.alarm_unknown_shift)
        val alarmTime = intent.getStringExtra("alarm_time") ?: getString(R.string.alarm_time_now)
        val alarmType = intent.getStringExtra("alarm_type")
        
        shiftNameText.text = shiftName
        alarmTimeText.text = getString(
            R.string.alarm_time_format,
            alarmTime,
            alarmType?.let { " ($it)" } ?: ""
        )
        
        Logger.i(LogTags.ALARM, "📋 Alarm details: $shiftName at $alarmTime")
    }
    
    private fun setupBackButtonHandling() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                Logger.d(LogTags.ALARM, "🚫 Back button pressed - ignoring")
            }
        })
    }
    
    /**
     * Starts AlarmSoundService to handle audio playback
     */
    private fun startAlarmSoundService() {
        val shiftName = intent.getStringExtra("shift_name") ?: "Alarm"
        val alarmId = intent.getIntExtra("alarm_id", -1)
        
        val serviceIntent = Intent(this, AlarmSoundService::class.java).apply {
            action = AlarmSoundService.ACTION_START_ALARM
            putExtra(AlarmSoundService.EXTRA_SHIFT_NAME, shiftName)
            putExtra(AlarmSoundService.EXTRA_ALARM_ID, alarmId)
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        
        Logger.business(LogTags.ALARM, "✅ AlarmSoundService started from activity")
    }
    
    /**
     * Binds to AlarmSoundService for status updates
     */
    private fun bindAlarmSoundService() {
        val bindIntent = Intent(this, AlarmSoundService::class.java)
        bindService(bindIntent, serviceConnection, Context.BIND_AUTO_CREATE)
        Logger.d(LogTags.ALARM, "📎 Binding to AlarmSoundService")
    }
    
    /**
     * Stops AlarmSoundService
     */
    private fun stopAlarmSoundService() {
        val serviceIntent = Intent(this, AlarmSoundService::class.java).apply {
            action = AlarmSoundService.ACTION_STOP_ALARM
        }
        startService(serviceIntent)
        Logger.i(LogTags.ALARM, "✅ AlarmSoundService stop requested")
    }
    
    /**
     * Dismisses the alarm - stops service and closes activity
     */
    private fun dismissAlarm() {
        Logger.i(LogTags.ALARM, "🛑 User dismissed alarm")
        
        // Stop sound service
        stopAlarmSoundService()
        
        // Cancel notifications
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancelAll()
        
        // Finish activity
        finish()
    }
    
    /**
     * Snoozes the alarm - CRITICAL: stops service BEFORE scheduling snooze
     */
    private fun snoozeAlarm() {
        Logger.i(LogTags.ALARM, "😴 User snoozed alarm for 5 minutes")
        
        try {
            // CRITICAL: Stop sound service BEFORE scheduling snooze
            // This prevents race conditions with MediaPlayer
            stopAlarmSoundService()
            
            // Calculate snooze time
            val snoozeTimeMillis = System.currentTimeMillis() + (5 * 60 * 1000L)
            
            // Get alarm details
            val shiftName = intent.getStringExtra("shift_name") ?: "Snooze"
            val alarmType = intent.getStringExtra("alarm_type")
            
            // Create snooze alarm intent
            val alarmIntent = Intent(this, AlarmReceiver::class.java).apply {
                putExtra("shift_name", shiftName)
                putExtra("alarm_time", LocalDateTime.now().plusMinutes(5).format(
                    DateTimeFormatter.ofPattern("HH:mm")
                ))
                putExtra("alarm_type", alarmType)
                putExtra("is_snoozed", true)
            }
            
            // Create pending intent with unique request code
            val requestCode = (System.currentTimeMillis() % Integer.MAX_VALUE).toInt()
            val pendingIntent = PendingIntent.getBroadcast(
                this,
                requestCode,
                alarmIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            
            // Schedule snooze alarm
            val alarmManager = getSystemService(ALARM_SERVICE) as AlarmManager
            val alarmClockInfo = AlarmManager.AlarmClockInfo(snoozeTimeMillis, pendingIntent)
            alarmManager.setAlarmClock(alarmClockInfo, pendingIntent)
            
            Logger.business(LogTags.ALARM, "✅ Snooze alarm scheduled for 5 minutes")
            Logger.d(LogTags.ALARM, "🕔 Snooze time: ${java.time.Instant.ofEpochMilli(snoozeTimeMillis)}")
            
            // Now dismiss the current alarm
            dismissAlarm()
            
        } catch (e: Exception) {
            Logger.e(LogTags.ALARM, "❌ Failed to snooze alarm", e)
            // If snooze fails, just dismiss
            dismissAlarm()
        }
    }
}
